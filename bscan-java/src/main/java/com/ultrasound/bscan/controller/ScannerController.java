package com.ultrasound.bscan.controller;

import com.ultrasound.bscan.dsp.SignalProcessor;
import com.ultrasound.bscan.io.DataAcquisitionSource;
import com.ultrasound.bscan.io.PortDiscoveryService;
import com.ultrasound.bscan.io.PortInfo;
import com.ultrasound.bscan.io.SerialDataAcquisitionSource;
import com.ultrasound.bscan.io.SimulatedDataAcquisitionSource;
import com.ultrasound.bscan.model.AScanFrame;
import com.ultrasound.bscan.model.PeakEcho;
import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScanMode;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.storage.ScanExporter;
import com.ultrasound.bscan.ui.AScanPanel;
import com.ultrasound.bscan.ui.BScanPanel;
import com.ultrasound.bscan.ui.ControlPanel;
import com.ultrasound.bscan.ui.StatusBar;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Controller orchestrating data acquisition, DSP pipeline, UI updates, and export actions.
 */
public final class ScannerController {

    private final ScanConfig config;
    private final ScanBuffer buffer;
    private final ScannerState state;
    private final SignalProcessor signalProcessor;
    private final ScanExporter scanExporter;
    private final PortDiscoveryService portDiscoveryService;

    private final AScanPanel aScanPanel;
    private final BScanPanel bScanPanel;
    private final ControlPanel controlPanel;
    private final StatusBar statusBar;
    private final JFrame mainFrame;

    private DataAcquisitionSource currentSource;
    private final ArrayBlockingQueue<byte[]> dataQueue = new ArrayBlockingQueue<>(4000);
    private Timer renderTimer;

    private long fpsTimerStart = System.currentTimeMillis();
    private long fpsFrameCount = 0;

    public ScannerController(ScanConfig config, ScanBuffer buffer, ScannerState state,
                             SignalProcessor signalProcessor, ScanExporter scanExporter,
                             PortDiscoveryService portDiscoveryService,
                             AScanPanel aScanPanel, BScanPanel bScanPanel,
                             ControlPanel controlPanel, StatusBar statusBar,
                             JFrame mainFrame) {
        this.config = config;
        this.buffer = buffer;
        this.state = state;
        this.signalProcessor = signalProcessor;
        this.scanExporter = scanExporter;
        this.portDiscoveryService = portDiscoveryService;
        this.aScanPanel = aScanPanel;
        this.bScanPanel = bScanPanel;
        this.controlPanel = controlPanel;
        this.statusBar = statusBar;
        this.mainFrame = mainFrame;

        initActions();
        initShortcuts();
        refreshPorts();
        startRenderLoop();
    }

    private void initActions() {
        controlPanel.onRecordClicked(this::toggleRecord);
        controlPanel.onClearClicked(this::clearScan);
        controlPanel.onModeClicked(this::toggleMode);
        controlPanel.onZoomClicked(this::toggleZoom);
        controlPanel.onColormapClicked(this::cycleColormap);
        controlPanel.onSaveClicked(this::saveScan);
        controlPanel.onRefreshPortsClicked(this::refreshPorts);
        controlPanel.onConnectClicked(this::toggleConnect);

        // When squelch or contrast changes, reprocess sweep if currently paused
        state.addListener(s -> {
            if (!s.isRecording() && s.getMode() == ScanMode.SWEEP && buffer.getSweepIndex() > 0) {
                buffer.reprocessSweep(signalProcessor, s.getSquelch(), s.getContrast());
                bScanPanel.repaint();
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
        mainFrame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_SPACE -> toggleRecord();
                    case KeyEvent.VK_C -> clearScan();
                    case KeyEvent.VK_M -> toggleMode();
                    case KeyEvent.VK_Z -> toggleZoom();
                    case KeyEvent.VK_V -> cycleColormap();
                    case KeyEvent.VK_S -> saveScan();
                    case KeyEvent.VK_OPEN_BRACKET -> state.setSquelch(state.getSquelch() - 5.0);
                    case KeyEvent.VK_CLOSE_BRACKET -> state.setSquelch(state.getSquelch() + 5.0);
                    case KeyEvent.VK_MINUS -> state.setContrast(state.getContrast() - 0.2);
                    case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS -> state.setContrast(state.getContrast() + 0.2);
                }
            }
        });
        mainFrame.setFocusable(true);
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
                    // Deliver payload to queue, dropping oldest if UI thread is backed up
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
        controlPanel.setConnected(false);
        statusBar.setMessage("Disconnected.");
    }

    public void toggleRecord() {
        state.toggleRecording();
    }

    public void clearScan() {
        buffer.clearSweep();
        buffer.clearWaterfall();
        bScanPanel.repaint();
        statusBar.setMessage("Cleared scan buffers.");
    }

    public void toggleMode() {
        state.toggleMode();
        bScanPanel.repaint();
    }

    public void toggleZoom() {
        state.toggleZoom();
        aScanPanel.repaint();
        bScanPanel.repaint();
    }

    public void cycleColormap() {
        state.cycleColormap();
        bScanPanel.repaint();
    }

    public void saveScan() {
        try {
            BufferedImage snapshot = bScanPanel.captureSnapshot();
            ScanExporter.ExportResult result = scanExporter.exportScan(snapshot, buffer, config, state);
            String msg = "Saved: " + result.imageFile().getName() + " & " + result.dataFile().getName();
            statusBar.setMessage(msg);
            System.out.println("[SCAN SAVED] " + result.imageFile().getAbsolutePath() + " | " + result.dataFile().getAbsolutePath());
        } catch (IOException e) {
            statusBar.setMessage("Save Error: " + e.getMessage());
        }
    }

    private void startRenderLoop() {
        List<byte[]> batch = new ArrayList<>(256);
        final int blankingSamples = 60; // Skip first 60 samples (~9mm) ring-down
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

                for (byte[] raw : batch) {
                    latestRaw = raw;
                    signalProcessor.process(raw, processed, state.getSquelch(), state.getContrast());

                    if (state.isRecording()) {
                        if (state.getMode() == ScanMode.SWEEP) {
                            buffer.addSweepLine(raw, processed);
                        } else {
                            buffer.addWaterfallLine(processed);
                        }
                    }
                }

                if (latestRaw != null) {
                    PeakEcho peak = signalProcessor.detectPeak(processed, config, blankingSamples, peakThreshold);
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

                    mainFrame.setTitle(String.format("Ultrasound B-Scan Acquisition | PRF: %.1f Hz | Frames: %,d | Mode: %s",
                            prf, state.getTotalFramesReceived(), state.getMode().name()));
                }

                aScanPanel.repaint();
                bScanPanel.repaint();
                statusBar.updateStats(state.getCurrentFps(), state.getTotalFramesReceived(), buffer.getSweepIndex());
            }
        });
        renderTimer.start();
    }

    public void shutdown() {
        if (renderTimer != null) renderTimer.stop();
        disconnect();
    }
}
