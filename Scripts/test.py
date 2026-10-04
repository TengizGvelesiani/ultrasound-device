import serial
import serial.tools.list_ports
import matplotlib.pyplot as plt
import threading
import queue
import time
import argparse
import sys
import numpy as np

# ============================================================
# CONFIGURATION
# ============================================================

BAUD_RATE = 115200

NUM_SAMPLES = 1024

# FPGA ADC sampling rate
# 100 MHz / 4 = 25 MHz
SAMPLE_RATE = 5_000_000

# ============================================================
# GLOBALS
# ============================================================

data_queue = queue.Queue(maxsize=0)
stop_event = threading.Event()


# ============================================================
# SERIAL THREAD
# ============================================================

def serial_reader(ser):

    print("Serial reader started.")

    buffer = bytearray()

    while not stop_event.is_set():

        try:
            # Read whatever is currently available.
            # This NEVER blocks for a long time.
            waiting = ser.in_waiting

            if waiting > 0:
                buffer.extend(ser.read(waiting))

            else:
                time.sleep(0.001)
                continue

            # ------------------------------------------------
            # FPGA format:
            #
            #   "ABC\r\n"
            #
            # 3 hex characters + CR + LF
            # ------------------------------------------------

            while b"\n" in buffer:

                newline = buffer.index(b"\n")

                line = buffer[:newline]
                del buffer[:newline + 1]

                line = line.strip()

                if len(line) != 3:
                    continue

                try:
                    value = int(line.decode("ascii"), 16)
                except (ValueError, UnicodeDecodeError):
                    continue

                if value > 4095:
                    continue

                # ------------------------------------------------
                # Put sample into queue
                # ------------------------------------------------

                try:
                    data_queue.put_nowait(value)

                except queue.Full:
                    # If GUI can't keep up, discard OLD samples.
                    # Don't let serial thread block.
                    try:
                        data_queue.get_nowait()
                    except queue.Empty:
                        pass

                    try:
                        data_queue.put_nowait(value)
                    except queue.Full:
                        pass

        except serial.SerialException as e:

            print("Serial error:", e)
            stop_event.set()
            break

    print("Serial reader stopped.")


# ============================================================
# MAIN
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="Live Ultrasound Echo Plotter")
    parser.add_argument("-p", "--port", type=str, help="Serial COM port (e.g., COM10)")
    args = parser.parse_args()

    com_port = args.port

    if not com_port:
        ports = list(serial.tools.list_ports.comports())
        print("Available COM ports:")
        for p in ports:
            print(f"  - {p.device}: {p.description}")
        if not ports:
            print("  (No COM ports found!)")
        
        com_port = input("\nEnter the COM port to connect to (e.g. COM10): ").strip()
        if not com_port:
            print("No COM port provided. Exiting.")
            sys.exit(1)

    print(f"Connecting to {com_port}...")

    while True:
        try:
            ser = serial.Serial(com_port, BAUD_RATE, timeout=0)
            break
        except serial.SerialException:
            print(f"Could not open {com_port}. Retrying in 1s...")
            time.sleep(1)
        except KeyboardInterrupt:
            print("\nExiting...")
            sys.exit(1)

    print(f"Connected to {com_port}")
    print(f"Baud: {BAUD_RATE}")

    # Throw away anything that existed before Python started.
    ser.reset_input_buffer()

    # ========================================================
    # Start serial thread
    # ========================================================

    thread = threading.Thread(
        target=serial_reader,
        args=(ser,),
        daemon=True
    )

    thread.start()

    # ========================================================
    # MATPLOTLIB
    # ========================================================

    plt.ion()

    fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(11, 10))

    # Time axis in microseconds
    time_axis = [i / SAMPLE_RATE * 1_000_000 for i in range(NUM_SAMPLES)]
    plot_data = [0] * NUM_SAMPLES

    # Raw signal subplot
    line1, = ax1.plot(time_axis, plot_data, color="#00aaff", linewidth=1.0)
    ax1.set_xlim(0, time_axis[-1])
    ax1.set_ylim(0, 4095)
    ax1.set_ylabel("Raw ADC Counts")
    ax1.set_title("Live Ultrasound Echo - Raw")
    ax1.grid(True, linestyle="--", alpha=0.5)
    ax1.axhline(2048, color="red", linestyle=":", alpha=0.5)

    # Processed envelope subplot
    line2, = ax2.plot(time_axis, plot_data, color="#ff7700", linewidth=1.5)
    ax2.set_xlim(0, time_axis[-1])
    ax2.set_ylim(0, 2048)
    ax2.set_ylabel("Envelope Amplitude")
    ax2.set_title("Processed Signal (High-Pass + Rectified + Smoothed)")
    ax2.grid(True, linestyle="--", alpha=0.5)

    # B-Scan (M-Mode) Waterfall subplot
    bscan_history = 300  # Number of frames to keep in history
    bscan_data = np.zeros((NUM_SAMPLES, bscan_history))
    
    # extent: [left, right, bottom, top]
    # We want top to be 0 us, bottom to be max time us
    img3 = ax3.imshow(bscan_data, aspect='auto', cmap='gray', vmin=0, vmax=50,
                      extent=[0, bscan_history, time_axis[-1], 0])
    ax3.set_xlabel("Frame History (Newest on Right)")
    ax3.set_ylabel("Time (µs) / Depth")
    ax3.set_title("B-Scan Waterfall Draft (M-Mode Simulation)")

    plt.tight_layout()
    plt.show(block=False)

    # ========================================================
    # RECEIVE 1024 SAMPLES
    # ========================================================

    frame = []
    last_update = time.time()
    frames = 0

    print("Waiting for FPGA frames...")

    try:
        while not stop_event.is_set():

            # ------------------------------------------------
            # Get samples from background thread
            # ------------------------------------------------
            try:
                while True:
                    value = data_queue.get_nowait()
                    frame.append(value)
            except queue.Empty:
                pass

            # ------------------------------------------------
            # Complete frame
            # ------------------------------------------------
            if len(frame) >= NUM_SAMPLES:
                current_frame = frame[:NUM_SAMPLES]

                # 1. Convert to numpy array
                sig = np.array(current_frame, dtype=float)

                # --- Auto-Align & Frame Synchronization ---
                # Find the steepest edge (which is the massive 100V transmit pulse)
                edge_idx = int(np.argmax(np.abs(np.diff(sig))))
                
                # If the transmit pulse is far into the buffer, we started reading mid-frame!
                if edge_idx > 50:
                    # We are out of sync. The true frame starts near edge_idx.
                    # Discard the garbage data from the previous frame.
                    sync_shift = edge_idx - 10
                    if sync_shift < 0: sync_shift = 0
                    
                    frame = frame[sync_shift:]
                    print(f"SYNCING! Discarded {sync_shift} samples to align to frame boundary.")
                    continue # Skip plotting this torn frame, wait for a full 1024 aligned samples
                
                # If we are here, the frame is perfectly synchronized.
                # Pop the valid 1024 samples from the queue buffer.
                frame = frame[NUM_SAMPLES:]
                
                # Minor alignment for jitter (shift by just a few samples)
                shift_amount = edge_idx - 10
                sig = np.roll(sig, -shift_amount)
                # ----------------------------------------------------

                # 2. High-pass filter (remove baseline drift)
                # We subtract a heavily smoothed version of the signal from itself
                window_drift = 100
                pad_drift = window_drift // 2
                padded_sig = np.pad(sig, (pad_drift, pad_drift), mode='edge')
                baseline = np.convolve(padded_sig, np.ones(window_drift)/window_drift, mode='valid')
                if len(baseline) > len(sig): baseline = baseline[:len(sig)]
                high_passed = sig - baseline

                # 3. Envelope Detection (Rectify + Low-pass)
                rectified = np.abs(high_passed)
                window_env = 15
                pad_env = window_env // 2
                padded_rect = np.pad(rectified, (pad_env, pad_env), mode='edge')
                envelope = np.convolve(padded_rect, np.ones(window_env)/window_env, mode='valid')
                if len(envelope) > len(sig): envelope = envelope[:len(sig)]

                # Update graph
                line1.set_ydata(sig)
                line2.set_ydata(envelope)
                
                # Update B-Scan Waterfall
                # Shift columns to the left (older data moves left, drops off)
                bscan_data = np.roll(bscan_data, -1, axis=1)
                
                # Ensure envelope is exactly NUM_SAMPLES long
                env_len = len(envelope)
                if env_len < NUM_SAMPLES:
                    env_padded = np.pad(envelope, (0, NUM_SAMPLES - env_len), 'constant')
                else:
                    env_padded = envelope[:NUM_SAMPLES]
                
                # Add new envelope as the right-most column
                bscan_data[:, -1] = env_padded
                img3.set_data(bscan_data)
                
                # Dynamic Y-axis for envelope and B-Scan contrast: 
                # CRITICAL: Ignore the massive Main Bang (first 200 samples / 40 us) 
                # when calculating the zoom, otherwise real echoes get squashed into a flat line!
                if len(envelope) > 200:
                    echo_region = envelope[200:]
                    max_env = np.max(echo_region)
                else:
                    max_env = np.max(envelope)

                if max_env > 5:
                    ax2.set_ylim(0, max_env * 1.5)
                    # Adjust B-Scan contrast (vmin, vmax) based on echo strength
                    # We use a lower vmax to make weak echoes brighter in the image
                    img3.set_clim(vmin=0, vmax=max_env * 0.8) 
                else:
                    ax2.set_ylim(0, 30)
                    img3.set_clim(vmin=0, vmax=20)

                ax1.set_title(f"Live Ultrasound Echo - Raw | Min: {int(np.min(sig))} | Max: {int(np.max(sig))}")

                fig.canvas.draw_idle()
                fig.canvas.flush_events()

                frames += 1

                print(
                    f"Frame {frames:4d} | "
                    f"Min={min(current_frame):4d} | "
                    f"Max={max(current_frame):4d}"
                )

            # ------------------------------------------------
            # CRITICAL:
            # Give Matplotlib/Windows time to process GUI.
            # ------------------------------------------------

            plt.pause(0.001)

    except KeyboardInterrupt:

        print("\nStopping...")

    finally:

        stop_event.set()

        try:
            ser.close()
        except:
            pass

        plt.close("all")

        print("Done.")


# ============================================================
# RUN
# ============================================================

if __name__ == "__main__":
    main()