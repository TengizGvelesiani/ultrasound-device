package com.ultrasound.bscan.colormap;

public enum ColormapType {
    TURBO("Turbo (High Contrast)"),
    AMBER("Amber (Gold 3D)"),
    JET("Jet (Rainbow)"),
    VIRIDIS("Viridis (Perceptual)"),
    COOL_WARM("Cool-Warm (Diverging)"),
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

    public String getShortName() {
        return switch (this) {
            case TURBO -> "Turbo";
            case AMBER -> "Amber";
            case JET -> "Jet";
            case VIRIDIS -> "Viridis";
            case COOL_WARM -> "CoolWarm";
            case BONE -> "Bone";
            case HOT -> "Hot";
            case INFERNO -> "Inferno";
            case GRAY -> "Gray";
        };
    }

    public ColormapType next() {
        ColormapType[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
