package com.ultrasound.bscan.controller;

import com.ultrasound.bscan.io.DataAcquisitionSource;
import com.ultrasound.bscan.io.FpgaCommandProtocol;
import com.ultrasound.bscan.model.MotorSettings;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service managing stepper motor communication, coordinates tracking,
 * and autonomous chamber raster scans.
 * <p>
 * Strictly guarantees that both bottom Y-axis motors (Y1 and Y2) receive
 * synchronized, identical step and direction commands at all times.
 */
public final class MotorControllerService {

    public interface MotorStateListener {
        void onPositionUpdated(double xMm, double yMm, long xSteps, long ySteps);
        void onScanProgress(int currentLine, int totalLines, double percent);
        void onScanStateChanged(boolean isScanning, String message);
    }

    private final MotorSettings settings;
    private final List<MotorStateListener> listeners = new CopyOnWriteArrayList<>();

    private volatile DataAcquisitionSource acquisitionSource;

    // Estimated current stage coordinates
    private volatile long currentStepX = 0;
    private volatile long currentStepY = 0;

    // Scan State
    private final AtomicBoolean chamberScanning = new AtomicBoolean(false);
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChamberScan-Supervisor");
        t.setDaemon(true);
        return t;
    });
    private Future<?> activeScanFuture;

    public MotorControllerService(MotorSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    public void setAcquisitionSource(DataAcquisitionSource source) {
        this.acquisitionSource = source;
    }

    public void addListener(MotorStateListener listener) {
        if (listener != null) {
            listeners.add(listener);
            notifyPosition();
        }
    }

    public void removeListener(MotorStateListener listener) {
        listeners.remove(listener);
    }

    public MotorSettings getSettings() {
        return settings;
    }

    public boolean isChamberScanning() {
        return chamberScanning.get();
    }

    public double getCurrentPositionXMm() {
        return settings.stepsToMmX(currentStepX);
    }

    public double getCurrentPositionYMm() {
        return settings.stepsToMmY(currentStepY);
    }

    public long getCurrentStepX() {
        return currentStepX;
    }

    public long getCurrentStepY() {
        return currentStepY;
    }

    /**
     * Resets stage coordinate reference to (0, 0).
     */
    public synchronized void zeroPosition() {
        currentStepX = 0;
        currentStepY = 0;
        sendPacket(FpgaCommandProtocol.buildZeroPositionPacket());
        notifyPosition();
        notifyStateChanged(chamberScanning.get(), "Stage coordinate zeroed (X=0.0 mm, Y=0.0 mm).");
    }

    /**
     * Sends the configured stepper step frequency (half-period clock cycles) to the FPGA.
     */
    public synchronized void updateSpeed() {
        long halfPeriod = settings.calculateHalfPeriodCycles();
        sendPacket(FpgaCommandProtocol.buildSetSpeedPacket(halfPeriod));
    }

    /**
     * Halts all stepper motion immediately and cancels any active scan.
     */
    public synchronized void halt() {
        if (activeScanFuture != null && !activeScanFuture.isDone()) {
            activeScanFuture.cancel(true);
        }
        chamberScanning.set(false);
        sendPacket(FpgaCommandProtocol.buildHaltPacket());
        notifyStateChanged(false, "Motion halted by user.");
    }

    /**
     * Called directly upon FPGA hardware frame metadata indicating scan completion.
     */
    public synchronized void notifyHardwareScanComplete() {
        if (chamberScanning.compareAndSet(true, false)) {
            if (activeScanFuture != null && !activeScanFuture.isDone()) {
                activeScanFuture.cancel(true);
            }
            // Send explicit halt to ensure FPGA coils are powered down and in M_IDLE
            sendPacket(FpgaCommandProtocol.buildHaltPacket());

            int totalLines = settings.getTotalScanLines();
            notifyProgress(totalLines, totalLines, 100.0);
            notifyPosition();
            notifyStateChanged(false, "Chamber Scan Completed (Hardware Synchronized)!");
        }
    }

    /**
     * Updates progress based on hardware raster lines acquired.
     */
    public void updateHardwareProgress(int currentLine, int totalLines, double percent) {
        if (chamberScanning.get()) {
            notifyProgress(currentLine, totalLines, percent);
        }
    }

    /**
     * Jogs the top X-axis stepper motor.
     *
     * @param positive   true for +X, false for -X
     * @param distanceMm distance in mm (if <= 0, uses settings jogIncrementMm)
     */
    public synchronized void jogX(boolean positive, double distanceMm) {
        if (chamberScanning.get()) {
            notifyStateChanged(true, "Cannot jog during an active chamber scan. Halt scan first.");
            return;
        }

        double mm = distanceMm > 0.0 ? distanceMm : settings.getJogIncrementMm();
        long steps = settings.mmToStepsX(mm);
        if (steps <= 0) {
            steps = 1;
        }

        long halfPeriod = settings.calculateHalfPeriodCycles();
        byte[] packet = FpgaCommandProtocol.buildJogPacket(
                FpgaCommandProtocol.AXIS_X,
                positive,
                steps,
                halfPeriod
        );

        sendPacket(packet);

        // Update estimated position
        if (positive) {
            currentStepX += steps;
        } else {
            currentStepX -= steps;
        }
        notifyPosition();
    }

    /**
     * Jogs both bottom Y-axis stepper motors (Y1 and Y2) in unison.
     * Strictly sends AXIS_Y command to guarantee lockstep direction and step count.
     *
     * @param positive   true for +Y, false for -Y
     * @param distanceMm distance in mm (if <= 0, uses settings jogIncrementMm)
     */
    public synchronized void jogY(boolean positive, double distanceMm) {
        if (chamberScanning.get()) {
            notifyStateChanged(true, "Cannot jog during an active chamber scan. Halt scan first.");
            return;
        }

        double mm = distanceMm > 0.0 ? distanceMm : settings.getJogIncrementMm();
        long steps = settings.mmToStepsY(mm);
        if (steps <= 0) {
            steps = 1;
        }

        long halfPeriod = settings.calculateHalfPeriodCycles();
        byte[] packet = FpgaCommandProtocol.buildJogPacket(
                FpgaCommandProtocol.AXIS_Y, // Dual bottom motors locked
                positive,
                steps,
                halfPeriod
        );

        sendPacket(packet);

        // Update estimated position
        if (positive) {
            currentStepY += steps;
        } else {
            currentStepY -= steps;
        }
        notifyPosition();
    }

    /**
     * One-click trigger for full XY chamber raster scan.
     * Transmits the hardware command to the FPGA with user-selected start directions.
     */
    public synchronized void triggerChamberScan() {
        if (chamberScanning.get()) {
            notifyStateChanged(true, "Chamber scan is already running.");
            return;
        }

        final double widthX = settings.getChamberWidthX();
        final double lengthY = settings.getChamberLengthY();
        final double lineSpacing = settings.getLineSpacingY();
        final double stepDeltaX = settings.getStepDeltaXMm();
        final double speed = settings.getSpeedMmPerSec();

        final long totalXSteps = settings.mmToStepsX(widthX);
        final long totalYSteps = settings.mmToStepsY(lengthY);
        final int deltaYSteps = (int) Math.max(1, settings.mmToStepsY(lineSpacing));
        final long halfPeriod = settings.calculateHalfPeriodCycles();
        final int totalLines = settings.getTotalScanLines();

        final boolean startPosX = settings.isStartPositiveX();
        final boolean advancePosY = settings.isAdvancePositiveY();

        // 1. Synchronize speed configuration with FPGA
        updateSpeed();

        // Brief 15ms pause to ensure FPGA UART RX latches CMD_SET_SPEED before CMD_CHAMBER_SCAN
        try {
            Thread.sleep(15);
        } catch (InterruptedException ignored) {}

        // 2. Transmit autonomous chamber scan packet (Position-Triggered Pulser)
        final int deltaXSteps = (int) Math.clamp(Math.round(settings.getStepsPerMmX() * stepDeltaX), 1, 255);
        byte[] packet = FpgaCommandProtocol.buildChamberScanPacket(
                totalXSteps,
                totalYSteps,
                deltaYSteps,
                deltaXSteps,
                startPosX,
                advancePosY,
                halfPeriod
        );

        boolean sent = sendPacket(packet);
        if (!sent) {
            notifyStateChanged(false, "Failed to send chamber scan command: port not ready.");
            return;
        }

        chamberScanning.set(true);
        String dirDesc = (startPosX ? "+X" : "-X") + ", " + (advancePosY ? "+Y" : "-Y");
        notifyStateChanged(true, "Autonomous Chamber Scan Started (" + widthX + " x " + lengthY + " mm @ " + speed + " mm/s, Start: " + dirDesc + ").");

        activeScanFuture = scanExecutor.submit(() -> runChamberScanSupervisor(widthX, lengthY, lineSpacing, speed, totalLines, startPosX));
    }

    private void runChamberScanSupervisor(double widthX, double lengthY, double lineSpacing, double speed, int totalLines, boolean initialPositiveX) {
        // Approximate time per X sweep
        double lineTimeSec = widthX / Math.max(0.5, speed);
        long lineTimeMs = Math.round(lineTimeSec * 1000.0);
        long updateIntervalMs = 100;

        try {
            for (int line = 1; line <= totalLines && chamberScanning.get(); line++) {
                long lineStart = System.currentTimeMillis();

                // Track position during X sweep
                boolean forward = (line % 2 != 0) ? initialPositiveX : !initialPositiveX;
                double targetY = (line - 1) * lineSpacing;
                currentStepY = settings.mmToStepsY(targetY);

                while (System.currentTimeMillis() - lineStart < lineTimeMs && chamberScanning.get()) {
                    double progressInLine = (double) (System.currentTimeMillis() - lineStart) / lineTimeMs;
                    progressInLine = Math.clamp(progressInLine, 0.0, 1.0);
                    double currentX = forward ? (progressInLine * widthX) : ((1.0 - progressInLine) * widthX);
                    currentStepX = settings.mmToStepsX(currentX);

                    double overallPercent = ((line - 1) + progressInLine) / (double) totalLines * 100.0;
                    notifyProgress(line, totalLines, overallPercent);
                    notifyPosition();

                    Thread.sleep(updateIntervalMs);
                }

                if (!chamberScanning.get()) {
                    break;
                }

                // Wait for physical Y-step advance on the gantry
                if (line < totalLines && chamberScanning.get()) {
                    long stepYTimeMs = Math.max(50, Math.round((lineSpacing / Math.max(0.5, speed)) * 1000.0));
                    long yStart = System.currentTimeMillis();
                    while (System.currentTimeMillis() - yStart < stepYTimeMs && chamberScanning.get()) {
                        Thread.sleep(50);
                    }
                }
            }

            // Wait for hardware scan completion signal from FPGA frame metadata
            long waitStart = System.currentTimeMillis();
            long maxWaitMs = 25_000; // 25s safety watchdog window
            while (chamberScanning.get() && (System.currentTimeMillis() - waitStart < maxWaitMs)) {
                Thread.sleep(150);
            }

            if (chamberScanning.compareAndSet(true, false)) {
                // Safety watchdog fired: send halt command to guarantee physical motors park
                sendPacket(FpgaCommandProtocol.buildHaltPacket());
                currentStepX = 0;
                currentStepY = settings.mmToStepsY(lengthY);
                notifyProgress(totalLines, totalLines, 100.0);
                notifyPosition();
                notifyStateChanged(false, "Chamber Scan Finished (Gantry Parked).");
            }
        } catch (InterruptedException ignored) {
            sendPacket(FpgaCommandProtocol.buildHaltPacket());
            notifyStateChanged(false, "Chamber scan aborted.");
        } finally {
            if (chamberScanning.compareAndSet(true, false)) {
                sendPacket(FpgaCommandProtocol.buildHaltPacket());
            }
        }
    }

    private boolean sendPacket(byte[] packet) {
        DataAcquisitionSource src = this.acquisitionSource;
        if (src != null) {
            return src.sendCommand(packet);
        }
        return false;
    }

    private void notifyPosition() {
        double xMm = getCurrentPositionXMm();
        double yMm = getCurrentPositionYMm();
        long sx = currentStepX;
        long sy = currentStepY;
        for (MotorStateListener l : listeners) {
            try {
                l.onPositionUpdated(xMm, yMm, sx, sy);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyProgress(int currentLine, int totalLines, double percent) {
        for (MotorStateListener l : listeners) {
            try {
                l.onScanProgress(currentLine, totalLines, percent);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyStateChanged(boolean isScanning, String message) {
        for (MotorStateListener l : listeners) {
            try {
                l.onScanStateChanged(isScanning, message);
            } catch (Exception ignored) {
            }
        }
    }
}
