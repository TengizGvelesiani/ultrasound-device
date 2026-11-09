package com.ultrasound.bscan.io;

import com.ultrasound.bscan.model.LineScanConfig;
import com.ultrasound.bscan.model.ScanConfig;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Synthetic ultrasound signal generator simulating acoustic reflections
 * from a water tank, a 3D metal coin phantom, and XY mechanical stage translation.
 */
public final class SimulatedDataAcquisitionSource implements DataAcquisitionSource {

    private final ScanConfig config;
    private final int prfHz;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread simThread;

    // Optional 2D Stage Coordinate binding
    private volatile boolean externalStageControl = false;
    private volatile double currentStageXNorm = 0.0; // -1.0 to +1.0
    private volatile double currentStageYNorm = 0.0; // -1.0 to +1.0

    // Chamber Scan Simulation State
    private volatile boolean simulatingChamberScan = false;
    private volatile double chamberScanDirX = 1.0;
    private volatile double chamberDeltaXNorm = 0.02; // step per A-scan point across X
    private volatile double chamberDeltaYNorm = 0.05; // step per line across Y

    // 2D Line Scan Simulation State
    private volatile boolean simulatingLineScan = false;
    private volatile LineScanConfig.ScanAxis lineScanAxis = LineScanConfig.ScanAxis.X;
    private volatile double lineScanDeltaNorm = 0.02;
    private volatile double lineScanTargetNorm = 1.0;
    private volatile boolean lineScanFirstFrame = true;

    public SimulatedDataAcquisitionSource(ScanConfig config) {
        this(config, 100); // 100 Hz PRF default
    }

    public SimulatedDataAcquisitionSource(ScanConfig config, int prfHz) {
        this.config = config;
        this.prfHz = Math.max(10, prfHz);
    }

    public void setStageCoordinate(double normX, double normY) {
        this.externalStageControl = true;
        this.currentStageXNorm = normX;
        this.currentStageYNorm = normY;
    }

    public void startLineScan(LineScanConfig.ScanAxis axis, double distanceMm, boolean positive, double speedMmPerSec) {
        this.externalStageControl = false;
        this.simulatingChamberScan = false;
        this.lineScanAxis = axis;
        this.lineScanFirstFrame = true;

        double spanMm = axis == LineScanConfig.ScanAxis.X ? 10.0 : 40.0;
        double normSpan = Math.clamp(distanceMm / spanMm * 2.0, 0.2, 2.0);

        if (axis == LineScanConfig.ScanAxis.X) {
            this.currentStageYNorm = 0.0;
            this.currentStageXNorm = positive ? -normSpan / 2.0 : normSpan / 2.0;
            this.lineScanTargetNorm = positive ? normSpan / 2.0 : -normSpan / 2.0;
        } else {
            this.currentStageXNorm = 0.0;
            this.currentStageYNorm = positive ? -normSpan / 2.0 : normSpan / 2.0;
            this.lineScanTargetNorm = positive ? normSpan / 2.0 : -normSpan / 2.0;
        }

        double mmPerFrame = speedMmPerSec / (double) prfHz;
        this.lineScanDeltaNorm = (mmPerFrame / spanMm * 2.0) * (positive ? 1.0 : -1.0);
        this.simulatingLineScan = true;
    }

    public void setAutonomousSweep(boolean autonomous) {
        this.externalStageControl = !autonomous;
    }

    @Override
    public synchronized void start(Consumer<byte[]> frameConsumer, Consumer<Throwable> errorHandler) {
        if (running.get()) return;

        running.set(true);
        simThread = new Thread(() -> runSimLoop(frameConsumer, errorHandler), "Ultrasound-SimulatedSource");
        simThread.setDaemon(true);
        simThread.start();
    }

    private void runSimLoop(Consumer<byte[]> frameConsumer, Consumer<Throwable> errorHandler) {
        final int numSamples = config.getNumSamples();
        final Random random = new Random(42);
        final long intervalMs = 1000 / prfHz;

        // Physical immersion tank depths
        final int substratePlateDepthSample = config.getSampleIndexForDepth(15.2);
        final int coinBaseDepthSample = config.getSampleIndexForDepth(14.0);

        double scanStep = 0.0;

        try {
            while (running.get()) {
                long start = System.currentTimeMillis();

                double probeX;
                double probeY;
                byte statusByte = 0x00;

                if (simulatingLineScan) {
                    statusByte |= (byte) 0x80; // isHardwareScanning
                    if (lineScanFirstFrame) {
                        statusByte |= (byte) 0x20; // isLineStart
                        lineScanFirstFrame = false;
                    }
                    if (lineScanAxis == LineScanConfig.ScanAxis.X) {
                        currentStageXNorm += lineScanDeltaNorm;
                        boolean reached = (lineScanDeltaNorm > 0 && currentStageXNorm >= lineScanTargetNorm)
                                || (lineScanDeltaNorm < 0 && currentStageXNorm <= lineScanTargetNorm);
                        if (reached) {
                            currentStageXNorm = lineScanTargetNorm;
                            simulatingLineScan = false;
                            statusByte |= (byte) 0x10; // isLineEnd
                            statusByte |= (byte) 0x40; // isScanComplete
                        }
                        if (lineScanDeltaNorm > 0) {
                            statusByte |= (byte) 0x08; // dirXPositive
                        }
                    } else {
                        currentStageYNorm += lineScanDeltaNorm;
                        boolean reached = (lineScanDeltaNorm > 0 && currentStageYNorm >= lineScanTargetNorm)
                                || (lineScanDeltaNorm < 0 && currentStageYNorm <= lineScanTargetNorm);
                        if (reached) {
                            currentStageYNorm = lineScanTargetNorm;
                            simulatingLineScan = false;
                            statusByte |= (byte) 0x10; // isLineEnd
                            statusByte |= (byte) 0x40; // isScanComplete
                        }
                    }
                    probeX = currentStageXNorm;
                    probeY = currentStageYNorm;
                } else if (simulatingChamberScan) {
                    currentStageXNorm += chamberDeltaXNorm * chamberScanDirX;
                    if (currentStageXNorm >= 1.0) {
                        currentStageXNorm = 1.0;
                        chamberScanDirX = -1.0;
                        currentStageYNorm += chamberDeltaYNorm;
                    } else if (currentStageXNorm <= -1.0) {
                        currentStageXNorm = -1.0;
                        chamberScanDirX = 1.0;
                        currentStageYNorm += chamberDeltaYNorm;
                    }
                    if (currentStageYNorm > 1.0) {
                        currentStageYNorm = 1.0;
                        simulatingChamberScan = false;
                    }
                    probeX = currentStageXNorm;
                    probeY = currentStageYNorm;
                } else if (externalStageControl) {
                    probeX = currentStageXNorm;
                    probeY = currentStageYNorm;
                } else {
                    scanStep += 0.015;
                    probeX = Math.sin(scanStep); // -1.0 to +1.0
                    probeY = 0.0;
                }

                byte[] frame = new byte[numSamples];

                // 1. Base acoustic speckle noise floor (values ~ 15 - 28)
                for (int i = 0; i < numSamples; i++) {
                    int noise = 15 + random.nextInt(14);
                    frame[i] = (byte) noise;
                }

                // 2. Transducer Main-Bang / Ring-down (first 40 samples near Z=0)
                for (int i = 0; i < 40; i++) {
                    double decay = Math.exp(-i / 10.0);
                    int ring = (int) (220.0 * decay * (1.0 + 0.3 * Math.sin(i * 1.5)));
                    int current = frame[i] & 0xFF;
                    frame[i] = (byte) Math.clamp(current + ring, 0, 255);
                }

                // 3. Acoustic Specimen Topography: Solid Metal Coin at Depth ~13mm
                // Transducer sweeps across immersion water tank.
                // Coin is positioned in the center of the scan area (normalized radius R ~ 0.68).
                double distToCenter = Math.sqrt(probeX * probeX + probeY * probeY);
                int echoCenterSample;
                int echoAmplitude;

                if (distToCenter < 0.68) {
                    // --- Metal Coin Specimen at Depth 13.0 mm-ish ---
                    double reliefMm = 0.0;
                    double angle = Math.atan2(probeY, probeX);

                    if (distToCenter > 0.54 && distToCenter <= 0.66) {
                        // Raised outer protective rim of coin (depth = 12.7 mm)
                        reliefMm = 0.30;
                    } else if (distToCenter > 0.66) {
                        // Outer beveled edge transition from rim down toward substrate plate
                        double frac = (distToCenter - 0.66) / 0.02; // 0.0 to 1.0
                        reliefMm = 0.30 * (1.0 - frac) - 3.0 * frac;
                    } else if (distToCenter > 0.36 && distToCenter <= 0.42) {
                        // Raised inner decorative ridge ring (depth = 12.85 mm)
                        reliefMm = 0.15;
                    } else if (distToCenter <= 0.30) {
                        // Embossed coin center medallion / profile emblem (depth = 12.75 mm)
                        double normR = distToCenter / 0.30;
                        double dome = Math.cos(normR * Math.PI * 0.5);
                        double crestTexture = 0.04 * Math.sin(6.0 * angle);
                        reliefMm = Math.clamp(0.25 * dome + crestTexture, 0.0, 0.30);
                    } else {
                        // Flat field / table of the metal coin (exact depth = 13.0 mm)
                        reliefMm = 0.0;
                    }

                    double depthMm = 94.4 - reliefMm;
                    echoCenterSample = config.getSampleIndexForDepth(depthMm);
                    echoAmplitude = 250; // Strong specular metal reflection

                    // High-amplitude specular ultrasound echo pulse packet from solid metal
                    for (int di = -8; di <= 8; di++) {
                        int idx = echoCenterSample + di;
                        if (idx >= 0 && idx < numSamples) {
                            double env = Math.exp(-(di * di) / 8.0);
                            int pulse = (int) (echoAmplitude * env * Math.cos(di * 1.25));
                            int current = frame[idx] & 0xFF;
                            frame[idx] = (byte) Math.clamp(current + Math.max(0, pulse), 0, 255);
                        }
                    }

                    // Total acoustic shadow beneath solid metal coin:
                    // Solid metal blocks >95% of acoustic transmission, casting deep shadow
                    int shadowEnd = Math.min(numSamples, echoCenterSample + 400);
                    for (int idx = echoCenterSample + 12; idx < shadowEnd; idx++) {
                        int current = frame[idx] & 0xFF;
                        frame[idx] = (byte) (current * 0.06);
                    }
                } else {
                    // --- Flat Tank Bottom / Container Floor at 126.8 mm ---
                    echoCenterSample = config.getSampleIndexForDepth(126.8);
                    echoAmplitude = 185;

                    for (int di = -6; di <= 6; di++) {
                        int idx = echoCenterSample + di;
                        if (idx >= 0 && idx < numSamples) {
                            double env = Math.exp(-(di * di) / 8.0);
                            int pulse = (int) (echoAmplitude * env * Math.cos(di * 1.2));
                            int current = frame[idx] & 0xFF;
                            frame[idx] = (byte) Math.clamp(current + Math.max(0, pulse), 0, 255);
                        }
                    }

                    // Acoustic shadow beneath substrate plate
                    int shadowEnd = Math.min(numSamples, echoCenterSample + 400);
                    for (int idx = echoCenterSample + 12; idx < shadowEnd; idx++) {
                        int current = frame[idx] & 0xFF;
                        frame[idx] = (byte) (current * 0.08);
                    }
                }

                byte[] payload = new byte[numSamples + 1];
                payload[0] = statusByte;
                System.arraycopy(frame, 0, payload, 1, numSamples);
                frameConsumer.accept(payload);

                long elapsed = System.currentTimeMillis() - start;
                long sleepTime = Math.max(1, intervalMs - elapsed);
                Thread.sleep(sleepTime);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            if (running.get()) errorHandler.accept(e);
        } finally {
            running.set(false);
        }
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        if (simThread != null) {
            simThread.interrupt();
            try {
                simThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            simThread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean sendCommand(byte[] commandBytes) {
        if (!FpgaCommandProtocol.isValidPacket(commandBytes)) {
            return false;
        }
        byte cmd = commandBytes[2];
        byte axis = commandBytes[3];
        byte dir = commandBytes[4];
        long param1 = (((long) (commandBytes[5] & 0xFF)) << 24) |
                      (((long) (commandBytes[6] & 0xFF)) << 16) |
                      (((long) (commandBytes[7] & 0xFF)) << 8) |
                      ((long) (commandBytes[8] & 0xFF));
        long param2 = (((long) (commandBytes[9] & 0xFF)) << 24) |
                      (((long) (commandBytes[10] & 0xFF)) << 16) |
                      (((long) (commandBytes[11] & 0xFF)) << 8) |
                      ((long) (commandBytes[12] & 0xFF));
        int param3 = (((commandBytes[13] & 0xFF)) << 8) |
                     ((commandBytes[14] & 0xFF));

        switch (cmd) {
            case FpgaCommandProtocol.CMD_HALT -> {
                simulatingChamberScan = false;
            }
            case FpgaCommandProtocol.CMD_JOG -> {
                simulatingChamberScan = false;
                externalStageControl = true;
                double delta = (param1 / 40000.0) * (dir == FpgaCommandProtocol.DIR_POSITIVE ? 1.0 : -1.0);
                if (axis == FpgaCommandProtocol.AXIS_X) {
                    currentStageXNorm = Math.clamp(currentStageXNorm + delta, -1.0, 1.0);
                } else if (axis == FpgaCommandProtocol.AXIS_Y) {
                    currentStageYNorm = Math.clamp(currentStageYNorm + delta, -1.0, 1.0);
                }
            }
            case FpgaCommandProtocol.CMD_CHAMBER_SCAN -> {
                simulatingChamberScan = true;
                externalStageControl = false;
                currentStageXNorm = -1.0;
                currentStageYNorm = -1.0;
                chamberScanDirX = 1.0;
                int ptsX = (param1 > 0 && axis > 0) ? (int) Math.max(10, param1 / axis) : 100;
                int ptsY = (param2 > 0 && param3 > 0) ? (int) Math.max(5, param2 / param3) : 40;
                chamberDeltaXNorm = 2.0 / ptsX;
                chamberDeltaYNorm = 2.0 / ptsY;
            }
            case FpgaCommandProtocol.CMD_ZERO_POSITION -> {
                currentStageXNorm = 0.0;
                currentStageYNorm = 0.0;
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public String getSourceName() {
        return "Synthetic Ultrasound Simulator (" + prfHz + " Hz)";
    }
}
