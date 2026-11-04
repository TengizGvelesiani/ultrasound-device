package com.ultrasound.bscan;

import com.ultrasound.bscan.model.ScanConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SerialProtocolTest {

    @Test
    void testFrameSlicingWithGarbageBytes() {
        byte[] syncHeader = ScanConfig.SYNC_HEADER;
        int payloadLen = 8; // simplified small test payload

        // Build a simulated serial stream:
        // [garbage: 3 bytes] [Header] [Payload1: 8 bytes] [garbage: 2 bytes] [Header] [Payload2: 8 bytes]
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(new byte[]{ 0x12, 0x34, 0x56 }, 0, 3);
        stream.write(syncHeader, 0, 4);
        stream.write(new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
        stream.write(new byte[]{ (byte) 0xEE, (byte) 0xFF }, 0, 2);
        stream.write(syncHeader, 0, 4);
        stream.write(new byte[]{ 10, 20, 30, 40, 50, 60, 70, 80 }, 0, 8);

        byte[] rawBuffer = stream.toByteArray();
        int bufferPos = rawBuffer.length;

        List<byte[]> extractedFrames = new ArrayList<>();

        while (true) {
            int headerIdx = -1;
            int limit = bufferPos - syncHeader.length;
            for (int i = 0; i <= limit; i++) {
                if (rawBuffer[i] == syncHeader[0] &&
                    rawBuffer[i + 1] == syncHeader[1] &&
                    rawBuffer[i + 2] == syncHeader[2] &&
                    rawBuffer[i + 3] == syncHeader[3]) {
                    headerIdx = i;
                    break;
                }
            }

            if (headerIdx == -1) break;

            if (bufferPos >= headerIdx + 4 + payloadLen) {
                byte[] payload = new byte[payloadLen];
                System.arraycopy(rawBuffer, headerIdx + 4, payload, 0, payloadLen);
                extractedFrames.add(payload);

                int consumed = headerIdx + 4 + payloadLen;
                int remaining = bufferPos - consumed;
                if (remaining > 0) {
                    System.arraycopy(rawBuffer, consumed, rawBuffer, 0, remaining);
                }
                bufferPos = remaining;
            } else {
                if (headerIdx > 0) {
                    int remaining = bufferPos - headerIdx;
                    System.arraycopy(rawBuffer, headerIdx, rawBuffer, 0, remaining);
                    bufferPos = remaining;
                }
                break;
            }
        }

        assertEquals(2, extractedFrames.size());
        assertArrayEquals(new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 }, extractedFrames.get(0));
        assertArrayEquals(new byte[]{ 10, 20, 30, 40, 50, 60, 70, 80 }, extractedFrames.get(1));
    }
}
