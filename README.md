# Open Ultrasound: Single-Element Pulse-Echo & 3D Scanning Rig

An open-source pulse-echo ultrasound imaging system built from the ground up. This repository contains the complete hardware, FPGA firmware, and software stack for capturing both 2D B-scan cross-sections and 3D volumetric surface scans of submerged targets:

* **Analog Front End (AFE) & High-Voltage Pulser PCB** (Altium Designer)
* **Real-Time DSP & Gantry Controller Firmware** (VHDL / Digilent Arty-S7)
* **Desktop Acquisition & 3D Visualization App** (Java 21 / Swing)

---

## What Is This?

Commercial ultrasound machines are expensive black boxes. If you want to experiment with custom beamforming, acoustic non-destructive testing (NDT), or high-frequency surface mapping, getting raw RF data out of vendor hardware is notoriously difficult.

This project is a completely open, benchtop-friendly ultrasound rig. It uses a single 5 MHz immersion transducer mounted on a motorized 2-axis (dual-Y, single-X) Cartesian gantry over a water immersion tank. An FPGA fires nanosecond high-voltage pulses, coordinates time-gain amplification, digitizes echoes at 20 MSPS, demodulates them to baseband in hardware, and streams the acoustic envelope over high-speed UART to a modern Java desktop application for live 2D and 3D visualization.

```
+---------------------------------------------------------------------------------------+
|                                    TRANSDUCER & TANK                                  |
|                                                                                       |
|   [Water Tank / Sample] <======== (5 MHz Acoustic Wave) ========> [Immersion Probe]   |
+---------------------------------------------------------------------------|-----------+
                                                                            |
                                            +-------------------------------+
                                            | (High-Voltage Pulse / Microvolt Echoes)
                                            v
+---------------------------------------------------------------------------------------+
|                                ANALOG FRONT END (PCB)                                 |
|                                                                                       |
|   HV Supply (+/-50V..100V) ---> [MD1213 Driver + TC6320 FETs]                         |
|                                         |                                             |
|                                         v (Fast HV Strike)                            |
|                                [Diode Bridge / TR Clamp]                              |
|                                         |                                             |
|                                         v (Echoes Only)                               |
|   [MCP4821 SPI DAC] ----------> [AD8331 VGA & LNA]                                    |
|   (Time-Gain Compensation)              |                                             |
|                                         v                                             |
|                                 [AD9226 20 MSPS ADC]                                  |
+------------------------------------------|--------------------------------------------+
                                           | 12-bit Parallel Bus @ 20 MHz
                                           v
+---------------------------------------------------------------------------------------+
|                               DIGILENT ARTY-S7 (SPARTAN-7)                            |
|                                                                                       |
|   [pulser_fsm] ---------> HV gate timing (60ns strike, 40ns dead-time, 500ns damp)    |
|   [tgc_controller] -----> Generates SPI ramp curve synchronized to each strike        |
|   [stepper_controller] -> Dual locked Y-axis (Y1+Y2) & X carriage (1/16 microstep)    |
|                           • Position-triggered firing: pulse every 40 microsteps      |
|                           • Hardware pulser gating during line indexing               |
|                           • acq_busy flow-control handshake                           |
|   [dsp_pipeline] -------> DC reject -> Multiplier-less 5 MHz mix -> FIR -> CORDIC    |
|                           • Logarithmic compression BRAM LUT (log_lut.coe)            |
|                           • 4:1 decimation to 5 MSPS baseband envelope                |
|   [acquisition_ram] ----> 1024-byte BRAM buffer -> 2,000,000 baud UART streamer      |
+------------------------------------------|--------------------------------------------+
                                           | 2 Mbaud Binary Telemetry over USB
                                           v
+---------------------------------------------------------------------------------------+
|                          JAVA HOST APPLICATION (ultrasound-app)                       |
|                                                                                       |
|   • Live 1D A-Scan: RF envelope trace, ring-down blanking, peak echo tracker (mm)     |
|   • Real-Time 2D B-Scan: Sweep pass & continuous waterfall modes                      |
|   • 2.5D / 3D Topography: Shaded surface, wireframe mesh, and C-scan MIP projection   |
|   • 1-Click Multi-View Suite: Exports 7 PNG views + raw CSV data table                |
|   • Gantry Controls: Jogging, speed calibration, and 1-click autonomous chamber scan  |
|   • Built-in Simulator: Full acoustic tank & 3D coin phantom modeling (no HW needed!) |
+---------------------------------------------------------------------------------------+
```

---

## How It Works

### 1. The Analog Front End (AFE)
Capturing ultrasound echoes requires dealing with an extreme dynamic range: you have to slam the transducer with a short high-voltage burst (up to 100 V) to ring the piezo crystal, and then listen for faint microvolt-level echoes returning microseconds later from acoustic impedance boundaries.

* **High-Voltage Pulser**: Uses a Microchip **MD1213** high-speed dual MOSFET driver paired with a **TC6320** complementary N/P MOSFET pair. The FPGA triggers precise half-bridge excitation bursts: a 60 ns strike, 40 ns dead time to prevent shoot-through, and a 500 ns active dampening pulse to quench transducer ringing so the near field doesn't get blinded.
* **T/R Switch & Diode Clamp**: Fast diode bridge clamps the receiving line during the high-voltage transmit phase, protecting the sensitive receiver without adding heavy insertion loss.
* **Time-Gain Compensation (TGC)**: Acoustic waves attenuate exponentially as they travel through a medium ($e^{-\alpha f z}$). Echoes from deep targets would be lost in the noise floor if gain were static. We use an Analog Devices **AD8331** ultralow-noise Variable Gain Amplifier (VGA) whose gain is ramped up in real time by an **MCP4821** 12-bit SPI DAC synchronized to each pulse strike.
* **Digitization**: An **AD9226** 12-bit parallel ADC samples the conditioned RF echo waveform at 20 MSPS.

The complete schematics and multi-layer PCB layout are in the [`PCB/`](PCB/) directory (Altium Designer).

---

### 2. FPGA DSP & Hardware Architecture (Digilent Arty-S7)
All timing, digital filtering, and gantry motion logic runs inside a Xilinx Spartan-7 FPGA on an Arty-S7 board. The VHDL architecture is split into clean, modular blocks:

| Module | Source File | Role |
| :--- | :--- | :--- |
| `pulser_fsm` | [`pulser_fsm.vhd`](FPGA/FPGA.srcs/sources_1/new/pulser_fsm.vhd) | Generates HV strike pulses, shoot-through protection, active damping, and relays. |
| `tgc_controller` | [`tgc_controller.vhd`](FPGA/FPGA.srcs/sources_1/new/tgc_controller.vhd) | SPI driver generating the real-time analog gain ramp for the AD8331 VGA. |
| `dsp_pipeline` | [`dsp_pipeline.vhd`](FPGA/FPGA.srcs/sources_1/new/dsp_pipeline.vhd) | EWMA DC filter, quadrature mixer, FIR lowpass, CORDIC envelope, and log compression. |
| `acquisition_ram` | [`acquisition_ram.vhd`](FPGA/FPGA.srcs/sources_1/new/acquisition_ram.vhd) | 1024-byte BRAM line buffer and 2 Mbaud UART transmitter. |
| `stepper_controller` | [`stepper_controller.vhd`](FPGA/FPGA.srcs/sources_1/new/stepper_controller.vhd) | Dual locked Y-axis and top X-axis motor driver, serpentine scanner, and position trigger. |
| `uart_rx_protocol` | [`uart_rx_protocol.vhd`](FPGA/FPGA.srcs/sources_1/new/uart_rx_protocol.vhd) | Midpoint serial sampler decoding 16-byte host commands with XOR checksum verification. |
| `pulser_rtz` | [`top.vhd`](FPGA/FPGA.srcs/sources_1/new/top.vhd) | Top-level entity wiring the submodules and mapping physical Arty-S7 I/O pins. |

#### Multiplier-Less Quadrature Demodulation
Because the ADC samples at 20 MSPS and our transducer center frequency is 5 MHz, the sampling frequency is exactly four times the carrier ($f_s = 4 f_0$). This makes digital quadrature mixing remarkably efficient:
$$\cos(2\pi \cdot 0.25 \cdot n) = [1, 0, -1, 0, \dots]$$
$$\sin(2\pi \cdot 0.25 \cdot n) = [0, 1, 0, -1, \dots]$$

Mixing down to in-phase ($I$) and quadrature ($Q$) baseband components requires zero hardware DSP multipliers—just simple sign-inversion and multiplexing. The mixed signals pass through an FIR lowpass filter, followed by a pipelined CORDIC magnitude engine calculating $\sqrt{I^2 + Q^2}$.

The resulting envelope is decimated 4:1 down to 5 MSPS (giving 1024 depth samples over 204.8 µs, or roughly 151.5 mm of one-way depth in water) and passed through a 1024-entry BRAM lookup table ([`log_lut.coe`](log_lut.coe)) for dynamic range logarithmic compression into an 8-bit display value (0–255).

---

### 3. Motion Control & Position-Triggered Scanning

The scanner uses a 3-axis mechanical gantry:
* **Dual-Y Lead Screws (Motors Y1 & Y2)**: If you drive a wide gantry with a single lead screw, it binds and racks. The FPGA drives two independent bottom stepper drivers with identical step and direction pulses in hardware lockstep, guaranteeing the gantry remains perfectly square.
* **Top X Carriage (Motor X)**: Carries the immersion probe across the target.

```
       [Top X-Axis Carriage: Transducer Mount]
           |=====[========]=====|
           |                    |
  [Y1 Lead Screw]          [Y2 Lead Screw]
  (Left Rail)              (Right Rail)
           |                    |
           +--------------------+
              (Dual-Y Lockstep)
```

#### Why Position-Triggered Firing?
If you fire pulses on a free-running timer while motors are moving, you get two nasty artifacts:
1. **Acceleration distortion**: The gantry speeds up at the start of a sweep and slows down at the end. A timer stretches pixels at the edges and squashes them in the middle.
2. **Serpentine comb/herringbone shift**: When scanning back and forth (boustrophedon), slight mechanical latency or timing drift causes odd and even scan rows to misalign into an ugly zigzag pattern.

To fix this, the FPGA pulser is **slave-locked to the stepper position counter**:
* In chamber scan mode, the pulser fires strictly every 40 microsteps ($0.100\text{ mm}$ of travel with 400 steps/mm calibration).
* During Y indexing steps, the pulser is strictly gated off so no diagonal movement pollutes the volume.
* The stepper controller checks `acq_busy` before advancing, ensuring zero dropped scan lines even at high gantry velocities.
* When the scanner is parked or jogged manually, it reverts to a gentle 100 Hz free-running benchtop pulse for live alignment.

---

### 4. Desktop Acquisition Application (`ultrasound-app`)

Written in Java 21 using lightweight Swing and a custom software 3D rasterizer, the host application runs without external dependencies or native driver headaches.

```
+---------------------------------------------------------------------------------------+
|  ULTRASOUND SCANNER - [LIVE ACQUISITION]                          [COM10 - 2,000,000] |
+---------------------------------------------------------------------------------------+
|                                                          | [1D A-Scan Envelope]       |
|  [2D B-Scan Waterfall / 3D Topographic Surface]          | Peak: 24.3 mm | Amp: 218   |
|                                                          | /\_/\                      |
|                                                          |/     \________/\_______    |
|                                                          +----------------------------+
|                                                          | [Gantry Position & Speed]  |
|                                                          | X: 45.2 mm   Y: 12.0 mm    |
|                                                          | [<- X] [X ->]  [+Y] [-Y]   |
|                                                          | Jog Step: [ 1.0 mm v ]     |
|                                                          | Speed: 15 mm/s             |
|                                                          +----------------------------+
|                                                          | [Chamber Scan Setup]       |
|                                                          | W: 50 mm  H: 50 mm  dY:1mm |
|                                                          | [ TRIGGER FULL CHAMBER ]   |
+---------------------------------------------------------------------------------------+
| Colormap: [Bone v] | Squelch: 25 | Gain: 1.4x | View: 3D Surface | Status: Ready      |
+---------------------------------------------------------------------------------------+
```

#### Key Capabilities:
* **Real-time 2D B-Scan**: Sweep mode (records single clean passes) and continuous waterfall mode (rolling sonar display).
* **Live 1D A-Scan Window**: Real-time RF envelope line, ring-down blanking gate (0–60 samples), and automatic peak echo detector displaying depth in millimeters and amplitude.
* **Instant Dynamic DSP**: Sliders for squelch and contrast gain re-process already-frozen scan buffers in memory without re-running the physical scan.
* **2.5D / 3D Surface Topography**: Renders 3D relief with smooth Lambertian hill-shading, Blinn-Phong specular highlights, Painter's algorithm depth sorting, and dynamic depth-gated colormapping.
* **6-DOF Interactive Camera**: Orbit (left click & drag), pan (right click & drag), zoom (mouse wheel), and instant preset hotkeys (`[1]` Isometric, `[2]` Top-down C-scan, `[3]` Front XZ, `[4]` Side YZ, `[5]` Reverse).
* **Automated 7-View Snapshot Suite (`[S]`)**: Generates 7 timestamped PNGs and a raw CSV data table in a single click:
  1. `01_Overview_MultiView_Report.png` (1600 × 1100 master 4-in-1 sheet with colorbars and metadata)
  2. `02_TopDown_CScan_XY.png`
  3. `03_Isometric_3D_Surface.png`
  4. `04_Front_Elevation_XZ.png`
  5. `05_Side_Elevation_YZ.png`
  6. `06_Reverse_Isometric_Back.png`
  7. `07_Active_Viewport.png`
  8. `Data_3D_Volume_Matrix.csv`
* **Synthetic Acoustic Simulator**: Launching with `--sim` runs a physics-based water tank simulation with submerged coin targets, relief engravings, and acoustic speckle noise. You can test the entire application and 3D rendering pipeline without touching hardware.

---

## Communication Protocols

The FPGA and PC communicate over standard USB-UART at **2,000,000 baud** (8N1).

### 1. Host Commands (PC to FPGA - 16 Bytes Fixed)

```
Byte:   0     1     2     3     4     5..8        9..12       13..14      15
     [ 0xA5  0x5A  CMD  AXIS   DIR   PARAM1      PARAM2      PARAM3     CHKSUM ]
```

* `CMD`: `0x00` HALT, `0x01` JOG, `0x02` CHAMBER_SCAN, `0x03` SET_SPEED, `0x04` ZERO
* `AXIS`: `0x01` X carriage, `0x02` Dual-Y locked motors (or $\Delta X$ steps for chamber scans)
* `DIR`: `0x00` Negative, `0x01` Positive
* `PARAM 1`: 32-bit unsigned (Big-Endian) jog steps or total chamber X steps
* `PARAM 2`: 32-bit unsigned (Big-Endian) step half-period clock cycles or total chamber Y steps
* `PARAM 3`: 16-bit unsigned (Big-Endian) chamber Y line increment steps
* `CHECKSUM`: XOR sum of bytes 2 through 14

### 2. Telemetry Packet (FPGA to PC - 1028 Bytes Fixed)

```
Byte:   0     1     2       3             4 .. 1027
     [ 0xFF  0xAA  0x55  STATUS_BYTE   ENVELOPE_SAMPLES[1024] ]
```

* Bytes 0–2: Fixed synchronization pattern (`0xFF`, `0xAA`, `0x55`)
* Byte 3 (`STATUS_BYTE`): Real-time hardware flags latched from the stepper controller:
  * Bit 7: Scan active
  * Bit 6: Scan complete
  * Bit 5: Line start marker
  * Bit 4: Line end marker
  * Bit 3: Current X direction (`1` = positive, `0` = negative)
  * Bits 2..0: Hardware line index counter
* Bytes 4–1027: 1024 baseband envelope samples (5 MSPS, 8-bit log-compressed, 0–255)

---

## Hardware Wiring & Pin Mapping

The FPGA design targets the **Digilent Arty-S7** (Spartan-7). All pin assignments are defined in [`constraints.xdc`](FPGA/FPGA.srcs/constrs_1/new/constraints.xdc).

### PMOD Headers

| Header / Pin | FPGA Pin | Signal | Description |
| :--- | :--- | :--- | :--- |
| **JA 1** | `L17` | `relay` | High-voltage pre-charge relay control |
| **JA 5** | `M16` | `cs` | MCP4821 SPI DAC Chip Select (TGC) |
| **JA 6** | `M17` | `sck` | MCP4821 SPI DAC Clock |
| **JA 7** | `M18` | `sci` | MCP4821 SPI DAC Data In |
| **JA 8** | `N18` | `clk` | 20 MHz clock out to AD9226 ADC |
| **JB 1** | `P17` | `otr` | AD9226 Out-of-Range indicator |
| **JB 2..4, 5..8** | Multiple | `adc_data[12..6]` | AD9226 parallel data upper bits |
| **JC 1..4, 8** | Multiple | `adc_data[5..1]` | AD9226 parallel data lower bits |
| **JC 5** | `U16` | `ina` | MD1213 Driver Input A (P-FET gate drive) |
| **JC 6** | `P13` | `inb` | MD1213 Driver Input B (N-FET gate drive) |
| **JC 7** | `R13` | `oe` | MD1213 Output Enable |
| **USB-UART** | `R12` / `V12`| `uart_tx` / `rx` | 2 Mbaud serial communication to host PC |

### Stepper Motor Interface (ChipKit Header)

| Motor Axis | STEP Pin | DIR Pin | SLEEP / RESET | Microstep Pins (MS1..MS3) |
| :--- | :--- | :--- | :--- | :--- |
| **Motor 1 (Y1 Left)** | `io1` (`N13`) | `io0` (`L13`) | `io6` / `io5` | `io2`..`io4` (configured for 1/16) |
| **Motor 2 (Y2 Right)**| `io8` (`R15`) | `io7` (`V17`) | `io9` / `io10` | `io11`..`io13` (configured for 1/16) |
| **Motor 3 (Top X)**   | `io27` (`T11`)| `io26` (`U11`)| `io31` / `io32`| `io28`..`io30` (configured for 1/16) |

---

## Repository Tour

```
ultrasound-device/
├── PCB/                           # Altium Designer hardware files
│   ├── Pulser.PrjPcb              # Master project
│   ├── Pulser.SchDoc              # HV pulser, T/R clamp & power schematics
│   ├── Analog Front End.SchDoc    # AD8331 VGA & MCP4821 TGC DAC
│   ├── ADC.SchDoc                 # AD9226 20 MSPS ADC interface
│   ├── Boost Converter.SchDoc     # High-voltage DC/DC booster
│   ├── Pulser.PcbDoc              # High-speed routing & layout
│   └── Output Gerber/             # Fabrication Gerbers & drill files
├── FPGA/                          # Xilinx Vivado project (Arty-S7 / Spartan-7)
│   ├── FPGA.xpr                   # Vivado project file
│   └── FPGA.srcs/
│       ├── constrs_1/new/
│       │   └── constraints.xdc    # Pin constraints & clock timing
│       └── sources_1/new/
│           ├── top.vhd            # pulser_rtz top-level entity
│           ├── pulser_fsm.vhd     # HV excitation & active damp timing
│           ├── tgc_controller.vhd # SPI ramp generator for VGA
│           ├── dsp_pipeline.vhd   # Mixer, FIR, CORDIC & log compression
│           ├── acquisition_ram.vhd# BRAM line buffer & 2 Mbaud UART TX
│           ├── uart_rx_protocol.vhd# Host command parser & XOR checksum
│           └── stepper_controller.vhd # Dual-Y lockstep & position trigger
├── log_lut.coe                    # BRAM coefficient file for logarithmic compression
└── ultrasound-app/                # Java 21 desktop acquisition & 3D visualizer
    ├── pom.xml                    # Maven build file
    ├── mvnw.cmd                   # Maven wrapper for Windows
    ├── scans/                     # Output directory for exported PNGs & CSVs
    └── src/
        ├── main/java/com/ultrasound/bscan/  # Application source code
        └── test/java/com/ultrasound/bscan/  # 55 automated unit tests
```

---

## Quickstart Guide

### 1. Test Drive the Java App (No Hardware Needed)
You can try out the full user interface, 2D B-scans, and 3D topographic reconstruction immediately using the built-in acoustic immersion simulator.

Make sure you have **JDK 21 or newer** installed:

```cmd
cd ultrasound-app

# 1. Run the test suite (55 tests verify DSP, protocols, and 3D buffers)
.\mvnw.cmd test -o

# 2. Build the executable JAR
.\mvnw.cmd package -DskipTests -o

# 3. Launch straight into 3D Simulation Mode
java -jar target/ultrasound-app-1.0.0.jar --sim --3d
```

### 2. Connect to Physical Hardware
Once your FPGA bitstream is loaded and the board is plugged in via USB:

```cmd
# List available COM ports and connect
java -jar target/ultrasound-app-1.0.0.jar -p COM10
```

### 3. Build & Flash the FPGA Bitstream
1. Open **Xilinx Vivado (2018.3 or newer)**.
2. Select **Open Project** and navigate to `FPGA/FPGA.xpr`.
3. In the Flow Navigator, click **Generate Bitstream**. Vivado will run synthesis, implementation, and produce `pulser_rtz.bit`.
4. Open the **Hardware Manager**, connect to your Arty-S7 board via JTAG, and program the device.

---

## Keyboard Shortcuts

| Key | Action | Description |
| :--- | :--- | :--- |
| `[TAB]` or `[T]` | **Toggle 2D / 3D** | Switch between 2D B-Scan and 2.5D/3D Volumetric mode |
| `[Y]` | **Cycle 3D Style** | Cycle between Shaded Surface, Wireframe Mesh, and C-Scan MIP |
| `[1]` .. `[5]` / `[0]` | **Camera Snap** | `1`: Isometric, `2`: Top-down, `3`: Front XZ, `4`: Side YZ, `5`: Reverse, `0`: Reset |
| `[SPACE]` | **Pause / Freeze** | Freeze live acquisition or resume scanning |
| `[C]` | **Clear Buffer** | Clear active 2D sweep or 3D volume data |
| `[V]` | **Cycle Colormap** | Cycle palettes: `Bone` (medical), `Hot`, `Inferno`, `Grayscale` |
| `[S]` | **Save Scan** | Export full **3D Multi-View Suite (7 PNGs + CSV)** to `scans/` |
| `[Z]` | **Depth Zoom** | Toggle between full depth (151.5 mm) and near-field gate (0–60 mm) |
| `[` / `]` | **Squelch ±5** | Adjust noise threshold in real time |
| `-` / `+` | **Contrast ±0.2**| Adjust contrast multiplier |

---

## Safety & High Voltage Notice

> **Caution: Experimental Hardware**
>
> This hardware generates high-voltage pulses ($\pm 50\text{ V}$ to $\pm 100\text{ V}$) with fast rise times adjacent to a water immersion tank.
>
> * **Not a medical device**: This system is designed solely for educational, research, and non-destructive testing (NDT) experiments on phantoms and inanimate objects. It has not been approved by the FDA, CE, or any medical regulatory agency.
> * **Do not use on humans or animals.**
> * Always double-check ground references between the high-voltage pulser board, the FPGA PMOD pins, and the transducer shield before energizing the high-voltage rails.

---

## License

This project is licensed under the [MIT License](LICENSE). Feel free to use, modify, and build upon it for your own research and hardware projects.
