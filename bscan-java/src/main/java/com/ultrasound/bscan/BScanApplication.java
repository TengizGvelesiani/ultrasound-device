package com.ultrasound.bscan;

import com.ultrasound.bscan.colormap.ColormapRegistry;
import com.ultrasound.bscan.controller.ScannerController;
import com.ultrasound.bscan.dsp.DefaultSignalProcessor;
import com.ultrasound.bscan.dsp.SignalProcessor;
import com.ultrasound.bscan.io.NativeLibraryBootstrap;
import com.ultrasound.bscan.io.PortDiscoveryService;
import com.ultrasound.bscan.io.PortInfo;
import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.storage.DefaultScanExporter;
import com.ultrasound.bscan.storage.ScanExporter;
import com.ultrasound.bscan.ui.AScanPanel;
import com.ultrasound.bscan.ui.BScanPanel;
import com.ultrasound.bscan.ui.ControlPanel;
import com.ultrasound.bscan.ui.StatusBar;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

/**
 * Main application entry point for the Ultrasound B-Scan Acquisition &amp; Visualization system.
 */
public final class BScanApplication {

    public static void main(String[] args) {
        NativeLibraryBootstrap.prepareNativeLibraries();

        String cliPort = null;
        boolean cliSimulation = false;

        for (int i = 0; i < args.length; i++) {
            if ("-p".equals(args[i]) || "--port".equals(args[i])) {
                if (i + 1 < args.length) {
                    cliPort = args[++i];
                }
            } else if ("--sim".equals(args[i]) || "-s".equals(args[i])) {
                cliSimulation = true;
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printHelp();
                return;
            }
        }

        final String targetPort = cliPort;
        final boolean simulationMode = cliSimulation;

        SwingUtilities.invokeLater(() -> launchGui(targetPort, simulationMode));
    }

    private static void printHelp() {
        System.out.println("Ultrasound 1-Axis B-Scan Acquisition System (Java Edition)");
        System.out.println("Usage: java -jar bscan-java.jar [options]");
        System.out.println("Options:");
        System.out.println("  -p, --port <COM_PORT>   Connect directly to specified COM port (e.g., COM10)");
        System.out.println("  --sim, -s               Start in synthetic ultrasound simulation mode");
        System.out.println("  -h, --help              Print this help menu");
    }

    private static void launchGui(String targetPort, boolean simulationMode) {
        try {
            // Apply native dark appearance hints
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Ultrasound 1-Axis B-Scan Scanner");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1280, 880);
        frame.setMinimumSize(new Dimension(800, 600));
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(new Color(0x12, 0x12, 0x16));

        // Dependency Injection
        ScanConfig config = new ScanConfig();
        ScanBuffer buffer = new ScanBuffer(config.getNumSamples());
        ScannerState state = new ScannerState();
        SignalProcessor signalProcessor = new DefaultSignalProcessor();
        ColormapRegistry colormapRegistry = new ColormapRegistry();
        ScanExporter scanExporter = new DefaultScanExporter();
        PortDiscoveryService portDiscoveryService = new PortDiscoveryService();

        // UI Views
        AScanPanel aScanPanel = new AScanPanel(config, state);
        BScanPanel bScanPanel = new BScanPanel(config, buffer, state, colormapRegistry);
        ControlPanel controlPanel = new ControlPanel(state);
        StatusBar statusBar = new StatusBar(state);

        // Layout: Vertical Split for A-Scan (top) and B-Scan (bottom)
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, aScanPanel, bScanPanel);
        splitPane.setResizeWeight(0.28);
        splitPane.setDividerSize(4);
        splitPane.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
        splitPane.setBackground(new Color(0x12, 0x12, 0x16));

        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
        southPanel.add(controlPanel);
        southPanel.add(statusBar);

        frame.setLayout(new BorderLayout());
        frame.add(splitPane, BorderLayout.CENTER);
        frame.add(southPanel, BorderLayout.SOUTH);

        // Controller
        ScannerController controller = new ScannerController(
                config, buffer, state, signalProcessor, scanExporter,
                portDiscoveryService, aScanPanel, bScanPanel, controlPanel, statusBar, frame
        );

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
    }

    private static void printBanner() {
        System.out.println("============================================================");
        System.out.println(" ULTRASOUND 1-AXIS B-SCAN SCANNER (JAVA EDITION)");
        System.out.println("============================================================");
        System.out.println(" Features:");
        System.out.println("  - Zero frame-loss hardware multi-threading at 2 Mbaud");
        System.out.println("  - Sub-millisecond direct GPU raster blitting");
        System.out.println("  - Interactive Sweep Pass & Continuous Waterfall modes");
        System.out.println("  - Real-time Squelch & Contrast tuning with instant re-filter");
        System.out.println("  - High-res PNG & CSV metadata export");
        System.out.println(" Controls:");
        System.out.println("  [SPACE]      : Start / Pause Sweep Acquisition");
        System.out.println("  [C]          : Clear Sweep Screen");
        System.out.println("  [M]          : Toggle Sweep Pass vs Rolling Waterfall");
        System.out.println("  [Z]          : Toggle Depth Zoom (Full 150mm <-> Near 60mm)");
        System.out.println("  [S]          : Save B-Scan image (PNG) and raw data (CSV)");
        System.out.println("  [V]          : Toggle Colormap (Bone, Hot, Inferno, Grayscale)");
        System.out.println("  [ [ / ] ]    : Adjust Noise Squelch (-5 / +5)");
        System.out.println("  [ - / + ]    : Adjust Contrast Gain (-0.2 / +0.2)");
        System.out.println("============================================================\n");
    }
}
