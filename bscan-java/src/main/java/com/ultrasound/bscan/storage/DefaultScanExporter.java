package com.ultrasound.bscan.storage;

import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScanMode;
import com.ultrasound.bscan.model.ScannerState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class DefaultScanExporter implements ScanExporter {

    private final File outputDirectory;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public DefaultScanExporter() {
        this(new File("scans"));
    }

    public DefaultScanExporter(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    @Override
    public ExportResult exportScan(BufferedImage renderedImage, ScanBuffer buffer, ScanConfig config, ScannerState state) throws IOException {
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Failed to create export directory: " + outputDirectory.getAbsolutePath());
        }

        String timestamp = LocalDateTime.now().format(timeFormatter);
        File imgFile = new File(outputDirectory, "bscan_" + timestamp + ".png");
        File dataFile = new File(outputDirectory, "bscan_" + timestamp + ".csv");

        // 1. Save Image
        if (renderedImage != null) {
            ImageIO.write(renderedImage, "PNG", imgFile);
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
}
