package com.ultrasound.bscan.colormap;

/**
 * Amber / Gold warm 3D ultrasound surface colormap.
 * Mimics clinical GE/Philips 3D/4D surface rendering,
 * transitioning from deep warm copper floor to brilliant radiant golden highlights.
 */
public final class AmberColormap implements Colormap {

    private final int[] lut = new int[256];

    public AmberColormap() {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;

            // R starts at 45 (dark copper floor), rises smoothly to 255
            double r = Math.clamp(45.0 + 350.0 * v, 0.0, 255.0);

            // G starts at 18, rises through rich gold to sunny yellow
            double g;
            if (v < 0.6) {
                g = 18.0 + (190.0 - 18.0) * (v / 0.6);
            } else {
                g = 190.0 + (255.0 - 190.0) * ((v - 0.6) / 0.4);
            }

            // B starts at 8, stays low in copper/amber, then ramps up for brilliant specular highlight
            double b;
            if (v < 0.75) {
                b = 8.0 + 30.0 * (v / 0.75);
            } else {
                b = 38.0 + (220.0 - 38.0) * ((v - 0.75) / 0.25);
            }

            int ir = (int) Math.clamp(Math.round(r), 0, 255);
            int ig = (int) Math.clamp(Math.round(g), 0, 255);
            int ib = (int) Math.clamp(Math.round(b), 0, 255);

            lut[i] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
        }
    }

    @Override
    public String getName() {
        return "Amber (Gold 3D)";
    }

    @Override
    public ColormapType getType() {
        return ColormapType.AMBER;
    }

    @Override
    public int[] getLut() {
        return lut;
    }
}
