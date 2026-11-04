"""
Manual 1-Axis Ultrasound B-Scan Acquisition & Visualization
Senior Project - Ultrasound Device

Optimized for manual transducer translation along a single axis (e.g. over a water tank with a coin).
Captures 100% of A-scan frames at 2 Mbaud (zero dropped lines).
Supports:
  - Sweep Pass Mode (records a clean left-to-right 2D cross-section pass)
  - Continuous Waterfall Mode (live real-time monitor)
  - Interactive Contrast Gain & Noise Squelch adjustment
  - Depth Zoom (Full 150mm vs Near-field 60mm)
  - High-res PNG & Raw Data (.npz) export
"""

import sys
import os
import time
from datetime import datetime
import threading
import queue
import argparse
import numpy as np
import serial
import serial.tools.list_ports
import matplotlib.pyplot as plt
from matplotlib.widgets import Button, Slider

# ============================================================
# ULTRASOUND & DAQ SPECIFICATIONS
# ============================================================
BAUD_RATE = 2_000_000
NUM_SAMPLES = 1024
SAMPLE_RATE = 5_000_000         # 5 MHz decimated rate
SOUND_SPEED_WATER = 1480.0      # m/s in water at room temp
SYNC_HEADER = b'\xFF\xAA\x55\x00'

# Depth per sample in mm: Depth = (t * c / 2)
# t = i / SAMPLE_RATE => depth = (i / 5e6) * (1480 / 2) * 1000 = i * 0.148 mm
DEPTH_AXIS = np.arange(NUM_SAMPLES) * (SOUND_SPEED_WATER / (2.0 * SAMPLE_RATE)) * 1000.0
MAX_DEPTH_MM = DEPTH_AXIS[-1]   # ~151.4 mm

# Default DSP parameters
DEFAULT_CONTRAST = 1.2
DEFAULT_SQUELCH = 35
INITIAL_SWEEP_WIDTH = 1000      # 1000 lines = ~10 seconds at 100 Hz PRF

# ============================================================
# STATE CONTAINER
# ============================================================
class ScannerState:
    def __init__(self):
        self.mode = "SWEEP"          # "SWEEP" or "WATERFALL"
        self.is_recording = True     # Recording state for Sweep mode
        self.contrast = DEFAULT_CONTRAST
        self.squelch = DEFAULT_SQUELCH
        self.zoom_near = False       # False: 0-150mm, True: 0-60mm
        self.colormap = "bone"
        self.colormaps = ["bone", "hot", "inferno", "gray"]
        self.cm_idx = 0
        
        # Sweep buffer (1024 x N)
        self.sweep_width = INITIAL_SWEEP_WIDTH
        self.sweep_data = np.zeros((NUM_SAMPLES, self.sweep_width), dtype=np.float32)
        self.sweep_idx = 0
        
        # Raw unprocessed buffer for instant re-filtering on slider change
        self.raw_sweep_data = np.zeros((NUM_SAMPLES, self.sweep_width), dtype=np.uint8)
        
        # Waterfall buffer (fixed 800 columns)
        self.waterfall_width = 800
        self.waterfall_data = np.zeros((NUM_SAMPLES, self.waterfall_width), dtype=np.float32)
        
        # Latest 1D A-Scan line
        self.latest_raw = np.zeros(NUM_SAMPLES, dtype=np.uint8)
        self.latest_sig = np.zeros(NUM_SAMPLES, dtype=np.float32)
        self.total_frames_received = 0
        self.fps_timer = time.time()
        self.fps_counter = 0
        self.current_fps = 0.0

state = ScannerState()
data_queue = queue.Queue(maxsize=4000)
stop_event = threading.Event()

# ============================================================
# HIGH-SPEED SERIAL READER THREAD
# ============================================================
def serial_reader(ser):
    print("[Serial] Reader thread active. Listening for sync header...")
    buffer = bytearray()
    expected_frame_len = 4 + NUM_SAMPLES

    while not stop_event.is_set():
        try:
            waiting = ser.in_waiting
            if waiting > 0:
                chunk = ser.read(waiting)
                buffer.extend(chunk)
            else:
                time.sleep(0.0005)
                continue

            # Process all complete packets in buffer
            while True:
                idx = buffer.find(SYNC_HEADER)
                if idx == -1:
                    if len(buffer) > 3:
                        buffer = buffer[-3:]
                    break

                if len(buffer) >= idx + expected_frame_len:
                    payload = buffer[idx + 4 : idx + expected_frame_len]
                    del buffer[:idx + expected_frame_len]
                    
                    try:
                        data_queue.put_nowait(payload)
                    except queue.Full:
                        # Drop oldest only if GUI is completely locked
                        try: data_queue.get_nowait()
                        except queue.Empty: pass
                        data_queue.put_nowait(payload)
                else:
                    del buffer[:idx]
                    break

        except serial.SerialException as e:
            print(f"[Serial Error] {e}")
            stop_event.set()
            break
        except Exception as e:
            print(f"[Unexpected Serial Error] {e}")
            break

    print("[Serial] Reader thread terminated.")

# ============================================================
# DSP PROCESSING HELPER
# ============================================================
def process_signal(raw_arr, squelch, contrast):
    """Squelches noise floor and applies dynamic contrast gain."""
    sig = raw_arr.astype(np.float32)
    sig = np.clip((sig - squelch) * contrast, 0.0, 255.0)
    return sig

# ============================================================
# GUI & VISUALIZATION
# ============================================================
def run_app(com_port):
    print(f"Connecting to {com_port} at {BAUD_RATE} baud...")
    while True:
        try:
            ser = serial.Serial(com_port, BAUD_RATE, timeout=0)
            break
        except serial.SerialException as e:
            print(f"Waiting for {com_port} to become available... ({e})")
            time.sleep(1)

    ser.reset_input_buffer()
    reader_thread = threading.Thread(target=serial_reader, args=(ser,), daemon=True)
    reader_thread.start()

    # Dark background aesthetic for ultrasound clarity
    plt.style.use('dark_background')
    fig = plt.figure(figsize=(14, 9), facecolor="#121216")
    fig.canvas.manager.set_window_title("Ultrasound 1-Axis Scanner - Metal Coin Test")

    # Layout:
    # Top: A-Scan Envelope
    # Bottom: B-Scan 2D Cross Section
    # Base: Controls (Buttons & Sliders)
    gs = fig.add_gridspec(2, 1, height_ratios=[1.1, 3.2], top=0.94, bottom=0.15, left=0.08, right=0.96, hspace=0.32)
    
    ax_ascan = fig.add_subplot(gs[0])
    ax_bscan = fig.add_subplot(gs[1])

    # ------------------ 1D A-SCAN SETUP ------------------
    ax_ascan.set_facecolor("#181820")
    line_ascan, = ax_ascan.plot(DEPTH_AXIS, np.zeros(NUM_SAMPLES), color="#00ffcc", lw=1.5, label="Echo Amplitude")
    line_squelch = ax_ascan.axhline(state.squelch, color="#ff4444", ls="--", lw=1.0, alpha=0.8, label=f"Squelch ({state.squelch})")
    peak_marker, = ax_ascan.plot([0], [0], "ro", markersize=6, label="Peak Echo")
    
    peak_text = ax_ascan.text(0.98, 0.88, "Peak: -- mm", transform=ax_ascan.transAxes,
                             color="#ffdd55", fontsize=11, weight='bold', ha='right', va='top',
                             bbox=dict(boxstyle='round,pad=0.4', facecolor='#22222a', edgecolor='#444455'))

    ax_ascan.set_xlim(0, MAX_DEPTH_MM)
    ax_ascan.set_ylim(-5, 260)
    ax_ascan.set_ylabel("Amplitude (0-255)", color="#cccccc", fontsize=10)
    ax_ascan.set_title("Real-Time A-Scan (Instantaneous Acoustic Reflection)", color="#ffffff", fontsize=12, pad=6)
    ax_ascan.grid(True, linestyle=":", alpha=0.35, color="#555566")
    ax_ascan.legend(loc="upper left", fontsize=8, facecolor="#1e1e24", edgecolor="#444455")
    ax_ascan.tick_params(colors="#aaaaaa")

    # ------------------ 2D B-SCAN SETUP ------------------
    ax_bscan.set_facecolor("#181820")
    img_bscan = ax_bscan.imshow(
        state.sweep_data,
        aspect='auto',
        cmap=state.colormap,
        vmin=0,
        vmax=255,
        extent=[0, state.sweep_width, MAX_DEPTH_MM, 0],  # 0mm depth at top!
        interpolation='bicubic'
    )
    
    # Red scanhead cursor line showing current manual position
    scan_cursor = ax_bscan.axvline(0, color="#ff3333", lw=1.5, ls="--", alpha=0.9)

    ax_bscan.set_ylabel("Depth (mm)", color="#ffffff", fontsize=11, weight='bold')
    ax_bscan.set_xlabel("Scan Position / Sample Lines", color="#ffffff", fontsize=11)
    title_bscan = ax_bscan.set_title("2D B-Scan (Mode: SWEEP PASS | [SPACE] to Freeze/Resume | Slide along Axis)",
                                     color="#ffffff", fontsize=13, weight='bold', pad=8)
    ax_bscan.tick_params(colors="#aaaaaa")

    # Status badge on top right of B-Scan
    status_text = ax_bscan.text(0.98, 0.95, "RECORDING", transform=ax_bscan.transAxes,
                                color="#00ff66", fontsize=12, weight='bold', ha='right', va='top',
                                bbox=dict(boxstyle='round,pad=0.3', facecolor='#112211', edgecolor='#00aa44', lw=1.5))

    # Hover measurement readout on B-Scan
    hover_text = ax_bscan.text(0.02, 0.95, "Cursor: -- mm | Line: --", transform=ax_bscan.transAxes,
                              color="#aaccff", fontsize=10, weight='bold', ha='left', va='top',
                              bbox=dict(boxstyle='round,pad=0.25', facecolor='#111825', edgecolor='#335577', alpha=0.9))

    # ------------------ BUTTONS & SLIDERS ------------------
    # Button row: [Record/Pause] [Clear] [Mode: Sweep/Waterfall] [Zoom 60mm] [Colormap] [Save Scan]
    btn_y = 0.075
    btn_h = 0.038
    
    ax_btn_rec = fig.add_axes([0.08, btn_y, 0.12, btn_h])
    btn_rec = Button(ax_btn_rec, 'Pause (Space)', color='#2b3540', hovercolor='#3b4a5a')
    btn_rec.label.set_color('white')
    btn_rec.label.set_fontsize(9)

    ax_btn_clear = fig.add_axes([0.21, btn_y, 0.10, btn_h])
    btn_clear = Button(ax_btn_clear, 'Clear (C)', color='#2b3540', hovercolor='#3b4a5a')
    btn_clear.label.set_color('white')
    btn_clear.label.set_fontsize(9)

    ax_btn_mode = fig.add_axes([0.32, btn_y, 0.14, btn_h])
    btn_mode = Button(ax_btn_mode, 'Mode: Sweep (M)', color='#2b3540', hovercolor='#3b4a5a')
    btn_mode.label.set_color('white')
    btn_mode.label.set_fontsize(9)

    ax_btn_zoom = fig.add_axes([0.47, btn_y, 0.12, btn_h])
    btn_zoom = Button(ax_btn_zoom, 'Zoom: Full (Z)', color='#2b3540', hovercolor='#3b4a5a')
    btn_zoom.label.set_color('white')
    btn_zoom.label.set_fontsize(9)

    ax_btn_cm = fig.add_axes([0.60, btn_y, 0.11, btn_h])
    btn_cm = Button(ax_btn_cm, 'Colormap (V)', color='#2b3540', hovercolor='#3b4a5a')
    btn_cm.label.set_color('white')
    btn_cm.label.set_fontsize(9)

    ax_btn_save = fig.add_axes([0.72, btn_y, 0.18, btn_h])
    btn_save = Button(ax_btn_save, 'Save Scan & Data (S)', color='#1b4d3e', hovercolor='#2b6d58')
    btn_save.label.set_color('#88ffcc')
    btn_save.label.set_fontsize(9)
    btn_save.label.set_weight('bold')

    # Sliders row: Contrast and Squelch
    slider_y = 0.020
    slider_h = 0.025
    ax_sld_squelch = fig.add_axes([0.15, slider_y, 0.30, slider_h], facecolor='#22222a')
    sld_squelch = Slider(ax_sld_squelch, 'Squelch', 0, 100, valinit=state.squelch, valstep=1, color='#ff5555')
    sld_squelch.label.set_color('white')
    sld_squelch.label.set_fontsize(9)
    sld_squelch.valtext.set_color('white')

    ax_sld_contrast = fig.add_axes([0.58, slider_y, 0.32, slider_h], facecolor='#22222a')
    sld_contrast = Slider(ax_sld_contrast, 'Contrast', 0.5, 3.0, valinit=state.contrast, valstep=0.1, color='#00ccff')
    sld_contrast.label.set_color('white')
    sld_contrast.label.set_fontsize(9)
    sld_contrast.valtext.set_color('white')

    # Notification text for saved files
    save_msg_text = fig.text(0.5, 0.125, "", color="#00ffaa", fontsize=10, ha="center", weight="bold")

    # ------------------ EVENT HANDLERS ------------------
    def toggle_record(event=None):
        state.is_recording = not state.is_recording
        if state.is_recording:
            btn_rec.label.set_text("Pause (Space)")
            status_text.set_text("RECORDING")
            status_text.set_color("#00ff66")
            status_text.set_bbox(dict(boxstyle='round,pad=0.3', facecolor='#112211', edgecolor='#00aa44', lw=1.5))
        else:
            btn_rec.label.set_text("Resume (Space)")
            status_text.set_text("FROZEN / PAUSED")
            status_text.set_color("#ffaa00")
            status_text.set_bbox(dict(boxstyle='round,pad=0.3', facecolor='#222011', edgecolor='#aa8800', lw=1.5))
        fig.canvas.draw_idle()

    def clear_buffer(event=None):
        state.sweep_data.fill(0)
        state.raw_sweep_data.fill(0)
        state.sweep_idx = 0
        img_bscan.set_data(state.sweep_data)
        scan_cursor.set_xdata([0, 0])
        save_msg_text.set_text("Cleared canvas for new scan.")
        fig.canvas.draw_idle()

    def toggle_mode(event=None):
        if state.mode == "SWEEP":
            state.mode = "WATERFALL"
            btn_mode.label.set_text("Mode: Waterfall (M)")
            title_bscan.set_text("2D B-Scan (Mode: CONTINUOUS WATERFALL | Real-Time Rolling View)")
            img_bscan.set_data(state.waterfall_data)
            img_bscan.set_extent([0, state.waterfall_width, MAX_DEPTH_MM, 0])
            scan_cursor.set_visible(False)
        else:
            state.mode = "SWEEP"
            btn_mode.label.set_text("Mode: Sweep (M)")
            title_bscan.set_text("2D B-Scan (Mode: SWEEP PASS | Manual 1-Axis Slide)")
            img_bscan.set_data(state.sweep_data)
            img_bscan.set_extent([0, state.sweep_width, MAX_DEPTH_MM, 0])
            scan_cursor.set_visible(True)
        fig.canvas.draw_idle()

    def toggle_zoom(event=None):
        state.zoom_near = not state.zoom_near
        if state.zoom_near:
            # Zoom to 0 - 60 mm (coin and near field)
            ax_ascan.set_xlim(0, 60.0)
            ax_bscan.set_ylim(60.0, 0)
            btn_zoom.label.set_text("Zoom: Near (Z)")
        else:
            ax_ascan.set_xlim(0, MAX_DEPTH_MM)
            ax_bscan.set_ylim(MAX_DEPTH_MM, 0)
            btn_zoom.label.set_text("Zoom: Full (Z)")
        fig.canvas.draw_idle()

    def cycle_colormap(event=None):
        state.cm_idx = (state.cm_idx + 1) % len(state.colormaps)
        state.colormap = state.colormaps[state.cm_idx]
        img_bscan.set_cmap(state.colormap)
        btn_cm.label.set_text(f"Color: {state.colormap}")
        fig.canvas.draw_idle()

    def save_scan(event=None):
        os.makedirs("scans", exist_ok=True)
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        img_path = os.path.join("scans", f"bscan_{ts}.png")
        data_path = os.path.join("scans", f"bscan_{ts}.npz")

        # Save high-res PNG of the figure
        fig.savefig(img_path, dpi=200, bbox_inches='tight')

        # Save numpy array with metadata
        current_data = state.sweep_data[:, :max(1, state.sweep_idx)] if state.mode == "SWEEP" else state.waterfall_data
        np.savez_compressed(
            data_path,
            bscan=current_data,
            depth_axis_mm=DEPTH_AXIS,
            contrast=state.contrast,
            squelch=state.squelch,
            sample_rate=SAMPLE_RATE,
            sound_speed=SOUND_SPEED_WATER
        )
        msg = f"Saved: {img_path} & {data_path}"
        print(f"\n[SCAN SAVED] {msg}")
        save_msg_text.set_text(msg)
        fig.canvas.draw_idle()

    def on_squelch_changed(val):
        state.squelch = val
        line_squelch.set_ydata([val, val])
        line_squelch.set_label(f"Squelch ({int(val)})")
        ax_ascan.legend(loc="upper left", fontsize=8, facecolor="#1e1e24", edgecolor="#444455")
        
        # Reprocess displayed sweep if paused
        if not state.is_recording and state.mode == "SWEEP" and state.sweep_idx > 0:
            active_cols = state.sweep_idx
            state.sweep_data[:, :active_cols] = process_signal(
                state.raw_sweep_data[:, :active_cols], state.squelch, state.contrast
            )
            img_bscan.set_data(state.sweep_data)
        fig.canvas.draw_idle()

    def on_contrast_changed(val):
        state.contrast = val
        if not state.is_recording and state.mode == "SWEEP" and state.sweep_idx > 0:
            active_cols = state.sweep_idx
            state.sweep_data[:, :active_cols] = process_signal(
                state.raw_sweep_data[:, :active_cols], state.squelch, state.contrast
            )
            img_bscan.set_data(state.sweep_data)
        fig.canvas.draw_idle()

    # Keyboard shortcut dispatcher
    def on_key_press(event):
        if event.key == " ":
            toggle_record()
        elif event.key in ("c", "C"):
            clear_buffer()
        elif event.key in ("m", "M"):
            toggle_mode()
        elif event.key in ("z", "Z"):
            toggle_zoom()
        elif event.key in ("s", "S"):
            save_scan()
        elif event.key in ("v", "V"):
            cycle_colormap()
        elif event.key == "[":
            sld_squelch.set_val(max(0, state.squelch - 5))
        elif event.key == "]":
            sld_squelch.set_val(min(150, state.squelch + 5))
        elif event.key in ("-", "_"):
            sld_contrast.set_val(max(0.5, round(state.contrast - 0.2, 1)))
        elif event.key in ("+", "="):
            sld_contrast.set_val(min(3.0, round(state.contrast + 0.2, 1)))

    # Connect UI events
    btn_rec.on_clicked(toggle_record)
    btn_clear.on_clicked(clear_buffer)
    btn_mode.on_clicked(toggle_mode)
    btn_zoom.on_clicked(toggle_zoom)
    btn_cm.on_clicked(cycle_colormap)
    btn_save.on_clicked(save_scan)
    sld_squelch.on_changed(on_squelch_changed)
    sld_contrast.on_changed(on_contrast_changed)
    fig.canvas.mpl_connect("key_press_event", on_key_press)

    def on_mouse_move(event):
        if event.inaxes == ax_bscan and event.xdata is not None and event.ydata is not None:
            col = int(round(event.xdata))
            depth_mm = float(event.ydata)
            max_c = state.sweep_width if state.mode == "SWEEP" else state.waterfall_width
            if 0 <= col < max_c:
                sample_idx = int(np.clip(depth_mm / MAX_DEPTH_MM * NUM_SAMPLES, 0, NUM_SAMPLES - 1))
                val = state.sweep_data[sample_idx, col] if state.mode == "SWEEP" else state.waterfall_data[sample_idx, col]
                hover_text.set_text(f"Cursor: {depth_mm:.1f} mm | Line {col} | Echo: {val:.0f}")
                fig.canvas.draw_idle()

    fig.canvas.mpl_connect("motion_notify_event", on_mouse_move)

    plt.show(block=False)

    print("\n" + "="*60, flush=True)
    print(" 1-AXIS ULTRASOUND SCANNER READY", flush=True)
    print("="*60, flush=True)
    print(" Controls:", flush=True)
    print("  [SPACE]      : Start / Pause Sweep Acquisition", flush=True)
    print("  [C]          : Clear Sweep Screen", flush=True)
    print("  [M]          : Toggle Sweep Pass vs Rolling Waterfall", flush=True)
    print("  [Z]          : Toggle Depth Zoom (Full 150mm <-> Near 60mm)", flush=True)
    print("  [S]          : Save B-Scan image (PNG) and raw data (NPZ)", flush=True)
    print("  [V]          : Toggle Colormap (bone, hot, inferno, gray)", flush=True)
    print("  [ [ / ] ]    : Adjust Noise Squelch", flush=True)
    print("  [ - / + ]    : Adjust Contrast Gain", flush=True)
    print("="*60 + "\n", flush=True)

    # ========================================================
    # MAIN GUI LOOP
    # ========================================================
    try:
        while plt.fignum_exists(fig.number) and not stop_event.is_set():
            # 1. Drain ALL pending frames from queue (Zero Frame Loss)
            batch = []
            while True:
                try:
                    payload = data_queue.get_nowait()
                    batch.append(payload)
                except queue.Empty:
                    break

            if batch:
                num_new = len(batch)
                state.total_frames_received += num_new
                state.fps_counter += num_new

                # Process all newly received lines
                raw_matrix = np.frombuffer(b''.join(batch), dtype=np.uint8).reshape(num_new, NUM_SAMPLES).T
                processed_matrix = process_signal(raw_matrix, state.squelch, state.contrast)

                # Keep track of latest single line for A-Scan
                latest_raw = raw_matrix[:, -1]
                latest_sig = processed_matrix[:, -1]
                state.latest_raw = latest_raw
                state.latest_sig = latest_sig

                # Update 1D A-Scan plot
                line_ascan.set_ydata(latest_sig)

                # Peak Echo Detection (ignore ring-down in the first 10mm / ~65 samples)
                BLANKING_SAMPLES = 60
                if np.max(latest_sig[BLANKING_SAMPLES:]) > 20:
                    peak_idx = BLANKING_SAMPLES + np.argmax(latest_sig[BLANKING_SAMPLES:])
                    peak_depth = DEPTH_AXIS[peak_idx]
                    peak_val = latest_sig[peak_idx]
                    peak_marker.set_data([peak_depth], [peak_val])
                    peak_text.set_text(f"Echo Peak: {peak_depth:.1f} mm\nAmp: {peak_val:.0f}/255")
                else:
                    peak_marker.set_data([], [])
                    peak_text.set_text("Echo Peak: None")

                # Update 2D B-Scan Display
                if state.mode == "SWEEP":
                    if state.is_recording:
                        curr = state.sweep_idx
                        end = curr + num_new

                        # Dynamically expand width if sweep exceeds initial buffer
                        if end > state.sweep_width:
                            add_cols = max(num_new, 500)
                            state.sweep_data = np.hstack([state.sweep_data, np.zeros((NUM_SAMPLES, add_cols), dtype=np.float32)])
                            state.raw_sweep_data = np.hstack([state.raw_sweep_data, np.zeros((NUM_SAMPLES, add_cols), dtype=np.uint8)])
                            state.sweep_width += add_cols
                            img_bscan.set_extent([0, state.sweep_width, MAX_DEPTH_MM, 0])
                            ax_bscan.set_xlim(0, state.sweep_width)

                        state.sweep_data[:, curr:end] = processed_matrix
                        state.raw_sweep_data[:, curr:end] = raw_matrix
                        state.sweep_idx = end

                        img_bscan.set_data(state.sweep_data)
                        scan_cursor.set_xdata([end, end])

                elif state.mode == "WATERFALL":
                    # Roll waterfall and insert new batch at the right edge
                    if num_new >= state.waterfall_width:
                        state.waterfall_data = processed_matrix[:, -state.waterfall_width:]
                    else:
                        state.waterfall_data = np.roll(state.waterfall_data, -num_new, axis=1)
                        state.waterfall_data[:, -num_new:] = processed_matrix
                    img_bscan.set_data(state.waterfall_data)

                # FPS Calculation
                now = time.time()
                elapsed = now - state.fps_timer
                if elapsed >= 1.0:
                    state.current_fps = state.fps_counter / elapsed
                    state.fps_counter = 0
                    state.fps_timer = now
                    # Update window title with live PRF stats
                    fig.canvas.manager.set_window_title(
                        f"Ultrasound 1-Axis Scanner | PRF: {state.current_fps:.1f} Hz | "
                        f"Frames: {state.total_frames_received} | Mode: {state.mode}"
                    )

                fig.canvas.draw_idle()
                fig.canvas.flush_events()

            plt.pause(0.005)

    except KeyboardInterrupt:
        print("\n[Application] KeyboardInterrupt received. Exiting...")
    finally:
        stop_event.set()
        try: ser.close()
        except: pass
        plt.close('all')
        print("[Application] Cleanup finished.")

# ============================================================
# CLI ENTRY POINT
# ============================================================
def main():
    parser = argparse.ArgumentParser(description="Manual 1-Axis Ultrasound B-Scan Acquisition")
    parser.add_argument("-p", "--port", type=str, help="Serial COM port (e.g., COM10)")
    args = parser.parse_args()

    com_port = args.port
    if not com_port:
        ports = list(serial.tools.list_ports.comports())
        # Filter for FTDI / USB Serial devices
        usb_ports = [p.device for p in ports if "USB" in p.description or "FTDI" in p.description or "UART" in p.description]
        
        if len(usb_ports) == 1:
            com_port = usb_ports[0]
            print(f"[Auto-Detect] Found USB Serial Device on {com_port}")
        else:
            print("\nAvailable COM ports:")
            for p in ports:
                print(f"  - {p.device}: {p.description}")
            if not ports:
                print("  (No COM ports found! Check FPGA USB connection.)")
                sys.exit(1)
            com_port = input("\nEnter COM port (default COM10): ").strip() or "COM10"

    run_app(com_port)

if __name__ == "__main__":
    main()
