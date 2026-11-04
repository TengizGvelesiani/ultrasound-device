package com.ultrasound.bscan.model;

import com.ultrasound.bscan.io.FpgaCommandProtocol;

/**
 * Configuration and calibration model for the motorized ultrasound scanning gantry.
 * Accommodates arbitrary container/chamber dimensions and 1/16 microstepping drivers.
 */
public final class MotorSettings {

    // Default physical chamber dimensions (user adjustable in GUI)
    private double chamberWidthX = 100.0;     // mm
    private double chamberLengthY = 100.0;    // mm
    private double lineSpacingY = 1.0;        // mm per raster line

    // Calibration: steps per mm (1/16 microstepping on standard 8mm lead screws = 400 steps/mm)
    private double stepsPerMmX = 400.0;
    private double stepsPerMmY = 400.0;

    // Speeds
    private double speedMmPerSec = 10.0;      // mm/s default

    // Jogging step size in mm (0.0 means continuous)
    private double jogIncrementMm = 1.0;

    public MotorSettings() {
    }

    public synchronized double getChamberWidthX() {
        return chamberWidthX;
    }

    public synchronized void setChamberWidthX(double chamberWidthX) {
        this.chamberWidthX = Math.max(5.0, chamberWidthX);
    }

    public synchronized double getChamberLengthY() {
        return chamberLengthY;
    }

    public synchronized void setChamberLengthY(double chamberLengthY) {
        this.chamberLengthY = Math.max(5.0, chamberLengthY);
    }

    public synchronized double getLineSpacingY() {
        return lineSpacingY;
    }

    public synchronized void setLineSpacingY(double lineSpacingY) {
        this.lineSpacingY = Math.clamp(lineSpacingY, 0.05, 50.0);
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

    public synchronized double getJogIncrementMm() {
        return jogIncrementMm;
    }

    public synchronized void setJogIncrementMm(double jogIncrementMm) {
        this.jogIncrementMm = Math.max(0.0, jogIncrementMm);
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
}
