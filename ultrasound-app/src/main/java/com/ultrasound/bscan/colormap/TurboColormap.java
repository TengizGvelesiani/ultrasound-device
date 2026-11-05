package com.ultrasound.bscan.colormap;

/**
 * Google Turbo high-contrast, perceptually uniform rainbow colormap.
 * Smoothly traverses deep navy -> vibrant cyan -> green -> yellow -> amber -> crimson,
 * providing exceptional visibility, brightness, and topographic distinction for 3D surfaces.
 */
public final class TurboColormap implements Colormap {

    private final int[] lut = new int[256];

    public TurboColormap() {
        for (int i = 0; i < 256; i++) {
            double x = i / 255.0;

            // Google Turbo 5th-order polynomial fit
            double r = 0.13572138 + 4.61539260 * x - 42.66032258 * x * x + 132.13108234 * Math.pow(x, 3)
                    - 152.94239396 * Math.pow(x, 4) + 59.28637943 * Math.pow(x, 5);
            double g = 0.09140261 + 2.19418839 * x + 4.84296658 * x * x - 14.18503333 * Math.pow(x, 3)
                    + 4.27729857 * Math.pow(x, 4) + 2.82956604 * Math.pow(x, 5);
            double b = 0.10667330 + 12.58787822 * x - 60.14780272 * x * x + 109.82103508 * Math.pow(x, 3)
                    - 89.92720448 * Math.pow(x, 4) + 27.46650424 * Math.pow(x, 5);

            int ir = (int) Math.clamp(Math.round(r * 255.0), 0, 255);
            int ig = (int) Math.clamp(Math.round(g * 255.0), 0, 255);
            int ib = (int) Math.clamp(Math.round(b * 255.0), 0, 255);

            lut[i] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
        }
    }

    @Override
    public String getName() {
        return "Turbo (High Contrast)";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.TURBO;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
