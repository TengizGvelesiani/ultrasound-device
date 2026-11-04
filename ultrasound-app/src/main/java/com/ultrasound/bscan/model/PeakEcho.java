package com.ultrasound.bscan.model;

/**
 * Represents the strongest acoustic echo detected in an A-scan frame,
 * after filtering out the near-field transducer ring-down.
 */
public record PeakEcho(boolean detected, double depthMm, float amplitude, int sampleIndex) {

    public static final PeakEcho NONE = new PeakEcho(false, 0.0, 0.0f, -1);

    public static PeakEcho of(double depthMm, float amplitude, int sampleIndex) {
        return new PeakEcho(true, depthMm, amplitude, sampleIndex);
    }
}
