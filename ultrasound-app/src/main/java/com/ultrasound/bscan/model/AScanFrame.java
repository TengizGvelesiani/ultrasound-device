package com.ultrasound.bscan.model;

import java.util.Arrays;

/**
 * Encapsulates a single instantaneous A-scan ultrasound reflection frame.
 */
public final class AScanFrame {

    private final long timestampNs;
    private final byte[] rawData;
    private final float[] signal;
    private final PeakEcho peakEcho;

    public AScanFrame(long timestampNs, byte[] rawData, float[] signal, PeakEcho peakEcho) {
        this.timestampNs = timestampNs;
        this.rawData = Arrays.copyOf(rawData, rawData.length);
        this.signal = Arrays.copyOf(signal, signal.length);
        this.peakEcho = peakEcho != null ? peakEcho : PeakEcho.NONE;
    }

    public long getTimestampNs() {
        return timestampNs;
    }

    public byte[] getRawData() {
        return rawData;
    }

    public float[] getSignal() {
        return signal;
    }

    public PeakEcho getPeakEcho() {
        return peakEcho;
    }

    public int getLength() {
        return signal.length;
    }
}
