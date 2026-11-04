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

    @Override
    public PeakEcho detectPeak(float[] signal, ScanConfig config, int blankingSamples, double threshold) {
        if (signal == null || signal.length == 0) {
            return PeakEcho.NONE;
        }

        int start = Math.clamp(blankingSamples, 0, signal.length - 1);
        float maxVal = -1.0f;
        int maxIdx = -1;

        for (int i = start; i < signal.length; i++) {
            float v = signal[i];
            if (v > maxVal) {
                maxVal = v;
                maxIdx = i;
            }
        }

        if (maxIdx >= 0 && maxVal > threshold) {
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
