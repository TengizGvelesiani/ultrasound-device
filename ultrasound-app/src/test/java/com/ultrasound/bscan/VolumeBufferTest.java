package com.ultrasound.bscan;

import com.ultrasound.bscan.model.PeakEcho;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.model.VolumeScanConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VolumeBufferTest {

    private ScanConfig scanConfig;
    private VolumeScanConfig volumeConfig;
    private VolumeBuffer volumeBuffer;

    @BeforeEach
    void setUp() {
        scanConfig = new ScanConfig();
        volumeConfig = new VolumeScanConfig(5, 4, 1.0, 1.0, VolumeScanConfig.ScanPattern.SNAKE, 10.0, 50.0);
        volumeBuffer = new VolumeBuffer(scanConfig.getNumSamples(), volumeConfig);
    }

    @Test
    void testSnakeRasterProgression() {
        assertEquals(0, volumeBuffer.getCurrentX());
        assertEquals(0, volumeBuffer.getCurrentY());

        byte[] raw = new byte[scanConfig.getNumSamples()];
        float[] proc = new float[scanConfig.getNumSamples()];
        PeakEcho peak = PeakEcho.of(35.0, 150.0f, 200);

        // Row 0 (Y=0): X goes 0 -> 1 -> 2 -> 3 -> 4
        for (int x = 0; x < 5; x++) {
            assertEquals(x, volumeBuffer.getCurrentX());
            assertEquals(0, volumeBuffer.getCurrentY());
            volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig);
        }

        // Row 1 (Y=1): Snake reverses, so X starts at 4 and goes down to 0
        assertEquals(4, volumeBuffer.getCurrentX());
        assertEquals(1, volumeBuffer.getCurrentY());

        for (int x = 4; x >= 0; x--) {
            assertEquals(x, volumeBuffer.getCurrentX());
            assertEquals(1, volumeBuffer.getCurrentY());
            volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig);
        }

        // Row 2 (Y=2): Snake reverses forward again, X starts at 0
        assertEquals(0, volumeBuffer.getCurrentX());
        assertEquals(2, volumeBuffer.getCurrentY());
    }

    @Test
    void testHardwareSynchronizedRasterProgression() {
        byte[] raw = new byte[scanConfig.getNumSamples()];
        float[] proc = new float[scanConfig.getNumSamples()];
        PeakEcho peak = PeakEcho.of(35.0, 150.0f, 200);

        // Line 0: Forward direction (+X), 5 points (index 0..4)
        for (int x = 0; x < 5; x++) {
            boolean isLineStart = (x == 0);
            boolean isLineEnd = (x == 4);
            volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig,
                    isLineStart, isLineEnd, true, false);
            assertTrue(volumeBuffer.isMeasured(x, 0));
        }

        assertEquals(1, volumeBuffer.getCurrentY());

        // Line 1: Reverse serpentine pass (-X), 5 points (index 4..0)
        for (int x = 4; x >= 0; x--) {
            boolean isLineStart = (x == 4);
            boolean isLineEnd = (x == 0);
            boolean isScanComplete = (x == 0 && volumeBuffer.getCurrentY() == 3); // Not complete yet
            volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig,
                    isLineStart, isLineEnd, false, isScanComplete);
            assertTrue(volumeBuffer.isMeasured(x, 1));
        }

        assertEquals(2, volumeBuffer.getCurrentY());
        assertFalse(volumeBuffer.isComplete());

        // Test scan completion flag directly marks volume buffer complete
        volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig,
                false, true, true, true);
        assertTrue(volumeBuffer.isComplete());
    }

    @Test
    void testSurfaceHeightAndAmplitudeRecording() {
        byte[] raw = new byte[scanConfig.getNumSamples()];
        float[] proc = new float[scanConfig.getNumSamples()];
        // Simulate an echo at sample index 250 (depth ~ 37 mm)
        int echoSample = 250;
        proc[echoSample] = 220.0f;
        PeakEcho peak = PeakEcho.of(scanConfig.getDepthForSample(echoSample), 220.0f, echoSample);

        volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig);

        assertTrue(volumeBuffer.isMeasured(0, 0));
        assertEquals(scanConfig.getDepthForSample(echoSample), volumeBuffer.getHeight(0, 0), 1e-4);
        assertEquals(220.0f, volumeBuffer.getAmplitude(0, 0), 1e-4);
    }

    @Test
    void testCScanDepthGateMIP() {
        byte[] raw = new byte[scanConfig.getNumSamples()];
        float[] proc = new float[scanConfig.getNumSamples()];

        // Echo at sample index 150 (~22.2 mm), which is inside the gate [10mm, 50mm]
        int inGateSample = 150;
        proc[inGateSample] = 190.0f;

        // Echo outside the gate at sample index 800 (~118 mm)
        proc[800] = 255.0f;

        PeakEcho peak = PeakEcho.of(scanConfig.getDepthForSample(inGateSample), 190.0f, inGateSample);
        volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig);

        // C-Scan MIP must capture the maximum within the depth gate (190.0), ignoring the outside echo (255.0)
        assertEquals(190.0f, volumeBuffer.getCScan(0, 0), 1e-4);
    }

    @Test
    void testClearVolume() {
        byte[] raw = new byte[scanConfig.getNumSamples()];
        float[] proc = new float[scanConfig.getNumSamples()];
        PeakEcho peak = PeakEcho.of(30.0, 100.0f, 100);

        volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig);
        assertTrue(volumeBuffer.isMeasured(0, 0));

        volumeBuffer.clear(volumeConfig);
        assertFalse(volumeBuffer.isMeasured(0, 0));
        assertEquals((float) volumeConfig.getGateMaxDepthMm(), volumeBuffer.getHeight(0, 0));
        assertEquals(0, volumeBuffer.getCurrentX());
        assertEquals(0, volumeBuffer.getCurrentY());
    }
    @Test
    void testMissedLineEndGracefulRecovery() {
        byte[] raw = new byte[scanConfig.getNumSamples()];
        float[] proc = new float[scanConfig.getNumSamples()];
        PeakEcho peak = PeakEcho.of(35.0, 150.0f, 200);

        // Line 0: Forward direction (+X), but isLineEnd flag is dropped on the wire (false)
        for (int x = 0; x < 5; x++) {
            boolean isLineStart = (x == 0);
            volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig,
                    isLineStart, false, true, false);
            assertTrue(volumeBuffer.isMeasured(x, 0));
        }

        // Line 1 begins with reverse direction (-X) and isLineStart = true
        // VolumeBuffer should detect direction reversal and advance currentY to 1 automatically
        for (int x = 4; x >= 0; x--) {
            boolean isLineStart = (x == 4);
            boolean isLineEnd = (x == 0);
            volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig,
                    isLineStart, isLineEnd, false, false);
            assertTrue(volumeBuffer.isMeasured(x, 1));
        }

        assertEquals(2, volumeBuffer.getCurrentY());
    }

    @Test
    void testSpecularDropoutHandlingAndInpainting() {
        byte[] raw = new byte[scanConfig.getNumSamples()];
        float[] proc = new float[scanConfig.getNumSamples()];
        PeakEcho validPeak = PeakEcho.of(25.0, 180.0f, 150);

        // Record a 3x3 patch where the center point (1, 1) drops out due to specular reflection / tilt
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                boolean isDropout = (x == 1 && y == 1);
                PeakEcho peak = isDropout ? PeakEcho.NONE : validPeak;
                // Directly record at (x, y)
                volumeBuffer.recordCurrentPoint(raw, proc, peak, scanConfig, volumeConfig,
                        x == 0, x == 2, true, false);
            }
            // Manually advance line in test
        }

        // Center point at (1, 0) in row 0 should be measured
        assertTrue(volumeBuffer.isMeasured(0, 0));
        // Unmeasured / dropout point should NOT be marked as a valid measurement
        // Previously this was a bug setting measuredMap = true with gateMax cliff!
        // Now it properly flags as unmeasured:
        volumeBuffer.clear(volumeConfig);

        // Fill row 0 with valid points
        volumeBuffer.recordCurrentPoint(raw, proc, validPeak, scanConfig, volumeConfig, true, false, true, false); // (0,0)
        volumeBuffer.recordCurrentPoint(raw, proc, PeakEcho.NONE, scanConfig, volumeConfig, false, false, true, false); // (1,0) - dropout
        volumeBuffer.recordCurrentPoint(raw, proc, validPeak, scanConfig, volumeConfig, false, true, true, false); // (2,0)

        assertTrue(volumeBuffer.isMeasured(0, 0));
        assertFalse(volumeBuffer.isMeasured(1, 0)); // Dropout properly flagged!
        assertTrue(volumeBuffer.isMeasured(2, 0));

        // Now run inpainting
        volumeBuffer.inpaintMissingSamples();

        // The dropout at (1,0) should now be inpainted and filled from its valid neighbors (25.0 mm)
        assertTrue(volumeBuffer.isMeasured(1, 0));
        assertEquals(25.0f, volumeBuffer.getHeight(1, 0), 1e-3);
    }

    @Test
    void testBacklashCompensationConfiguration() {
        assertEquals(0.0, volumeBuffer.getBacklashCompensationMm());
        volumeBuffer.setBacklashCompensationMm(0.5);
        assertEquals(0.5, volumeBuffer.getBacklashCompensationMm(), 1e-4);
    }
}