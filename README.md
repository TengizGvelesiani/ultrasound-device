# Single Element Ultrasound Imaging & Volumetric Scanning System

An open-source hardware, FPGA firmware, and high-performance software project for single-element pulse-echo ultrasound imaging. This repository contains the complete design files:
* **Analog Front End (AFE) & High-Voltage Pulser PCB** (Altium Designer)
* **Modular FPGA Digital Signal Processing & Stepper Gantry Firmware** (VHDL / Xilinx Vivado)
* **Real-time 2D B-Scan & 2.5D / 3D Volumetric Visualization Application** (Java 21 / Maven)

---

## System Architecture

```
+---------------------------------------------------------------------------------------------------------+
|                                        ANALOG FRONT END (AFE)                                           |
|                                                                                                         |
|  [ HV Power / Relay ] ---> [ MD1213 Driver + TC6320 FETs ] ---> [ 5 MHz Transducer ]                     |
|                                                                          |                              |
|  [ AD9226 20 MSPS ADC ] <-- [ AD8331 VGA ] <--- [ T/R Switch / Clamp ] <--+                              |
|           |                       ^                                                                     |
|           |                       |                                                                     |
|           |            [ MCP4821 SPI DAC (TGC) ]                                                        |
+-----------|-----------------------|---------------------------------------------------------------------+
            |                       |
            v                       v
+---------------------------------------------------------------------------------------------------------+
|                                            FPGA FIRMWARE                                                |
|                                                                                                         |
|  [ pulser_fsm ] ----------> Relay, OE, INA, INB Timing (60ns Strike, 40ns Dead-Time, 500ns Active Damp)|
|        ^                    • Benchtop Live Mode: Free-running 100 Hz PRF                               |
|        |                    • Scan Mode: Position-Triggered Firing (every 40 microsteps = 0.1 mm)        |
|        |                                                                                                |
|  [ stepper_controller ] --> Dual Locked Bottom Y-Axis (Y1 + Y2) & Top X-Axis Steppers (1/16 Microstep)   |
|        |                    • Autonomous Serpentine Raster Trajectory Engine                            |
|        |                    • Hardware Gating: Pulser inhibited during Y indexing                       |
|        |                    • acq_busy Flow Control Interlock (Zero Dropped Frames)                     |
|        v                                                                                                |
|  [ dsp_pipeline ] --------> EWMA DC Rejection -> 5 MHz Mix (fs/4) -> FIR Lowpass -> CORDIC -> Log LUT  |
|        |                    (Decimated 4:1 to 5 MSPS Baseband Envelope)                                 |
|        v                                                                                                |
|  [ acquisition_ram ] -----> 1024-Byte BRAM Buffer -> 2,000,000 Baud UART Streamer                       |
|        ^                                                                                                |
|  [ uart_rx_protocol ] ----> 2 Mbaud Midpoint RX & 16-Byte Host Command Protocol Decoder (XOR Checksum) |
+--------|------------------------------------------------------------------------------------------------+
         |
         |  2 Mbaud Binary Telemetry: [0xFF, 0xAA, 0x55, 0x00] + 1024 Envelope Bytes
         |  16-Byte Binary Host Commands: [0xA5, 0x5A, CMD, AXIS, DIR, PARAM1..3, CHKSUM]
         v
+---------------------------------------------------------------------------------------------------------+
|                                  JAVA HOST APPLICATION (ultrasound-app)                                 |
|                                                                                                         |
|  • Real-Time A-Scan: 1D RF Envelope Waveform, Ring-Down Blanking, Dynamic Peak Echo Marker (mm & Amp)  |
|  • 2D B-Scan: Sweep Pass & Continuous Rolling Waterfall Imaging, Instant Squelch & Contrast Reprocess  |
|  • 2.5D / 3D Volumetric: Shaded Topography (Lambertian + Blinn-Phong + Depth Sorting), C-Scan MIP     |
|  • Gantry & Motion Control: Dual-Y Locked & X Jogging, Speed Calibration, 1-Click Chamber Scan Trigger |
|  • Multi-View 3D Snapshot Suite: Automated 7-View PNG Report Sheet + Calibrated Colorbars + CSV        |
|  • Integrated Synthetic Ultrasound Simulator: Acoustic immersion tank & 3D coin phantom modeling      |
+---------------------------------------------------------------------------------------------------------+
```

---

## 1. Hardware & Analog Front End (AFE)
* **High-Voltage Pulser:** Driven by an **MD1213** high-speed dual-MOSFET driver paired with a **TC6320** complementary MOSFET pair. The FPGA generates nanosecond-precise half-bridge excitation bursts ($3$ cycles at $5\text{ MHz}$, $60\text{ ns}$ strike, $40\text{ ns}$ shoot-through dead-times, and $500\text{ ns}$ active ringing damp).
* **Variable Gain Amplifier (VGA):** Features an **AD8331** ultralow-noise VGA optimized for medical ultrasound. Gain is dynamically ramped by an **MCP4821** 12-bit SPI DAC generating hardware Time-Gain Compensation (TGC) to compensate for acoustic attenuation ($e^{-\alpha f z}$).
* **Data Acquisition (ADC):** An **AD9226** 12-bit parallel ADC samples high-frequency RF echoes at $20\text{ MSPS}$.

---

## 2. FPGA Digital Signal Processing & Motion Firmware
The FPGA firmware is structured into dedicated, modular VHDL components coordinated by a structural top-level entity (`pulser_rtz`):

| Module | File | Description |
| :--- | :--- | :--- |
| **pulser_fsm** | [`pulser_fsm.vhd`](file:///C:/Projects/Ultrasound/ultrasound-device/FPGA/FPGA.srcs/sources_1/new/pulser_fsm.vhd) | HV relay pre-charge, gate driver enable, 3-cycle $5\text{ MHz}$ burst timing, shoot-through protection, active ringing damp, and dual-mode trigger control. |
| **stepper_controller** | [`stepper_controller.vhd`](file:///C:/Projects/Ultrasound/ultrasound-device/FPGA/FPGA.srcs/sources_1/new/stepper_controller.vhd) | Dual locked bottom Y-axis motors (Y1+Y2) & top X carriage, $1/16$ microstepping, autonomous serpentine raster scanning, position-triggered pulser, and hardware gating. |
| **dsp_pipeline** | [`dsp_pipeline.vhd`](file:///C:/Projects/Ultrasound/ultrasound-device/FPGA/FPGA.srcs/sources_1/new/dsp_pipeline.vhd) | EWMA DC baseline subtraction, multiplier-less quadrature mixing ($f_s/4$), FIR lowpass filtering, CORDIC envelope magnitude ($\sqrt{I^2+Q^2}$), log compression BRAM LUT, and $4:1$ decimation to $5\text{ MSPS}$. |
| **tgc_controller** | [`tgc_controller.vhd`](file:///C:/Projects/Ultrasound/ultrasound-device/FPGA/FPGA.srcs/sources_1/new/tgc_controller.vhd) | Hardware Time-Gain Compensation SPI DAC ramp curve generator synchronized to pulser strike. |
| **acquisition_ram** | [`acquisition_ram.vhd`](file:///C:/Projects/Ultrasound/ultrasound-device/FPGA/FPGA.srcs/sources_1/new/acquisition_ram.vhd) | $1024$-byte BRAM line buffer, $2,000,000\text{ baud}$ UART transmitter, and flow-control handshake (`acq_busy`). |
| **uart_rx_protocol** | [`uart_rx_protocol.vhd`](file:///C:/Projects/Ultrasound/ultrasound-device/FPGA/FPGA.srcs/sources_1/new/uart_rx_protocol.vhd) | $2\text{ Mbaud}$ midpoint serial sampler and 16-byte host command protocol decoder with XOR checksum verification. |
| **pulser_rtz** | [`top.vhd`](file:///C:/Projects/Ultrasound/ultrasound-device/FPGA/FPGA.srcs/sources_1/new/top.vhd) | Structural top-level entity integrating all subsystem modules and mapping physical I/O constraints. |

### Position-Triggered Pulser & Hardware Gating
During continuous serpentine chamber scans, triggering on a free-running timer causes acceleration skew (stretched pixels at sweep edges) and serpentine comb/herringbone phase shift artifacts. 

To solve this:
1. **Position-Triggered Pulser**: When in chamber scan mode (`M_SCAN_SWEEP_X`), the pulser is slave-locked to the stepper displacement counter. Ultrasound excitation occurs strictly every $N$ microsteps (default $40\text{ microsteps} = 0.100\text{ mm}$ of carriage travel).
2. **Hardware Gating during Line Indexing**: During Y-axis advancement (`M_SCAN_STEP_Y`) and idle, pulse generation is strictly inhibited so no diagonal or stationary frames pollute the 3D volume.
3. **Flow Control Interlock**: `stepper_controller` checks `acquisition_ram.acq_busy` before advancing past a pulse interval, guaranteeing zero dropped frames even at maximum gantry velocity.
4. **Benchtop Fallback**: When idle or jogging manually, the FPGA automatically reverts to the safe free-running $100\text{ Hz}$ PRF timer for real-time acoustic feedback.

---

## 3. Java Host Application (`ultrasound-app`)
A modern, dark-themed Java 21+ desktop application replacing legacy Python scripts for low-latency acquisition and rendering:

* **Real-time 2D B-Scan Display**: Dual-mode Sweep Pass and Continuous Waterfall display with medical colormaps (`Bone`, `Hot`, `Inferno`, `Grayscale`).
* **Real-Time 1D A-Scan Window**: Live RF envelope display with ring-down blanking gate ($0\dots 60$ samples) and real-time echo peak detector displaying millimeter depth and echo amplitude ($0\dots 255$).
* **Interactive Dynamic DSP**: Real-time Squelch and Contrast Gain sliders that instantly re-filter already recorded frozen sweeps without re-scanning.
* **2.5D / 3D Volumetric Visualization**: Real-time 3D rendered surface topography with smooth Lambertian hill-shading, Blinn-Phong diffuse lighting, and Painter's algorithm depth sorting that completely eliminates polygon overlap and visual tearing.
* **Camera Navigation & Presets**: 6-DOF camera orbit (left drag), pan (right drag), zoom (mouse wheel), and instant hotkeys (`[1]` Isometric, `[2]` Top-Down C-Scan, `[3]` Front XZ, `[4]` Side YZ, `[5]` Reverse Isometric, `[0]` Reset).
* **Multi-View 3D Snapshot Suite (`[S]`)**: Generates **7 distinct PNG visualizations** in one click:
  1. `_multiview.png`: Master 4-in-1 Report Sheet ($1600 \times 1100$) combining Top-Down, 3D Isometric, Front XZ, and Side YZ views with calibrated colorbars and acquisition metadata.
  2. `_iso.png`: Isometric Perspective 3D View.
  3. `_top.png`: Top-Down En-Face View (C-Scan XY Map).
  4. `_front.png`: Front Elevation View (XZ Cross-Section).
  5. `_side.png`: Side Elevation View (YZ Cross-Section).
  6. `_back_iso.png`: Reverse Angle Isometric View.
  7. `_active.png`: Current User Viewport.
  8. Raw CSV Data Table (`_data.csv`).
* **Stage & Motor Control Panel**:
  * Real-time gantry coordinates display $(X, Y)$ in millimeters and microsteps.
  * Manual jog buttons for $X$ carriage and dual locked bottom $Y$ motors (both bottom motors guaranteed to move in unison).
  * Jog step size dropdown ($0.1\text{ mm}$, $0.5\text{ mm}$, $1.0\text{ mm}$, $5.0\text{ mm}$, $10.0\text{ mm}$, $25.0\text{ mm}$).
  * Interactive gantry speed slider ($1.0\dots 30.0\text{ mm/s}$).
  * Container / chamber dimension inputs ($X$, $Y$, line spacing $\Delta Y$, steps/mm calibration).
  * Styled hero button: `TRIGGER FULL CHAMBER SCAN`.
* **Integrated Synthetic Ultrasound Simulator**: Models an acoustic immersion tank with a submerged 3D circular coin phantom, relief engraving, metal backing plate, and Gaussian speckle noise.

---

## Binary Communication Protocols

### 1. Host-to-FPGA Command Protocol (16 Bytes Fixed)
Transmitted over UART at $2,000,000\text{ baud}$ ($8\text{N}1$):

| Byte Offset | Field | Description |
| :--- | :--- | :--- |
| `0` | `0xA5` | Sync Byte 1 |
| `1` | `0x5A` | Sync Byte 2 |
| `2` | `CMD` | Command ID (`0x00`: HALT, `0x01`: JOG, `0x02`: CHAMBER_SCAN, `0x03`: SET_SPEED, `0x04`: ZERO) |
| `3` | `AXIS` | Target Axis (`0x01`: X, `0x02`: Y [Dual Locked]) or $\Delta X$ steps for Chamber Scan |
| `4` | `DIR` | Direction (`0x00`: Negative, `0x01`: Positive) |
| `5 .. 8` | `PARAM 1` | 32-bit unsigned Big-Endian: Jog Steps / Chamber Total X Steps |
| `9 .. 12` | `PARAM 2` | 32-bit unsigned Big-Endian: Step Half-Period Cycles / Chamber Total Y Steps |
| `13 .. 14` | `PARAM 3` | 16-bit unsigned Big-Endian: Chamber Delta Y Line Increment Steps |
| `15` | `CHECKSUM` | XOR sum of bytes `2 .. 14` |

### 2. FPGA-to-Host Telemetry Protocol (1028 Bytes Fixed)
Streamed over UART at $2,000,000\text{ baud}$ ($8\text{N}1$):

| Byte Offset | Field | Description |
| :--- | :--- | :--- |
| `0 .. 3` | `[0xFF, 0xAA, 0x55, 0x00]` | Frame Synchronization Header |
| `4 .. 1027` | `ENVELOPE[1024]` | 1024 baseband envelope samples ($5\text{ MSPS}$ decimated, log-compressed $0\dots 255$) |

---

## Repository Structure

```
ultrasound-device/
├── .gitignore                     # Comprehensive repository ignore rules
├── README.md                      # System documentation and hardware/firmware overview
├── log_lut.coe                    # 1024-word BRAM Logarithmic Compression coefficients
├── Pulser_PCB/                    # Altium Designer project files
│   ├── Pulser.PrjPcb              # Master project
│   ├── Pulser.SchDoc              # HV pulser, VGA, and ADC schematics
│   └── Pulser.PcbDoc              # High-speed PCB layout and routing
├── FPGA/                          # Xilinx Vivado Project
│   ├── FPGA.xpr                   # Vivado project file
│   └── FPGA.srcs/
│       ├── constrs_1/new/
│       │   └── constraints.xdc    # Physical pin mapping and clock timing constraints
│       └── sources_1/new/
│           ├── top.vhd            # Structural top-level entity (pulser_rtz)
│           ├── pulser_fsm.vhd     # HV pulser sequence & circuit protection
│           ├── tgc_controller.vhd # Time-Gain Compensation SPI DAC driver
│           ├── dsp_pipeline.vhd   # Quadrature mixer, FIR, CORDIC & log LUT
│           ├── acquisition_ram.vhd# BRAM buffer & 2 Mbaud UART TX
│           ├── uart_rx_protocol.vhd# 2 Mbaud UART RX command decoder
│           └── stepper_controller.vhd # Stepper gantry & position-triggered raster engine
├── ultrasound-app/                # Java 21+ Desktop Acquisition & Visualization Application
│   ├── pom.xml                    # Maven configuration and shade packaging
│   ├── README.md                  # Java application user guide and controls reference
│   └── src/
│       ├── main/java/com/ultrasound/bscan/
│       │   ├── UltrasoundApp.java          # Main entry point and CLI parser
│       │   ├── controller/                 # Scanner and Stepper motor services
│       │   ├── dsp/                        # Peak detection, squelch, and contrast processing
│       │   ├── io/                         # Serial communications, simulation, protocol
│       │   ├── model/                      # A-Scan, B-Scan, Volume buffer models
│       │   ├── storage/                    # Multi-view snapshot and CSV data export
│       │   ├── ui/                         # Swing dark-themed UI components and 3D renderer
│       │   └── colormap/                   # Medical colormaps (Bone, Hot, Inferno, Gray)
│       └── test/java/com/ultrasound/bscan/ # 35 JUnit 5 test suites
```

---

## Getting Started

### Prerequisites
* **Altium Designer** (for PCB viewing and fabrication output generation)
* **Xilinx Vivado (2018.3 or newer)** (for FPGA synthesis and bitstream generation)
* **Java Development Kit (JDK 21+)** (Java 21, 23, or 25 verified)
* **Maven 3.8+** (or use the included `mvnw.cmd` wrapper in `ultrasound-app/`)

### Building the FPGA Bitstream
1. Open Xilinx Vivado.
2. Select **Open Project** and navigate to `FPGA/FPGA.xpr`.
3. Click **Generate Bitstream** in Vivado to synthesize, implement, and generate `pulser_rtz.bit`.
4. Program the target FPGA board using the Vivado Hardware Manager via JTAG.

### Building & Running the Java Application
Open a terminal in the `ultrasound-app/` directory:

1. **Run Unit Tests (35/35 passing)**:
   ```cmd
   cd ultrasound-app
   .\mvnw.cmd test -o
   ```

2. **Build Production Executable Application JAR**:
   ```cmd
   .\mvnw.cmd package -DskipTests -o
   ```

3. **Launch in 2.5D / 3D Simulation Mode (Instant Coin Phantom Test)**:
   ```cmd
   java -jar target/ultrasound-app-1.0.0.jar --sim --3d
   ```

4. **Launch Standard 2D B-Scan Mode**:
   ```cmd
   java -jar target/ultrasound-app-1.0.0.jar
   ```

5. **Connect Directly to Hardware COM Port**:
   ```cmd
   java -jar target/ultrasound-app-1.0.0.jar -p COM10
   ```

---

## Key Shortcuts in Java Application

| Shortcut | Action | Description |
| :--- | :--- | :--- |
| `[TAB]` or `[T]` | **Toggle 2D / 3D** | Switches between 2D B-Scan and 2.5D/3D Volumetric mode |
| `[Y]` | **Cycle 3D Style** | Cycles between Shaded Surface, Wireframe Mesh, and C-Scan MIP |
| `[1]` .. `[5]` / `[0]` | **Camera Snap** | `1`: Isometric, `2`: Top-Down, `3`: Front XZ, `4`: Side YZ, `5`: Reverse, `0`: Reset |
| `[SPACE]` | **Pause / Resume** | Freezes live acquisition or resumes real-time scanning |
| `[C]` | **Clear Buffers** | Clears the active 2D sweep or 3D volume buffer |
| `[V]` | **Cycle Colormap** | Cycles: `Bone` (medical), `Hot`, `Inferno`, `Grayscale` |
| `[S]` | **Save Scan** | Exports 2D B-Scan or full **3D Multi-View Suite (7 PNGs + CSV)** into `scans/` |
| `[Z]` | **Depth Zoom** | Toggles between Full Depth ($151.4\text{ mm}$) and Near Field ($0\dots 60\text{ mm}$) |
| `[` / `]` | **Squelch ±5** | Adjusts noise rejection threshold |
| `-` / `+` | **Contrast ±0.2** | Adjusts contrast gain multiplier |

---

## Disclaimer

**For educational, research, and experimental laboratory purposes only.** This hardware generates high-voltage pulses ($\pm 50\text{ V}\dots \pm 100\text{ V}$) and is not an approved medical device. It has not been evaluated or certified by the FDA, CE, or any other regulatory authority. Do not use this device on human subjects. The authors and contributors assume no liability for personal injury, circuit damage, or property loss resulting from the construction or use of this project.

---

## License

This project is licensed under the [MIT License](LICENSE) - see the LICENSE file for details.
