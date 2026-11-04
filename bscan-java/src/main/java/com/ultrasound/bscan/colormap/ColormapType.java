package com.ultrasound.bscan.colormap;

public enum ColormapType {
    BONE("Bone (Medical)"),
    HOT("Hot"),
    INFERNO("Inferno"),
    GRAY("Grayscale");

    private final String label;

    ColormapType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public ColormapType next() {
        ColormapType[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
