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
BAUD_RATE = 2_000_000
NUM_SAMPLES = 1024

# Decimated sample rate (20 MHz / 4 = 5 MHz)
SAMPLE_RATE = 5_000_000 

# Digital post-processing contrast (increase to make echoes brighter)
CONTRAST_GAIN = 1.0 

# Noise Reject / Squelch (Hides the amplified noise floor at deep depths)
NOISE_REJECT = 35

# ============================================================
# GLOBALS
# ============================================================
data_queue = queue.Queue(maxsize=10)
stop_event = threading.Event()

# ============================================================
# SERIAL THREAD (BINARY HIGH-SPEED)
# ============================================================
def serial_reader(ser):
    print("Serial reader started.")
    buffer = bytearray()
    
    # Sync Header from FPGA
    SYNC_HEADER = b'\xFF\xAA\x55\x00'

    while not stop_event.is_set():
        try:
            waiting = ser.in_waiting
            if waiting > 0:
                buffer.extend(ser.read(waiting))
            else:
                time.sleep(0.001)
                continue

            # Locate the 4-byte synchronization header
            while True:
                idx = buffer.find(SYNC_HEADER)
                if idx == -1:
                    # Preserve potential partial headers across serial reads
                    if len(buffer) > 3:
                        buffer = buffer[-3:]
                    break
                
                # Check if the complete payload has been received
                if len(buffer) >= idx + 4 + NUM_SAMPLES:
                    payload = buffer[idx + 4 : idx + 4 + NUM_SAMPLES]
                    
                    # Consume the processed frame from the buffer
                    del buffer[:idx + 4 + NUM_SAMPLES]
                    
                    try:
                        data_queue.put_nowait(payload)
                    except queue.Full:
                        # Drop the oldest frame to prioritize real-time data
                        try:
                            data_queue.get_nowait()
                        except queue.Empty:
                            pass
                        try:
                            data_queue.put_nowait(payload)
                        except queue.Full:
                            pass
                else:
                    # Incomplete payload: wait for subsequent serial reads.
                    # Discard out-of-sync bytes preceding the header.
                    del buffer[:idx]
                    break

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
            sys.exit(1)

    print(f"Connecting to {com_port} at {BAUD_RATE} baud...")

    while True:
        try:
            ser = serial.Serial(com_port, BAUD_RATE, timeout=0)
            break
        except serial.SerialException:
            print(f"Could not open {com_port}. Retrying...")
            time.sleep(1)

    print(f"Connected to {com_port}")
    ser.reset_input_buffer()

    thread = threading.Thread(target=serial_reader, args=(ser,), daemon=True)
    thread.start()

    # ========================================================
    # MATPLOTLIB SETUP
    # ========================================================
    plt.style.use('dark_background')
    plt.ion()
    
    # Make the B-Scan take up much more of the window than the A-scan (1:3 ratio)
    fig, (ax1, ax3) = plt.subplots(2, 1, figsize=(12, 8), gridspec_kw={'height_ratios': [1, 3]})
    fig.canvas.manager.set_window_title("Real-Time Ultrasound B-Scan")

    # Convert time to Depth in mm (Speed of sound in water ~ 1480 m/s)
    # Depth = (Time * Speed) / 2 (Round trip)
    time_s = [i / SAMPLE_RATE for i in range(NUM_SAMPLES)]
    depth_axis = [t * 1480 / 2 * 1000 for t in time_s]
    
    # 1D Envelope Line (A-Scan)
    line1, = ax1.plot(depth_axis, [0]*NUM_SAMPLES, color="#00ffcc", linewidth=1.5)
    ax1.set_xlim(0, depth_axis[-1])
    ax1.set_ylim(0, 255)
    ax1.set_ylabel("Amplitude")
    ax1.set_title("A-Scan (Real-Time Envelope)", color="lightgray", fontsize=10)
    ax1.grid(True, linestyle=":", alpha=0.3)
    ax1.tick_params(colors="lightgray")
    for spine in ax1.spines.values():
        spine.set_color('#333333')

    # 2D B-Scan Waterfall
    bscan_history = 800  # Wider history for a sweeping scan
    bscan_data = np.zeros((NUM_SAMPLES, bscan_history))
    
    # 'bone' colormap gives the classic ultrasound blue-ish/gray medical look
    # interpolation='bicubic' smoothes the pixels so it looks like continuous tissue
    img3 = ax3.imshow(bscan_data, aspect='auto', cmap='bone', vmin=0, vmax=255,
                      extent=[0, bscan_history, depth_axis[-1], 0], interpolation='bicubic')
    ax3.set_xlabel("Sweep History", color="lightgray")
    ax3.set_ylabel("Depth (mm)", color="lightgray")
    ax3.set_title("B-Scan Imaging", color="white", fontsize=14, weight='bold')
    ax3.tick_params(colors="lightgray")
    
    for spine in ax3.spines.values():
        spine.set_color('#555555')
        spine.set_linewidth(2)

    plt.tight_layout()
    plt.show(block=False)

    frames = 0
    
    try:
        while not stop_event.is_set():
            try:
                payload = None
                while True:
                    payload = data_queue.get_nowait()
            except queue.Empty:
                pass

            if payload is not None:
                # Convert binary payload to numpy array, apply Noise Reject and Contrast
                sig = np.frombuffer(payload, dtype=np.uint8).astype(float)
                sig = np.clip((sig - NOISE_REJECT) * CONTRAST_GAIN, 0, 255)
                
                # Plot 1D line
                line1.set_ydata(sig)
                
                # Plot 2D Waterfall
                bscan_data = np.roll(bscan_data, -1, axis=1)
                bscan_data[:, -1] = sig
                img3.set_data(bscan_data)
                
                fig.canvas.draw_idle()
                fig.canvas.flush_events()
                frames += 1

            plt.pause(0.001)

    except KeyboardInterrupt:
        print("\nStopping...")
    finally:
        stop_event.set()
        try: ser.close()
        except: pass
        plt.close("all")

if __name__ == "__main__":
    main()