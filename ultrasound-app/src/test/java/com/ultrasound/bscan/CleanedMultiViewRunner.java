package com.ultrasound.bscan;

import com.ultrasound.bscan.colormap.ColormapRegistry;
import com.ultrasound.bscan.colormap.ColormapType;
import com.ultrasound.bscan.model.Render3DStyle;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.model.VolumeScanConfig;
import com.ultrasound.bscan.ui.Volume3DPanel;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CleanedMultiViewRunner {

    @Test
    void generateCleanedMultiViews() throws IOException {
        File csvFile = new File("target/scans/third scan/Data_3D_Volume_Matrix_Cleaned.csv").getAbsoluteFile();
        if (!csvFile.exists()) {
            csvFile = new File("scans/Scan_3D_20261106_012545/Data_3D_Volume_Matrix_Cleaned.csv").getAbsoluteFile();
        }
        System.out.println("Loading cleaned matrix from: " + csvFile.getAbsolutePath());

        int gridX = 200;
        int gridY = 41;
        double stepX = 0.1;
        double stepY = 1.0;
        double gateMin = 108.0;
        double gateMax = 130.0;

        ScanConfig config = new ScanConfig();
        VolumeScanConfig vConfig = new VolumeScanConfig(gridX, gridY, stepX, stepY,
                VolumeScanConfig.ScanPattern.SNAKE, gateMin, gateMax);

        VolumeBuffer volumeBuffer = new VolumeBuffer(config.getNumSamples(), vConfig);

        float[] heightMap = volumeBuffer.getHeightMapUnsafe();
        float[] ampMap = volumeBuffer.getAmplitudeMapUnsafe();
        float[] cScanMap = volumeBuffer.getCScanMapUnsafe();
        boolean[] measuredMap = volumeBuffer.getMeasuredMapUnsafe();

        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("X_idx")) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length >= 7) {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    float z = Float.parseFloat(parts[4].trim());
                    float amp = Float.parseFloat(parts[5].trim());
                    float cscan = Float.parseFloat(parts[6].trim());

                    int idx = y * gridX + x;
                    if (idx >= 0 && idx < heightMap.length) {
                        heightMap[idx] = z;
                        ampMap[idx] = amp;
                        cScanMap[idx] = (z < 129.5f) ? cscan : 0.0f;
                        measuredMap[idx] = true;
                    }
                }
            }
        }

        volumeBuffer.setComplete(true);

        ScannerState state = new ScannerState();
        state.setVolumeConfig(vConfig);
        state.setColormap(ColormapType.TURBO);
        state.setRender3DStyle(Render3DStyle.SHADED_SURFACE);
        state.setContrast(1.2);
        state.setDepthExaggeration(1.8);
        state.setColorRangeLockToGate(false);
        state.setColorMinDepthMm(123.0);
        state.setColorMaxDepthMm(130.0);

        ColormapRegistry registry = new ColormapRegistry();
        Volume3DPanel panel = new Volume3DPanel(config, volumeBuffer, state, registry);

        Volume3DPanel.MultiViewResult mvResult = panel.captureMultiViewSnapshots();

        List<File> targetDirs = new ArrayList<>();
        targetDirs.add(new File("target/scans/third scan").getAbsoluteFile());
        targetDirs.add(new File("scans/third scan").getAbsoluteFile());
        targetDirs.add(new File("scans/Scan_3D_20261106_012545").getAbsoluteFile());

        for (File dir : targetDirs) {
            dir.mkdirs();
            ImageIO.write(mvResult.compositeImage(), "PNG", new File(dir, "01_Cleaned_Overview_MultiView_Report.png"));
            ImageIO.write(mvResult.topImage(), "PNG", new File(dir, "02_Cleaned_TopDown_CScan_XY.png"));
            ImageIO.write(mvResult.isoImage(), "PNG", new File(dir, "03_Cleaned_Isometric_3D_Surface.png"));
            ImageIO.write(mvResult.frontImage(), "PNG", new File(dir, "04_Cleaned_Front_Elevation_XZ.png"));
            ImageIO.write(mvResult.sideImage(), "PNG", new File(dir, "05_Cleaned_Side_Elevation_YZ.png"));
            ImageIO.write(mvResult.backIsoImage(), "PNG", new File(dir, "06_Cleaned_Reverse_Isometric_Back.png"));
            ImageIO.write(mvResult.activeImage(), "PNG", new File(dir, "07_Cleaned_Active_Viewport.png"));
            System.out.println("Saved 7 cleaned views to: " + dir.getAbsolutePath());
        }

        File artifactDir = new File("C:/Users/nikad/.gemini/antigravity/brain/31870478-11c9-44db-a8ec-a58a8082c474");
        if (artifactDir.exists()) {
            ImageIO.write(mvResult.compositeImage(), "PNG", new File(artifactDir, "cleaned_nut_01_Overview_MultiView_Report.png"));
            ImageIO.write(mvResult.topImage(), "PNG", new File(artifactDir, "cleaned_nut_02_TopDown_CScan_XY.png"));
            ImageIO.write(mvResult.isoImage(), "PNG", new File(artifactDir, "cleaned_nut_03_Isometric_3D_Surface.png"));
            ImageIO.write(mvResult.frontImage(), "PNG", new File(artifactDir, "cleaned_nut_04_Front_Elevation_XZ.png"));
            ImageIO.write(mvResult.sideImage(), "PNG", new File(artifactDir, "cleaned_nut_05_Side_Elevation_YZ.png"));
            ImageIO.write(mvResult.backIsoImage(), "PNG", new File(artifactDir, "cleaned_nut_06_Reverse_Isometric_Back.png"));
            ImageIO.write(mvResult.activeImage(), "PNG", new File(artifactDir, "cleaned_nut_07_Active_Viewport.png"));
            System.out.println("Saved to artifactDir: " + artifactDir.getAbsolutePath());
        }
    }
}
