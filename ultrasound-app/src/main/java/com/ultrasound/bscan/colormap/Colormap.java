package com.ultrasound.bscan.colormap;

/**
 * Strategy interface for converting 8-bit echo intensity (0-255)
 * into a packed 32-bit ARGB color (0xAARRGGBB).
 */
public interface Colormap {

    /**
     * Name of the colormap.
     */
    String getName();

    /**
     * Type identifier.
     */
    ColormapType getType();

    /**
     * Precomputed 256-entry Look-Up Table of 32-bit ARGB values.
     */
    int[] getLut();

    /**
     * Maps an 8-bit intensity value (0-255) to a packed ARGB integer.
     */
    default int getColor(int intensity) {
        int idx = Math.clamp(intensity, 0, 255);
        return getLut()[idx];
    }
}
