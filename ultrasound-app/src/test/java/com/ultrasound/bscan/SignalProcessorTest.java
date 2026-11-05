package com.ultrasound.bscan;

import com.ultrasound.bscan.dsp.DefaultSignalProcessor;
import com.ultrasound.bscan.dsp.SignalProcessor;
import com.ultrasound.bscan.model.PeakEcho;
import com.ultrasound.bscan.model.ScanConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignalProcessorTest {

    private SignalProcessor processor;
    private ScanConfig config;

    @BeforeEach
    void setUp() {
        processor = new DefaultSignalProcessor();
        config = new ScanConfig();
    }

    @Test
    void testProcessSignalSquelchAndContrast() {
        byte[] raw = new byte[] { 10, 35, 55, 100, (byte) 200, (byte) 255 };
        float squelch = 35.0f;
        float contrast = 1.5f;

        float[] processed = processor.process(raw, squelch, contrast);

        // 10 < 35 -> clamped to 0
        assertEquals(0.0f, processed[0]);
        // 35 == 35 -> 0
        assertEquals(0.0f, processed[1]);
        // 55 - 35 = 20 -> 20 * 1.5 = 30.0
        assertEquals(30.0f, processed[2], 1e-4);
        // 100 - 35 = 65 -> 65 * 1.5 = 97.5
        assertEquals(97.5f, processed[3], 1e-4);
        // 255 - 35 = 220 -> 220 * 1.5 = 330 -> clamped to 255
        assertEquals(255.0f, processed[5]);
    }

    @Test
    void testPeakDetectionWithBlanking() {
        float[] signal = new float[1024];

        // Strong ring-down artifact before blanking cutoff
        signal[10] = 250.0f;
        signal[30] = 220.0f;

        // Actual acoustic reflection at sample 300
        signal[300] = 180.0f;

        PeakEcho peak = processor.detectPeak(signal, config, 60, 20.0);

        assertTrue(peak.detected());
        assertEquals(300, peak.sampleIndex());
        assertEquals(180.0f, peak.amplitude(), 1e-4);
        assertEquals(config.getDepthForSample(300), peak.depthMm(), 1e-4);
    }

    @Test
    void testPeakDetectionWithinGate() {
        float[] signal = new float[1024];

        // Large artifact outside gate at 3.0 mm (~sample 20)
        signal[20] = 250.0f;

        // Container bottom reflection outside gate at 35.0 mm (~sample 236)
        signal[236] = 240.0f;

        // True target echo inside gate [5.0, 25.0] mm at 13.5 mm (~sample 91)
        int sample13mm = config.getSampleIndexForDepth(13.5);
        signal[sample13mm] = 185.0f;

        // Gated detection strictly ignores both near ringdown (<5mm) and tank bottom (>25mm)
        PeakEcho peak = processor.detectPeakWithinGate(signal, config, 5.0, 25.0, 20.0);

        assertTrue(peak.detected());
        assertEquals(sample13mm, peak.sampleIndex());
        assertEquals(185.0f, peak.amplitude(), 1e-4);
        assertEquals(config.getDepthForSample(sample13mm), peak.depthMm(), 1e-4);
    }

    @Test
    void testTransmitWaveSentFromTransducerIsIgnored() {
        float[] signal = new float[1024];

        // Transmit wave sent directly from transducer (amplitude saturated at 255)
        for (int i = 0; i < 45; i++) {
            signal[i] = 255.0f - (i * 2.0f);
        }

        // True echo from target reflection at 13.5 mm (sample 91)
        int targetIdx = config.getSampleIndexForDepth(13.5);
        signal[targetIdx - 1] = 120.0f;
        signal[targetIdx]     = 180.0f; // local crest
        signal[targetIdx + 1] = 130.0f;

        // Even if gateMin is set to 0.0 or 5.0, transmit pulse at start MUST be ignored
        PeakEcho peak = processor.detectPeakWithinGate(signal, config, 0.0, 25.0, 20.0);

        assertTrue(peak.detected(), "Target echo at 13.5mm should be detected");
        assertEquals(targetIdx, peak.sampleIndex(), "Should point to 13.5mm echo, NOT the transmit pulse");
        assertEquals(180.0f, peak.amplitude(), 1e-4);
    }

    @Test
    void testPeakDetectionNoneWhenBelowThreshold() {
        float[] signal = new float[1024];
        signal[200] = 15.0f; // below threshold 20.0

        PeakEcho peak = processor.detectPeak(signal, config, 60, 20.0);

        assertFalse(peak.detected());
    }

    @Test
    void testReprocessColumns() {
        int numSamples = 4;
        int activeCols = 2;
        byte[] raw = new byte[] {
                10, 20, 30, 40,   // col 0
                50, 60, 70, 80    // col 1
        };
        float[] out = new float[8];

        processor.reprocessColumns(raw, activeCols, numSamples, activeCols, out, 20.0, 2.0);

        // Col 0: (10-20)*2 -> 0, (20-20)*2 -> 0, (30-20)*2 -> 20, (40-20)*2 -> 40
        assertEquals(0.0f, out[0]);
        assertEquals(0.0f, out[1]);
        assertEquals(20.0f, out[2]);
        assertEquals(40.0f, out[3]);

        // Col 1: (50-20)*2 -> 60, (60-20)*2 -> 80, (70-20)*2 -> 100, (80-20)*2 -> 120
        assertEquals(60.0f, out[4]);
        assertEquals(80.0f, out[5]);
        assertEquals(100.0f, out[6]);
        assertEquals(120.0f, out[7]);
    }
    @Test
    void testSaturatedPlateauCentroidDetection() {
        float[] signal = new float[1024];
        // Flat plateau saturated at 255 from sample 85 to sample 95
        for (int i = 85; i <= 95; i++) {
            signal[i] = 255.0f;
        }

        PeakEcho peak = processor.detectPeakWithinGate(signal, config, 10.0, 20.0, 20.0);
        assertTrue(peak.detected());
        assertEquals(90, peak.sampleIndex(), "Plateau centroid should be exact midpoint between 85 and 95");
        assertEquals(255.0f, peak.amplitude());
    }
}