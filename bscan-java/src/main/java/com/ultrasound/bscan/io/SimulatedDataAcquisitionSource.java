package com.ultrasound.bscan.io;

import com.ultrasound.bscan.model.ScanConfig;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Synthetic ultrasound signal generator simulating acoustic reflections
 * from a water tank, a metal coin target, and transducer translation.
 */
public final class SimulatedDataAcquisitionSource implements DataAcquisitionSource {

    private final ScanConfig config;
    private final int prfHz;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread simThread;

    public SimulatedDataAcquisitionSource(ScanConfig config) {
        this(config, 100); // 100 Hz PRF default
    }

    public SimulatedDataAcquisitionSource(ScanConfig config, int prfHz) {
        this.config = config;
        this.prfHz = Math.max(10, prfHz);
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

        // Target geometry in sample coordinates
        // Coin center around depth 35 mm: sample = 35 / 0.148 ~ 236
        final int coinDepthSample = config.getSampleIndexForDepth(35.0);
        // Tank bottom around depth 110 mm: sample = 110 / 0.148 ~ 743
        final int tankBottomSample = config.getSampleIndexForDepth(110.0);

        double scanStep = 0.0;

        try {
            while (running.get()) {
                long start = System.currentTimeMillis();

                // Synthetic scanhead position oscillates back and forth
                scanStep += 0.015;
                double probeX = Math.sin(scanStep); // -1.0 to +1.0

                byte[] frame = new byte[numSamples];

                // 1. Base acoustic speckle noise floor (values ~ 15 - 30)
                for (int i = 0; i < numSamples; i++) {
                    int noise = 15 + random.nextInt(18);
                    frame[i] = (byte) noise;
                }

                // 2. Transducer Main-Bang / Ring-down (first 40 samples)
                for (int i = 0; i < 40; i++) {
                    double decay = Math.exp(-i / 10.0);
                    int ring = (int) (220.0 * decay * (1.0 + 0.3 * Math.sin(i * 1.5)));
                    int current = frame[i] & 0xFF;
                    frame[i] = (byte) Math.clamp(current + ring, 0, 255);
                }

                // 3. Metal Coin Reflection (present when probe is near center: |probeX| < 0.45)
                double distToCoin = Math.abs(probeX);
                if (distToCoin < 0.45) {
                    double coinProfile = Math.cos((distToCoin / 0.45) * (Math.PI / 2.0));
                    int coinEchoCenter = coinDepthSample - (int) (12.0 * (1.0 - coinProfile));
                    int coinAmp = (int) (240.0 * coinProfile);

                    // Echo pulse envelope (Gaussian-like packet)
                    for (int di = -8; di <= 8; di++) {
                        int idx = coinEchoCenter + di;
                        if (idx >= 0 && idx < numSamples) {
                            double env = Math.exp(-(di * di) / 10.0);
                            int pulse = (int) (coinAmp * env * Math.cos(di * 1.2));
                            int current = frame[idx] & 0xFF;
                            frame[idx] = (byte) Math.clamp(current + Math.max(0, pulse), 0, 255);
                        }
                    }

                    // Acoustic shadow beneath the coin (sound is blocked by metal)
                    int shadowEnd = Math.min(numSamples, coinEchoCenter + 350);
                    for (int idx = coinEchoCenter + 12; idx < shadowEnd; idx++) {
                        int current = frame[idx] & 0xFF;
                        frame[idx] = (byte) (current * 0.15); // heavily attenuated
                    }
                }

                // 4. Tank Bottom Reflection
                for (int di = -6; di <= 6; di++) {
                    int idx = tankBottomSample + di;
                    if (idx >= 0 && idx < numSamples) {
                        double env = Math.exp(-(di * di) / 8.0);
                        int pulse = (int) (180.0 * env);
                        int current = frame[idx] & 0xFF;
                        frame[idx] = (byte) Math.clamp(current + pulse, 0, 255);
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
    public String getSourceName() {
        return "Synthetic Ultrasound Simulator (" + prfHz + " Hz)";
    }
}
