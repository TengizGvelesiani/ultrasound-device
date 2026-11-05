package com.ultrasound.bscan.controller;

import com.ultrasound.bscan.dsp.SignalProcessor;
import com.ultrasound.bscan.io.DataAcquisitionSource;
import com.ultrasound.bscan.io.PortDiscoveryService;
import com.ultrasound.bscan.io.PortInfo;
import com.ultrasound.bscan.io.SerialDataAcquisitionSource;
import com.ultrasound.bscan.io.SimulatedDataAcquisitionSource;
import com.ultrasound.bscan.model.AScanFrame;
import com.ultrasound.bscan.model.MotorSettings;
import com.ultrasound.bscan.model.VolumeScanConfig;
import com.ultrasound.bscan.model.PeakEcho;
import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScanMode;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.ViewMode;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.storage.ScanExporter;
import com.ultrasound.bscan.ui.AScanPanel;
import com.ultrasound.bscan.ui.BScanPanel;
import com.ultrasound.bscan.ui.ControlPanel;
import com.ultrasound.bscan.ui.MotorControlPanel;
import com.ultrasound.bscan.ui.StatusBar;
import com.ultrasound.bscan.ui.Volume3DPanel;
import com.ultrasound.bscan.ui.VolumeControlPanel;
import com.ultrasound.bscan.ui.ColormapTuningDialog;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.CardLayout;
import java.awt.Desktop;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Controller orchestrating 2D and 2.5D/3D data acquisition, DSP pipeline, UI updates, and export actions.
 */
public final class ScannerController {

    private final ScanConfig config;
    private final ScanBuffer buffer;
    private final VolumeBuffer volumeBuffer;
    private final ScannerState state;
    private final SignalProcessor signalProcessor;
    private final ScanExporter scanExporter;
    private final PortDiscoveryService portDiscoveryService;

    private final AScanPanel aScanPanel;
    private final BScanPanel bScanPanel;
    private final Volume3DPanel volume3DPanel;
    private final ControlPanel controlPanel;
    private final VolumeControlPanel volumeControlPanel;

    private final JPanel centerContainer;
    private final CardLayout centerLayout;
    private final JPanel controlsContainer;
    private final CardLayout controlsLayout;

    private final StatusBar statusBar;
    private final JFrame mainFrame;
    private final MotorControllerService motorService;
    private final MotorControlPanel motorControlPanel;

    private DataAcquisitionSource currentSource;
    private final ArrayBlockingQueue<byte[]> dataQueue = new ArrayBlockingQueue<>(4000);
    private Timer renderTimer;

    private long fpsTimerStart = System.currentTimeMillis();
    private long fpsFrameCount = 0;

    public ScannerController(ScanConfig config, ScanBuffer buffer, VolumeBuffer volumeBuffer,
                             ScannerState state, SignalProcessor signalProcessor,
                             ScanExporter scanExporter, PortDiscoveryService portDiscoveryService,
                             AScanPanel aScanPanel, BScanPanel bScanPanel, Volume3DPanel volume3DPanel,
                             ControlPanel controlPanel, VolumeControlPanel volumeControlPanel,
                             JPanel centerContainer, CardLayout centerLayout,
                             JPanel controlsContainer, CardLayout controlsLayout,
                             StatusBar statusBar, JFrame mainFrame,
                             MotorControllerService motorService, MotorControlPanel motorControlPanel) {
        this.config = config;
        this.buffer = buffer;
        this.volumeBuffer = volumeBuffer;
        this.state = state;
        this.signalProcessor = signalProcessor;
        this.scanExporter = scanExporter;
        this.portDiscoveryService = portDiscoveryService;
        this.aScanPanel = aScanPanel;
        this.bScanPanel = bScanPanel;
        this.volume3DPanel = volume3DPanel;
        this.controlPanel = controlPanel;
        this.volumeControlPanel = volumeControlPanel;
        this.centerContainer = centerContainer;
        this.centerLayout = centerLayout;
        this.controlsContainer = controlsContainer;
        this.controlsLayout = controlsLayout;
        this.statusBar = statusBar;
        this.mainFrame = mainFrame;
        this.motorService = motorService;
        this.motorControlPanel = motorControlPanel;

        // Synchronize initial CardLayout view to match initial ViewMode
        this.centerLayout.show(centerContainer, state.getViewMode() == ViewMode.MODE_2D ? "2D" : "3D");
        this.controlsLayout.show(controlsContainer, state.getViewMode() == ViewMode.MODE_2D ? "2D" : "3D");

        initActions();
        initShortcuts();
        refreshPorts();
        startRenderLoop();
    }

    private void initActions() {
        // 2D Controls
        controlPanel.onRecordClicked(this::toggleRecord);
        controlPanel.onClearClicked(this::clearScan);
        controlPanel.onModeClicked(this::toggleMode);
        controlPanel.onViewModeClicked(this::toggleViewMode);
        controlPanel.onZoomClicked(this::toggleZoom);
        controlPanel.onColormapClicked(this::cycleColormap);
        controlPanel.onSaveClicked(this::saveScan);
        controlPanel.onOpenFolderClicked(this::openScansFolder);
        controlPanel.onRefreshPortsClicked(this::refreshPorts);
        controlPanel.onConnectClicked(this::toggleConnect);
        controlPanel.onSimulationToggled(this::onSimulationToggled);

        // Motor Controller Listener for Autonomous 3D Chamber Scans
        if (motorService != null) {
            motorService.addListener(new MotorControllerService.MotorStateListener() {
                @Override
                public void onScanStateChanged(boolean isScanning, String message) {
                    SwingUtilities.invokeLater(() -> {
                        if (isScanning) {
                            prepareChamberScan3D();
                        } else {
                            if (volumeBuffer != null && !volumeBuffer.isComplete()) {
                                volumeBuffer.setComplete(true);
                            }
                            volume3DPanel.repaint();
                        }
                    });
                }

                @Override
                public void onPositionUpdated(double xMm, double yMm, long xSteps, long ySteps) {}

                @Override
                public void onScanProgress(int currentLine, int totalLines, double percent) {}
            });
        }

        // 3D Controls
        volumeControlPanel.onSwitchModeClicked(this::toggleViewMode);
        volumeControlPanel.onRecordClicked(this::toggleRecord);
        volumeControlPanel.onStyleClicked(this::cycleRender3DStyle);
        volumeControlPanel.onColormapClicked(this::cycleColormap);
        volumeControlPanel.onTuneColorsClicked(this::openColormapDialog);
        volumeControlPanel.onSaveClicked(this::saveScan);
        volumeControlPanel.onOpenFolderClicked(this::openScansFolder);
        volumeControlPanel.onClear3DClicked(this::clearScan);
        volumeControlPanel.onCameraPresetSelected(volume3DPanel::setCameraPreset);

        // View Mode Switcher
        state.addListener(s -> {
            Runnable r = () -> {
                try {
                    if (s.getViewMode() == ViewMode.MODE_2D) {
                        centerLayout.show(centerContainer, "2D");
                        controlsLayout.show(controlsContainer, "2D");
                        centerContainer.revalidate();
                        controlsContainer.revalidate();
                        bScanPanel.repaint();
                        aScanPanel.repaint();
                    } else {
                        centerLayout.show(centerContainer, "3D");
                        controlsLayout.show(controlsContainer, "3D");
                        centerContainer.revalidate();
                        controlsContainer.revalidate();
                        volume3DPanel.repaint();
                    }

                    // Reprocess 2D sweep if paused
                    if (!s.isRecording() && s.getMode() == ScanMode.SWEEP && buffer.getSweepIndex() > 0) {
                        buffer.reprocessSweep(signalProcessor, s.getSquelch(), s.getContrast());
                        bScanPanel.repaint();
                    }
                } catch (Exception ex) {
                    System.err.println("[ViewMode Switch Error] " + ex.getMessage());
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                SwingUtilities.invokeLater(r);
            }
        });

        mainFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
    }

    private void initShortcuts() {
        // Disable default TAB focus traversal so TAB can directly toggle 2D/3D view mode
        mainFrame.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Collections.emptySet());
        mainFrame.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Collections.emptySet());

        JRootPane root = mainFrame.getRootPane();
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "toggleViewModeTab", this::toggleViewMode);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_T, 0), "toggleViewModeT", this::toggleViewMode);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleRecord", this::toggleRecord);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_C, 0), "clearScan", this::clearScan);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "toggleMode", this::toggleMode);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_Z, 0), "toggleZoom", this::toggleZoom);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_V, 0), "cycleColormap", this::cycleColormap);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), "toggleInvertColormap", () -> state.toggleColormapInverted());
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_K, 0), "openColormapDialog", this::openColormapDialog);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "saveScan", this::saveScan);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_Y, 0), "cycle3DStyle", this::cycleRender3DStyle);
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, 0), "squelchDown",
                () -> state.setSquelch(state.getSquelch() - 5.0));
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, 0), "squelchUp",
                () -> state.setSquelch(state.getSquelch() + 5.0));
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0), "contrastDown",
                () -> state.setContrast(state.getContrast() - 0.2));
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0), "contrastUp",
                () -> state.setContrast(state.getContrast() + 0.2));
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, 0), "contrastUp2",
                () -> state.setContrast(state.getContrast() + 0.2));
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "refreshPorts", this::refreshPorts);

        // Echo Gate Quick Adjust: G / Shift+G for Gate Min, H / Shift+H for Gate Max
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), "gateMinDown", () -> nudgeGateMin(-0.5));
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.SHIFT_DOWN_MASK), "gateMinUp", () -> nudgeGateMin(0.5));
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "gateMaxDown", () -> nudgeGateMax(-0.5));
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_H, KeyEvent.SHIFT_DOWN_MASK), "gateMaxUp", () -> nudgeGateMax(0.5));

        // 3D Camera Presets: 1: Iso, 2: Top, 3: Front, 4: Side, 5: Back-Iso, 0: Reset
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "camIso",
                () -> { volume3DPanel.setCameraPreset(Volume3DPanel.CameraPreset.ISOMETRIC); volumeControlPanel.setCameraPresetSelection(Volume3DPanel.CameraPreset.ISOMETRIC); });
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "camTop",
                () -> { volume3DPanel.setCameraPreset(Volume3DPanel.CameraPreset.TOP_DOWN); volumeControlPanel.setCameraPresetSelection(Volume3DPanel.CameraPreset.TOP_DOWN); });
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_3, 0), "camFront",
                () -> { volume3DPanel.setCameraPreset(Volume3DPanel.CameraPreset.FRONT); volumeControlPanel.setCameraPresetSelection(Volume3DPanel.CameraPreset.FRONT); });
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_4, 0), "camSide",
                () -> { volume3DPanel.setCameraPreset(Volume3DPanel.CameraPreset.SIDE); volumeControlPanel.setCameraPresetSelection(Volume3DPanel.CameraPreset.SIDE); });
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_5, 0), "camBackIso",
                () -> { volume3DPanel.setCameraPreset(Volume3DPanel.CameraPreset.BACK_ISOMETRIC); volumeControlPanel.setCameraPresetSelection(Volume3DPanel.CameraPreset.BACK_ISOMETRIC); });
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_0, 0), "camReset",
                () -> { volume3DPanel.resetCamera(); volumeControlPanel.setCameraPresetSelection(Volume3DPanel.CameraPreset.ISOMETRIC); });

        // Emergency Gantry Halt on ESC
        registerAction(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "haltGantry",
                () -> {
                    if (motorService != null) {
                        motorService.halt();
                    }
                });
    }

    private void registerAction(JRootPane root, KeyStroke keyStroke, String actionKey, Runnable action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionKey);
        root.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    public void refreshPorts() {
        List<PortInfo> ports = portDiscoveryService.listAvailablePorts();
        Optional<PortInfo> autoPort = portDiscoveryService.autoDetectUltrasoundPort();
        controlPanel.setPorts(ports, autoPort.orElse(ports.isEmpty() ? null : ports.getFirst()));

        if (autoPort.isPresent()) {
            statusBar.setMessage("Auto-detected USB Ultrasound port: " + autoPort.get().systemPortName());
        } else if (ports.isEmpty()) {
            statusBar.setMessage("No COM ports found. You can enable Simulation Mode to test.");
        }
    }

    public void toggleConnect() {
        if (currentSource != null && currentSource.isRunning()) {
            disconnect();
        } else {
            connect();
        }
    }

    public void connect() {
        disconnect();

        if (controlPanel.isSimulationMode()) {
            currentSource = new SimulatedDataAcquisitionSource(config);
            startSource();
            if (motorService != null) {
                motorService.setAcquisitionSource(currentSource);
            }
            statusBar.setMessage("Connected to Synthetic Ultrasound Simulator (100 Hz PRF).");
            controlPanel.setConnected(true);
            return;
        }

        PortInfo selectedPort = controlPanel.getSelectedPort();
        if (selectedPort == null) {
            statusBar.setMessage("Error: No COM port selected.");
            return;
        }

        try {
            currentSource = new SerialDataAcquisitionSource(selectedPort.systemPortName(), config);
            startSource();
            if (motorService != null) {
                motorService.setAcquisitionSource(currentSource);
            }
            statusBar.setMessage("Connected to " + selectedPort.systemPortName() + " @ " + config.getBaudRate() + " baud.");
            controlPanel.setConnected(true);
        } catch (Exception ex) {
            statusBar.setMessage("Connection failed: " + ex.getMessage());
        }
    }

    private void startSource() {
        dataQueue.clear();
        currentSource.start(
                payload -> {
                    if (!dataQueue.offer(payload)) {
                        dataQueue.poll();
                        dataQueue.offer(payload);
                    }
                },
                err -> SwingUtilities.invokeLater(() -> statusBar.setMessage("DAQ Error: " + err.getMessage()))
        );
    }

    public void disconnect() {
        if (currentSource != null) {
            currentSource.stop();
            currentSource = null;
        }
        if (motorService != null) {
            motorService.setAcquisitionSource(null);
        }
        controlPanel.setConnected(false);
        statusBar.setMessage("Disconnected.");
    }

    public void toggleRecord() {
        state.toggleRecording();
    }

    public void clearScan() {
        if (state.getViewMode() == ViewMode.MODE_2D) {
            buffer.clearSweep();
            buffer.clearWaterfall();
            bScanPanel.repaint();
            statusBar.setMessage("Cleared 2D scan buffers.");
        } else {
            volumeBuffer.clear(state.getVolumeConfig());
            volume3DPanel.repaint();
            statusBar.setMessage("Cleared 3D volume buffer.");
        }
    }

    public void toggleMode() {
        state.toggleMode();
        bScanPanel.repaint();
    }

    public void toggleZoom() {
        state.toggleZoom();
        aScanPanel.repaint();
        bScanPanel.repaint();
        volume3DPanel.repaint();
    }

    public void cycleColormap() {
        state.cycleColormap();
        bScanPanel.repaint();
        volume3DPanel.repaint();
    }

    public void toggleViewMode() {
        state.toggleViewMode();
        ViewMode mode = state.getViewMode();
        statusBar.setMessage(mode == ViewMode.MODE_2D
                ? "Switched to 2D B-Scan mode."
                : "Switched to 2.5D / 3D Volumetric mode.");
    }

    public void cycleRender3DStyle() {
        state.cycleRender3DStyle();
        volume3DPanel.repaint();
        statusBar.setMessage("3D Render Style: " + state.getRender3DStyle().getLabel());
    }

    public void onSimulationToggled(boolean simEnabled) {
        if (currentSource != null && currentSource.isRunning()) {
            disconnect();
            connect();
        } else {
            if (simEnabled) {
                statusBar.setMessage("Simulation Mode enabled. Click 'Connect' to stream synthetic ultrasound data.");
            } else {
                statusBar.setMessage("Simulation Mode disabled. Select COM port and click 'Connect'.");
            }
        }
    }

    public void saveScan() {
        try {
            ScanExporter.ExportResult result;
            if (state.getViewMode() == ViewMode.MODE_2D) {
                BufferedImage snapshot = bScanPanel.captureSnapshot();
                result = scanExporter.exportScan(snapshot, buffer, config, state);
                String msg = "Saved 2D Scan: " + result.imageFile().getName() + " & " + result.dataFile().getName();
                statusBar.setMessage(msg);
                System.out.println("[SCAN SAVED 2D] " + result.imageFile().getAbsolutePath());
            } else {
                Volume3DPanel.MultiViewResult multiViews = volume3DPanel.captureMultiViewSnapshots();
                result = scanExporter.exportVolumeScanMultiView(multiViews, volumeBuffer,
                        state.getVolumeConfig(), config, state);
                String msg = "Saved 3D Multi-Views (" + (1 + result.additionalImages().size()) + " PNGs) & CSV: " + result.imageFile().getName();
                statusBar.setMessage(msg);
                System.out.println("[SCAN SAVED 3D MULTI-VIEWS] " + result.imageFile().getAbsolutePath());
            }

            notifySaveSuccess(result);
        } catch (Exception e) {
            e.printStackTrace();
            String errMsg = "Save Failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            statusBar.setMessage(errMsg);
            JOptionPane.showMessageDialog(mainFrame, errMsg, "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void notifySaveSuccess(ScanExporter.ExportResult result) {
        String title = "Scan Saved Successfully";
        StringBuilder sb = new StringBuilder();

        if (result.additionalImages().isEmpty()) {
            sb.append("2D B-Scan export complete!\n\n");
            sb.append("Image: ").append(result.imageFile().getName()).append("\n");
            sb.append("Data:  ").append(result.dataFile().getName()).append("\n\n");
        } else {
            sb.append("3D Volumetric Multi-View export complete!\n\n");
            sb.append("Saved ").append(result.additionalImages().size() + 1).append(" multi-angle PNG images:\n");
            sb.append(" • Master 4-View:   ").append(result.imageFile().getName()).append("\n");
            for (File f : result.additionalImages()) {
                String desc = f.getName().contains("iso") && !f.getName().contains("back") ? "Isometric 3D" :
                              f.getName().contains("top") ? "Top-Down (C-Scan)" :
                              f.getName().contains("front") ? "Front Elevation (XZ)" :
                              f.getName().contains("side") ? "Side Elevation (YZ)" :
                              f.getName().contains("back") ? "Reverse Isometric" :
                              f.getName().contains("active") ? "Active Viewport" : "View";
                sb.append(String.format(" • %-18s: %s\n", desc, f.getName()));
            }
            sb.append(" • Raw Data Table:     ").append(result.dataFile().getName()).append("\n\n");
        }

        sb.append("Directory:\n").append(result.imageFile().getParentFile().getAbsolutePath());

        Object[] options = {"Open Scans Folder", "OK"};
        int choice = JOptionPane.showOptionDialog(
                mainFrame,
                sb.toString(),
                title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[1]
        );

        if (choice == 0) { // "Open Scans Folder" selected
            openFolder(result.imageFile().getParentFile());
        }
    }

    public void openScansFolder() {
        openFolder(scanExporter.getOutputDirectory());
    }

    public void openFolder(File dir) {
        try {
            if (dir == null) dir = scanExporter.getOutputDirectory();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir);
                statusBar.setMessage("Opened directory: " + dir.getAbsolutePath());
            } else {
                statusBar.setMessage("Directory: " + dir.getAbsolutePath());
            }
        } catch (Exception ex) {
            statusBar.setMessage("Could not open folder: " + ex.getMessage());
        }
    }

    private void startRenderLoop() {
        List<byte[]> batch = new ArrayList<>(256);
        final int blankingSamples = 65;
        final double peakThreshold = 20.0;

        renderTimer = new Timer(16, e -> { // ~60 FPS
            batch.clear();
            dataQueue.drainTo(batch);

            if (!batch.isEmpty()) {
                int count = batch.size();
                state.incrementFrames(count);
                fpsFrameCount += count;

                float[] processed = new float[config.getNumSamples()];
                byte[] latestRaw = null;

                for (byte[] rawPacket : batch) {
                    byte statusByte = 0x00;
                    byte[] raw;
                    if (rawPacket.length > config.getNumSamples()) {
                        statusByte = rawPacket[0];
                        raw = Arrays.copyOfRange(rawPacket, 1, 1 + config.getNumSamples());
                    } else {
                        raw = rawPacket;
                    }

                    latestRaw = raw;
                    signalProcessor.process(raw, processed, state.getSquelch(), state.getContrast());

                    // Hardware status byte flags (from FPGA 2Mbaud UART)
                    boolean isHardwareScanning = (statusByte & 0x80) != 0;
                    boolean isScanComplete = (statusByte & 0x40) != 0;
                    boolean isLineStart = (statusByte & 0x20) != 0;
                    boolean isLineEnd = (statusByte & 0x10) != 0;
                    boolean dirXPositive = (statusByte & 0x08) != 0;

                    if (state.getViewMode() == ViewMode.MODE_2D) {
                        if (isHardwareScanning) {
                            if (state.getMode() == ScanMode.SWEEP) {
                                if (isLineStart) {
                                    buffer.clearSweep();
                                }
                                buffer.addSweepLine(raw, processed);
                            } else {
                                buffer.addWaterfallLine(processed);
                            }
                        } else if (state.isRecording()) {
                            if (state.getMode() == ScanMode.SWEEP) {
                                buffer.addSweepLine(raw, processed);
                            } else {
                                buffer.addWaterfallLine(processed);
                            }
                        }
                    }

                    // 3D Volumetric Mode: Record whenever FPGA hardware is actively scanning
                    // or when simulated data acquisition is recording
                    boolean isScanning3D = isHardwareScanning ||
                            (currentSource instanceof SimulatedDataAcquisitionSource && state.isRecording());

                    if (isScanning3D && !volumeBuffer.isComplete()) {
                        VolumeScanConfig vc = state.getVolumeConfig();
                        PeakEcho peak = signalProcessor.detectPeakWithinGate(processed, config,
                                vc.getGateMinDepthMm(), vc.getGateMaxDepthMm(), peakThreshold);
                        if (isHardwareScanning) {
                            volumeBuffer.recordCurrentPoint(raw, processed, peak, config, vc,
                                    isLineStart, isLineEnd, dirXPositive, isScanComplete);
                            if (isScanComplete && motorService != null) {
                                motorService.notifyHardwareScanComplete();
                            }
                        } else {
                            volumeBuffer.recordCurrentPoint(raw, processed, peak, config, vc);
                            if (currentSource instanceof SimulatedDataAcquisitionSource sim) {
                                double normX = (volumeBuffer.getCurrentX() / (double) Math.max(1, volumeBuffer.getGridX() - 1)) * 2.0 - 1.0;
                                double normY = (volumeBuffer.getCurrentY() / (double) Math.max(1, volumeBuffer.getGridY() - 1)) * 2.0 - 1.0;
                                sim.setStageCoordinate(normX, normY);
                            }
                        }
                    }
                }

                if (latestRaw != null) {
                    VolumeScanConfig vc = state.getVolumeConfig();
                    PeakEcho peak = signalProcessor.detectPeakWithinGate(processed, config,
                            vc.getGateMinDepthMm(), vc.getGateMaxDepthMm(), peakThreshold);
                    if (!peak.detected()) {
                        peak = signalProcessor.detectPeak(processed, config, blankingSamples, peakThreshold);
                    }
                    AScanFrame frame = new AScanFrame(System.nanoTime(), latestRaw, processed, peak);
                    state.setLatestFrame(frame);
                }

                // PRF Calculation every 1 second
                long now = System.currentTimeMillis();
                long elapsed = now - fpsTimerStart;
                if (elapsed >= 1000) {
                    double prf = (fpsFrameCount * 1000.0) / elapsed;
                    state.setCurrentFps(prf);
                    fpsFrameCount = 0;
                    fpsTimerStart = now;

                    String modeStr = state.getViewMode() == ViewMode.MODE_2D
                            ? ("2D " + state.getMode().name())
                            : ("3D " + state.getRender3DStyle().name());

                    mainFrame.setTitle(String.format("Ultrasound App | Mode: %s | PRF: %.1f Hz | Frames: %,d",
                            modeStr, prf, state.getTotalFramesReceived()));
                }

                if (state.getViewMode() == ViewMode.MODE_2D) {
                    aScanPanel.repaint();
                    bScanPanel.repaint();
                    statusBar.updateStats(state.getCurrentFps(), state.getTotalFramesReceived(), buffer.getSweepIndex());
                } else {
                    volume3DPanel.repaint();
                    statusBar.updateStats(state.getCurrentFps(), state.getTotalFramesReceived(), volumeBuffer.getLinesRecorded());
                }
            }
        });
        renderTimer.start();
    }

    public void prepareChamberScan3D() {
        // 1. Switch to 3D ViewMode automatically so the user sees the scan in real time
        state.setViewMode(ViewMode.MODE_3D);

        // 2. Synchronize VolumeScanConfig with exact gantry chamber parameters
        if (motorService != null) {
            MotorSettings ms = motorService.getSettings();
            double widthX = ms.getChamberWidthX();
            double lengthY = ms.getChamberLengthY();
            double lineSpacingY = ms.getLineSpacingY();
            double stepDeltaX = ms.getStepDeltaXMm();

            long totalXSteps = ms.mmToStepsX(widthX);
            long totalYSteps = ms.mmToStepsY(lengthY);
            int deltaYSteps = (int) Math.max(1, ms.mmToStepsY(lineSpacingY));
            int deltaXSteps = (int) Math.clamp(Math.round(ms.getStepsPerMmX() * stepDeltaX), 1, 255);

            int gridX = (int) Math.max(2, totalXSteps / deltaXSteps);
            int gridY = (int) Math.max(2, totalYSteps / deltaYSteps + 1);

            double actualStepSizeX = ms.stepsToMmX(deltaXSteps);
            double actualStepSizeY = lineSpacingY;

            VolumeScanConfig vConfig = state.getVolumeConfig();
            vConfig.setGridX(gridX);
            vConfig.setGridY(gridY);
            vConfig.setStepSizeXMm(actualStepSizeX);
            vConfig.setStepSizeYMm(actualStepSizeY);
            vConfig.setPattern(VolumeScanConfig.ScanPattern.SNAKE);

            // 3. Reallocate and clear buffer with fresh clean state
            volumeBuffer.reallocate(vConfig);
            volumeBuffer.clear(vConfig);

            // 4. Drain any leftover frames in queue
            dataQueue.clear();

            // 5. Trigger repaint
            volume3DPanel.repaint();
            statusBar.setMessage("Chamber Scan in progress: " + gridX + " x " + gridY + " grid (" + (gridX * gridY) + " pts)...");
        }
    }

    private ColormapTuningDialog colormapDialog = null;

    private void openColormapDialog() {
        if (colormapDialog == null) {
            colormapDialog = new ColormapTuningDialog(mainFrame, state, volume3DPanel, volume3DPanel.getColormapRegistry());
        }
        colormapDialog.setLocationRelativeTo(mainFrame);
        colormapDialog.setVisible(true);
    }

    private void nudgeGateMin(double deltaMm) {
        VolumeScanConfig vc = state.getVolumeConfig();
        double newMin = Math.clamp(vc.getGateMinDepthMm() + deltaMm, 2.0, vc.getGateMaxDepthMm() - 0.5);
        vc.setGateMinDepthMm(newMin);
        if (state.isColorRangeLockToGate()) {
            state.setColorMinDepthMm(newMin);
        }
        state.setVolumeConfig(vc);
        volumeControlPanel.setGateMinSelection((int) Math.round(newMin));
        statusBar.setMessage(String.format("Echo Gate & Colormap: [%.1f - %.1f mm]", newMin, vc.getGateMaxDepthMm()));
        aScanPanel.repaint();
        volume3DPanel.repaint();
    }

    private void nudgeGateMax(double deltaMm) {
        VolumeScanConfig vc = state.getVolumeConfig();
        double newMax = Math.clamp(vc.getGateMaxDepthMm() + deltaMm, vc.getGateMinDepthMm() + 0.5, 100.0);
        vc.setGateMaxDepthMm(newMax);
        if (state.isColorRangeLockToGate()) {
            state.setColorMaxDepthMm(newMax);
        }
        state.setVolumeConfig(vc);
        volumeControlPanel.setGateMaxSelection((int) Math.round(newMax));
        statusBar.setMessage(String.format("Echo Gate & Colormap: [%.1f - %.1f mm]", vc.getGateMinDepthMm(), newMax));
        aScanPanel.repaint();
        volume3DPanel.repaint();
    }

    public void shutdown() {
        if (renderTimer != null) renderTimer.stop();
        disconnect();
    }
}
