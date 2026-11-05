package com.ultrasound.bscan.colormap;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry and factory for ultrasound colormaps.
 */
public final class ColormapRegistry {

    private final Map<ColormapType, Colormap> colormaps = new EnumMap<>(ColormapType.class);

    public ColormapRegistry() {
        register(new TurboColormap());
        register(new AmberColormap());
        register(new JetColormap());
        register(new ViridisColormap());
        register(new CoolWarmColormap());
        register(new BoneColormap());
        register(new HotColormap());
        register(new InfernoColormap());
        register(new GrayColormap());
    }

    public void register(Colormap colormap) {
        colormaps.put(colormap.getType(), colormap);
    }

    public Colormap get(ColormapType type) {
        Colormap cm = colormaps.get(type);
        return cm != null ? cm : colormaps.get(ColormapType.TURBO);
    }
}
