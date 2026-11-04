package com.ultrasound.bscan.io;

import java.util.function.Consumer;

/**
 * Interface representing a source of ultrasound A-scan frames.
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
}
