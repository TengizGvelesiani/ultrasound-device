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
}
