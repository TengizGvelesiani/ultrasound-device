package com.ultrasound.bscan.dsp;

import com.ultrasound.bscan.model.PeakEcho;
import com.ultrasound.bscan.model.ScanConfig;

/**
 * Contract for digital signal processing of ultrasound echo vectors.
 */
public interface SignalProcessor {

    /**
     * Processes a single raw byte array into a float array of amplitudes [0..255]
     * using the given noise squelch threshold and contrast multiplier.
     */
    void process(byte[] rawInput, float[] outputSignal, double squelch, double contrast);

    /**
     * Convenience method returning a newly allocated processed float array.
     */
    default float[] process(byte[] rawInput, double squelch, double contrast) {
        float[] out = new float[rawInput.length];
        process(rawInput, out, squelch, contrast);
        return out;
    }

    /**
     * Detects the dominant acoustic reflection peak in the signal, ignoring
     * near-field ring-down before blankingSamples.
     */
    PeakEcho detectPeak(float[] signal, ScanConfig config, int blankingSamples, double threshold);

    /**
     * Detects the dominant acoustic reflection peak strictly within the configured depth gate [gateMinMm, gateMaxMm].
     * Ignores ring-down before gateMinMm and container bottom/reverberations beyond gateMaxMm.
     */
    PeakEcho detectPeakWithinGate(float[] signal, ScanConfig config, double gateMinMm, double gateMaxMm, double threshold);

    /**
     * Batch-reprocesses an entire 2D sweep buffer from raw byte storage into
     * float display storage (e.g. when sliders are moved while paused).
     */
    void reprocessColumns(byte[] rawData, int activeCols, int numSamples, int totalAllocatedCols,
                          float[] processedData, double squelch, double contrast);
}
