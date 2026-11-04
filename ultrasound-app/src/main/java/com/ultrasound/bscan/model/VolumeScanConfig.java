package com.ultrasound.bscan.model;

/**
 * Geometric and mechanical configuration for 2.5D / 3D volumetric scanning over an XY stage.
 */
public final class VolumeScanConfig {

    public enum ScanPattern {
        SNAKE("Snake (Boustrophedon)"),
        UNIDIRECTIONAL("Unidirectional (Left-to-Right)");

        private final String label;
        ScanPattern(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public static final int DEFAULT_GRID_X = 50;
    public static final int DEFAULT_GRID_Y = 50;
    public static final double DEFAULT_STEP_X_MM = 1.0;
    public static final double DEFAULT_STEP_Y_MM = 1.0;

    private int gridX;
    private int gridY;
    private double stepSizeXMm;
    private double stepSizeYMm;
    private ScanPattern pattern;
    private double gateMinDepthMm;
    private double gateMaxDepthMm;

    public VolumeScanConfig() {
        this(DEFAULT_GRID_X, DEFAULT_GRID_Y, DEFAULT_STEP_X_MM, DEFAULT_STEP_Y_MM, ScanPattern.SNAKE, 15.0, 60.0);
    }

    public VolumeScanConfig(int gridX, int gridY, double stepSizeXMm, double stepSizeYMm,
                            ScanPattern pattern, double gateMinDepthMm, double gateMaxDepthMm) {
        this.gridX = Math.max(5, gridX);
        this.gridY = Math.max(5, gridY);
        this.stepSizeXMm = Math.max(0.01, stepSizeXMm);
        this.stepSizeYMm = Math.max(0.01, stepSizeYMm);
        this.pattern = pattern != null ? pattern : ScanPattern.SNAKE;
        this.gateMinDepthMm = gateMinDepthMm;
        this.gateMaxDepthMm = gateMaxDepthMm;
    }

    public int getGridX() { return gridX; }
    public void setGridX(int gridX) { this.gridX = Math.max(5, gridX); }

    public int getGridY() { return gridY; }
    public void setGridY(int gridY) { this.gridY = Math.max(5, gridY); }

    public double getStepSizeXMm() { return stepSizeXMm; }
    public void setStepSizeXMm(double stepSizeXMm) { this.stepSizeXMm = Math.max(0.01, stepSizeXMm); }

    public double getStepSizeYMm() { return stepSizeYMm; }
    public void setStepSizeYMm(double stepSizeYMm) { this.stepSizeYMm = Math.max(0.01, stepSizeYMm); }

    public double getTotalWidthXMm() { return (gridX - 1) * stepSizeXMm; }
    public double getTotalHeightYMm() { return (gridY - 1) * stepSizeYMm; }

    public ScanPattern getPattern() { return pattern; }
    public void setPattern(ScanPattern pattern) { this.pattern = pattern; }

    public double getGateMinDepthMm() { return gateMinDepthMm; }
    public void setGateMinDepthMm(double gateMinDepthMm) { this.gateMinDepthMm = gateMinDepthMm; }

    public double getGateMaxDepthMm() { return gateMaxDepthMm; }
    public void setGateMaxDepthMm(double gateMaxDepthMm) { this.gateMaxDepthMm = gateMaxDepthMm; }
}
