package com.ultrasound.bscan.colormap;

public final class GrayColormap implements Colormap {

    private final int[] lut = new int[256];

    public GrayColormap() {
        for (int i = 0; i < 256; i++) {
            lut[i] = (0xFF << 24) | (i << 16) | (i << 8) | i;
        }
    }

    @Override
    public String getName() {
        return "Grayscale";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.GRAY;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
