# Ultrasound 1-Axis B-Scan Acquisition & Visualization (Java)

High-performance, object-oriented Java replacement for the Python ultrasound acquisition scripts (`manual_bscan.py` and `test.py`).

---

## 🚀 Why Java? (Eliminating Python & Matplotlib Lag)

In Python, `matplotlib` redraws entire figures inside a single-threaded GIL event loop. When updating 1024×1000 pixel waterfall/sweep matrices at 50–100+ Hz Pulse Repetition Frequency (PRF), Python suffers severe frame drops, UI stutter, and display lag.

This Java rewrite solves those performance bottlenecks through:
1. **Decoupled Multi-Threading**: Serial data acquisition runs on a dedicated high-priority daemon thread parsing the 2 Mbaud binary stream (`0xFF 0xAA 0x55 0x00` sync header) without ever blocking the UI.
2. **Zero-Allocation Memory Architecture**: Incoming 1024-byte A-scan lines are copied directly into contiguous memory blocks via native `System.arraycopy()`, avoiding per-frame GC pressure.
3. **Hardware-Accelerated Direct Blitting**: Instead of plotting slow scatter/image primitives, pixel intensities are translated via precomputed 256-element Look-Up Tables (LUTs) directly into a `BufferedImage` integer raster `int[]` and blitted via Direct3D/OpenGL hardware acceleration in **sub-millisecond (< 1 ms)** redraw times.
4. **Interactive Reprocessing**: When paused in Sweep mode, moving Squelch or Contrast immediately re-filters the stored raw A-scan buffer in memory without modifying the original sensor data.

---

## 🏛️ Object-Oriented Architecture

The project adheres to clean OOP principles and separation of concerns:

```
bscan-java/
├── pom.xml
├── run.bat
├── run-simulation.bat
├── mvnw.cmd
└── src/
    ├── main/java/com/ultrasound/bscan/
    │   ├── BScanApplication.java         # Main entry point & DI container
    │   ├── model/
    │   │   ├── ScanConfig.java           # Immutable physics (5 MHz, 1480 m/s, depth mapping)
    │   │   ├── ScanMode.java             # Enum: SWEEP, WATERFALL
    │   │   ├── PeakEcho.java             # Detected reflection record
    │   │   ├── AScanFrame.java           # Time-stamped A-scan frame
    │   │   ├── ScanBuffer.java           # Thread-safe dual buffer (Dynamic sweep + Waterfall)
    │   │   ├── ScannerState.java         # Presentation model & state listener hub
    │   │   └── StateChangeListener.java  # Observer pattern interface
    │   ├── dsp/
    │   │   ├── SignalProcessor.java      # DSP contract (squelch, contrast, clipping, blanking)
    │   │   └── DefaultSignalProcessor.java # High-speed vectorized signal processing
    │   ├── colormap/
    │   │   ├── Colormap.java             # Strategy interface for 32-bit ARGB LUTs
    │   │   ├── ColormapType.java         # Enum: BONE, HOT, INFERNO, GRAY
    │   │   ├── BoneColormap.java         # Medical ultrasound blue-slate palette
    │   │   ├── HotColormap.java          # Black -> Red -> Yellow -> White
    │   │   ├── InfernoColormap.java      # Perceptually uniform scientific colormap
    │   │   ├── GrayColormap.java         # Standard grayscale
    │   │   └── ColormapRegistry.java     # Colormap factory & registry
    │   ├── io/
    │   │   ├── DataAcquisitionSource.java# Data source interface
    │   │   ├── SerialDataAcquisitionSource.java # jSerialComm 2 Mbaud binary reader
    │   │   ├── SimulatedDataAcquisitionSource.java # Synthetic ultrasound simulator
    │   │   ├── PortInfo.java             # Port descriptor record
    │   │   └── PortDiscoveryService.java # Auto-detects USB/FTDI serial devices
    │   ├── storage/
    │   │   ├── ScanExporter.java         # Exporter interface
    │   │   └── DefaultScanExporter.java  # High-res PNG & CSV metadata export
    │   ├── ui/
    │   │   ├── AScanPanel.java           # Real-time 1D envelope plot with peak callout
    │   │   ├── BScanPanel.java           # 2D cross-section raster with mouse hover HUD
    │   │   ├── ControlPanel.java         # Connection bar, action buttons & DSP sliders
    │   │   └── StatusBar.java            # Notifications, PRF metrics & frame stats
    │   └── controller/
    │       └── ScannerController.java    # MVC controller & 60 FPS event scheduler
    └── test/java/com/ultrasound/bscan/
        ├── SignalProcessorTest.java      # Verifies DSP math, blanking & peak detection
        ├── ScanConfigTest.java           # Verifies depth-to-sample acoustic calculations
        ├── ScanBufferTest.java           # Verifies sweep expansion & waterfall rolling
        ├── ColormapTest.java             # Verifies ARGB LUT color values
        └── SerialProtocolTest.java       # Verifies 4-byte sync header parsing & recovery
```

---

## ⚡ Controls & Keyboard Shortcuts

| Shortcut | Action | Description |
| :--- | :--- | :--- |
| `[SPACE]` | **Pause / Resume** | Freezes acquisition or resumes live streaming |
| `[C]` | **Clear** | Clears the current sweep and waterfall buffers |
| `[M]` | **Mode Toggle** | Switches between **Sweep Pass** and **Continuous Waterfall** |
| `[Z]` | **Depth Zoom** | Toggles between **Full Depth (~151.4 mm)** and **Near Field (0–60 mm)** |
| `[V]` | **Colormap** | Cycles through `Bone`, `Hot`, `Inferno`, and `Grayscale` |
| `[S]` | **Save Scan** | Exports high-resolution PNG image and raw CSV metadata into `scans/` |
| `[` / `]` | **Squelch ±5** | Decreases / Increases noise reject squelch threshold |
| `-` / `+` | **Contrast ±0.2** | Decreases / Increases dynamic contrast gain |

---

## 🛠️ Building & Running

### Requirements
- **Java 21+** (Java 25 verified)
- **Maven 3.8+** (or use the included `mvnw.cmd` wrapper)

### 1. Quick Launch (Automated Build & Run)
Double-click or run:
```cmd
run.bat
```

### 2. Run with Synthetic Ultrasound Simulator (No hardware needed)
```cmd
run-simulation.bat
```
*(or pass `--sim` to `run.bat`)*

### 3. Connect Directly to a Specific COM Port
```cmd
run.bat -p COM10
```

### 4. Build with Maven Manually
```cmd
mvn clean package
java -jar target/bscan-java-1.0.0.jar
```
*(If `mvn` is not globally on your PATH, use `mvnw.cmd clean package`)*

---

## 🧪 Running Unit Tests

Run the test suite:
```cmd
mvn test
```
All 11 automated unit tests cover:
- DSP squelch thresholding and contrast clipping
- Ring-down blanking and peak detection
- Depth calibration ($0.148\text{ mm/sample}$)
- Dynamic sweep buffer auto-expansion
- Waterfall scrolling
- 24/32-bit ARGB colormaps
- Multi-byte serial framing synchronization
