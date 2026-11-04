package com.ultrasound.bscan.model;

/**
 * Rendering style for the 2.5D / 3D volumetric view.
 */
public enum Render3DStyle {
    SHADED_SURFACE("3D Shaded Surface"),
    WIREFRAME("3D Wireframe Mesh"),
    CSCAN_MIP("En-face C-Scan Projection");

    private final String label;
    Render3DStyle(String label) { this.label = label; }
    public String getLabel() { return label; }
    public Render3DStyle next() {
        Render3DStyle[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }
}
