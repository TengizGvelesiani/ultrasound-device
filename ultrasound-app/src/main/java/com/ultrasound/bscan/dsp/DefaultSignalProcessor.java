package com.ultrasound.bscan.dsp;

import com.ultrasound.bscan.model.PeakEcho;
import com.ultrasound.bscan.model.ScanConfig;

public final class DefaultSignalProcessor implements SignalProcessor {

    @Override
    public void process(byte[] rawInput, float[] outputSignal, double squelch, double contrast) {
        int len = Math.min(rawInput.length, outputSignal.length);
        float sq = (float) squelch;
        float ct = (float) contrast;

        for (int i = 0; i < len; i++) {
            int rawVal = rawInput[i] & 0xFF;
            float val = (rawVal - sq) * ct;
            outputSignal[i] = Math.clamp(val, 0.0f, 255.0f);
        }
    }

    public static final double MIN_TRANSMIT_BLANKING_MM = 8.5; // Strictly blanks excitation pulse & initial ringdown
    public static final int MIN_BLANKING_SAMPLES = 60;          // ~8.9 mm @ 5 MSPS in water

    @Override
    public PeakEcho detectPeak(float[] signal, ScanConfig config, int blankingSamples, double threshold) {
        if (signal == null || signal.length == 0) {
            return PeakEcho.NONE;
        }

        // Never search inside the high-voltage transmit burst (minimum 60 samples / ~8.9 mm)
        int minBlank = Math.max(blankingSamples, MIN_BLANKING_SAMPLES);
        int start = Math.clamp(minBlank, 0, signal.length - 1);
        float maxVal = -1.0f;
        int plateauStart = -1;
        int plateauEnd = -1;

        for (int i = start; i < signal.length; i++) {
            float v = signal[i];
            if (v > maxVal) {
                maxVal = v;
                plateauStart = i;
                plateauEnd = i;
            } else if (maxVal > threshold && Math.abs(v - maxVal) < 1.0f && plateauStart >= 0) {
                plateauEnd = i;
            }
        }

        if (plateauStart >= 0 && maxVal > threshold) {
            int maxIdx = (plateauStart + plateauEnd) / 2;
            double depthMm = config.getDepthForSample(maxIdx);
            return PeakEcho.of(depthMm, maxVal, maxIdx);
        }

        return PeakEcho.NONE;
    }

    @Override
    public PeakEcho detectPeakWithinGate(float[] signal, ScanConfig config, double gateMinMm, double gateMaxMm, double threshold) {
        if (signal == null || signal.length == 0) {
            return PeakEcho.NONE;
        }

        double minMm = Math.min(gateMinMm, gateMaxMm);
        double maxMm = Math.max(gateMinMm, gateMaxMm);

        // Never search inside the excitation pulse / main bang wave sent from transducer (minimum 8.5 mm)
        double effectiveMinMm = Math.max(minMm, MIN_TRANSMIT_BLANKING_MM);
        if (effectiveMinMm >= maxMm) {
            effectiveMinMm = minMm; // Fallback if user intentionally gate-frames the near boundary
        }

        int startIdx = Math.clamp(config.getSampleIndexForDepth(effectiveMinMm), 0, signal.length - 1);
        int endIdx = Math.clamp(config.getSampleIndexForDepth(maxMm), startIdx, signal.length - 1);

        float maxVal = -1.0f;
        int plateauStart = -1;
        int plateauEnd = -1;

        for (int i = startIdx; i <= endIdx; i++) {
            float v = signal[i];
            if (v > maxVal && v > threshold) {
                // Reject monotonically decaying edges entering the gate (e.g. ringdown tail artifacts)
                boolean isDecayingEdge = (i == startIdx && i + 1 < signal.length && signal[i] > signal[i + 1] && i > 0 && signal[i - 1] >= signal[i]);
                if (!isDecayingEdge) {
                    maxVal = v;
                    plateauStart = i;
                    plateauEnd = i;
                }
            } else if (maxVal > threshold && Math.abs(v - maxVal) < 1.0f && plateauStart >= 0) {
                plateauEnd = i;
            }
        }

        if (plateauStart >= 0 && maxVal > threshold) {
            int maxIdx = (plateauStart + plateauEnd) / 2;
            double depthMm = config.getDepthForSample(maxIdx);
            return PeakEcho.of(depthMm, maxVal, maxIdx);
        }

        return PeakEcho.NONE;
    }

    @Override
    public void reprocessColumns(byte[] rawData, int activeCols, int numSamples, int totalAllocatedCols,
                                float[] processedData, double squelch, double contrast) {
        float sq = (float) squelch;
        float ct = (float) contrast;
        int totalElements = activeCols * numSamples;

        for (int i = 0; i < totalElements; i++) {
            int rawVal = rawData[i] & 0xFF;
            float val = (rawVal - sq) * ct;
            processedData[i] = Math.clamp(val, 0.0f, 255.0f);
        }
    }
}
