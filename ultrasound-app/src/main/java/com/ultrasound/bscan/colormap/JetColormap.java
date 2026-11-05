package com.ultrasound.bscan.colormap;

/**
 * Classic Jet rainbow colormap (Blue -> Cyan -> Yellow -> Orange -> Red).
 * Provides distinct, high-contrast color bands across elevation depths.
 */
public final class JetColormap implements Colormap {

    private final int[] lut = new int[256];

    public JetColormap() {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;

            double r = Math.clamp(1.5 - Math.abs(4.0 * v - 3.0), 0.0, 1.0);
            double g = Math.clamp(1.5 - Math.abs(4.0 * v - 2.0), 0.0, 1.0);
            double b = Math.clamp(1.5 - Math.abs(4.0 * v - 1.0), 0.0, 1.0);

            int ir = (int) Math.round(r * 255.0);
            int ig = (int) Math.round(g * 255.0);
            int ib = (int) Math.round(b * 255.0);

            lut[i] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
        }
    }

    @Override
    public String getName() {
        return "Jet (Rainbow)";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.JET;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
