package com.ultrasound.bscan.io;

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
    private volatile double chamberDeltaYNorm = 0.05;

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

                if (simulatingChamberScan) {
                    currentStageXNorm += 0.02 * chamberScanDirX;
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

                // 3. Acoustic Specimen Topography
                // Metal coin specimen resting on top of a flat metal substrate plate (depth 38.0 mm).
                // Coin diameter ~ 26 mm on 50x50 mm stage -> normalized radius R ~ 0.52.
                double distToCenter = Math.sqrt(probeX * probeX + probeY * probeY);
                int echoCenterSample;
                int echoAmplitude;

                if (distToCenter < 0.52) {
                    // --- Metal Coin Specimen ---
                    // Relief features measured in mm above the coin body (35.0 mm):
                    double reliefMm = 0.0;
                    double angle = Math.atan2(probeY, probeX);
                    // 5-pointed star emblem in center (radius ~ 0.18)
                    double starR = 0.13 + 0.05 * Math.cos(5.0 * angle);

                    if (distToCenter < starR) {
                        reliefMm = 1.0; // Raised star center emblem (depth = 13.0 mm)
                    } else if (distToCenter > 0.40 && distToCenter < 0.50) {
                        reliefMm = 0.6; // Raised outer protective rim (depth = 13.4 mm)
                    } else if (distToCenter >= 0.50) {
                        // Smooth bevel transition from outer rim (13.4 mm) down to substrate plate (15.2 mm)
                        double frac = (distToCenter - 0.50) / 0.02; // 0.0 to 1.0
                        reliefMm = 0.6 * (1.0 - frac) - 1.2 * frac;
                    } else {
                        reliefMm = 0.0; // Flat field of the coin (depth = 14.0 mm)
                    }

                    double depthMm = 14.0 - reliefMm;
                    echoCenterSample = config.getSampleIndexForDepth(depthMm);
                    echoAmplitude = 245;

                    // Strong specular ultrasound echo pulse packet from metal surface
                    for (int di = -8; di <= 8; di++) {
                        int idx = echoCenterSample + di;
                        if (idx >= 0 && idx < numSamples) {
                            double env = Math.exp(-(di * di) / 8.0);
                            int pulse = (int) (echoAmplitude * env * Math.cos(di * 1.2));
                            int current = frame[idx] & 0xFF;
                            frame[idx] = (byte) Math.clamp(current + Math.max(0, pulse), 0, 255);
                        }
                    }

                    // Total acoustic shadow beneath the metal coin
                    int shadowEnd = Math.min(numSamples, echoCenterSample + 400);
                    for (int idx = echoCenterSample + 12; idx < shadowEnd; idx++) {
                        int current = frame[idx] & 0xFF;
                        frame[idx] = (byte) (current * 0.12);
                    }
                } else {
                    // --- Flat Metal Substrate / Stage Plate at 38.0 mm ---
                    echoCenterSample = substratePlateDepthSample;
                    echoAmplitude = 195;

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
                        frame[idx] = (byte) (current * 0.12);
                    }
                }

                frameConsumer.accept(frame);

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
                if (param2 > 0 && param3 > 0) {
                    chamberDeltaYNorm = Math.max(0.01, 2.0 * ((double) param3 / (double) param2));
                } else {
                    chamberDeltaYNorm = 0.05;
                }
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
