package com.ultrasound.bscan.model;

import java.util.Arrays;

/**
 * Immutable ultrasound hardware configuration and physical acoustic constants.
 */
public final class ScanConfig {

    public static final int DEFAULT_NUM_SAMPLES = 1024;
    public static final double DEFAULT_SAMPLE_RATE = 5_000_000.0;     // 5 MHz decimated sample rate
    public static final double DEFAULT_SOUND_SPEED = 1480.0;          // m/s in water at 20°C
    public static final int DEFAULT_BAUD_RATE = 2_000_000;            // 2 Mbaud
    public static final byte[] SYNC_HEADER = new byte[] { (byte) 0xFF, (byte) 0xAA, (byte) 0x55, (byte) 0x00 };

    private final int numSamples;
    private final double sampleRate;
    private final double soundSpeed;
    private final int baudRate;
    private final double[] depthAxisMm;
    private final double maxDepthMm;

    public ScanConfig() {
        this(DEFAULT_NUM_SAMPLES, DEFAULT_SAMPLE_RATE, DEFAULT_SOUND_SPEED, DEFAULT_BAUD_RATE);
    }

    public ScanConfig(int numSamples, double sampleRate, double soundSpeed, int baudRate) {
        if (numSamples <= 0) throw new IllegalArgumentException("numSamples must be > 0");
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        if (soundSpeed <= 0) throw new IllegalArgumentException("soundSpeed must be > 0");

        this.numSamples = numSamples;
        this.sampleRate = sampleRate;
        this.soundSpeed = soundSpeed;
        this.baudRate = baudRate;

        // Precompute depth for each sample index:
        // Depth = (t * c / 2) = (i / sampleRate) * (soundSpeed / 2) * 1000 (mm)
        this.depthAxisMm = new double[numSamples];
        double mmPerSample = (soundSpeed / (2.0 * sampleRate)) * 1000.0;
        for (int i = 0; i < numSamples; i++) {
            this.depthAxisMm[i] = i * mmPerSample;
        }
        this.maxDepthMm = this.depthAxisMm[numSamples - 1];
    }

    public int getNumSamples() {
        return numSamples;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public double getSoundSpeed() {
        return soundSpeed;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public double[] getDepthAxisMm() {
        return Arrays.copyOf(depthAxisMm, depthAxisMm.length);
    }

    public double getDepthForSample(int sampleIndex) {
        if (sampleIndex < 0) return 0.0;
        if (sampleIndex >= numSamples) return maxDepthMm;
        return depthAxisMm[sampleIndex];
    }

    public int getSampleIndexForDepth(double depthMm) {
        if (depthMm <= 0.0) return 0;
        if (depthMm >= maxDepthMm) return numSamples - 1;
        int idx = (int) Math.round((depthMm / maxDepthMm) * (numSamples - 1));
        return Math.clamp(idx, 0, numSamples - 1);
    }

    public double getMaxDepthMm() {
        return maxDepthMm;
    }
}
