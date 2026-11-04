package com.ultrasound.bscan.colormap;

public final class InfernoColormap implements Colormap {

    private final int[] lut = new int[256];

    public InfernoColormap() {
        // Control points for Inferno colormap: {pos, r, g, b}
        double[][] points = {
            { 0.00,   0,   0,   4 },
            { 0.15,  30,   7,  60 },
            { 0.35,  95,  18, 110 },
            { 0.55, 187,  55,  84 },
            { 0.75, 237, 121,  30 },
            { 0.90, 249, 190,  57 },
            { 1.00, 252, 255, 164 }
        };

        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            double r = 0, g = 0, b = 0;

            for (int p = 0; p < points.length - 1; p++) {
                double p1 = points[p][0];
                double p2 = points[p + 1][0];
                if (v >= p1 && v <= p2) {
                    double t = (v - p1) / (p2 - p1);
                    r = points[p][1] + t * (points[p + 1][1] - points[p][1]);
                    g = points[p][2] + t * (points[p + 1][2] - points[p][2]);
                    b = points[p][3] + t * (points[p + 1][3] - points[p][3]);
                    break;
                }
            }
            if (v >= 1.0) {
                r = points[points.length - 1][1];
                g = points[points.length - 1][2];
                b = points[points.length - 1][3];
            }

            int ir = (int) Math.clamp(Math.round(r), 0, 255);
            int ig = (int) Math.clamp(Math.round(g), 0, 255);
            int ib = (int) Math.clamp(Math.round(b), 0, 255);

            lut[i] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
        }
    }

    @Override
    public String getName() {
        return "Inferno";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.INFERNO;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
