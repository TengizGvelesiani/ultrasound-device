package com.ultrasound.bscan.colormap;

public final class HotColormap implements Colormap {

    private final int[] lut = new int[256];

    public HotColormap() {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            // Hot formula:
            // R: 0 to 1 over [0, 3/8], then 1.0
            // G: 0 over [0, 3/8], 0 to 1 over [3/8, 3/4], then 1.0
            // B: 0 over [0, 3/4], 0 to 1 over [3/4, 1.0]
            double r = Math.clamp(v / 0.375, 0.0, 1.0);
            double g = Math.clamp((v - 0.375) / 0.375, 0.0, 1.0);
            double b = Math.clamp((v - 0.75) / 0.25, 0.0, 1.0);

            int ir = (int) Math.round(r * 255);
            int ig = (int) Math.round(g * 255);
            int ib = (int) Math.round(b * 255);

            lut[i] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
        }
    }

    @Override
    public String getName() {
        return "Hot";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.HOT;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
