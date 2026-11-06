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
        this(resolveDefaultScansDirectory());
    }

    public DefaultScanExporter(File outputDirectory) {
        this.outputDirectory = outputDirectory != null ? outputDirectory.getAbsoluteFile() : resolveDefaultScansDirectory();
    }

    public static File resolveDefaultScansDirectory() {
        File userDir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        if (userDir.getName().equalsIgnoreCase("target") && userDir.getParentFile() != null) {
            return new File(userDir.getParentFile(), "scans").getAbsoluteFile();
        }
        File childSub = new File(userDir, "ultrasound-app");
        if (childSub.exists() && childSub.isDirectory()) {
            return new File(childSub, "scans").getAbsoluteFile();
        }
        return new File(userDir, "scans").getAbsoluteFile();
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

        File rawDataFile = new File(scanDir, "Data_2D_Raw_AScans.csv");

        File procDataFile = new File(scanDir, "Data_2D_Processed_AScans.csv");

        File summaryFile = new File(scanDir, "Data_2D_Echo_Summary.csv");



        // 1. Save Image

        if (renderedImage != null) {

            boolean written = ImageIO.write(renderedImage, "PNG", imgFile);

            if (!written) {

                throw new IOException("Failed to save image in PNG format: " + imgFile.getAbsolutePath());

            }

        }



        int numSamples = config.getNumSamples();

        int activeCols = state.getMode() == ScanMode.SWEEP ? Math.max(1, buffer.getSweepIndex()) : buffer.getWaterfallWidth();

        boolean isLine = buffer.isLineScanActive();

        String axis = isLine ? buffer.getLineScanAxisLabel() : "X";

        double startMm = isLine ? buffer.getLineScanStartMm() : 0.0;

        double stepMm = isLine ? buffer.getLineScanStepMm() : 0.1;

        double dtUs = 1.0e6 / config.getSampleRate();



        // 2. Save Raw A-Scan Matrix CSV (Raw ADC Bytes 0..255 direct from FPGA)

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(rawDataFile, StandardCharsets.UTF_8))) {

            writer.write("# Ultrasound B-Scan Acquisition Export\n");

            writer.write("# Timestamp: " + timestamp + "\n");

            writer.write("# Mode: " + state.getMode().name() + "\n");

            writer.write("# ScanType: " + (isLine ? ("2D_LINE_SCAN (Axis: " + axis + ", Start: " + String.format(java.util.Locale.US, "%.2f", startMm) + " mm, Step: " + String.format(java.util.Locale.US, "%.3f", stepMm) + " mm)") : "CONTINUOUS_B_SCAN") + "\n");

            writer.write("# SampleRate_Hz: " + config.getSampleRate() + "\n");

            writer.write("# SoundSpeed_mps: " + config.getSoundSpeed() + "\n");

            writer.write("# NumSamples: " + numSamples + "\n");

            writer.write("# ActiveColumns: " + activeCols + "\n");

            writer.write("# Squelch: " + state.getSquelch() + "\n");

            writer.write("# Contrast: " + state.getContrast() + "\n");

            writer.write("# MaxDepth_mm: " + String.format(java.util.Locale.US, "%.3f", config.getMaxDepthMm()) + "\n");



            StringBuilder header = new StringBuilder("Sample_Idx,Time_us,Depth_mm");

            for (int c = 0; c < activeCols; c++) {

                double pos = startMm + c * stepMm;

                header.append(',').append(String.format(java.util.Locale.US, "Line_%03d_%s_%.2fmm", c, axis, pos));

            }

            header.append('\n');

            writer.write(header.toString());



            for (int s = 0; s < numSamples; s++) {

                double timeUs = s * dtUs;

                double depthMm = config.getDepthForSample(s);

                StringBuilder sb = new StringBuilder();

                sb.append(s).append(',').append(String.format(java.util.Locale.US, "%.3f", timeUs)).append(',').append(String.format(java.util.Locale.US, "%.3f", depthMm));

                for (int c = 0; c < activeCols; c++) {

                    int rawVal = buffer.getRawSample(state.getMode(), c, s);

                    sb.append(',').append(rawVal);

                }

                sb.append('\n');

                writer.write(sb.toString());

            }

        }



        // 3. Save Processed A-Scan Matrix CSV (After Squelch & Contrast)

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(procDataFile, StandardCharsets.UTF_8))) {

            writer.write("# Ultrasound B-Scan Processed Data Export\n");

            writer.write("# Timestamp: " + timestamp + "\n");

            writer.write("# Squelch: " + state.getSquelch() + " | Contrast: " + state.getContrast() + "\n");



            StringBuilder header = new StringBuilder("Sample_Idx,Time_us,Depth_mm");

            for (int c = 0; c < activeCols; c++) {

                double pos = startMm + c * stepMm;

                header.append(',').append(String.format(java.util.Locale.US, "Line_%03d_%s_%.2fmm", c, axis, pos));

            }

            header.append('\n');

            writer.write(header.toString());



            for (int s = 0; s < numSamples; s++) {

                double timeUs = s * dtUs;

                double depthMm = config.getDepthForSample(s);

                StringBuilder sb = new StringBuilder();

                sb.append(s).append(',').append(String.format(java.util.Locale.US, "%.3f", timeUs)).append(',').append(String.format(java.util.Locale.US, "%.3f", depthMm));

                for (int c = 0; c < activeCols; c++) {

                    float val = buffer.getSample(state.getMode(), c, s);

                    sb.append(',').append(String.format(java.util.Locale.US, "%.1f", val));

                }

                sb.append('\n');

                writer.write(sb.toString());

            }

        }



        // 4. Save Per-Line Echo Diagnostic & Peak Summary CSV

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(summaryFile, StandardCharsets.UTF_8))) {

            writer.write("# 2D Line Scan Echo Diagnostic & Peak Summary\n");

            writer.write("# Timestamp: " + timestamp + "\n");

            writer.write("# Axis: " + axis + " | Range: " + String.format(java.util.Locale.US, "%.2f", startMm) + " mm -> " + String.format(java.util.Locale.US, "%.2f", startMm + (activeCols - 1) * stepMm) + " mm\n");

            writer.write("Line_Idx,Axis,Position_mm,First_Echo_Depth_mm,First_Echo_Raw_Amp,Max_Echo_Depth_mm,Max_Echo_Raw_Amp,Container_Floor_Depth_mm,Container_Floor_Raw_Amp,Submerged_Object_Detected\n");



            int minBlankIdx = Math.max(60, config.getSampleIndexForDepth(8.5));



            for (int c = 0; c < activeCols; c++) {

                double pos = startMm + c * stepMm;

                byte[] rawCol = buffer.getRawColumn(c);

                if (rawCol == null) {

                    rawCol = new byte[numSamples];

                    for (int s = 0; s < numSamples; s++) {

                        rawCol[s] = (byte) buffer.getRawSample(state.getMode(), c, s);

                    }

                }



                // Global maximum peak across the full depth buffer

                int maxIdx = minBlankIdx;

                int maxVal = 0;

                for (int s = minBlankIdx; s < rawCol.length; s++) {

                    int v = rawCol[s] & 0xFF;

                    if (v > maxVal) {

                        maxVal = v;

                        maxIdx = s;

                    }

                }



                // First distinct crest above threshold (> 8.5 mm blanking)

                int firstIdx = maxIdx;

                int firstVal = maxVal;

                for (int s = minBlankIdx + 1; s < rawCol.length - 1; s++) {

                    int v = rawCol[s] & 0xFF;

                    int vPrev = rawCol[s - 1] & 0xFF;

                    int vNext = rawCol[s + 1] & 0xFF;

                    if (v >= 30 && v >= vPrev && v > vNext) {

                        firstIdx = s;

                        firstVal = v;

                        break;

                    }

                }



                // Deepest strong reflection (e.g. chamber floor)

                int deepIdx = maxIdx;

                int deepVal = maxVal;

                for (int s = rawCol.length - 2; s >= minBlankIdx + 1; s--) {

                    int v = rawCol[s] & 0xFF;

                    int vPrev = rawCol[s - 1] & 0xFF;

                    int vNext = rawCol[s + 1] & 0xFF;

                    if (v >= 30 && v >= vPrev && v > vNext) {

                        deepIdx = s;

                        deepVal = v;

                        break;

                    }

                }



                double firstDepth = config.getDepthForSample(firstIdx);

                double maxDepth = config.getDepthForSample(maxIdx);

                double deepDepth = config.getDepthForSample(deepIdx);



                // An elevated object is detected if the first reflection is significantly shallower than the deepest boundary

                boolean objectDetected = (deepDepth - firstDepth >= 1.5 && firstVal >= 30);



                writer.write(String.format(java.util.Locale.US,

                        "%d,%s,%.2f,%.2f,%d,%.2f,%d,%.2f,%d,%s\n",

                        c, axis, pos, firstDepth, firstVal, maxDepth, maxVal, deepDepth, deepVal, objectDetected ? "YES" : "NO"));

            }

        }



        return new ExportResult(imgFile, rawDataFile, List.of(), List.of(procDataFile, summaryFile));

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



                        writer.write(String.format(java.util.Locale.US, "%d,%d,%.2f,%.2f,%.2f,%.1f,%.1f,%b\n",

                                x, y, x * dx, y * dy, depthZ, amp, cscan, measured));

                    }

                }

            } finally {

                volumeBuffer.getLock().readLock().unlock();

            }

        }

    }

}

