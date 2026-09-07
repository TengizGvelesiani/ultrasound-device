# Portable Ultrasound System PCB

An open-source hardware and firmware project for a portable ultrasound system. This repository contains the schematic designs, PCB layouts, and FPGA-based digital signal processing infrastructure required to drive ultrasonic transducers, receive echoes, and process the resulting data.

## System Architecture

The hardware is designed around a high-voltage transmit path and a highly sensitive, low-noise receive path, coordinated by an FPGA for precise timing and signal processing.

### Key Components
* **High-Voltage Pulser:** Unipolar high-voltage pulser circuit utilizing an **MD1213** high-speed dual-MOSFET driver paired with a **TC6320** complementary MOSFET pair for driving the ultrasonic transducer.
* **Analog Front End (AFE):** Features an **AD8331** Variable Gain Amplifier (VGA) optimized for ultrasound applications, ensuring low noise and high dynamic range for incoming echo signals.
* **Data Acquisition:** An **AD9226** Analog-to-Digital Converter (ADC) captures the amplified high-frequency RF signals.
* **Digital Signal Processing:** FPGA-based architecture handles precise transmit-receive (T/R) switching, pulse generation, and real-time digital signal processing of the incoming ADC data stream.

## Repository Structure

* `/Hardware/` - Altium Designer project files (`.PrjPcb`), schematics (`.SchDoc`), and PCB layouts (`.PcbDoc`).
* `/Firmware/` - VHDL/Verilog source code for the FPGA, including the pulse controller and DSP pipelines.
* `/Scripts/` - Python/MATLAB scripts for data parsing, signal processing visualization, and testing.
* `/Docs/` - Datasheets, component references, and system block diagrams.

## Getting Started

### Prerequisites
* **Altium Designer** for viewing, editing, and generating manufacturing files from the hardware project.
* [Insert your FPGA Toolchain] for synthesizing the digital logic.

### Viewing the Design
1. Clone this repository: `git clone https://github.com/yourusername/ultrasound-pcb.git`
2. Open the hardware project file located in the `/Hardware/` directory using Altium Designer.
3. The top-level schematic outlines the power distribution, high-voltage pulser, and analog receive chain.

## Current Status & Roadmap

* [x] Initial schematic capture (Pulser, VGA, ADC)
* [x] PCB layout and routing
* [ ] Board bring-up and power sequence testing
* [ ] FPGA pulse generation and T/R switch timing verification
* [ ] Echo acquisition and DSP filtering

## Disclaimer

**Educational and experimental purposes only.** This hardware generates high voltages and is not an approved medical device. It has not been evaluated by the FDA or any other regulatory body. Do not use this device on human subjects. The creators and contributors are not responsible for any damage, injury, or liability resulting from the use or misuse of this design. 

## License

This project is licensed under the [MIT License](LICENSE) - see the LICENSE file for details.
