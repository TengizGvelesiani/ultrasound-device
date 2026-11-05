package com.ultrasound.bscan.colormap;

/**
 * Cool-Warm diverging colormap (Cool Blue -> Light Gray -> Warm Coral Red).
 * Highlights deviations from a central baseline depth.
 */
public final class CoolWarmColormap implements Colormap {

    private static final double[][] KNOTS = {
            {0.00,  59,  76, 192},
            {0.25, 122, 157, 248},
            {0.50, 221, 221, 221},
            {0.75, 244, 154, 123},
            {1.00, 180,   4,  38}
    };

    private final int[] lut = new int[256];

    public CoolWarmColormap() {
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
        return "Cool-Warm (Diverging)";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.COOL_WARM;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
