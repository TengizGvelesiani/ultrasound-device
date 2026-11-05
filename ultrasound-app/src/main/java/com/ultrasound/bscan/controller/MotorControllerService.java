package com.ultrasound.bscan.controller;

import com.ultrasound.bscan.io.DataAcquisitionSource;
import com.ultrasound.bscan.io.FpgaCommandProtocol;
import com.ultrasound.bscan.io.SimulatedDataAcquisitionSource;
import com.ultrasound.bscan.model.LineScanConfig;
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
    private final AtomicBoolean previewRunning = new AtomicBoolean(false);
    private final AtomicBoolean lineScanning = new AtomicBoolean(false);
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChamberScan-Supervisor");
        t.setDaemon(true);
        return t;
    });
    private Future<?> activeScanFuture;
    private volatile Runnable preScanAction;

    public void setPreScanAction(Runnable action) {
        this.preScanAction = action;
    }

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

    public boolean isPreviewRunning() {
        return previewRunning.get();
    }

    public boolean isLineScanning() {
        return lineScanning.get();
    }

    public boolean isBusy() {
        return chamberScanning.get() || previewRunning.get() || lineScanning.get();
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
        boolean wasScanning = chamberScanning.getAndSet(false);
        boolean wasPreviewing = previewRunning.getAndSet(false);
        boolean wasLineScanning = lineScanning.getAndSet(false);
        sendPacket(FpgaCommandProtocol.buildHaltPacket());
        if (wasScanning || wasPreviewing || wasLineScanning) {
            String abortMsg = wasPreviewing ? "Scan area preview aborted."
                    : (wasLineScanning ? "2D Line Scan aborted." : "Motion halted by user.");
            notifyStateChanged(false, abortMsg);
        } else {
            notifyStateChanged(false, "Motion halted by user.");
        }
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
        } else if (lineScanning.compareAndSet(true, false)) {
            if (activeScanFuture != null && !activeScanFuture.isDone()) {
                activeScanFuture.cancel(true);
            }
            sendPacket(FpgaCommandProtocol.buildHaltPacket());
            notifyProgress(1, 1, 100.0);
            notifyPosition();
            notifyStateChanged(false, "2D Line Scan Completed (Hardware Synchronized)!");
        }
    }

    /**
     * Updates progress based on hardware raster lines acquired.
     */
    public void updateHardwareProgress(int currentLine, int totalLines, double percent) {
        if (chamberScanning.get() || lineScanning.get()) {
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
        if (isBusy()) {
            notifyStateChanged(true, "Cannot jog during active scan or area preview. Halt first.");
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
        if (isBusy()) {
            notifyStateChanged(true, "Cannot jog during active scan or area preview. Halt first.");
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
    /**
     * One-click trigger for motorized single-axis 2D Line Scan along X or Y.
     * Synchronously prepares 2D buffer and supervises motor motion.
     *
     * @param lineConfig line scan settings (axis, distance, direction, speed, autoReturn)
     */
    public synchronized void triggerLineScan(LineScanConfig lineConfig) {
        if (isBusy()) {
            notifyStateChanged(true, "Scanner or stage motion is already active.");
            return;
        }

        final LineScanConfig config = new LineScanConfig(lineConfig);
        final LineScanConfig.ScanAxis axis = config.getAxis();
        final double distanceMm = config.getDistanceMm();
        final double stepDeltaMm = config.getStepDeltaMm();
        final boolean positive = config.isPositiveDir();
        final double speed = config.getSpeedMmPerSec();

        final long totalSteps = axis == LineScanConfig.ScanAxis.X
                ? settings.mmToStepsX(distanceMm)
                : settings.mmToStepsY(distanceMm);

        final int deltaSteps = (int) Math.clamp(Math.round(
                (axis == LineScanConfig.ScanAxis.X ? settings.getStepsPerMmX() : settings.getStepsPerMmY()) * stepDeltaMm),
                1, 255);

        final long halfPeriod = FpgaCommandProtocol.calculateHalfPeriodCycles(
                speed * (axis == LineScanConfig.ScanAxis.X ? settings.getStepsPerMmX() : settings.getStepsPerMmY()));
        final int totalLines = config.getTotalScanLines();

        final long startStepX = currentStepX;
        final long startStepY = currentStepY;

        // 1. Synchronize speed configuration
        updateSpeed();
        try {
            Thread.sleep(15);
        } catch (InterruptedException ignored) {}

        lineScanning.set(true);

        // 2. Pre-scan action (resizes 2D buffer, initializes spatial calibration)
        if (preScanAction != null) {
            try {
                preScanAction.run();
            } catch (Exception ex) {
                System.err.println("[MotorControllerService] Pre-scan action error: " + ex.getMessage());
            }
        }

        // 3. Transmit packet or start simulation
        if (acquisitionSource instanceof SimulatedDataAcquisitionSource sim) {
            sim.startLineScan(axis, distanceMm, positive, speed);
        } else {
            byte axisByte = axis == LineScanConfig.ScanAxis.X ? FpgaCommandProtocol.AXIS_X : FpgaCommandProtocol.AXIS_Y;
            byte[] packet = FpgaCommandProtocol.buildLineScanPacket(axisByte, positive, totalSteps, deltaSteps, halfPeriod);
            boolean sent = sendPacket(packet);
            if (!sent) {
                lineScanning.set(false);
                notifyStateChanged(false, "Failed to send line scan command: port not ready.");
                return;
            }
        }
        String dirSymbol = positive ? "+" : "-";
        notifyStateChanged(true, String.format("2D Line Scan Started (%s-Axis: %s%.1f mm @ %.1f mm/s, %d lines)...",
                axis.name(), dirSymbol, distanceMm, speed, totalLines));

        activeScanFuture = scanExecutor.submit(() -> runLineScanSupervisor(
                config, startStepX, startStepY, totalSteps, halfPeriod, totalLines));
    }

    private void runLineScanSupervisor(LineScanConfig config,
                                       long startStepX, long startStepY,
                                       long totalSteps, long halfPeriod, int totalLines) {
        final LineScanConfig.ScanAxis axis = config.getAxis();
        final boolean positive = config.isPositiveDir();
        final double distanceMm = config.getDistanceMm();
        final double speed = config.getSpeedMmPerSec();
        final boolean autoReturn = config.isAutoReturn();

        long sweepTimeMs = Math.max(50, Math.round((distanceMm / Math.max(0.5, speed)) * 1000.0));
        long startTime = System.currentTimeMillis();
        long updateIntervalMs = 25;

        long endStepX = startStepX + (axis == LineScanConfig.ScanAxis.X ? (positive ? totalSteps : -totalSteps) : 0);
        long endStepY = startStepY + (axis == LineScanConfig.ScanAxis.Y ? (positive ? totalSteps : -totalSteps) : 0);

        try {
            while (lineScanning.get()) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= sweepTimeMs) {
                    break;
                }
                double frac = Math.clamp((double) elapsed / sweepTimeMs, 0.0, 1.0);
                if (axis == LineScanConfig.ScanAxis.X) {
                    currentStepX = Math.round(startStepX + frac * (endStepX - startStepX));
                } else {
                    currentStepY = Math.round(startStepY + frac * (endStepY - startStepY));
                }

                int curLine = (int) Math.clamp(Math.round(frac * totalLines), 1, totalLines);
                notifyProgress(curLine, totalLines, frac * 100.0);
                notifyPosition();

                long sleepTime = Math.min(updateIntervalMs, sweepTimeMs - elapsed);
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            }

            if (!lineScanning.get()) {
                return;
            }

            // Snap to final coordinate
            currentStepX = endStepX;
            currentStepY = endStepY;
            notifyProgress(totalLines, totalLines, 100.0);
            notifyPosition();

            // Settle pause
            Thread.sleep(100);

            // Handle auto-return if configured
            if (autoReturn && lineScanning.get()) {
                notifyStateChanged(true, "Line scan complete. Returning to start position...");
                byte axisByte = axis == LineScanConfig.ScanAxis.X ? FpgaCommandProtocol.AXIS_X : FpgaCommandProtocol.AXIS_Y;
                long returnHalfPeriod = settings.calculateHalfPeriodCycles();
                byte[] returnPacket = FpgaCommandProtocol.buildJogPacket(axisByte, !positive, totalSteps, returnHalfPeriod);
                sendPacket(returnPacket);

                long returnStart = System.currentTimeMillis();
                while (lineScanning.get()) {
                    long elapsed = System.currentTimeMillis() - returnStart;
                    if (elapsed >= sweepTimeMs) {
                        break;
                    }
                    double frac = Math.clamp((double) elapsed / sweepTimeMs, 0.0, 1.0);
                    if (axis == LineScanConfig.ScanAxis.X) {
                        currentStepX = Math.round(endStepX + frac * (startStepX - endStepX));
                    } else {
                        currentStepY = Math.round(endStepY + frac * (startStepY - endStepY));
                    }
                    notifyPosition();

                    long sleepTime = Math.min(updateIntervalMs, sweepTimeMs - elapsed);
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                }

                currentStepX = startStepX;
                currentStepY = startStepY;
                notifyPosition();
            }

            if (lineScanning.compareAndSet(true, false)) {
                sendPacket(FpgaCommandProtocol.buildHaltPacket());
                String msg = String.format("2D Line Scan Completed (%s-Axis: %.1f mm)%s",
                        axis.name(), distanceMm, autoReturn ? " (Returned to origin)" : "");
                notifyStateChanged(false, msg);
            }
        } catch (InterruptedException ignored) {
            sendPacket(FpgaCommandProtocol.buildHaltPacket());
            notifyStateChanged(false, "2D Line Scan aborted.");
        } finally {
            if (lineScanning.compareAndSet(true, false)) {
                sendPacket(FpgaCommandProtocol.buildHaltPacket());
                notifyStateChanged(false, "2D Line Scan finished.");
            }
        }
    }

    public synchronized void triggerChamberScan() {
        if (isBusy()) {
            notifyStateChanged(true, "Chamber scan or preview is already running.");
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

        chamberScanning.set(true);

        // Execute synchronous pre-scan preparation (buffer allocation & queue flush) before FPGA starts pulsing
        if (preScanAction != null) {
            try {
                preScanAction.run();
            } catch (Exception ex) {
                System.err.println("[MotorControllerService] Pre-scan action error: " + ex.getMessage());
            }
        }

        boolean sent = sendPacket(packet);
        if (!sent) {
            chamberScanning.set(false);
            notifyStateChanged(false, "Failed to send chamber scan command: port not ready.");
            return;
        }
        String dirDesc = (startPosX ? "+X" : "-X") + ", " + (advancePosY ? "+Y" : "-Y");
        notifyStateChanged(true, "Autonomous Chamber Scan Started (" + widthX + " x " + lengthY + " mm @ " + speed + " mm/s, Start: " + dirDesc + ").");

        activeScanFuture = scanExecutor.submit(() -> runChamberScanSupervisor(widthX, lengthY, lineSpacing, speed, totalLines, startPosX));
    }

    /**
     * One-click trigger for fast perimeter scan area framing.
     * Moves the stepper motors along the rectangular boundary of the configured chamber scan
     * (starting in the configured X and Y directions) and precisely returns to the original starting position.
     */
    public synchronized void previewScanArea() {
        if (isBusy()) {
            notifyStateChanged(true, "Chamber scan or preview is already running.");
            return;
        }

        final double widthX = settings.getChamberWidthX();
        final double lengthY = settings.getChamberLengthY();
        final boolean startPosX = settings.isStartPositiveX();
        final boolean advancePosY = settings.isAdvancePositiveY();
        final double previewSpeed = settings.getPreviewSpeedMmPerSec();

        final long startStepX = currentStepX;
        final long startStepY = currentStepY;

        previewRunning.set(true);
        String dirDesc = (startPosX ? "+X" : "-X") + ", " + (advancePosY ? "+Y" : "-Y");
        notifyStateChanged(true, String.format("Tracing Scan Area Rectangle (%.1f x %.1f mm @ %.1f mm/s, %s)...",
                widthX, lengthY, previewSpeed, dirDesc));

        activeScanFuture = scanExecutor.submit(() -> runPreviewAreaSupervisor(
                startStepX, startStepY, widthX, lengthY, startPosX, advancePosY, previewSpeed));
    }

    private void runPreviewAreaSupervisor(long startStepX, long startStepY,
                                          double widthX, double lengthY,
                                          boolean startPosX, boolean advancePosY,
                                          double speed) {
        long stepsX = settings.mmToStepsX(widthX);
        long stepsY = settings.mmToStepsY(lengthY);

        long halfPeriodX = FpgaCommandProtocol.calculateHalfPeriodCycles(speed * settings.getStepsPerMmX());
        long halfPeriodY = FpgaCommandProtocol.calculateHalfPeriodCycles(speed * settings.getStepsPerMmY());

        long stepX1 = startStepX + (startPosX ? stepsX : -stepsX);
        long stepY1 = startStepY;

        long stepX2 = stepX1;
        long stepY2 = startStepY + (advancePosY ? stepsY : -stepsY);

        long stepX3 = startStepX;
        long stepY3 = stepY2;

        long stepX4 = startStepX;
        long stepY4 = startStepY;

        record PreviewLeg(byte axis, boolean dir, double distMm, long steps, long halfPeriod,
                          long fromStepX, long fromStepY, long toStepX, long toStepY, String desc) {}

        PreviewLeg[] legs = new PreviewLeg[]{
                new PreviewLeg(FpgaCommandProtocol.AXIS_X, startPosX, widthX, stepsX, halfPeriodX,
                        startStepX, startStepY, stepX1, stepY1, "Leg 1/4 (X: " + (startPosX ? "+X" : "-X") + ")"),
                new PreviewLeg(FpgaCommandProtocol.AXIS_Y, advancePosY, lengthY, stepsY, halfPeriodY,
                        stepX1, stepY1, stepX2, stepY2, "Leg 2/4 (Y: " + (advancePosY ? "+Y" : "-Y") + ")"),
                new PreviewLeg(FpgaCommandProtocol.AXIS_X, !startPosX, widthX, stepsX, halfPeriodX,
                        stepX2, stepY2, stepX3, stepY3, "Leg 3/4 (Return X: " + (!startPosX ? "+X" : "-X") + ")"),
                new PreviewLeg(FpgaCommandProtocol.AXIS_Y, !advancePosY, lengthY, stepsY, halfPeriodY,
                        stepX3, stepY3, stepX4, stepY4, "Leg 4/4 (Return Y to origin: " + (!advancePosY ? "+Y" : "-Y") + ")")
        };

        try {
            for (int i = 0; i < legs.length && previewRunning.get(); i++) {
                PreviewLeg leg = legs[i];
                byte[] packet = FpgaCommandProtocol.buildJogPacket(leg.axis, leg.dir, leg.steps, leg.halfPeriod);
                boolean sent = sendPacket(packet);
                if (!sent) {
                    notifyStateChanged(false, "Failed to send preview command: port not ready.");
                    previewRunning.set(false);
                    return;
                }

                notifyStateChanged(true, "Tracing Scan Area: " + leg.desc);

                long legTimeMs = Math.max(20, Math.round((leg.distMm / Math.max(0.5, speed)) * 1000.0));
                long legStart = System.currentTimeMillis();
                long updateIntervalMs = 40;

                while (previewRunning.get()) {
                    long elapsed = System.currentTimeMillis() - legStart;
                    if (elapsed >= legTimeMs) {
                        break;
                    }
                    double frac = Math.clamp((double) elapsed / legTimeMs, 0.0, 1.0);
                    currentStepX = Math.round(leg.fromStepX + frac * (leg.toStepX - leg.fromStepX));
                    currentStepY = Math.round(leg.fromStepY + frac * (leg.toStepY - leg.fromStepY));

                    double overallPercent = ((i + frac) / 4.0) * 100.0;
                    notifyProgress(i + 1, 4, overallPercent);
                    notifyPosition();

                    long sleepTime = Math.min(updateIntervalMs, legTimeMs - elapsed);
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                }

                if (!previewRunning.get()) {
                    break;
                }

                currentStepX = leg.toStepX;
                currentStepY = leg.toStepY;
                notifyProgress(i + 1, 4, ((i + 1) / 4.0) * 100.0);
                notifyPosition();

                // Brief settling pause between orthogonal motion legs
                Thread.sleep(50);
            }

            if (previewRunning.compareAndSet(true, false)) {
                currentStepX = startStepX;
                currentStepY = startStepY;
                updateSpeed(); // Restore normal FPGA speed
                notifyProgress(4, 4, 100.0);
                notifyPosition();
                notifyStateChanged(false, "Scan Area Preview Completed (Returned to Origin).");
            }
        } catch (InterruptedException ignored) {
            sendPacket(FpgaCommandProtocol.buildHaltPacket());
                notifyStateChanged(false, "Scan area preview aborted.");
        } finally {
            if (previewRunning.compareAndSet(true, false)) {
                sendPacket(FpgaCommandProtocol.buildHaltPacket());
                        notifyStateChanged(false, "Scan area preview finished.");
            }
        }
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
