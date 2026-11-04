package com.ultrasound.bscan.storage;

import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.model.VolumeScanConfig;
import com.ultrasound.bscan.ui.Volume3DPanel;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Interface for saving 2D and 2.5D/3D scan images and raw data.
 */
public interface ScanExporter {

    record ExportResult(File imageFile, File dataFile, List<File> additionalImages) {
        public ExportResult(File imageFile, File dataFile) {
            this(imageFile, dataFile, List.of());
        }
    }

    File getOutputDirectory();

    ExportResult exportScan(BufferedImage renderedImage, ScanBuffer buffer, ScanConfig config, ScannerState state) throws IOException;

    ExportResult exportVolumeScan(BufferedImage renderedImage, VolumeBuffer volumeBuffer,
                                  VolumeScanConfig volumeConfig, ScanConfig config, ScannerState state) throws IOException;

    ExportResult exportVolumeScanMultiView(Volume3DPanel.MultiViewResult multiViews, VolumeBuffer volumeBuffer,
                                          VolumeScanConfig volumeConfig, ScanConfig config, ScannerState state) throws IOException;
}
