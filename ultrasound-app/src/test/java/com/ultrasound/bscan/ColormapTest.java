package com.ultrasound.bscan;

import com.ultrasound.bscan.colormap.*;
import com.ultrasound.bscan.model.ScannerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColormapTest {

    @Test
    void testColormapsHaveValidLut() {
        ColormapRegistry registry = new ColormapRegistry();

        for (ColormapType type : ColormapType.values()) {
            Colormap cm = registry.get(type);
            assertNotNull(cm);
            assertEquals(type, cm.getType());

            int[] lut = cm.getLut();
            assertEquals(256, lut.length);

            for (int i = 0; i < 256; i++) {
                int argb = lut[i];
                // Alpha must be 0xFF
                int alpha = (argb >>> 24) & 0xFF;
                assertEquals(0xFF, alpha, "Alpha channel should be 255");
            }
        }
    }

    @Test
    void testGrayscaleMapping() {
        GrayColormap gray = new GrayColormap();
        int c0 = gray.getColor(0);
        int c255 = gray.getColor(255);

        assertEquals(0xFF000000, c0);
        assertEquals(0xFFFFFFFF, c255);
    }

    @Test
    void testJetMapping() {
        JetColormap jet = new JetColormap();
        int[] lut = jet.getLut();
        // Blue at 0, Red at 255
        int blueAtZero = lut[0] & 0xFF;
        int redAt255 = (lut[255] >> 16) & 0xFF;
        assertTrue(blueAtZero > 100);
        assertTrue(redAt255 > 100);
    }

    @Test
    void testScannerStateColormapCustomization() {
        ScannerState state = new ScannerState();
        assertFalse(state.isColormapInverted());
        state.toggleColormapInverted();
        assertTrue(state.isColormapInverted());

        // Default gate is 85.0 - 130.0 mm (matched to chamber target & floor)
        assertTrue(state.isColorRangeLockToGate());
        assertEquals(85.0, state.getColorMinDepthMm(), 1e-3);
        assertEquals(130.0, state.getColorMaxDepthMm(), 1e-3);

        // Decoupled custom range
        state.setColorRangeLockToGate(false);
        state.setColorMinDepthMm(12.5);
        state.setColorMaxDepthMm(14.5);
        assertEquals(12.5, state.getColorMinDepthMm(), 1e-3);
        assertEquals(14.5, state.getColorMaxDepthMm(), 1e-3);

        // Gamma tuning
        state.setColormapGamma(1.5);
        assertEquals(1.5, state.getColormapGamma(), 1e-3);
    }
}
