package com.ultrasound.bscan.io;

import com.fazecast.jSerialComm.SerialPort;
import com.ultrasound.bscan.model.ScanConfig;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * High-speed serial reader thread handling binary frame synchronization at 2 Mbaud.
 */
public final class SerialDataAcquisitionSource implements DataAcquisitionSource {

    private final String portName;
    private final int baudRate;
    private final int numSamples;
    private final byte[] syncHeader;

    private SerialPort serialPort;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SerialDataAcquisitionSource(String portName, ScanConfig config) {
        this.portName = portName;
        this.baudRate = config.getBaudRate();
        this.numSamples = config.getNumSamples();
        this.syncHeader = ScanConfig.SYNC_HEADER;
    }

    @Override
    public synchronized void start(Consumer<byte[]> frameConsumer, Consumer<Throwable> errorHandler) {
        if (running.get()) {
            return;
        }

        NativeLibraryBootstrap.prepareNativeLibraries();

        serialPort = SerialPort.getCommPort(portName);
        serialPort.setBaudRate(baudRate);
        serialPort.setNumDataBits(8);
        serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        serialPort.setParity(SerialPort.NO_PARITY);
        // Semi-blocking timeout so thread can periodically check running flag
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 50, 0);

        if (!serialPort.openPort()) {
            throw new IllegalStateException("Failed to open serial port " + portName + ". Check permissions or connection.");
        }

        // Flush any stale bytes
        serialPort.flushIOBuffers();
        running.set(true);

        readerThread = new Thread(() -> runReaderLoop(frameConsumer, errorHandler), "Ultrasound-SerialReader");
        readerThread.setDaemon(true);
        readerThread.setPriority(Thread.MAX_PRIORITY);
        readerThread.start();
    }

    private void runReaderLoop(Consumer<byte[]> frameConsumer, Consumer<Throwable> errorHandler) {
        final int headerLen = syncHeader.length;
        final int frameLen = headerLen + numSamples;

        byte[] rawBuffer = new byte[65536];
        int bufferPos = 0;
        byte[] readChunk = new byte[8192];

        try {
            while (running.get() && serialPort.isOpen()) {
                int bytesAvailable = serialPort.bytesAvailable();
                if (bytesAvailable > 0) {
                    int bytesToRead = Math.min(bytesAvailable, readChunk.length);
                    int bytesRead = serialPort.readBytes(readChunk, bytesToRead);
                    if (bytesRead > 0) {
                        // Ensure capacity in rawBuffer
                        if (bufferPos + bytesRead > rawBuffer.length) {
                            rawBuffer = Arrays.copyOf(rawBuffer, Math.max(rawBuffer.length * 2, bufferPos + bytesRead));
                        }
                        System.arraycopy(readChunk, 0, rawBuffer, bufferPos, bytesRead);
                        bufferPos += bytesRead;
                    }
                } else {
                    // Small sleep if no data available to yield CPU
                    Thread.sleep(1);
                    continue;
                }

                // Process frames in buffer
                while (true) {
                    int headerIdx = findSyncHeader(rawBuffer, bufferPos);
                    if (headerIdx == -1) {
                        // If header not found, retain last (headerLen - 1) bytes for partial match
                        if (bufferPos > headerLen - 1) {
                            int keep = headerLen - 1;
                            System.arraycopy(rawBuffer, bufferPos - keep, rawBuffer, 0, keep);
                            bufferPos = keep;
                        }
                        break;
                    }

                    if (bufferPos >= headerIdx + frameLen) {
                        // Complete packet found
                        byte[] payload = new byte[numSamples];
                        System.arraycopy(rawBuffer, headerIdx + headerLen, payload, 0, numSamples);
                        frameConsumer.accept(payload);

                        // Shift remaining buffer
                        int consumed = headerIdx + frameLen;
                        int remaining = bufferPos - consumed;
                        if (remaining > 0) {
                            System.arraycopy(rawBuffer, consumed, rawBuffer, 0, remaining);
                        }
                        bufferPos = remaining;
                    } else {
                        // Incomplete packet: discard garbage preceding the header and wait for more data
                        if (headerIdx > 0) {
                            int remaining = bufferPos - headerIdx;
                            System.arraycopy(rawBuffer, headerIdx, rawBuffer, 0, remaining);
                            bufferPos = remaining;
                        }
                        break;
                    }
                }
            }
        } catch (InterruptedException ignored) {
            // normal termination
        } catch (Exception e) {
            if (running.get()) {
                errorHandler.accept(e);
            }
        } finally {
            closePortQuietly();
            running.set(false);
        }
    }

    private int findSyncHeader(byte[] buffer, int length) {
        int limit = length - syncHeader.length;
        for (int i = 0; i <= limit; i++) {
            if (buffer[i] == syncHeader[0] &&
                buffer[i + 1] == syncHeader[1] &&
                buffer[i + 2] == syncHeader[2] &&
                buffer[i + 3] == syncHeader[3]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }
        closePortQuietly();
    }

    private void closePortQuietly() {
        if (serialPort != null && serialPort.isOpen()) {
            try {
                serialPort.closePort();
            } catch (Exception ignored) {
            }
            serialPort = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean sendCommand(byte[] commandBytes) {
        if (commandBytes == null || commandBytes.length == 0) {
            return false;
        }
        SerialPort port = this.serialPort;
        if (port != null && port.isOpen()) {
            try {
                int written = port.writeBytes(commandBytes, commandBytes.length);
                return written == commandBytes.length;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public String getSourceName() {
        return "Serial (" + portName + " @ " + baudRate + " baud)";
    }
}
