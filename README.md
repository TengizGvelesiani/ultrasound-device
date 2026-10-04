# Portable Ultrasound System PCB

An open-source hardware and firmware project for a portable ultrasound system. This repository contains the schematic designs, PCB layouts, and FPGA-based digital signal processing infrastructure required to drive ultrasonic transducers, receive echoes, and process the resulting data.

## System Architecture

The architecture is divided into three main domains: the analog high-voltage front end, the FPGA-based digital signal processor (DSP), and the Python-based visualization software.

### Hardware & Analog Front End (AFE)
* **High-Voltage Pulser:** A high-voltage pulser circuit utilizing an **MD1213** high-speed dual-MOSFET driver paired with a **TC6320** complementary MOSFET pair. The FPGA generates highly precise transmit bursts (e.g., 3-cycle 5 MHz bursts) to ring the ultrasonic transducer.
* **Variable Gain Amplifier:** Features an **AD8331** Variable Gain Amplifier (VGA) optimized for ultrasound applications. The gain is dynamically driven by a hardware Time Gain Compensation (TGC) ramp using an **MCP4821** DAC to compensate for exponential acoustic attenuation in tissue.
* **Data Acquisition:** An **AD9226** Analog-to-Digital Converter (ADC) captures the amplified high-frequency RF signals at 20 MSPS.

### FPGA Digital Signal Processing (DSP)
The FPGA manages microsecond-precise transmit-receive (T/R) switching and processes the incoming 20 MHz data stream in real-time:
* **DC Blocking:** An Exponentially Weighted Moving Average (EWMA) high-pass filter actively tracks and rejects the ADC baseline DC offset.
* **Quadrature Demodulation:** Multiplier-less $f_s/4$ mixing (`[1, 0, -1, 0]`) brings the 5 MHz carrier frequency down to baseband.
* **Filtering & Magnitude:** FIR filters smooth the separated I and Q channels, and a hardware CORDIC calculates the exact signal magnitude ($\sqrt{I^2 + Q^2}$).
* **Log Compression:** To map the massive acoustic dynamic range to a visible spectrum, a BRAM-based Look-Up Table applies logarithmic compression before decimation.

### Software Stack & Visualization
* **High-Speed Telemetry:** The compressed ultrasound envelope is streamed over a 2,000,000 baud binary UART interface.
* **Python GUI:** A custom Python application reconstructs the data into a real-time medical-grade B-Scan waterfall using a `bone` colormap. The software applies customizable noise squelch (`NOISE_REJECT`) and contrast gain for optimal imaging of various targets.

## Repository Structure

* `/Pulser_PCB/` - Altium Designer project files (`.PrjPcb`), schematics (`.SchDoc`), and PCB layouts (`.PcbDoc`).
* `/FPGA/` - Vivado project and VHDL/Verilog source code for the FPGA, including the pulse controller and DSP pipelines.

## Getting Started

### Prerequisites
* **Altium Designer** for viewing, editing, and generating manufacturing files from the hardware project.
* **Xilinx Vivado (2018.3 or newer)** for synthesizing the digital logic.

### Viewing the Design
1. Clone this repository: `git clone https://github.com/TengizGvelesiani/ultrasound-device.git`
2. Open the hardware project file located in the `/Pulser_PCB/` directory using Altium Designer.
3. The top-level schematic outlines the power distribution, high-voltage pulser, and analog receive chain.

## Current Status & Roadmap

* [x] Initial schematic capture (Pulser, VGA, ADC)
* [x] PCB layout and routing
* [x] Board bring-up and power sequence testing
* [x] FPGA pulse generation and T/R switch timing verification (tuned for 5 MHz resonance)
* [x] Echo acquisition and DSP filtering (stable decimation, FIR, CORDIC)
* [x] Software visualization and UART pipeline integration
* [ ] Mechanical scanning and full 2D B-Scan implementation

## Disclaimer

**Educational and experimental purposes only.** This hardware generates high voltages and is not an approved medical device. It has not been evaluated by the FDA or any other regulatory body. Do not use this device on human subjects. The creators and contributors are not responsible for any damage, injury, or liability resulting from the use or misuse of this design. 

## License

This project is licensed under the [MIT License](LICENSE) - see the LICENSE file for details.
