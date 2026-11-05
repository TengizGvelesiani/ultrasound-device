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
            float defaultFloor = (float) config.getGateMaxDepthMm();
            Arrays.fill(this.heightMapMm, defaultFloor);
            this.amplitudeMap = new float[totalPixels];
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
            Arrays.fill(amplitudeMap, 0.0f);
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
                forwardX = dirXPositive;
                currentX = dirXPositive ? 0 : Math.max(0, gridX - 1);
            }

            int idx = currentY * gridX + currentX;
            if (idx >= 0 && idx < heightMapMm.length) {
                // 1. Surface elevation & amplitude from peak echo inside the active gate
                double gateMin = volumeConfig.getGateMinDepthMm();
                double gateMax = volumeConfig.getGateMaxDepthMm();
                if (gateMax <= gateMin) gateMax = gateMin + 1.0;

                if (peak != null && peak.detected() && peak.depthMm() >= gateMin && peak.depthMm() <= gateMax) {
                    heightMapMm[idx] = (float) Math.clamp(peak.depthMm(), gateMin, gateMax);
                    amplitudeMap[idx] = peak.amplitude();
                } else {
                    heightMapMm[idx] = (float) gateMax;
                    amplitudeMap[idx] = 0.0f;
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
                measuredMap[idx] = true;

                // 3. Store full A-scan in 3D voxel volume
                int voxelOffset = idx * numSamples;
                System.arraycopy(raw, 0, voxelData, voxelOffset, Math.min(raw.length, numSamples));

                linesRecorded++;
            }

            if (isScanComplete) {
                isComplete = true;
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
                if (currentY < gridY - 1) {
                    currentY++;
                } else {
                    isComplete = true;
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
                amplitudeMap[idx] = peak.amplitude();
            } else {
                heightMapMm[idx] = (float) gateMax;
                amplitudeMap[idx] = 0.0f;
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
            measuredMap[idx] = true;

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
}
