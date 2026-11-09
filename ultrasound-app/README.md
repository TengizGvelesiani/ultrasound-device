# Ultrasound Acquisition & 3D Visualization App (`ultrasound-app`)

A desktop Java application for real-time ultrasound acquisition, 2D B-scan cross-sectional imaging, and 3D volumetric surface rendering. 

Built with pure Java 21+ and Swing, it communicates with the FPGA gantry rig over high-speed USB-UART (2,000,000 baud) and includes an integrated acoustic immersion simulator so you can test everything without physical hardware connected.

---

## Highlights

### 1. 2D B-Scan Imaging (1-Axis Sweep & Sonar)
* **Live 1D A-Scan Window**: Real-time RF envelope line, ring-down blanking gate (0–60 samples), and automatic peak echo detector tracking depth in millimeters and echo amplitude (0–255).
* **Sweep Pass Mode**: Records clean left-to-right 2D cross sections as the transducer scans across an axis.
* **Continuous Waterfall Mode**: Rolling sonar/oscilloscope monitor for real-time acoustic feedback.
* **Instant Dynamic DSP**: Real-time squelch and contrast gain sliders that instantly re-filter already recorded frozen sweeps without re-scanning.

### 2. 3D Volumetric Topography (2-Axis Gantry Scanning)
* **Custom Software 3D Renderer**: Smooth Lambertian hill-shading, Blinn-Phong specular highlights, and Painter's algorithm depth sorting that prevents polygon overlap and visual tearing.
* **Dynamic Depth-Gated Colormapping**: Automatically scales the full 256-color palette across the active depth gate ($Z_{min} \dots Z_{max}$), ensuring subtle millimeter-scale reliefs (like coin engravings) pop out vividly.
* **Multiple Visual Styles (`[Y]`)**:
  * **3D Shaded Surface**: Elevation-colored topographic terrain with directional lighting.
  * **3D Wireframe Mesh**: High-contrast geometric contours.
  * **En-Face C-Scan Projection**: Maximum Intensity Projection (MIP) XY view between user-selected depth gates.
* **6-DOF Interactive Camera**: Orbit (left mouse drag), pan (right mouse drag or Shift+drag), zoom (mouse wheel), and instant preset snaps (`[1]` to `[5]`, `[0]` to reset).
* **Automated 7-View Snapshot Suite (`[S]`)**: Exports 7 timestamped PNGs and a raw CSV data table in a single click:
  1. `01_Overview_MultiView_Report.png` (Master 1600 × 1100 4-in-1 report sheet with calibrated colorbars and acquisition parameters)
  2. `02_TopDown_CScan_XY.png`
  3. `03_Isometric_3D_Surface.png`
  4. `04_Front_Elevation_XZ.png`
  5. `05_Side_Elevation_YZ.png`
  6. `06_Reverse_Isometric_Back.png`
  7. `07_Active_Viewport.png`
  8. `Data_3D_Volume_Matrix.csv`

### 3. Integrated Stepper Gantry Control
* **Dual-Y Locked Bottom Motors**: Bottom Y1 and Y2 lead-screw motors receive synchronized step and direction pulses to eliminate mechanical racking.
* **Manual Jogging**: Jog $+X$, $-X$, $+Y$, and $-Y$ with selectable step increments (0.1 mm to 25.0 mm).
* **Speed & Steps/mm Calibration**: Interactive speed slider (1.0 to 30.0 mm/s) with live step frequency calculation (default 400 steps/mm for 1/16 microstepping on 8 mm pitch lead screws).
* **1-Click Autonomous Chamber Scan**: Set $X$, $Y$, and $\Delta Y$ spacing, then click **`TRIGGER FULL CHAMBER SCAN`**. The FPGA executes the entire scan autonomously while the app tracks progress.

---

## Keyboard Shortcuts

| Key / Control | Action | Context | Description |
| :--- | :--- | :--- | :--- |
| `[TAB]` or `[T]` | **Toggle 2D / 3D** | Global | Switches between 2D B-Scan and 3D Volumetric Mode |
| `[Y]` | **Cycle 3D Style** | 3D Mode | Cycles between Shaded Surface, Wireframe Mesh, and C-Scan MIP |
| `[1]` .. `[5]` / `[0]` | **Camera Snap** | 3D Mode | `1`: Isometric, `2`: Top-Down, `3`: Front XZ, `4`: Side YZ, `5`: Reverse, `0`: Reset |
| `[SPACE]` | **Pause / Freeze** | Global | Freezes live acquisition or resumes real-time scanning |
| `[C]` | **Clear Buffers** | Global | Clears active 2D sweep or 3D volume buffer |
| `[V]` | **Cycle Colormap** | Global | Cycles palettes: `Bone` (medical), `Hot`, `Inferno`, `Grayscale` |
| `[S]` | **Save Scan** | Global | Exports 2D B-Scan or full 3D Multi-View Suite (7 PNGs + CSV) to `scans/` |
| `[Z]` | **Depth Zoom** | 2D Mode | Toggles between Full Depth (151.5 mm) and Near Field (0–60 mm) |
| `[` / `]` | **Squelch ±5** | Global | Adjusts noise squelch threshold |
| `-` / `+` | **Contrast ±0.2** | Global | Adjusts contrast multiplier |

---

## Building & Running

### Requirements
* **Java 21 or newer** (tested on OpenJDK 21, 23, and 25)
* **Maven 3.8+** (or use the included `mvnw.cmd` wrapper)

### 1. Run Unit Tests (55 Tests Passing)
```cmd
cd ultrasound-app
.\mvnw.cmd test -o
```

### 2. Package Executable JAR
```cmd
cd ultrasound-app
.\mvnw.cmd package -DskipTests -o
```
This builds `target/ultrasound-app-1.0.0.jar` with all dependencies gathered in `target/lib/`.

### 3. Launch in 3D Simulation Mode (No Hardware Needed)
```cmd
java -jar target/ultrasound-app-1.0.0.jar --sim --3d
```
Simulates a 2-axis raster scan over a water tank with a submerged 3D coin phantom, relief engraving, and acoustic speckle noise.

### 4. Standard 2D B-Scan Launch
```cmd
java -jar target/ultrasound-app-1.0.0.jar
```

### 5. Connect to Physical Hardware COM Port
```cmd
java -jar target/ultrasound-app-1.0.0.jar -p COM10
```
