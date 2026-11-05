package com.ultrasound.bscan.model;

import java.util.Objects;

/**
 * Configuration model for motorized single-axis 2D Line Scanning.
 * Supports autonomous sweeps along either the X-axis (carriage) or Y-axis (dual bottom motors).
 */
public final class LineScanConfig {

    public enum ScanAxis {
        X("X-Axis (Carriage)"),
        Y("Y-Axis (Dual Gantry)");

        private final String displayName;

        ScanAxis(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public static final double DEFAULT_X_DISTANCE_MM = 10.0;
    public static final double DEFAULT_Y_DISTANCE_MM = 40.0;
    public static final double DEFAULT_STEP_DELTA_MM = 0.10;
    public static final double DEFAULT_SPEED_MM_PER_SEC = 10.0;

    private ScanAxis axis;
    private double distanceMm;
    private double stepDeltaMm;
    private boolean positiveDir;
    private boolean autoReturn;
    private double speedMmPerSec;

    public LineScanConfig() {
        this.axis = ScanAxis.X;
        this.distanceMm = DEFAULT_X_DISTANCE_MM;
        this.stepDeltaMm = DEFAULT_STEP_DELTA_MM;
        this.positiveDir = true;
        this.autoReturn = true;
        this.speedMmPerSec = DEFAULT_SPEED_MM_PER_SEC;
    }

    public LineScanConfig(LineScanConfig other) {
        Objects.requireNonNull(other, "other must not be null");
        this.axis = other.axis;
        this.distanceMm = other.distanceMm;
        this.stepDeltaMm = other.stepDeltaMm;
        this.positiveDir = other.positiveDir;
        this.autoReturn = other.autoReturn;
        this.speedMmPerSec = other.speedMmPerSec;
    }

    public ScanAxis getAxis() {
        return axis;
    }

    public void setAxis(ScanAxis axis) {
        this.axis = Objects.requireNonNull(axis, "axis must not be null");
    }

    public double getDistanceMm() {
        return distanceMm;
    }

    public void setDistanceMm(double distanceMm) {
        this.distanceMm = Math.max(0.5, distanceMm);
    }

    public double getStepDeltaMm() {
        return stepDeltaMm;
    }

    public void setStepDeltaMm(double stepDeltaMm) {
        this.stepDeltaMm = Math.max(0.01, stepDeltaMm);
    }

    public boolean isPositiveDir() {
        return positiveDir;
    }

    public void setPositiveDir(boolean positiveDir) {
        this.positiveDir = positiveDir;
    }

    public boolean isAutoReturn() {
        return autoReturn;
    }

    public void setAutoReturn(boolean autoReturn) {
        this.autoReturn = autoReturn;
    }

    public double getSpeedMmPerSec() {
        return speedMmPerSec;
    }

    public void setSpeedMmPerSec(double speedMmPerSec) {
        this.speedMmPerSec = Math.max(0.1, speedMmPerSec);
    }

    /**
     * Total number of A-scan sample lines across the line distance.
     */
    public int getTotalScanLines() {
        return (int) Math.max(1, Math.round(distanceMm / Math.max(0.01, stepDeltaMm)));
    }

    /**
     * Estimated sweep time for the single pass in seconds.
     */
    public double getEstimatedSweepSeconds() {
        return distanceMm / Math.max(0.1, speedMmPerSec);
    }

    @Override
    public String toString() {
        return String.format("LineScanConfig{axis=%s, distance=%.1f mm, step=%.2f mm, dir=%s, autoReturn=%b, speed=%.1f mm/s}",
                axis, distanceMm, stepDeltaMm, positiveDir ? "+" : "-", autoReturn, speedMmPerSec);
    }
}
