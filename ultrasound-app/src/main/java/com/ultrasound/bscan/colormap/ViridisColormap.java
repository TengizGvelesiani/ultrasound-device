package com.ultrasound.bscan.colormap;

/**
 * Viridis perceptually uniform colormap (Deep Violet -> Teal -> Lime -> Yellow).
 * Uniform lightness gradient ensures smooth, artifact-free depth reading.
 */
public final class ViridisColormap implements Colormap {

    private static final double[][] KNOTS = {
            {0.00,  68,   1,  84},
            {0.25,  59,  82, 139},
            {0.50,  33, 145, 140},
            {0.75,  94, 201,  98},
            {1.00, 253, 231,  37}
    };

    private final int[] lut = new int[256];

    public ViridisColormap() {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            int r = 0, g = 0, b = 0;

            for (int k = 0; k < KNOTS.length - 1; k++) {
                double x0 = KNOTS[k][0];
                double x1 = KNOTS[k + 1][0];
                if (v <= x1 || k == KNOTS.length - 2) {
                    double t = (v - x0) / (x1 - x0);
                    t = Math.clamp(t, 0.0, 1.0);
                    r = (int) Math.round(KNOTS[k][1] + t * (KNOTS[k + 1][1] - KNOTS[k][1]));
                    g = (int) Math.round(KNOTS[k][2] + t * (KNOTS[k + 1][2] - KNOTS[k][2]));
                    b = (int) Math.round(KNOTS[k][3] + t * (KNOTS[k + 1][3] - KNOTS[k][3]));
                    break;
                }
            }

            lut[i] = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        }
    }

    @Override
    public String getName() {
        return "Viridis (Perceptual)";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.VIRIDIS;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
