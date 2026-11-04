# Ultrasound 2D & 2.5D/3D Volumetric Acquisition System (Java)

High-performance, object-oriented Java replacement for ultrasound acquisition and visualization.
Supports both **2D B-Scan cross-sectional imaging** and **2.5D / 3D volumetric surface & C-scan imaging** across mechanically actuated XY stages.

---

## 🌟 Key Features

### 1. 2D B-Scan Imaging (1-Axis Translation)
* **Real-time A-Scan Envelope**: 1D acoustic waveform line with ring-down blanking ($0\dots 60$ samples) and real-time echo peak marker (`Echo Peak: XX.X mm | Amp: YYY/255`).
* **Sweep Pass Mode**: Records clean left-to-right 2D cross sections as the transducer slides along an axis.
* **Continuous Waterfall Mode**: Real-time rolling sonar/oscilloscope monitor.
* **Interactive Dynamic DSP**: Real-time Squelch and Contrast Gain sliders that instantly re-filter already recorded frozen sweeps.

### 2. 2.5D / 3D Volumetric Scanning (2-Axis Mechanical XY Stage)
* **High-Fidelity 3D Surface Rendering**: Real-time 3D rendered surface topography with smooth Lambertian hill-shading, Blinn-Phong diffuse lighting, and Painter's algorithm depth sorting that completely eliminates polygon overlap and visual tearing.
* **Dynamic Depth-Gate Colormapping**: Automatically scales the full 256-color palette across the active depth gate ($Z_{min} \dots Z_{max}$), ensuring coin reliefs and millimeter-scale features use the entire spectrum instead of a muddy band.
* **Multi-View 3D Snapshot Suite (`[S]`)**: Automatically captures and exports **7 distinct PNG visualizations** in one click:
  1. `_multiview.png`: **Master 4-in-1 Report Sheet** ($1600 \times 1100$) combining Top-Down, 3D Isometric, Front XZ, and Side YZ views with calibrated colorbars and acquisition metadata.
  2. `_iso.png`: **Isometric Perspective 3D View** (Yaw -45°, Pitch 32°).
  3. `_top.png`: **Top-Down En-Face View** (C-Scan XY Map, Yaw 0°, Pitch 85°).
  4. `_front.png`: **Front Elevation View** (XZ Cross-Section, Yaw 0°, Pitch 8°).
  5. `_side.png`: **Side Elevation View** (YZ Cross-Section, Yaw 90°, Pitch 8°).
  6. `_back_iso.png`: **Reverse Angle Isometric View** (Yaw 135°, Pitch 32°).
  7. `_active.png`: **Current User Viewport** (whatever custom orbit the user navigated to).
  8. `_data.csv`: **Raw Data Table** (every measured grid coordinate and peak echo depth).
* **Multiple 3D Visual Styles (`[Y]`)**:
  * **3D Shaded Surface**: Topographic terrain with elevation-coded colormap and directional lighting.
  * **3D Wireframe Mesh**: Topographic wireframe mesh contours.
  * **En-face C-Scan Projection**: Flat XY projection with interactive depth-gating (Maximum Intensity Projection / MIP between $Z_{min}$ and $Z_{max}$).
* **Camera Presets & 6-DOF Interactive Camera**:
  * **Camera Presets Dropdown & Hotkeys (`[1]` to `[5]`, `[0]` to reset)**: Instant 1-click snap to Isometric, Top-Down, Front, Side, or Reverse.
  * **Left Mouse Drag**: Orbit rotation (Azimuth / Yaw $\theta$ and Elevation / Pitch $\phi$).
  * **Right Mouse Drag (or Shift + Drag)**: Pan $(X, Y)$ across the screen.
  * **Mouse Wheel**: Zoom in and zoom out.
  * **Double Click**: Instantly reset camera view to default.
* **Calibrated Colorbar Legend**: Automatically placed on exported PNGs showing millimeter depth mapping ($Z_{min} \dots Z_{max}$).
* **Raster Scanning Geometries**:
  * **Snake Pattern (Boustrophedon)**: Alternating bidirectional translation ($X \to$, next $Y$, $\leftarrow X$, next $Y$) for efficient mechanical scanning without return moves.
  * **Unidirectional Pattern**: Standard left-to-right pass per row ($X \to$, reset $X$, next $Y$).
* **Configurable Grid Resolutions**: Presets for `50 x 50 (Fast)`, `64 x 64 (Standard)`, and `100 x 100 (High-Res)`.
* **Dynamic Z-Exaggeration Slider**: Scales depth relief from $0.5\times$ to $4.0\times$ so subtle coin reliefs (1–2 mm) stand out vividly.
* **Scanhead Tracker**: Real-time 3D probe beacon showing current physical stage coordinates $(X, Y)$ and acoustic reflection contact spot.

### 3. Integrated Stepper Gantry & Stage Control
* **Dual-Y Locked Bottom Motors**: Bottom Y1 and Y2 lead-screw motors are strictly commanded with identical step and direction pulses to eliminate mechanical racking of the gantry.
* **Manual Jogging**: Jog $+X$, $-X$, $+Y$, and $-Y$ with selectable step increments ($0.1\text{ mm}$ to $25.0\text{ mm}$).
* **Speed Calibration**: Interactive gantry speed slider ($1.0\dots 30.0\text{ mm/s}$) with live step frequency calculation in Hz.
* **Calibration Inputs**: Customizable steps/mm settings (default $400\text{ stp/mm}$ for $1/16$ microstepping on $8\text{ mm}$ pitch lead screws).
* **One-Click Autonomous Chamber Scan**: Click **`TRIGGER FULL CHAMBER SCAN`** to send chamber dimensions ($X$, $Y$, $\Delta Y$) to the FPGA. The FPGA executes the entire scan autonomously while the app tracks live supervisory progress.
* **Position-Triggered Pulser & Hardware Gating**: In chamber scan mode, the FPGA fires strictly every $40\text{ microsteps}$ ($0.100\text{ mm}$), completely eliminating acceleration skew and herringbone comb artifacts. Pulses are strictly inhibited during Y indexing.

---

## ⚡ Controls & Keybindings

| Key / Button | Action | Context | Description |
| :--- | :--- | :--- | :--- |
| `[TAB]` or `[T]` | **Toggle 2D / 3D** | Global | Switches between **2D B-Scan Mode** and **2.5D / 3D Volumetric Mode** |
| `[Y]` | **Cycle 3D Style** | 3D Mode | Cycles between **Shaded Surface**, **Wireframe Mesh**, and **C-Scan MIP** |
| `[1]` .. `[5]` / `[0]`| **Camera Presets** | 3D Mode | `1`: Isometric, `2`: Top-Down (C-Scan), `3`: Front XZ, `4`: Side YZ, `5`: Reverse, `0`: Reset |
| `[SPACE]` | **Pause / Resume** | Global | Freezes live acquisition or resumes scanning |
| `[C]` | **Clear Screen / Vol**| Global | Clears the active 2D sweep or 3D volume buffer |
| `[V]` | **Colormap** | Global | Cycles colormaps: `Bone` (medical), `Hot`, `Inferno`, `Grayscale` |
| `[S]` | **Save Scan** | Global | Exports 2D B-Scan or full **3D Multi-View Suite (7 PNGs + CSV)** into `scans/` |
| `[Z]` | **Depth Zoom** | 2D Mode | Toggles between **Full Depth (151.4 mm)** and **Near Field (0–60 mm)** |
| `[` / `]` | **Squelch ±5** | Global | Adjusts noise squelch threshold |
| `-` / `+` | **Contrast ±0.2** | Global | Adjusts contrast gain multiplier |
| `Scans Folder 📁` | **Open Directory** | UI Button | Directly opens `scans/` in Windows File Explorer |

---

## 🛠️ Building & Running

### Requirements
- **Java 21+** (Java 21, 23, 25 verified)
- **Maven 3.8+** (or use the included `mvnw.cmd` wrapper)

### 1. Build and Run Tests
```cmd
cd ultrasound-app
.\mvnw.cmd test -o
```
*(All 35 unit tests run and verify 2D and 3D buffer logic, colormaps, DSP filtering, serial protocol parsing, motor controller services, and PNG/CSV exporters)*

### 2. Package Executable Application JAR
```cmd
cd ultrasound-app
.\mvnw.cmd package -DskipTests -o
```
Produces `target/ultrasound-app-1.0.0.jar` with dependencies copied to `target/lib/` and linked via the manifest classpath.

### 3. Launch in 2.5D / 3D Simulation Mode (Instant 3D Coin Test)
```cmd
java -jar target/ultrasound-app-1.0.0.jar --sim --3d
```
*(Simulates a 2-axis raster scan over a water tank with a 3D circular coin phantom and relief features)*

### 4. Standard 2D B-Scan Launch
```cmd
java -jar target/ultrasound-app-1.0.0.jar
```

### 5. Connect Directly to a Physical Hardware COM Port
```cmd
java -jar target/ultrasound-app-1.0.0.jar -p COM10
```
