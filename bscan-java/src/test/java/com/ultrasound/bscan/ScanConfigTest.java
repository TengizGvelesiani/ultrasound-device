package com.ultrasound.bscan;

import com.ultrasound.bscan.model.ScanConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScanConfigTest {

    @Test
    void testDepthCalculations() {
        ScanConfig config = new ScanConfig();

        assertEquals(1024, config.getNumSamples());
        assertEquals(5_000_000.0, config.getSampleRate());
        assertEquals(1480.0, config.getSoundSpeed());

        // Sample 0 should be 0 mm
        assertEquals(0.0, config.getDepthForSample(0), 1e-4);

        // Expected max depth: (1023 / 5e6) * (1480 / 2) * 1000 = 151.404 mm
        double expectedMaxDepth = (1023.0 / 5_000_000.0) * (1480.0 / 2.0) * 1000.0;
        assertEquals(expectedMaxDepth, config.getMaxDepthMm(), 1e-3);

        // Test depth to sample index roundtrip
        int sampleIdx = 500;
        double depthMm = config.getDepthForSample(sampleIdx);
        int reconstructedIdx = config.getSampleIndexForDepth(depthMm);
        assertEquals(sampleIdx, reconstructedIdx);
    }
}
