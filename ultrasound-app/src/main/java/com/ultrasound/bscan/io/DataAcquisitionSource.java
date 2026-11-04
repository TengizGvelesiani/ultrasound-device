package com.ultrasound.bscan.io;

import java.util.function.Consumer;

/**
 * Interface representing a source of ultrasound A-scan frames,
 * with bidirectional command dispatching to the hardware controller.
 */
public interface DataAcquisitionSource {

    /**
     * Starts receiving data. The frameConsumer is invoked for each 1024-byte payload.
     */
    void start(Consumer<byte[]> frameConsumer, Consumer<Throwable> errorHandler);

    /**
     * Stops receiving data and releases any hardware resources.
     */
    void stop();

    /**
     * Checks if the source is currently active.
     */
    boolean isRunning();

    /**
     * Returns a human-readable name of this source.
     */
    String getSourceName();

    /**
     * Transmits a binary command packet to the FPGA controller.
     *
     * @param commandBytes raw byte array conforming to FpgaCommandProtocol
     * @return true if successfully transmitted, false otherwise
     */
    default boolean sendCommand(byte[] commandBytes) {
        return false;
    }
}
