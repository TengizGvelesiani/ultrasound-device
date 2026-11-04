package com.ultrasound.bscan;

import com.ultrasound.bscan.colormap.ColormapRegistry;
import com.ultrasound.bscan.controller.MotorControllerService;
import com.ultrasound.bscan.controller.ScannerController;
import com.ultrasound.bscan.dsp.DefaultSignalProcessor;
import com.ultrasound.bscan.dsp.SignalProcessor;
import com.ultrasound.bscan.io.NativeLibraryBootstrap;
import com.ultrasound.bscan.io.PortDiscoveryService;
import com.ultrasound.bscan.io.PortInfo;
import com.ultrasound.bscan.model.MotorSettings;
import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.ViewMode;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.storage.DefaultScanExporter;
import com.ultrasound.bscan.storage.ScanExporter;
import com.ultrasound.bscan.ui.AScanPanel;
import com.ultrasound.bscan.ui.BScanPanel;
import com.ultrasound.bscan.ui.ControlPanel;
import com.ultrasound.bscan.ui.MotorControlPanel;
import com.ultrasound.bscan.ui.StatusBar;
import com.ultrasound.bscan.ui.Volume3DPanel;
import com.ultrasound.bscan.ui.VolumeControlPanel;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;

/**
 * Main application entry point for the Ultrasound App (2D B-Scan &amp; 2.5D/3D Volumetric Acquisition System).
 */
public final class UltrasoundApp {

    public static void main(String[] args) {
        NativeLibraryBootstrap.prepareNativeLibraries();

        String cliPort = null;
        boolean cliSimulation = false;
        boolean cliStart3D = false;

        for (int i = 0; i < args.length; i++) {
            if ("-p".equals(args[i]) || "--port".equals(args[i])) {
                if (i + 1 < args.length) {
                    cliPort = args[++i];
                }
            } else if ("--sim".equals(args[i]) || "-s".equals(args[i])) {
                cliSimulation = true;
            } else if ("--3d".equals(args[i]) || "-3".equals(args[i])) {
                cliStart3D = true;
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printHelp();
                return;
            }
        }

        final String targetPort = cliPort;
        final boolean simulationMode = cliSimulation;
        final boolean start3D = cliStart3D;

        SwingUtilities.invokeLater(() -> launchGui(targetPort, simulationMode, start3D));
    }

    private static void printHelp() {
        System.out.println("Ultrasound App - Real-Time Acquisition & 2.5D/3D Volumetric Imaging");
        System.out.println("Usage: java -jar ultrasound-app-1.0.0.jar [options]");
        System.out.println("Options:");
        System.out.println("  -p, --port <COM_PORT>   Connect directly to specified COM port (e.g., COM10)");
        System.out.println("  --sim, -s               Start in synthetic ultrasound simulation mode");
        System.out.println("  --3d, -3                Start directly in 2.5D/3D volumetric view");
        System.out.println("  -h, --help              Print this help menu");
    }

    private static void launchGui(String targetPort, boolean simulationMode, boolean start3D) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Ultrasound App (2D B-Scan & 2.5D/3D Volumetric Imaging)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1400, 920);
        frame.setMinimumSize(new Dimension(950, 680));
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(new Color(0x12, 0x12, 0x16));

        // Dependency Injection
        ScanConfig config = new ScanConfig();
        ScanBuffer buffer = new ScanBuffer(config.getNumSamples());
        ScannerState state = new ScannerState();
        VolumeBuffer volumeBuffer = new VolumeBuffer(config.getNumSamples(), state.getVolumeConfig());

        SignalProcessor signalProcessor = new DefaultSignalProcessor();
        ColormapRegistry colormapRegistry = new ColormapRegistry();
        ScanExporter scanExporter = new DefaultScanExporter();
        PortDiscoveryService portDiscoveryService = new PortDiscoveryService();

        // 2D Views
        AScanPanel aScanPanel = new AScanPanel(config, state);
        BScanPanel bScanPanel = new BScanPanel(config, buffer, state, colormapRegistry);
        JSplitPane splitPane2D = new JSplitPane(JSplitPane.VERTICAL_SPLIT, aScanPanel, bScanPanel);
        splitPane2D.setResizeWeight(0.28);
        splitPane2D.setDividerSize(4);
        splitPane2D.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
        splitPane2D.setBackground(new Color(0x12, 0x12, 0x16));

        // 3D Views
        Volume3DPanel volume3DPanel = new Volume3DPanel(config, volumeBuffer, state, colormapRegistry);

        // Center View Mode Switcher (CardLayout: "2D" vs "3D")
        CardLayout centerCardLayout = new CardLayout();
        JPanel centerCardContainer = new JPanel(centerCardLayout);
        centerCardContainer.setBackground(new Color(0x12, 0x12, 0x16));
        centerCardContainer.add(splitPane2D, "2D");
        centerCardContainer.add(volume3DPanel, "3D");

        // Controls
        ControlPanel controlPanel = new ControlPanel(state);
        VolumeControlPanel volumeControlPanel = new VolumeControlPanel(state, volumeBuffer);

        // South Controls Switcher (CardLayout: "2D" vs "3D")
        CardLayout controlsCardLayout = new CardLayout();
        JPanel controlsCardContainer = new JPanel(controlsCardLayout);
        controlsCardContainer.setBackground(new Color(0x18, 0x18, 0x20));
        controlsCardContainer.add(controlPanel, "2D");
        controlsCardContainer.add(volumeControlPanel, "3D");

        StatusBar statusBar = new StatusBar(state);

        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
        southPanel.add(controlsCardContainer);
        southPanel.add(statusBar);

        // Motor & Full Chamber Scanner Subsystem
        MotorSettings motorSettings = new MotorSettings();
        MotorControllerService motorService = new MotorControllerService(motorSettings);
        MotorControlPanel motorControlPanel = new MotorControlPanel(motorService);

        JPanel leftMainPanel = new JPanel(new BorderLayout());
        leftMainPanel.setBackground(new Color(0x12, 0x12, 0x16));
        leftMainPanel.add(centerCardContainer, BorderLayout.CENTER);
        leftMainPanel.add(southPanel, BorderLayout.SOUTH);

        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftMainPanel, motorControlPanel);
        mainSplitPane.setResizeWeight(1.0); // Keeps motorControlPanel at full width during resizing
        mainSplitPane.setDividerLocation(980);
        mainSplitPane.setDividerSize(5);
        mainSplitPane.setBorder(null);
        mainSplitPane.setBackground(new Color(0x12, 0x12, 0x16));

        frame.setLayout(new BorderLayout());
        frame.add(mainSplitPane, BorderLayout.CENTER);

        // Controller
        ScannerController controller = new ScannerController(
                config, buffer, volumeBuffer, state, signalProcessor, scanExporter,
                portDiscoveryService, aScanPanel, bScanPanel, volume3DPanel,
                controlPanel, volumeControlPanel,
                centerCardContainer, centerCardLayout,
                controlsCardContainer, controlsCardLayout,
                statusBar, frame,
                motorService, motorControlPanel
        );

        if (start3D) {
            state.setViewMode(ViewMode.MODE_3D);
        }

        if (simulationMode) {
            controlPanel.setSimulationMode(true);
            controller.connect();
        } else if (targetPort != null && !targetPort.isEmpty()) {
            controlPanel.setPorts(portDiscoveryService.listAvailablePorts(),
                    new PortInfo(targetPort, "CLI Target Port", true));
            controller.connect();
        }

        printBanner();
        frame.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            mainSplitPane.setDividerLocation(frame.getWidth() - 370);
        });
    }

    private static void printBanner() {
        System.out.println("============================================================");
        System.out.println(" ULTRASOUND SCANNER: 2D B-SCAN & 2.5D/3D VOLUMETRIC SYSTEM");
        System.out.println("============================================================");
        System.out.println(" Modes:");
        System.out.println("  [TAB]        : Toggle between 2D B-Scan & 2.5D/3D Volumetric Mode");
        System.out.println(" 2.5D / 3D Controls:");
        System.out.println("  [Y]          : Cycle 3D Style (Shaded Surface, Wireframe, C-Scan MIP)");
        System.out.println("  [Mouse Drag] : Left drag to Orbit / Rotate | Right drag to Pan");
        System.out.println("  [Wheel]      : Zoom in / Zoom out | Double-click to Reset View");
        System.out.println(" Stage & Chamber Controls:");
        System.out.println("  [Trigger Scan]  : One-Click Full XY Autonomous Chamber Scan");
        System.out.println("  [Jog X / Y]     : Manual Top X-Axis & Synchronized Dual Bottom Y-Axis");
        System.out.println("  [ESC]           : Immediate Emergency Motion Halt");
        System.out.println(" General Controls:");
        System.out.println("  [SPACE]      : Start / Pause Acquisition");
        System.out.println("  [C]          : Clear Current Screen / Volume");
        System.out.println("  [S]          : Save Snapshot Image & Data Table");
        System.out.println("  [V]          : Cycle Colormaps (Bone, Hot, Inferno, Gray)");
        System.out.println("  [ [ / ] ]    : Adjust Noise Squelch (-5 / +5)");
        System.out.println("  [ - / + ]    : Adjust Contrast Gain (-0.2 / +0.2)");
        System.out.println("============================================================\n");
    }
}
