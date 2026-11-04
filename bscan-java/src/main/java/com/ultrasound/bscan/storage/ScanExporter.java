package com.ultrasound.bscan.storage;

import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScannerState;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Interface for saving scan images and raw data.
 */
public interface ScanExporter {

    record ExportResult(File imageFile, File dataFile) {}

    ExportResult exportScan(BufferedImage renderedImage, ScanBuffer buffer, ScanConfig config, ScannerState state) throws IOException;
}
