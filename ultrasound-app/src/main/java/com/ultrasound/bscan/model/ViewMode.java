package com.ultrasound.bscan.model;

/**
 * Top-level application viewing mode.
 */
public enum ViewMode {
    /**
     * Standard 2D mode: Real-time 1D A-scan waveform + 2D B-scan cross section (sweep/waterfall).
     */
    MODE_2D("2D B-Scan Mode"),

    /**
     * 2.5D / 3D mode: Volumetric acquisition along XY axes + depth lines.
     * Generates interactive 3D surface topography, C-scan en-face slices, and orthographic views.
     */
    MODE_3D("2.5D / 3D Volume Mode");

    private final String label;

    ViewMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public ViewMode toggle() {
        return this == MODE_2D ? MODE_3D : MODE_2D;
    }
}
