package com.ultrasound.bscan;

import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScanBufferTest {

    @Test
    void testSweepBufferDynamicExpansion() {
        int numSamples = 4;
        ScanBuffer buffer = new ScanBuffer(numSamples, 2, 2);

        assertEquals(2, buffer.getSweepCapacity());
        assertEquals(0, buffer.getSweepIndex());

        byte[] raw = new byte[] { 1, 2, 3, 4 };
        float[] proc = new float[] { 10f, 20f, 30f, 40f };

        // Add 2 lines
        buffer.addSweepLine(raw, proc);
        buffer.addSweepLine(raw, proc);
        assertEquals(2, buffer.getSweepIndex());
        assertEquals(2, buffer.getSweepCapacity());

        // Adding 3rd line must trigger dynamic growth
        buffer.addSweepLine(raw, proc);
        assertEquals(3, buffer.getSweepIndex());
        assertTrue(buffer.getSweepCapacity() > 2);

        // Verify sample values
        assertEquals(20f, buffer.getSample(ScanMode.SWEEP, 1, 1));
        assertEquals(40f, buffer.getSample(ScanMode.SWEEP, 2, 3));
    }

    @Test
    void testWaterfallScrolling() {
        int numSamples = 2;
        int waterfallWidth = 3;
        ScanBuffer buffer = new ScanBuffer(numSamples, 10, waterfallWidth);

        float[] line1 = new float[] { 100f, 101f };
        float[] line2 = new float[] { 200f, 201f };
        float[] line3 = new float[] { 300f, 301f };
        float[] line4 = new float[] { 400f, 401f };

        buffer.addWaterfallLine(line1);
        buffer.addWaterfallLine(line2);
        buffer.addWaterfallLine(line3);

        // Rightmost (col 2) is line3, col 1 is line2, col 0 is line1
        assertEquals(100f, buffer.getSample(ScanMode.WATERFALL, 0, 0));
        assertEquals(200f, buffer.getSample(ScanMode.WATERFALL, 1, 0));
        assertEquals(300f, buffer.getSample(ScanMode.WATERFALL, 2, 0));

        // Adding line4 scrolls everything left: col 0 becomes line2, col 1 becomes line3, col 2 is line4
        buffer.addWaterfallLine(line4);
        assertEquals(200f, buffer.getSample(ScanMode.WATERFALL, 0, 0));
        assertEquals(300f, buffer.getSample(ScanMode.WATERFALL, 1, 0));
        assertEquals(400f, buffer.getSample(ScanMode.WATERFALL, 2, 0));
    }

    @Test
    void testClearSweep() {
        ScanBuffer buffer = new ScanBuffer(2, 5, 5);
        buffer.addSweepLine(new byte[]{1, 2}, new float[]{10f, 20f});
        assertEquals(1, buffer.getSweepIndex());

        buffer.clearSweep();
        assertEquals(0, buffer.getSweepIndex());
        assertEquals(0f, buffer.getSample(ScanMode.SWEEP, 0, 0));
    }
}
