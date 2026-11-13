# Single Element Ultrasound Imaging & Volumetric Scanning System

<p align="center">
  <img src="images/project1.jpg" alt="Ultrasound Scanner Benchtop Setup" width="49%" />
  <img src="images/project2.jpg" alt="Gantry and Water Tank" width="49%" />
</p>

A complete open-source ultrasound imaging platform combining custom analog front-end hardware, FPGA digital signal processing, and real-time 2D/3D visualization software. This system enables pulse-echo ultrasound imaging, non-destructive testing, and volumetric scanning with submillimeter resolution.

**Key Features:**
- 🔬 Real-time 2D B-Scan cross-sectional imaging
- 🎯 3D volumetric surface reconstruction and topography
- ⚡ FPGA-based DSP pipeline (20 MSPS acquisition, hardware demodulation)
- 🤖 Automated motorized gantry scanning with dual-axis synchronization
- 💻 Pure Java 21 desktop application (no native dependencies)
- 🎮 Built-in acoustic simulator for hardware-free testing

---

## 📋 Table of Contents

- [Overview](#overview)
- [Real-World Scan Results](#real-world-scan-results)
- [System Architecture](#system-architecture)
  - [Analog Front End & High-Voltage Pulser](#1-analog-front-end-afe--high-voltage-pulser-pcb)
  - [FPGA DSP & Hardware Architecture](#2-fpga-dsp--hardware-architecture-digilent-arty-s7)
  - [Motion Control](#3-motion-control--position-triggered-scanning)
  - [Desktop Application](#4-desktop-acquisition-application-ultrasound-app)
- [Quick Start](#quickstart-guide)
- [Documentation](#documentation)
- [License](#license)

---

## Overview

## Overview

This project provides a completely open benchtop ultrasound imaging system. A 5 MHz immersion transducer mounted to a motorized Cartesian gantry performs automated raster scanning over a water immersion tank. An FPGA handles high-voltage pulsing, time-gain amplification, 20 MSPS digitization, and hardware demodulation, streaming processed data to a custom Java desktop application for real-time 2D and 3D visualization.

### Software Highlights

#### 1. 2D B-Scan Imaging
* **Live 1D A-Scan Display**: Real-time RF envelope with automatic peak detection and depth measurement
* **Sweep Mode**: Clean single-pass cross-sectional imaging
* **Waterfall Mode**: Continuous rolling display for live acoustic feedback
* **Dynamic DSP Controls**: Adjust squelch, contrast, and gain on frozen scans without re-acquiring

#### 2. 3D Volumetric Topography
* **Software 3D Renderer**: Lambertian shading, Blinn-Phong highlights, and depth-sorted rendering
* **Depth-Gated Colormapping**: Automatically scales color palette across active depth range
* **Multiple Visual Modes**: Shaded surface, wireframe mesh, and C-scan MIP projection
* **Interactive Camera**: Orbit, pan, zoom, and instant camera presets
* **7-View Export**: One-click export of comprehensive multi-angle report with CSV data

#### 3. Motorized Gantry Control
* **Dual-Y Synchronization**: Hardware-locked stepper motors eliminate mechanical racking
* **Manual Jogging**: Precise positioning with adjustable step sizes (0.1-25mm)
* **Automated Scanning**: One-click chamber scans with real-time progress tracking
* **Calibration Tools**: Speed control and steps-per-mm calibration interface

---

## ⚡ Quick Start

### Prerequisites
- **Java 21 or newer** (OpenJDK recommended)
- **Maven 3.8+** (or use included `mvnw.cmd` wrapper)

### Test Without Hardware
Launch the built-in acoustic simulator with 3D visualization:

```bash
cd ultrasound-app

# Build the application
./mvnw.cmd clean package -DskipTests

# Launch simulator in 3D mode
java -jar target/ultrasound-app-1.0.0.jar --sim --3d
```

The simulator generates realistic ultrasound data with a submerged coin phantom, surface relief features, and acoustic speckle noise.

### Run Unit Tests
```bash
./mvnw.cmd test
```
55 unit tests verify DSP algorithms, communication protocols, and 3D rendering pipeline.

### Connect to Hardware
```bash
# Auto-detect COM port
java -jar target/ultrasound-app-1.0.0.jar

# Specify COM port directly
java -jar target/ultrasound-app-1.0.0.jar -p COM10
```

### Command-Line Options
```
Usage: java -jar ultrasound-app-1.0.0.jar [options]

Options:
  -p, --port <PORT>    Connect to specific COM port (e.g., COM10)
  --sim, -s            Launch in simulation mode
  --3d, -3             Start directly in 3D volumetric view
  -h, --help           Display help message
```

---

## ⌨️ Keyboard Shortcuts

| Key | Action | Description |
| :--- | :--- | :--- |
| `TAB` or `T` | **Toggle 2D/3D Mode** | Switch between B-Scan and volumetric views |
| `Y` | **Cycle 3D Style** | Rotate through shaded, wireframe, and C-scan modes |
| `1-5` | **Camera Presets** | 1: Iso, 2: Top, 3: Front, 4: Side, 5: Back |
| `0` | **Reset Camera** | Return to default isometric view |
| `SPACE` | **Pause/Resume** | Freeze or resume live acquisition |
| `C` | **Clear Buffer** | Clear current scan data |
| `V` | **Cycle Colormap** | Switch between Bone, Hot, Inferno, Turbo, etc. |
| `S` | **Save Snapshot** | Export multi-view report and data |
| `Z` | **Depth Zoom** | Toggle near-field zoom (2D mode) |
| `[` / `]` | **Adjust Squelch** | Decrease/increase noise threshold (±5) |
| `-` / `+` | **Adjust Contrast** | Decrease/increase contrast gain (±0.2) |
| `ESC` | **Emergency Stop** | Immediately halt motor movement |

**Mouse Controls (3D Mode):**
- **Left Drag**: Orbit/rotate camera
- **Right Drag / Shift+Drag**: Pan viewport
- **Mouse Wheel**: Zoom in/out
- **Double Click**: Reset camera position

---

## 📁 Repository Structure

```
ultrasound-device/
├── docs/                          # Engineering documentation
│   ├── Senior Project Report.pdf
│   └── Ultrasound Scanner Presentation.pptx
├── images/                        # Hardware photos and scan results
│   ├── metal_scan/                # Coin specimen scans
│   └── screw_scan/                # Machine screw scans
├── PCB/                           # Altium Designer hardware files
│   ├── Pulser.PrjPcb             # Main project
│   └── Output Gerber/            # Manufacturing files
├── FPGA/                          # Xilinx Vivado VHDL sources
│   ├── FPGA.xpr                  # Vivado project
│   └── FPGA.srcs/
│       ├── sources_1/new/        # VHDL modules
│       └── constrs_1/new/        # Pin constraints
└── ultrasound-app/               # Java desktop application
    ├── pom.xml                   # Maven build configuration
    ├── src/main/java/            # Application source
    └── src/test/java/            # Unit tests (55 tests)
```

---

## 🔧 Building & Development

## 🔧 Building & Development

### Building the Java Application

```bash
cd ultrasound-app

# Clean build with tests
./mvnw.cmd clean test

# Package executable JAR
./mvnw.cmd package -DskipTests
```

Output: `target/ultrasound-app-1.0.0.jar` with dependencies in `target/lib/`

### FPGA Development

1. Open Xilinx Vivado (2018.3 or newer)
2. Load project: `FPGA/FPGA.xpr`
3. Generate bitstream: Flow Navigator → Generate Bitstream
4. Program device via JTAG to Arty-S7 board

### Hardware Design

PCB schematics and layout are in Altium Designer format under `PCB/`. Manufacturing Gerber files are available in `PCB/Output Gerber/`.

---

## 📊 Technical Specifications

| Component | Specification |
|:----------|:--------------|
| **Transducer** | 5 MHz immersion probe |
| **ADC Sampling** | 20 MSPS, 12-bit (AD9226) |
| **Envelope Output** | 5 MSPS, 8-bit log-compressed |
| **Depth Range** | 0-151.5 mm (one-way in water) |
| **Time Resolution** | 50 ns per sample |
| **Spatial Resolution** | ~0.15 mm (depth), 0.10 mm (lateral) |
| **Communication** | 2 Mbaud UART over USB |
| **Gantry Resolution** | 1/16 microstep (400 steps/mm) |
| **Scan Speed** | 1-30 mm/s adjustable |

---

## 🔬 Real-World Scan Results
