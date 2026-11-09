package com.ultrasound.bscan.model;

import com.ultrasound.bscan.io.FpgaCommandProtocol;

/**
 * Configuration and calibration model for the motorized ultrasound scanning gantry.
 * Accommodates arbitrary container/chamber dimensions, 1/16 microstepping drivers,
 * and user-selected initial scan directions (+X/-X, +Y/-Y).
 */
public final class MotorSettings {

    // Default physical chamber dimensions (user adjustable in GUI)
    private double chamberWidthX = 10.0;     // mm
    private double chamberLengthY = 40.0;    // mm
    private double lineSpacingY = 1.0;        // mm per raster line
    private double stepDeltaXMm = 0.1;        // mm per position-triggered pulse on X axis (default 40 microsteps @ 400 stp/mm)

    // Calibration: steps per mm (1/16 microstepping on standard 8mm lead screws = 400 steps/mm)
    private double stepsPerMmX = 400.0;
    private double stepsPerMmY = 400.0;

    // Speeds
    private double speedMmPerSec = 10.0;      // mm/s default
    private double previewSpeedMmPerSec = 25.0; // mm/s fast preview framing speed

    // Jogging step size in mm (0.0 means continuous)
    private double jogIncrementMm = 1.0;

    // Scan Trajectory Start Directions
    private boolean startPositiveX = true;    // true = +X (Right), false = -X (Left)
    private boolean advancePositiveY = true;  // true = +Y (Forward), false = -Y (Backward)

    // Backlash compensation (in mm) for serpentine line reversal (+X vs -X)
    private double backlashCompensationMm = 0.0;

    public MotorSettings() {
    }

    public synchronized double getChamberWidthX() {
        return chamberWidthX;
    }

    public synchronized void setChamberWidthX(double chamberWidthX) {
        this.chamberWidthX = Math.max(1.0, chamberWidthX);
    }

    public synchronized double getChamberLengthY() {
        return chamberLengthY;
    }

    public synchronized void setChamberLengthY(double chamberLengthY) {
        this.chamberLengthY = Math.max(1.0, chamberLengthY);
    }

    public synchronized double getLineSpacingY() {
        return lineSpacingY;
    }

    public synchronized void setLineSpacingY(double lineSpacingY) {
        this.lineSpacingY = Math.clamp(lineSpacingY, 0.05, 50.0);
    }

    public synchronized double getStepDeltaXMm() {
        return stepDeltaXMm;
    }

    public synchronized void setStepDeltaXMm(double stepDeltaXMm) {
        this.stepDeltaXMm = Math.clamp(stepDeltaXMm, 0.01, 10.0);
    }

    public synchronized double getStepsPerMmX() {
        return stepsPerMmX;
    }

    public synchronized void setStepsPerMmX(double stepsPerMmX) {
        this.stepsPerMmX = Math.max(1.0, stepsPerMmX);
    }

    public synchronized double getStepsPerMmY() {
        return stepsPerMmY;
    }

    public synchronized void setStepsPerMmY(double stepsPerMmY) {
        this.stepsPerMmY = Math.max(1.0, stepsPerMmY);
    }

    public synchronized double getSpeedMmPerSec() {
        return speedMmPerSec;
    }

    public synchronized void setSpeedMmPerSec(double speedMmPerSec) {
        this.speedMmPerSec = Math.clamp(speedMmPerSec, 0.5, 100.0);
    }

    public synchronized double getPreviewSpeedMmPerSec() {
        return previewSpeedMmPerSec;
    }

    public synchronized void setPreviewSpeedMmPerSec(double previewSpeedMmPerSec) {
        this.previewSpeedMmPerSec = Math.clamp(previewSpeedMmPerSec, 1.0, 100.0);
    }

    public synchronized double getJogIncrementMm() {
        return jogIncrementMm;
    }

    public synchronized void setJogIncrementMm(double jogIncrementMm) {
        this.jogIncrementMm = Math.max(0.0, jogIncrementMm);
    }

    public synchronized boolean isStartPositiveX() {
        return startPositiveX;
    }

    public synchronized void setStartPositiveX(boolean startPositiveX) {
        this.startPositiveX = startPositiveX;
    }

    public synchronized boolean isAdvancePositiveY() {
        return advancePositiveY;
    }

    public synchronized void setAdvancePositiveY(boolean advancePositiveY) {
        this.advancePositiveY = advancePositiveY;
    }

    public synchronized long mmToStepsX(double mm) {
        return Math.round(mm * stepsPerMmX);
    }

    public synchronized long mmToStepsY(double mm) {
        return Math.round(mm * stepsPerMmY);
    }

    public synchronized double stepsToMmX(long steps) {
        return (double) steps / stepsPerMmX;
    }

    public synchronized double stepsToMmY(long steps) {
        return (double) steps / stepsPerMmY;
    }

    /**
     * Calculates the FPGA 100 MHz clock half-period cycles corresponding to current mm/s speed.
     */
    public synchronized long calculateHalfPeriodCycles() {
        double stepFrequencyHz = speedMmPerSec * stepsPerMmX;
        return FpgaCommandProtocol.calculateHalfPeriodCycles(stepFrequencyHz);
    }

    public synchronized int getTotalScanLines() {
        return (int) Math.max(1, Math.ceil(chamberLengthY / lineSpacingY)) + 1;
    }

    public synchronized int getTotalScanPointsX() {
        long totalXSteps = mmToStepsX(chamberWidthX);
        int deltaXSteps = (int) Math.clamp(Math.round(stepsPerMmX * stepDeltaXMm), 1, 255);
        return (int) Math.max(2, totalXSteps / deltaXSteps);
    }

    public synchronized int getGridPointsX() {
        return getTotalScanPointsX();
    }

    public synchronized void setGridPointsX(int pointsX) {
        int pts = Math.max(2, pointsX);
        long totalXSteps = mmToStepsX(chamberWidthX);
        int deltaXSteps = (int) Math.clamp(Math.round((double) totalXSteps / pts), 1, 255);
        this.stepDeltaXMm = Math.max(0.01, stepsToMmX(deltaXSteps));
    }

    public synchronized int getGridLinesY() {
        return getTotalScanLines();
    }

    public synchronized void setGridLinesY(int linesY) {
        int lines = Math.max(2, linesY);
        double targetSpacing = chamberLengthY / (double) (lines - 1);
        setLineSpacingY(targetSpacing);
    }

    public synchronized int getTotalScanPoints() {
        return getGridPointsX() * getGridLinesY();
    }

    public synchronized double getEstimatedScanTimeSec() {
        int lines = getTotalScanLines();
        double sweepTimeSec = chamberWidthX / Math.max(0.5, speedMmPerSec);
        double stepYTimeSec = lineSpacingY / Math.max(0.5, speedMmPerSec);
        return (lines * sweepTimeSec) + ((lines - 1) * stepYTimeSec) + (lines * 0.05);
    }

    public synchronized double getBacklashCompensationMm() {
        return backlashCompensationMm;
    }

    public synchronized void setBacklashCompensationMm(double backlashMm) {
        this.backlashCompensationMm = Math.clamp(backlashMm, 0.0, 5.0);
    }

    public synchronized void setPrecision3DSpeed() {
        setSpeedMmPerSec(2.0);
    }

    public synchronized void setStandard3DSpeed() {
        setSpeedMmPerSec(5.0);
    }

    public synchronized void setFast2DSpeed() {
        setSpeedMmPerSec(10.0);
    }
}
