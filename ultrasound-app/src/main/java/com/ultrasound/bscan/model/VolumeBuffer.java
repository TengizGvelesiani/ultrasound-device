package com.ultrasound.bscan.model;



import java.util.Arrays;

import java.util.concurrent.locks.ReentrantReadWriteLock;



/**

 * Thread-safe volumetric and 2.5D surface container.

 * Stores 3D voxel matrices, surface elevation heightmaps Z(x, y),

 * peak echo amplitudes A(x, y), and en-face C-scan depth gate projections.

 * Supports direct hardware-synchronized coordinate alignment from FPGA gantry status flags.

 */

public final class VolumeBuffer {



    private final int numSamples;

    private int gridX;

    private int gridY;



    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();



    // 2.5D Surface Topography

    private float[] heightMapMm;      // Depth in mm for each (x, y)
    private float[] rawHeightMapMm;
    private float[] rawAmplitudeMap;
    private boolean filterEnabled = true;
    private double backlashCompensationMm = 0.0;

    private float[] amplitudeMap;     // Echo intensity 0..255 for each (x, y)

    private float[] cScanMap;         // Depth-gated C-scan Maximum Intensity Projection

    private boolean[] measuredMap;    // True if a valid scan line has been recorded at (x, y)



    // Full 3D Volumetric Voxel Storage: (x * gridY + y) * numSamples + sampleIdx

    private byte[] voxelData;



    // Raster Scan Head Tracker

    private int currentX = 0;

    private int currentY = 0;

    private boolean forwardX = true;

    private int linesRecorded = 0;

    private boolean isComplete = false;

    private boolean lineAdvanced = false;



    public VolumeBuffer(int numSamples, VolumeScanConfig volumeConfig) {

        this.numSamples = numSamples;

        reallocate(volumeConfig);

    }



    public void reallocate(VolumeScanConfig config) {

        rwLock.writeLock().lock();

        try {

            this.gridX = config.getGridX();

            this.gridY = config.getGridY();

            int totalPixels = gridX * gridY;



            this.heightMapMm = new float[totalPixels];
            this.rawHeightMapMm = new float[totalPixels];

            float defaultFloor = (float) config.getGateMaxDepthMm();

            Arrays.fill(this.heightMapMm, defaultFloor);
            Arrays.fill(this.rawHeightMapMm, defaultFloor);

            this.amplitudeMap = new float[totalPixels];
            this.rawAmplitudeMap = new float[totalPixels];

            this.cScanMap = new float[totalPixels];

            this.measuredMap = new boolean[totalPixels];

            this.voxelData = new byte[totalPixels * numSamples];



            resetScanningState();

        } finally {

            rwLock.writeLock().unlock();

        }

    }



    public void clear(VolumeScanConfig config) {

        rwLock.writeLock().lock();

        try {

            int total = gridX * gridY;

            float defaultFloor = (float) config.getGateMaxDepthMm();

            Arrays.fill(heightMapMm, defaultFloor);
            if (rawHeightMapMm != null) Arrays.fill(rawHeightMapMm, defaultFloor);

            Arrays.fill(amplitudeMap, 0.0f);
            if (rawAmplitudeMap != null) Arrays.fill(rawAmplitudeMap, 0.0f);

            Arrays.fill(cScanMap, 0.0f);

            Arrays.fill(measuredMap, false);

            Arrays.fill(voxelData, (byte) 0);

            resetScanningState();

        } finally {

            rwLock.writeLock().unlock();

        }

    }



    private void resetScanningState() {

        this.currentX = 0;

        this.currentY = 0;

        this.forwardX = true;

        this.linesRecorded = 0;

        this.isComplete = false;

        this.lineAdvanced = false;

    }



    /**

     * Stores incoming A-scan frame with hardware-synchronized alignment from FPGA frame status flags.

     */

    public void recordCurrentPoint(byte[] raw, float[] processed, PeakEcho peak,

                                   ScanConfig scanConfig, VolumeScanConfig volumeConfig,

                                   boolean isLineStart, boolean isLineEnd, boolean dirXPositive, boolean isScanComplete) {

        rwLock.writeLock().lock();

        try {

            if (isComplete) return;



            // If hardware signaled LINE_START, align raster head directly with gantry state

            if (isLineStart) {

                if (!lineAdvanced && forwardX != dirXPositive && currentY < gridY - 1) {

                    currentY++;

                }

                lineAdvanced = false;

                forwardX = dirXPositive;

                currentX = dirXPositive ? 0 : Math.max(0, gridX - 1);

            }



            int effectiveX = currentX;
            if (!forwardX && backlashCompensationMm > 0.0 && volumeConfig != null && volumeConfig.getStepSizeXMm() > 0) {
                int offsetCells = (int) Math.round(backlashCompensationMm / volumeConfig.getStepSizeXMm());
                effectiveX = Math.clamp(currentX + offsetCells, 0, gridX - 1);
            }

            int idx = currentY * gridX + effectiveX;

            if (idx >= 0 && idx < heightMapMm.length) {

                // 1. Surface elevation & amplitude from peak echo inside the active gate

                double gateMin = volumeConfig.getGateMinDepthMm();

                double gateMax = volumeConfig.getGateMaxDepthMm();

                if (gateMax <= gateMin) gateMax = gateMin + 1.0;



                if (peak != null && peak.detected() && peak.depthMm() >= gateMin && peak.depthMm() <= gateMax) {

                    heightMapMm[idx] = (float) Math.clamp(peak.depthMm(), gateMin, gateMax);
                    if (rawHeightMapMm != null) rawHeightMapMm[idx] = heightMapMm[idx];

                    amplitudeMap[idx] = peak.amplitude();
                    if (rawAmplitudeMap != null) rawAmplitudeMap[idx] = amplitudeMap[idx];

                    measuredMap[idx] = true;

                } else {

                    // Do not mark as valid measurement when specular echo dips below threshold
                    heightMapMm[idx] = (float) gateMax;
                    if (rawHeightMapMm != null) rawHeightMapMm[idx] = heightMapMm[idx];

                    amplitudeMap[idx] = 0.0f;
                    if (rawAmplitudeMap != null) rawAmplitudeMap[idx] = 0.0f;

                    measuredMap[idx] = false;

                }



                // 2. Depth-gated C-Scan MIP

                int startSample = scanConfig.getSampleIndexForDepth(volumeConfig.getGateMinDepthMm());

                int endSample = scanConfig.getSampleIndexForDepth(volumeConfig.getGateMaxDepthMm());

                float maxGatedAmp = 0.0f;

                for (int s = startSample; s <= endSample && s < processed.length; s++) {

                    if (processed[s] > maxGatedAmp) {

                        maxGatedAmp = processed[s];

                    }

                }

                cScanMap[idx] = maxGatedAmp;



                // 3. Store full A-scan in 3D voxel volume

                int voxelOffset = idx * numSamples;

                System.arraycopy(raw, 0, voxelData, voxelOffset, Math.min(raw.length, numSamples));



                linesRecorded++;

            }



            if (isScanComplete) {

                isComplete = true;

                if (filterEnabled) {
                    applySpatialFiltersInternal();
                }

                return;

            }



            // Advance X along current sweep line

            if (forwardX) {

                if (currentX < gridX - 1) {

                    currentX++;

                }

            } else {

                if (currentX > 0) {

                    currentX--;

                }

            }



            // If hardware signaled LINE_END, step Y to next raster line

            if (isLineEnd) {

                lineAdvanced = true;

                if (currentY < gridY - 1) {

                    currentY++;

                } else {

                    isComplete = true;

                    if (filterEnabled) {
                        applySpatialFiltersInternal();
                    }

                }

            }

        } finally {

            rwLock.writeLock().unlock();

        }

    }



    /**

     * Stores incoming A-scan frame at current (x, y) head and automatically advances raster position (simulation fallback).

     */

    public void recordCurrentPoint(byte[] raw, float[] processed, PeakEcho peak,

                                   ScanConfig scanConfig, VolumeScanConfig volumeConfig) {

        rwLock.writeLock().lock();

        try {

            if (isComplete) return;



            int idx = currentY * gridX + currentX;

            if (idx < 0 || idx >= heightMapMm.length) { isComplete = true; return; }



            // 1. Surface elevation & amplitude from peak echo inside the active gate

            double gateMin = volumeConfig.getGateMinDepthMm();

            double gateMax = volumeConfig.getGateMaxDepthMm();

            if (gateMax <= gateMin) gateMax = gateMin + 1.0;



            if (peak != null && peak.detected() && peak.depthMm() >= gateMin && peak.depthMm() <= gateMax) {

                heightMapMm[idx] = (float) Math.clamp(peak.depthMm(), gateMin, gateMax);
                if (rawHeightMapMm != null) rawHeightMapMm[idx] = heightMapMm[idx];

                amplitudeMap[idx] = peak.amplitude();
                if (rawAmplitudeMap != null) rawAmplitudeMap[idx] = amplitudeMap[idx];

                measuredMap[idx] = true;

            } else {

                heightMapMm[idx] = (float) gateMax;
                if (rawHeightMapMm != null) rawHeightMapMm[idx] = heightMapMm[idx];

                amplitudeMap[idx] = 0.0f;
                if (rawAmplitudeMap != null) rawAmplitudeMap[idx] = 0.0f;

                measuredMap[idx] = false;

            }



            // 2. Depth-gated C-Scan MIP

            int startSample = scanConfig.getSampleIndexForDepth(volumeConfig.getGateMinDepthMm());

            int endSample = scanConfig.getSampleIndexForDepth(volumeConfig.getGateMaxDepthMm());

            float maxGatedAmp = 0.0f;

            for (int s = startSample; s <= endSample && s < processed.length; s++) {

                if (processed[s] > maxGatedAmp) {

                    maxGatedAmp = processed[s];

                }

            }

            cScanMap[idx] = maxGatedAmp;



            // 3. Store full A-scan in 3D voxel volume

            int voxelOffset = idx * numSamples;

            System.arraycopy(raw, 0, voxelData, voxelOffset, Math.min(raw.length, numSamples));



            linesRecorded++;



            // 4. Advance raster position

            advanceRasterPosition(volumeConfig);

        } finally {

            rwLock.writeLock().unlock();

        }

    }



    private void advanceRasterPosition(VolumeScanConfig config) {

        if (config.getPattern() == VolumeScanConfig.ScanPattern.SNAKE) {

            if (forwardX) {

                if (currentX < gridX - 1) {

                    currentX++;

                } else {

                    // Reached right edge, step Y and reverse direction

                    if (currentY < gridY - 1) {

                        currentY++;

                        forwardX = false;

                    } else {

                        isComplete = true;

                    }

                }

            } else {

                if (currentX > 0) {

                    currentX--;

                } else {

                    // Reached left edge, step Y and forward direction

                    if (currentY < gridY - 1) {

                        currentY++;

                        forwardX = true;

                    } else {

                        isComplete = true;

                    }

                }

            }

        } else { // UNIDIRECTIONAL

            if (currentX < gridX - 1) {

                currentX++;

            } else {

                currentX = 0;

                if (currentY < gridY - 1) {

                    currentY++;

                } else {

                    isComplete = true;

                    if (filterEnabled) {
                        applySpatialFiltersInternal();
                    }

                }

            }

        }

    }



    /**

     * Recalculates C-scan MIP values when gate boundaries are adjusted.

     */

    public void recomputeCScan(double minDepthMm, double maxDepthMm, ScanConfig scanConfig, double squelch, double contrast) {

        rwLock.writeLock().lock();

        try {

            int startSample = scanConfig.getSampleIndexForDepth(minDepthMm);

            int endSample = scanConfig.getSampleIndexForDepth(maxDepthMm);

            float sq = (float) squelch;

            float ct = (float) contrast;



            int total = gridX * gridY;

            for (int i = 0; i < total; i++) {

                if (!measuredMap[i]) continue;



                int offset = i * numSamples;

                float maxVal = 0.0f;

                for (int s = startSample; s <= endSample && s < numSamples; s++) {

                    int raw = voxelData[offset + s] & 0xFF;

                    float val = Math.clamp((raw - sq) * ct, 0.0f, 255.0f);

                    if (val > maxVal) {

                        maxVal = val;

                    }

                }

                cScanMap[i] = maxVal;

            }

        } finally {

            rwLock.writeLock().unlock();

        }

    }



    public float getHeight(int x, int y) {

        rwLock.readLock().lock();

        try {

            if (x < 0 || x >= gridX || y < 0 || y >= gridY) return 0.0f;

            return heightMapMm[y * gridX + x];

        } finally {

            rwLock.readLock().unlock();

        }

    }



    public float getAmplitude(int x, int y) {

        rwLock.readLock().lock();

        try {

            if (x < 0 || x >= gridX || y < 0 || y >= gridY) return 0.0f;

            return amplitudeMap[y * gridX + x];

        } finally {

            rwLock.readLock().unlock();

        }

    }



    public float getCScan(int x, int y) {

        rwLock.readLock().lock();

        try {

            if (x < 0 || x >= gridX || y < 0 || y >= gridY) return 0.0f;

            return cScanMap[y * gridX + x];

        } finally {

            rwLock.readLock().unlock();

        }

    }



    public boolean isMeasured(int x, int y) {

        rwLock.readLock().lock();

        try {

            if (x < 0 || x >= gridX || y < 0 || y >= gridY) return false;

            return measuredMap[y * gridX + x];

        } finally {

            rwLock.readLock().unlock();

        }

    }



    public int getGridX() { return gridX; }

    public int getGridY() { return gridY; }

    public int getCurrentX() { return currentX; }

    public int getCurrentY() { return currentY; }

    public int getLinesRecorded() { return linesRecorded; }

    public boolean isComplete() { return isComplete; }

    public void setComplete(boolean complete) { this.isComplete = complete; }

    public ReentrantReadWriteLock getLock() { return rwLock; }



    public float[] getHeightMapUnsafe() { return heightMapMm; }

    public float[] getAmplitudeMapUnsafe() { return amplitudeMap; }

    public float[] getCScanMapUnsafe() { return cScanMap; }

    public boolean[] getMeasuredMapUnsafe() { return measuredMap; }

    public byte[] getVoxelDataUnsafe() { return voxelData; }


    public boolean isFilterEnabled() {
        return filterEnabled;
    }

    public void setFilterEnabled(boolean enabled) {
        rwLock.writeLock().lock();
        try {
            this.filterEnabled = enabled;
            if (enabled) {
                applySpatialFiltersInternal();
            } else {
                if (rawHeightMapMm != null && heightMapMm != null) {
                    System.arraycopy(rawHeightMapMm, 0, heightMapMm, 0, heightMapMm.length);
                    System.arraycopy(rawAmplitudeMap, 0, amplitudeMap, 0, amplitudeMap.length);
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public double getBacklashCompensationMm() {
        return backlashCompensationMm;
    }

    public void setBacklashCompensationMm(double mm) {
        this.backlashCompensationMm = Math.clamp(mm, 0.0, 5.0);
    }

    /**
     * Inpaints / fills missing specular dropout holes across the 2.5D surface
     * using the 2D neighborhood median of valid measured samples.
     */
    public void inpaintMissingSamples() {
        if (heightMapMm == null || measuredMap == null || gridX <= 0 || gridY <= 0) return;
        float[] filledZ = Arrays.copyOf(heightMapMm, heightMapMm.length);
        float[] filledA = Arrays.copyOf(amplitudeMap, amplitudeMap.length);
        boolean[] filledM = Arrays.copyOf(measuredMap, measuredMap.length);
        float[] winZ = new float[25];
        float[] winA = new float[25];

        for (int pass = 0; pass < 2; pass++) {
            boolean filledAny = false;
            for (int y = 0; y < gridY; y++) {
                for (int x = 0; x < gridX; x++) {
                    int idx = y * gridX + x;
                    if (!filledM[idx]) {
                        int count = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            int ny = y + dy;
                            if (ny < 0 || ny >= gridY) continue;
                            for (int dx = -1; dx <= 1; dx++) {
                                int nx = x + dx;
                                if (nx < 0 || nx >= gridX) continue;
                                int nIdx = ny * gridX + nx;
                                if (filledM[nIdx]) {
                                    winZ[count] = filledZ[nIdx];
                                    winA[count] = filledA[nIdx];
                                    count++;
                                }
                            }
                        }
                        if (count > 0) {
                            Arrays.sort(winZ, 0, count);
                            filledZ[idx] = winZ[count / 2];
                            float sumA = 0;
                            for (int k = 0; k < count; k++) sumA += winA[k];
                            filledA[idx] = sumA / count;
                            filledM[idx] = true;
                            filledAny = true;
                        }
                    }
                }
            }
            if (!filledAny) break;
        }

        System.arraycopy(filledZ, 0, heightMapMm, 0, heightMapMm.length);
        System.arraycopy(filledA, 0, amplitudeMap, 0, amplitudeMap.length);
        System.arraycopy(filledM, 0, measuredMap, 0, measuredMap.length);
    }

    public void applySpatialFiltersInternal() {
        if (rawHeightMapMm == null || heightMapMm == null || gridX <= 0 || gridY <= 0) return;

        // First, fill in any gaps where we didn't get a good echo
        inpaintMissingSamples();

        // Then smooth out noise with a median filter that keeps edges sharp
        // Using median instead of averaging prevents blurring sharp features
        float[] tempZ = new float[heightMapMm.length];
        float[] tempA = new float[amplitudeMap.length];
        float[] windowZ = new float[9];
        float[] windowA = new float[9];

        for (int y = 0; y < gridY; y++) {
            for (int x = 0; x < gridX; x++) {
                int count = 0;
                // Grab the 3x3 neighborhood around this point
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= gridY) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= gridX) continue;
                        int nIdx = ny * gridX + nx;
                        if (measuredMap != null && measuredMap[nIdx]) {
                            windowZ[count] = heightMapMm[nIdx];
                            windowA[count] = amplitudeMap[nIdx];
                            count++;
                        }
                    }
                }
                int idx = y * gridX + x;
                if (count > 0) {
                    // Pick the middle value to filter out spikes
                    java.util.Arrays.sort(windowZ, 0, count);
                    java.util.Arrays.sort(windowA, 0, count);
                    tempZ[idx] = windowZ[count / 2];
                    tempA[idx] = windowA[count / 2];
                } else {
                    tempZ[idx] = heightMapMm[idx];
                    tempA[idx] = amplitudeMap[idx];
                }
            }
        }
        System.arraycopy(tempZ, 0, heightMapMm, 0, heightMapMm.length);
        System.arraycopy(tempA, 0, amplitudeMap, 0, amplitudeMap.length);
    }
}

