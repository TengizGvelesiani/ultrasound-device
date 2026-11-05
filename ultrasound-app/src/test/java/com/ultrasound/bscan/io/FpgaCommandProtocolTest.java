package com.ultrasound.bscan.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FpgaCommandProtocolTest {

    @Test
    @DisplayName("Halt packet has correct header, command, length and valid checksum")
    void testHaltPacket() {
        byte[] packet = FpgaCommandProtocol.buildHaltPacket();
        assertNotNull(packet);
        assertEquals(16, packet.length);
        assertEquals(FpgaCommandProtocol.SYNC_1, packet[0]);
        assertEquals(FpgaCommandProtocol.SYNC_2, packet[1]);
        assertEquals(FpgaCommandProtocol.CMD_HALT, packet[2]);
        assertTrue(FpgaCommandProtocol.isValidPacket(packet));
    }

    @Test
    @DisplayName("Jog packet encodes axis, direction, steps and speed half-period correctly")
    void testJogPacket() {
        // Jog X positive by 4,000 steps (10 mm @ 400 stp/mm) at 31,250 cycles
        byte[] packetX = FpgaCommandProtocol.buildJogPacket(
                FpgaCommandProtocol.AXIS_X,
                true,
                4000L,
                31250L
        );
        assertTrue(FpgaCommandProtocol.isValidPacket(packetX));
        assertEquals(FpgaCommandProtocol.CMD_JOG, packetX[2]);
        assertEquals(FpgaCommandProtocol.AXIS_X, packetX[3]);
        assertEquals(FpgaCommandProtocol.DIR_POSITIVE, packetX[4]);

        long param1 = (((long) (packetX[5] & 0xFF)) << 24) |
                      (((long) (packetX[6] & 0xFF)) << 16) |
                      (((long) (packetX[7] & 0xFF)) << 8) |
                      ((long) (packetX[8] & 0xFF));
        assertEquals(4000L, param1);

        long param2 = (((long) (packetX[9] & 0xFF)) << 24) |
                      (((long) (packetX[10] & 0xFF)) << 16) |
                      (((long) (packetX[11] & 0xFF)) << 8) |
                      ((long) (packetX[12] & 0xFF));
        assertEquals(31250L, param2);

        // Jog Y negative by 8,000 steps (Dual bottom motors locked)
        byte[] packetY = FpgaCommandProtocol.buildJogPacket(
                FpgaCommandProtocol.AXIS_Y,
                false,
                8000L,
                25000L
        );
        assertTrue(FpgaCommandProtocol.isValidPacket(packetY));
        assertEquals(FpgaCommandProtocol.AXIS_Y, packetY[3]);
        assertEquals(FpgaCommandProtocol.DIR_NEGATIVE, packetY[4]);
    }

    @Test
    @DisplayName("Chamber scan packet encodes chamber dimensions and line increments correctly")
    void testChamberScanPacket() {
        long totalX = 40000L; // 100 mm @ 400 stp/mm
        long totalY = 60000L; // 150 mm @ 400 stp/mm
        int deltaY = 400;     // 1 mm @ 400 stp/mm
        long halfPeriod = 20000L;

        byte[] packet = FpgaCommandProtocol.buildChamberScanPacket(totalX, totalY, deltaY, halfPeriod);
        assertTrue(FpgaCommandProtocol.isValidPacket(packet));
        assertEquals(FpgaCommandProtocol.CMD_CHAMBER_SCAN, packet[2]);

        long p1 = (((long) (packet[5] & 0xFF)) << 24) |
                  (((long) (packet[6] & 0xFF)) << 16) |
                  (((long) (packet[7] & 0xFF)) << 8) |
                  ((long) (packet[8] & 0xFF));
        assertEquals(totalX, p1);

        long p2 = (((long) (packet[9] & 0xFF)) << 24) |
                  (((long) (packet[10] & 0xFF)) << 16) |
                  (((long) (packet[11] & 0xFF)) << 8) |
                  ((long) (packet[12] & 0xFF));
        assertEquals(totalY, p2);

        int p3 = (((packet[13] & 0xFF)) << 8) |
                 ((packet[14] & 0xFF));
        assertEquals(deltaY, p3);
    }

    @Test
    @DisplayName("Position-triggered chamber scan packet encodes deltaX steps in axis field")
    void testPositionTriggeredChamberScanPacket() {
        long totalX = 40000L;
        long totalY = 40000L;
        int deltaY = 400;
        int deltaX = 40; // 40 microsteps = 0.1 mm
        long halfPeriod = 25000L;

        byte[] packet = FpgaCommandProtocol.buildChamberScanPacket(totalX, totalY, deltaY, deltaX, halfPeriod);
        assertTrue(FpgaCommandProtocol.isValidPacket(packet));
        assertEquals(FpgaCommandProtocol.CMD_CHAMBER_SCAN, packet[2]);
        assertEquals(40, packet[3]); // Position-triggered step spacing verified
    }

    @Test
    @DisplayName("Chamber scan packet correctly encodes user-selected initial directions in Byte 4")
    void testChamberScanDirections() {
        long totalX = 40000L;
        long totalY = 40000L;
        int deltaY = 400;
        int deltaX = 40;
        long halfPeriod = 25000L;

        // +X and +Y -> 0x03
        byte[] p1 = FpgaCommandProtocol.buildChamberScanPacket(totalX, totalY, deltaY, deltaX, true, true, halfPeriod);
        assertTrue(FpgaCommandProtocol.isValidPacket(p1));
        assertEquals(0x03, p1[4]);

        // +X and -Y -> 0x01
        byte[] p2 = FpgaCommandProtocol.buildChamberScanPacket(totalX, totalY, deltaY, deltaX, true, false, halfPeriod);
        assertTrue(FpgaCommandProtocol.isValidPacket(p2));
        assertEquals(0x01, p2[4]);

        // -X and +Y -> 0x02
        byte[] p3 = FpgaCommandProtocol.buildChamberScanPacket(totalX, totalY, deltaY, deltaX, false, true, halfPeriod);
        assertTrue(FpgaCommandProtocol.isValidPacket(p3));
        assertEquals(0x02, p3[4]);

        // -X and -Y -> 0x00
        byte[] p4 = FpgaCommandProtocol.buildChamberScanPacket(totalX, totalY, deltaY, deltaX, false, false, halfPeriod);
        assertTrue(FpgaCommandProtocol.isValidPacket(p4));
        assertEquals(0x00, p4[4]);
    }

    @Test
    @DisplayName("Corrupted packet or invalid checksum is rejected")
    void testCorruptedPacket() {
        byte[] packet = FpgaCommandProtocol.buildHaltPacket();
        assertTrue(FpgaCommandProtocol.isValidPacket(packet));

        // Corrupt a byte
        packet[5] = (byte) (packet[5] ^ 0xFF);
        assertFalse(FpgaCommandProtocol.isValidPacket(packet));

        // Corrupt sync header
        packet = FpgaCommandProtocol.buildHaltPacket();
        packet[0] = 0x00;
        assertFalse(FpgaCommandProtocol.isValidPacket(packet));

        // Null or bad length
        assertFalse(FpgaCommandProtocol.isValidPacket(null));
        assertFalse(FpgaCommandProtocol.isValidPacket(new byte[15]));
        assertFalse(FpgaCommandProtocol.isValidPacket(new byte[17]));
    }

    @Test
    @DisplayName("Step frequency to half period cycle calculations are accurate")
    void testHalfPeriodCalculations() {
        // 1600 Hz @ 100 MHz clock -> 100_000_000 / (2 * 1600) = 31,250
        assertEquals(31250L, FpgaCommandProtocol.calculateHalfPeriodCycles(1600.0));

        // 1000 Hz -> 50,000 cycles
        assertEquals(50000L, FpgaCommandProtocol.calculateHalfPeriodCycles(1000.0));

        // Default fallback on 0 or negative
        assertEquals(FpgaCommandProtocol.DEFAULT_STEP_HALF_PERIOD, FpgaCommandProtocol.calculateHalfPeriodCycles(0.0));
    }
}
