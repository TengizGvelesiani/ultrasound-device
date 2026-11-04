package com.ultrasound.bscan.model;

/**
 * Operational mode of the B-Scan system.
 */
public enum ScanMode {
    /**
     * Sweep Pass Mode: records a 2D cross-section pass along an axis
     * from left to right as the transducer translates over the target.
     */
    SWEEP("Sweep Pass"),

    /**
     * Continuous Rolling Waterfall Mode: live real-time continuous rolling display,
     * where new A-scan lines enter on the right and older lines scroll left.
     */
    WATERFALL("Continuous Waterfall");

    private final String displayName;

    ScanMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
