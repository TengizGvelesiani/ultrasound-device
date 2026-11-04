package com.ultrasound.bscan;

import com.ultrasound.bscan.model.PeakEcho;
import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.ViewMode;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.model.VolumeScanConfig;
import com.ultrasound.bscan.storage.DefaultScanExporter;
import com.ultrasound.bscan.storage.ScanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultScanExporterTest {

    private File tempDir;
    private DefaultScanExporter exporter;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("bscan_export_test").toFile();
        exporter = new DefaultScanExporter(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir.exists()) {
            Files.walk(tempDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void testExportScan2D() throws IOException {
        ScanConfig config = new ScanConfig();
        ScanBuffer buffer = new ScanBuffer(config.getNumSamples());
        ScannerState state = new ScannerState();

        // Add some test scan lines
        byte[] raw = new byte[config.getNumSamples()];
        float[] proc = new float[config.getNumSamples()];
        for (int i = 0; i < config.getNumSamples(); i++) {
            raw[i] = (byte) (i % 256);
            proc[i] = (float) (i % 256);
        }
        buffer.addSweepLine(raw, proc);
        buffer.addSweepLine(raw, proc);

        BufferedImage img = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.BLUE);
        g2.fillRect(0, 0, 800, 450);
        g2.dispose();

        ScanExporter.ExportResult result = exporter.exportScan(img, buffer, config, state);

        assertNotNull(result);
        assertNotNull(result.imageFile());
        assertNotNull(result.dataFile());

        assertTrue(result.imageFile().exists());
        assertTrue(result.imageFile().length() > 0);
        assertTrue(result.imageFile().getName().endsWith(".png"));
        assertTrue(result.imageFile().getParentFile().getName().startsWith("Scan_2D_"));

        assertTrue(result.dataFile().exists());
        assertTrue(result.dataFile().length() > 0);
        assertTrue(result.dataFile().getName().endsWith(".csv"));
        assertTrue(result.dataFile().getParentFile().getName().startsWith("Scan_2D_"));

        List<String> lines = Files.readAllLines(result.dataFile().toPath());
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).contains("Ultrasound B-Scan Acquisition Export"));
    }

    @Test
    void testExportVolumeScan3D() throws IOException {
        ScanConfig config = new ScanConfig();
        ScannerState state = new ScannerState();
        state.setViewMode(ViewMode.MODE_3D);
        VolumeScanConfig vConfig = state.getVolumeConfig();
        VolumeBuffer volumeBuffer = new VolumeBuffer(config.getNumSamples(), vConfig);

        byte[] raw = new byte[config.getNumSamples()];
        float[] proc = new float[config.getNumSamples()];
        PeakEcho peak = PeakEcho.of(35.0, 180.0f, 120);
        volumeBuffer.recordCurrentPoint(raw, proc, peak, config, vConfig);

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.DARK_GRAY);
        g2.fillRect(0, 0, 800, 600);
        g2.dispose();

        ScanExporter.ExportResult result = exporter.exportVolumeScan(img, volumeBuffer, vConfig, config, state);

        assertNotNull(result);
        assertTrue(result.imageFile().exists());
        assertTrue(result.imageFile().length() > 0);
        assertTrue(result.imageFile().getName().endsWith(".png"));
        assertTrue(result.imageFile().getParentFile().getName().startsWith("Scan_3D_"));

        assertTrue(result.dataFile().exists());
        assertTrue(result.dataFile().length() > 0);
        assertTrue(result.dataFile().getName().endsWith(".csv"));
        assertTrue(result.dataFile().getParentFile().getName().startsWith("Scan_3D_"));

        List<String> lines = Files.readAllLines(result.dataFile().toPath());
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).contains("Ultrasound 2.5D / 3D Volumetric Scan Export"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("X_idx,Y_idx,X_mm,Y_mm,Depth_Z_mm")));
    }

    @Test
    void testExportVolumeScanMultiView() throws IOException {
        ScanConfig config = new ScanConfig();
        ScannerState state = new ScannerState();
        state.setViewMode(ViewMode.MODE_3D);
        VolumeScanConfig vConfig = state.getVolumeConfig();
        VolumeBuffer volumeBuffer = new VolumeBuffer(config.getNumSamples(), vConfig);

        byte[] raw = new byte[config.getNumSamples()];
        float[] proc = new float[config.getNumSamples()];
        PeakEcho peak = PeakEcho.of(35.0, 180.0f, 120);
        volumeBuffer.recordCurrentPoint(raw, proc, peak, config, vConfig);

        BufferedImage dummy = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        com.ultrasound.bscan.ui.Volume3DPanel.MultiViewResult multiViews =
                new com.ultrasound.bscan.ui.Volume3DPanel.MultiViewResult(
                        dummy, dummy, dummy, dummy, dummy, dummy, dummy
                );

        ScanExporter.ExportResult result = exporter.exportVolumeScanMultiView(multiViews, volumeBuffer, vConfig, config, state);

        assertNotNull(result);
        assertTrue(result.imageFile().exists());
        assertTrue(result.imageFile().getName().contains("MultiView"));
        assertTrue(result.imageFile().getParentFile().getName().startsWith("Scan_3D_"));
        assertEquals(6, result.additionalImages().size());
        for (File f : result.additionalImages()) {
            assertTrue(f.exists());
            assertTrue(f.length() > 0);
            assertTrue(f.getParentFile().getName().startsWith("Scan_3D_"));
        }
    }

    @Test
    void testFullVolumetricSimulationMultiViewGeneration() throws IOException {
        ScanConfig config = new ScanConfig();
        ScannerState state = new ScannerState();
        state.setViewMode(ViewMode.MODE_3D);
        state.setRender3DStyle(com.ultrasound.bscan.model.Render3DStyle.SHADED_SURFACE);
        state.setColormap(com.ultrasound.bscan.colormap.ColormapType.INFERNO);

        VolumeScanConfig vConfig = new VolumeScanConfig(50, 50, 1.0, 1.0,
                VolumeScanConfig.ScanPattern.SNAKE, 20.0, 50.0);
        state.setVolumeConfig(vConfig);

        VolumeBuffer volumeBuffer = new VolumeBuffer(config.getNumSamples(), vConfig);
        com.ultrasound.bscan.dsp.DefaultSignalProcessor signalProcessor = new com.ultrasound.bscan.dsp.DefaultSignalProcessor();

        // Synthetic raster scanning
        java.util.Random rnd = new java.util.Random(42);
        int plateSample = config.getSampleIndexForDepth(38.0);
        int coinBaseSample = config.getSampleIndexForDepth(35.0);

        for (int step = 0; step < 50 * 50; step++) {
            int cx = volumeBuffer.getCurrentX();
            int cy = volumeBuffer.getCurrentY();
            double probeX = (cx / 49.0) * 2.0 - 1.0;
            double probeY = (cy / 49.0) * 2.0 - 1.0;

            byte[] raw = new byte[config.getNumSamples()];
            for (int i = 0; i < raw.length; i++) raw[i] = (byte) (15 + rnd.nextInt(12));

            double dist = Math.sqrt(probeX * probeX + probeY * probeY);
            int echoCenter;
            int echoAmp;

            if (dist < 0.52) {
                double angle = Math.atan2(probeY, probeX);
                double starR = 0.13 + 0.05 * Math.cos(5.0 * angle);
                double reliefMm;
                if (dist < starR) {
                    reliefMm = 2.0;
                } else if (dist > 0.40 && dist < 0.50) {
                    reliefMm = 1.2;
                } else if (dist >= 0.50) {
                    double frac = (dist - 0.50) / 0.02;
                    reliefMm = 1.2 * (1.0 - frac) - 3.0 * frac;
                } else {
                    reliefMm = 0.0;
                }
                echoCenter = config.getSampleIndexForDepth(35.0 - reliefMm);
                echoAmp = 245;
            } else {
                echoCenter = plateSample;
                echoAmp = 195;
            }

            for (int di = -6; di <= 6; di++) {
                int idx = echoCenter + di;
                if (idx >= 0 && idx < raw.length) {
                    double env = Math.exp(-(di * di) / 8.0);
                    int pulse = (int) (echoAmp * env * Math.cos(di * 1.2));
                    raw[idx] = (byte) Math.clamp((raw[idx] & 0xFF) + Math.max(0, pulse), 0, 255);
                }
            }

            float[] processed = new float[config.getNumSamples()];
            signalProcessor.process(raw, processed, 10.0, 1.0);
            PeakEcho peak = signalProcessor.detectPeak(processed, config, 60, 20.0);
            volumeBuffer.recordCurrentPoint(raw, processed, peak, config, vConfig);
        }

        assertTrue(volumeBuffer.isComplete());
        assertEquals(2500, volumeBuffer.getLinesRecorded());

        // Render actual 3D multi-views
        com.ultrasound.bscan.colormap.ColormapRegistry registry = new com.ultrasound.bscan.colormap.ColormapRegistry();
        com.ultrasound.bscan.ui.Volume3DPanel panel = new com.ultrasound.bscan.ui.Volume3DPanel(config, volumeBuffer, state, registry);

        com.ultrasound.bscan.ui.Volume3DPanel.MultiViewResult mvResult = panel.captureMultiViewSnapshots();
        assertNotNull(mvResult.compositeImage());
        assertNotNull(mvResult.isoImage());
        assertNotNull(mvResult.topImage());
        assertNotNull(mvResult.frontImage());
        assertNotNull(mvResult.sideImage());

        DefaultScanExporter liveExporter = new DefaultScanExporter(new File("scans").getAbsoluteFile());
        ScanExporter.ExportResult exportResult = liveExporter.exportVolumeScanMultiView(mvResult, volumeBuffer, vConfig, config, state);

        assertNotNull(exportResult);
        assertTrue(exportResult.imageFile().exists());
        assertTrue(exportResult.imageFile().getName().startsWith("01_Overview_MultiView_Report"));
        assertEquals(6, exportResult.additionalImages().size());
        assertTrue(exportResult.dataFile().exists());
    }

    @Test
    void testGetOutputDirectory() {
        assertEquals(tempDir.getAbsoluteFile(), exporter.getOutputDirectory());
    }
}
