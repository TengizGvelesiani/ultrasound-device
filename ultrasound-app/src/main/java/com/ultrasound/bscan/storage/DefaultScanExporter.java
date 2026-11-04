package com.ultrasound.bscan.storage;

import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScanMode;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.model.VolumeScanConfig;
import com.ultrasound.bscan.ui.Volume3DPanel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class DefaultScanExporter implements ScanExporter {

    private final File outputDirectory;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public DefaultScanExporter() {
        this(new File("scans").getAbsoluteFile());
    }

    public DefaultScanExporter(File outputDirectory) {
        this.outputDirectory = outputDirectory != null ? outputDirectory.getAbsoluteFile() : new File("scans").getAbsoluteFile();
    }

    @Override
    public File getOutputDirectory() {
        return outputDirectory;
    }

    @Override
    public ExportResult exportScan(BufferedImage renderedImage, ScanBuffer buffer, ScanConfig config, ScannerState state) throws IOException {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        File scanDir = new File(outputDirectory, "Scan_2D_" + timestamp);
        if (!scanDir.exists()) {
            boolean created = scanDir.mkdirs();
            if (!created && !scanDir.exists()) {
                throw new IOException("Failed to create export directory: " + scanDir.getAbsolutePath());
            }
        }

        File imgFile = new File(scanDir, "01_BScan_2D_Image.png");
        File dataFile = new File(scanDir, "Data_2D_Raw_AScans.csv");

        // 1. Save Image
        if (renderedImage != null) {
            boolean written = ImageIO.write(renderedImage, "PNG", imgFile);
            if (!written) {
                throw new IOException("Failed to save image in PNG format: " + imgFile.getAbsolutePath());
            }
        }

        // 2. Save CSV with header metadata + matrix
        int numSamples = config.getNumSamples();
        int activeCols = state.getMode() == ScanMode.SWEEP ? Math.max(1, buffer.getSweepIndex()) : buffer.getWaterfallWidth();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(dataFile, StandardCharsets.UTF_8))) {
            writer.write("# Ultrasound B-Scan Acquisition Export\n");
            writer.write("# Timestamp: " + timestamp + "\n");
            writer.write("# Mode: " + state.getMode().name() + "\n");
            writer.write("# SampleRate_Hz: " + config.getSampleRate() + "\n");
            writer.write("# SoundSpeed_mps: " + config.getSoundSpeed() + "\n");
            writer.write("# NumSamples: " + numSamples + "\n");
            writer.write("# ActiveColumns: " + activeCols + "\n");
            writer.write("# Squelch: " + state.getSquelch() + "\n");
            writer.write("# Contrast: " + state.getContrast() + "\n");
            writer.write("# MaxDepth_mm: " + config.getMaxDepthMm() + "\n");

            // Write row by row (each row is a depth sample across all columns)
            for (int s = 0; s < numSamples; s++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < activeCols; c++) {
                    float val = buffer.getSample(state.getMode(), c, s);
                    if (c > 0) sb.append(',');
                    sb.append((int) val);
                }
                sb.append('\n');
                writer.write(sb.toString());
            }
        }

        return new ExportResult(imgFile, dataFile);
    }

    @Override
    public ExportResult exportVolumeScan(BufferedImage renderedImage, VolumeBuffer volumeBuffer,
                                        VolumeScanConfig volumeConfig, ScanConfig config, ScannerState state) throws IOException {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        File scanDir = new File(outputDirectory, "Scan_3D_" + timestamp);
        if (!scanDir.exists()) {
            boolean created = scanDir.mkdirs();
            if (!created && !scanDir.exists()) {
                throw new IOException("Failed to create export directory: " + scanDir.getAbsolutePath());
            }
        }

        File imgFile = new File(scanDir, "01_3D_Surface_View.png");
        File dataFile = new File(scanDir, "Data_3D_Volume_Matrix.csv");

        // 1. Save 3D snapshot image
        if (renderedImage != null) {
            boolean written = ImageIO.write(renderedImage, "PNG", imgFile);
            if (!written) {
                throw new IOException("Failed to save 3D image in PNG format: " + imgFile.getAbsolutePath());
            }
        }

        // 2. Save 3D surface heightmap & C-Scan table
        exportVolumeCsv(dataFile, volumeBuffer, volumeConfig, timestamp);

        return new ExportResult(imgFile, dataFile);
    }

    @Override
    public ExportResult exportVolumeScanMultiView(Volume3DPanel.MultiViewResult multiViews,
                                                 VolumeBuffer volumeBuffer, VolumeScanConfig volumeConfig,
                                                 ScanConfig config, ScannerState state) throws IOException {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        File scanDir = new File(outputDirectory, "Scan_3D_" + timestamp);
        if (!scanDir.exists()) {
            boolean created = scanDir.mkdirs();
            if (!created && !scanDir.exists()) {
                throw new IOException("Failed to create export directory: " + scanDir.getAbsolutePath());
            }
        }

        // Ordered, numbered filenames for clean file manager sorting
        File compositeFile = new File(scanDir, "01_Overview_MultiView_Report.png");
        File topFile       = new File(scanDir, "02_TopDown_CScan_XY.png");
        File isoFile       = new File(scanDir, "03_Isometric_3D_Surface.png");
        File frontFile     = new File(scanDir, "04_Front_Elevation_XZ.png");
        File sideFile      = new File(scanDir, "05_Side_Elevation_YZ.png");
        File backIsoFile   = new File(scanDir, "06_Reverse_Isometric_Back.png");
        File activeFile    = new File(scanDir, "07_Active_Viewport.png");
        File dataFile      = new File(scanDir, "Data_3D_Volume_Matrix.csv");

        List<File> additionalFiles = new ArrayList<>();

        if (multiViews.compositeImage() != null) {
            ImageIO.write(multiViews.compositeImage(), "PNG", compositeFile);
        }
        if (multiViews.topImage() != null) {
            ImageIO.write(multiViews.topImage(), "PNG", topFile);
            additionalFiles.add(topFile);
        }
        if (multiViews.isoImage() != null) {
            ImageIO.write(multiViews.isoImage(), "PNG", isoFile);
            additionalFiles.add(isoFile);
        }
        if (multiViews.frontImage() != null) {
            ImageIO.write(multiViews.frontImage(), "PNG", frontFile);
            additionalFiles.add(frontFile);
        }
        if (multiViews.sideImage() != null) {
            ImageIO.write(multiViews.sideImage(), "PNG", sideFile);
            additionalFiles.add(sideFile);
        }
        if (multiViews.backIsoImage() != null) {
            ImageIO.write(multiViews.backIsoImage(), "PNG", backIsoFile);
            additionalFiles.add(backIsoFile);
        }
        if (multiViews.activeImage() != null) {
            ImageIO.write(multiViews.activeImage(), "PNG", activeFile);
            additionalFiles.add(activeFile);
        }

        exportVolumeCsv(dataFile, volumeBuffer, volumeConfig, timestamp);

        return new ExportResult(compositeFile, dataFile, additionalFiles);
    }

    private void exportVolumeCsv(File dataFile, VolumeBuffer volumeBuffer,
                                 VolumeScanConfig volumeConfig, String timestamp) throws IOException {
        int gridX = volumeBuffer.getGridX();
        int gridY = volumeBuffer.getGridY();
        double dx = volumeConfig.getStepSizeXMm();
        double dy = volumeConfig.getStepSizeYMm();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(dataFile, StandardCharsets.UTF_8))) {
            writer.write("# Ultrasound 2.5D / 3D Volumetric Scan Export\n");
            writer.write("# Timestamp: " + timestamp + "\n");
            writer.write("# GridX: " + gridX + "\n");
            writer.write("# GridY: " + gridY + "\n");
            writer.write("# StepX_mm: " + dx + "\n");
            writer.write("# StepY_mm: " + dy + "\n");
            writer.write("# TotalWidthX_mm: " + volumeConfig.getTotalWidthXMm() + "\n");
            writer.write("# TotalHeightY_mm: " + volumeConfig.getTotalHeightYMm() + "\n");
            writer.write("# GateMinDepth_mm: " + volumeConfig.getGateMinDepthMm() + "\n");
            writer.write("# GateMaxDepth_mm: " + volumeConfig.getGateMaxDepthMm() + "\n");
            writer.write("X_idx,Y_idx,X_mm,Y_mm,Depth_Z_mm,Peak_Amplitude,CScan_MIP,Measured\n");

            volumeBuffer.getLock().readLock().lock();
            try {
                for (int y = 0; y < gridY; y++) {
                    for (int x = 0; x < gridX; x++) {
                        float depthZ = volumeBuffer.getHeight(x, y);
                        float amp = volumeBuffer.getAmplitude(x, y);
                        float cscan = volumeBuffer.getCScan(x, y);
                        boolean measured = volumeBuffer.isMeasured(x, y);

                        writer.write(String.format("%d,%d,%.2f,%.2f,%.2f,%.1f,%.1f,%b\n",
                                x, y, x * dx, y * dy, depthZ, amp, cscan, measured));
                    }
                }
            } finally {
                volumeBuffer.getLock().readLock().unlock();
            }
        }
    }
}
