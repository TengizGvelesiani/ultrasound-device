package com.ultrasound.bscan.colormap;

public final class BoneColormap implements Colormap {

    private final int[] lut = new int[256];

    public BoneColormap() {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;

            // Hot components
            double hr = Math.clamp(v / 0.375, 0.0, 1.0);
            double hg = Math.clamp((v - 0.375) / 0.375, 0.0, 1.0);
            double hb = Math.clamp((v - 0.75) / 0.25, 0.0, 1.0);

            // Bone = 7/8 * gray + 1/8 * hot, with a touch of ultrasound cool blue
            double r = Math.clamp((7.0 * v + hr) / 8.0, 0.0, 1.0);
            double g = Math.clamp((7.0 * v + hg) / 8.0, 0.0, 1.0);
            double b = Math.clamp((7.0 * v + hb) / 8.0, 0.0, 1.0);

            // Add subtle medical contrast boost in blue channel for low-mid echos
            if (v > 0.0 && v < 0.75) {
                b = Math.min(1.0, b * 1.15 + 0.02);
            }

            int ir = (int) Math.round(r * 255);
            int ig = (int) Math.round(g * 255);
            int ib = (int) Math.round(b * 255);

            lut[i] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
        }
    }

    @Override
    public String getName() {
        return "Bone (Medical)";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.BONE;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
