package com.ultrasound.bscan.model;

import com.ultrasound.bscan.dsp.SignalProcessor;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * High-performance dual-buffer container for Sweep Pass and Waterfall 2D data.
 * Internally organizes data in column-major order (column * numSamples + sampleIndex)
 * to allow nanosecond block copies of incoming 1024-byte A-scan lines.
 */
public final class ScanBuffer {

    public static final int DEFAULT_INITIAL_SWEEP_WIDTH = 1000;
    public static final int DEFAULT_WATERFALL_WIDTH = 800;
    public static final int SWEEP_GROWTH_INCREMENT = 500;

    private final int numSamples;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    // Sweep Pass Data
    private int sweepCapacity;
    private int sweepIndex; // Number of columns recorded so far
    private byte[] rawSweepBuffer;
    private float[] processedSweepBuffer;

    // Waterfall Data
    private final int waterfallWidth;
    private final float[] waterfallBuffer;

    public ScanBuffer(int numSamples) {
        this(numSamples, DEFAULT_INITIAL_SWEEP_WIDTH, DEFAULT_WATERFALL_WIDTH);
    }

    public ScanBuffer(int numSamples, int initialSweepCapacity, int waterfallWidth) {
        this.numSamples = numSamples;
        this.sweepCapacity = Math.max(initialSweepCapacity, 1);
        this.sweepIndex = 0;
        this.rawSweepBuffer = new byte[sweepCapacity * numSamples];
        this.processedSweepBuffer = new float[sweepCapacity * numSamples];

        this.waterfallWidth = Math.max(waterfallWidth, 1);
        this.waterfallBuffer = new float[this.waterfallWidth * numSamples];
    }

    public int getNumSamples() {
        return numSamples;
    }

    // -------------------------------------------------------------
    // SWEEP OPERATIONS
    // -------------------------------------------------------------

    public void addSweepLine(byte[] raw, float[] processed) {
        rwLock.writeLock().lock();
        try {
            if (sweepIndex >= sweepCapacity) {
                growSweepCapacity(sweepCapacity + SWEEP_GROWTH_INCREMENT);
            }

            int offset = sweepIndex * numSamples;
            System.arraycopy(raw, 0, rawSweepBuffer, offset, numSamples);
            System.arraycopy(processed, 0, processedSweepBuffer, offset, numSamples);
            sweepIndex++;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void clearSweep() {
        rwLock.writeLock().lock();
        try {
            sweepIndex = 0;
            Arrays.fill(rawSweepBuffer, (byte) 0);
            Arrays.fill(processedSweepBuffer, 0.0f);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void reprocessSweep(SignalProcessor processor, double squelch, double contrast) {
        rwLock.writeLock().lock();
        try {
            if (sweepIndex > 0) {
                processor.reprocessColumns(rawSweepBuffer, sweepIndex, numSamples, sweepCapacity,
                        processedSweepBuffer, squelch, contrast);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void growSweepCapacity(int minCapacity) {
        int newCap = Math.max(sweepCapacity + SWEEP_GROWTH_INCREMENT, minCapacity);
        byte[] newRaw = new byte[newCap * numSamples];
        float[] newProc = new float[newCap * numSamples];

        System.arraycopy(rawSweepBuffer, 0, newRaw, 0, sweepIndex * numSamples);
        System.arraycopy(processedSweepBuffer, 0, newProc, 0, sweepIndex * numSamples);

        rawSweepBuffer = newRaw;
        processedSweepBuffer = newProc;
        sweepCapacity = newCap;
    }

    public int getSweepCapacity() {
        rwLock.readLock().lock();
        try {
            return sweepCapacity;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getSweepIndex() {
        rwLock.readLock().lock();
        try {
            return sweepIndex;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // -------------------------------------------------------------
    // WATERFALL OPERATIONS
    // -------------------------------------------------------------

    public void addWaterfallLine(float[] processed) {
        rwLock.writeLock().lock();
        try {
            // Shift left by 1 column: columns 1..N-1 move to 0..N-2
            int copyBytes = (waterfallWidth - 1) * numSamples;
            System.arraycopy(waterfallBuffer, numSamples, waterfallBuffer, 0, copyBytes);
            // Insert newest column at the rightmost position: (waterfallWidth - 1)
            int targetOffset = (waterfallWidth - 1) * numSamples;
            System.arraycopy(processed, 0, waterfallBuffer, targetOffset, numSamples);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void clearWaterfall() {
        rwLock.writeLock().lock();
        try {
            Arrays.fill(waterfallBuffer, 0.0f);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public int getWaterfallWidth() {
        return waterfallWidth;
    }

    // -------------------------------------------------------------
    // SNAPSHOT / RENDER ACCESS
    // -------------------------------------------------------------

    /**
     * Reads a sample value safely at column x, depth sample y.
     */
    public float getSample(ScanMode mode, int col, int sampleIdx) {
        rwLock.readLock().lock();
        try {
            if (sampleIdx < 0 || sampleIdx >= numSamples) return 0.0f;

            if (mode == ScanMode.SWEEP) {
                if (col < 0 || col >= sweepCapacity) return 0.0f;
                return processedSweepBuffer[col * numSamples + sampleIdx];
            } else {
                if (col < 0 || col >= waterfallWidth) return 0.0f;
                return waterfallBuffer[col * numSamples + sampleIdx];
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public ReentrantReadWriteLock getLock() {
        return rwLock;
    }

    public float[] getProcessedSweepBufferUnsafe() {
        return processedSweepBuffer;
    }

    public byte[] getRawSweepBufferUnsafe() {
        return rawSweepBuffer;
    }

    public float[] getWaterfallBufferUnsafe() {
        return waterfallBuffer;
    }
}
