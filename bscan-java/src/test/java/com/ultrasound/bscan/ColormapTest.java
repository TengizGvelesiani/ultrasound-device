package com.ultrasound.bscan;

import com.ultrasound.bscan.colormap.*;
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
}
