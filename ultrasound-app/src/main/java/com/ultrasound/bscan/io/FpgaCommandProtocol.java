package com.ultrasound.bscan.io;

import java.util.Objects;

/**
 * Binary command communication protocol between the PC host application and the FPGA controller.
 * <p>
 * Packet Format (16 Bytes Fixed Length):
 * <ul>
 *   <li>Byte 0:  0xA5 (SYNC 1)</li>
 *   <li>Byte 1:  0x5A (SYNC 2)</li>
 *   <li>Byte 2:  CMD  (HALT=0x00, JOG=0x01, CHAMBER_SCAN=0x02, SET_SPEED=0x03, ZERO=0x04)</li>
 *   <li>Byte 3:  AXIS (NONE=0x00, X=0x01, Y=0x02 [Dual Y1+Y2 Locked], or Delta X microsteps)</li>
 *   <li>Byte 4:  DIR  (For JOG: Bit 0 = DIR; For CHAMBER_SCAN: Bit 0 = Start X Dir, Bit 1 = Advance Y Dir)</li>
 *   <li>Bytes 5..8:   PARAM 1 (32-bit unsigned Big-Endian): Jog Steps / Chamber X Steps</li>
 *   <li>Bytes 9..12:  PARAM 2 (32-bit unsigned Big-Endian): Speed Half-Period / Chamber Y Steps</li>
 *   <li>Bytes 13..14: PARAM 3 (16-bit unsigned Big-Endian): Chamber Delta Y Steps</li>
 *   <li>Byte 15: CHECKSUM (XOR sum of bytes 2..14)</li>
 * </ul>
 */
public final class FpgaCommandProtocol {

    public static final byte SYNC_1 = (byte) 0xA5;
    public static final byte SYNC_2 = (byte) 0x5A;
    public static final int PACKET_LENGTH = 16;

    // Commands
    public static final byte CMD_HALT = 0x00;
    public static final byte CMD_JOG = 0x01;
    public static final byte CMD_CHAMBER_SCAN = 0x02;
    public static final byte CMD_SET_SPEED = 0x03;
    public static final byte CMD_ZERO_POSITION = 0x04;

    // Axes
    public static final byte AXIS_NONE = 0x00;
    public static final byte AXIS_X = 0x01;
    public static final byte AXIS_Y = 0x02; // Dual Y1 and Y2 bottom steppers

    // Direction
    public static final byte DIR_NEGATIVE = 0x00;
    public static final byte DIR_POSITIVE = 0x01;

    // Default FPGA clock frequency = 100 MHz
    public static final long FPGA_CLOCK_HZ = 100_000_000L;
    public static final long DEFAULT_STEP_HALF_PERIOD = 31_250L; // 1,600 Hz step rate

    private FpgaCommandProtocol() {
    }

    /**
     * Builds an immediate emergency HALT packet to stop all motors and abort any scan.
     */
    public static byte[] buildHaltPacket() {
        return buildPacket(CMD_HALT, AXIS_NONE, DIR_NEGATIVE, 0L, 0L, 0);
    }

    /**
     * Builds a manual jog packet for a single axis.
     *
     * @param axis             AXIS_X or AXIS_Y (both bottom steppers)
     * @param positive         true for positive (+), false for negative (-)
     * @param steps            number of microsteps to execute
     * @param halfPeriodCycles clock cycles per step half-period (0 to use FPGA default)
     */
    public static byte[] buildJogPacket(byte axis, boolean positive, long steps, long halfPeriodCycles) {
        byte dir = positive ? DIR_POSITIVE : DIR_NEGATIVE;
        return buildPacket(CMD_JOG, axis, dir, steps, halfPeriodCycles, 0);
    }

    /**
     * Builds a one-click autonomous full chamber raster scan packet.
     *
     * @param totalXSteps      total microsteps across chamber width (X)
     * @param totalYSteps      total microsteps along chamber length (Y)
     * @param deltaYSteps      microsteps to advance Y after each X sweep
     * @param halfPeriodCycles clock cycles per step half-period
     */
    public static byte[] buildChamberScanPacket(long totalXSteps, long totalYSteps, int deltaYSteps, long halfPeriodCycles) {
        return buildChamberScanPacket(totalXSteps, totalYSteps, deltaYSteps, 0, true, true, halfPeriodCycles);
    }

    /**
     * Builds a one-click autonomous full chamber raster scan packet with explicit position-trigger step spacing.
     *
     * @param totalXSteps      total microsteps across chamber width (X)
     * @param totalYSteps      total microsteps along chamber length (Y)
     * @param deltaYSteps      microsteps to advance Y after each X sweep
     * @param deltaXSteps      microsteps per position-triggered A-scan pulse (e.g. 40 microsteps = 0.1 mm)
     * @param halfPeriodCycles clock cycles per step half-period
     */
    public static byte[] buildChamberScanPacket(long totalXSteps, long totalYSteps, int deltaYSteps, int deltaXSteps, long halfPeriodCycles) {
        return buildChamberScanPacket(totalXSteps, totalYSteps, deltaYSteps, deltaXSteps, true, true, halfPeriodCycles);
    }

    /**
     * Builds a one-click autonomous full chamber raster scan packet with explicit position-trigger step spacing
     * and configurable initial raster start directions.
     *
     * @param totalXSteps      total microsteps across chamber width (X)
     * @param totalYSteps      total microsteps along chamber length (Y)
     * @param deltaYSteps      microsteps to advance Y after each X sweep
     * @param deltaXSteps      microsteps per position-triggered A-scan pulse (e.g. 40 microsteps = 0.1 mm)
     * @param startPositiveX   initial X sweep direction: true for +X (Right), false for -X (Left)
     * @param advancePositiveY Y advance direction: true for +Y (Forward), false for -Y (Backward)
     * @param halfPeriodCycles clock cycles per step half-period
     */
    public static byte[] buildChamberScanPacket(long totalXSteps, long totalYSteps, int deltaYSteps, int deltaXSteps,
                                                boolean startPositiveX, boolean advancePositiveY, long halfPeriodCycles) {
        byte axisByte = deltaXSteps > 0 ? (byte) Math.clamp(deltaXSteps, 1, 255) : AXIS_NONE;
        byte dirByte = (byte) ((startPositiveX ? 0x01 : 0x00) | (advancePositiveY ? 0x02 : 0x00));
        return buildPacket(CMD_CHAMBER_SCAN, axisByte, dirByte, totalXSteps, totalYSteps, deltaYSteps);
    }

    /**
     * Builds a packet configuring the default stepper speed (half-period cycles).
     */
    public static byte[] buildSetSpeedPacket(long halfPeriodCycles) {
        return buildPacket(CMD_SET_SPEED, AXIS_NONE, DIR_NEGATIVE, 0L, halfPeriodCycles, 0);
    }

    /**
     * Builds a packet setting the current mechanical position as zero/home.
     */
    public static byte[] buildZeroPositionPacket() {
        return buildPacket(CMD_ZERO_POSITION, AXIS_NONE, DIR_NEGATIVE, 0L, 0L, 0);
    }

    /**
     * Converts a desired step frequency in Hz to FPGA 100 MHz clock half-period cycles.
     */
    public static long calculateHalfPeriodCycles(double stepFrequencyHz) {
        if (stepFrequencyHz <= 0.0) {
            return DEFAULT_STEP_HALF_PERIOD;
        }
        long cycles = Math.round((double) FPGA_CLOCK_HZ / (2.0 * stepFrequencyHz));
        return Math.clamp(cycles, 500L, 50_000_000L); // Limit between 1 Hz and 100 kHz
    }

    /**
     * Low-level builder creating and checksumming the 16-byte command packet.
     */
    public static byte[] buildPacket(byte cmd, byte axis, byte dir, long param1, long param2, int param3) {
        byte[] packet = new byte[PACKET_LENGTH];
        packet[0] = SYNC_1;
        packet[1] = SYNC_2;
        packet[2] = cmd;
        packet[3] = axis;
        packet[4] = dir;

        // Param 1 (32-bit unsigned Big-Endian)
        packet[5] = (byte) ((param1 >>> 24) & 0xFF);
        packet[6] = (byte) ((param1 >>> 16) & 0xFF);
        packet[7] = (byte) ((param1 >>> 8) & 0xFF);
        packet[8] = (byte) (param1 & 0xFF);

        // Param 2 (32-bit unsigned Big-Endian)
        packet[9] = (byte) ((param2 >>> 24) & 0xFF);
        packet[10] = (byte) ((param2 >>> 16) & 0xFF);
        packet[11] = (byte) ((param2 >>> 8) & 0xFF);
        packet[12] = (byte) (param2 & 0xFF);

        // Param 3 (16-bit unsigned Big-Endian)
        packet[13] = (byte) ((param3 >>> 8) & 0xFF);
        packet[14] = (byte) (param3 & 0xFF);

        // Checksum = XOR sum of bytes 2..14
        byte checksum = 0;
        for (int i = 2; i <= 14; i++) {
            checksum ^= packet[i];
        }
        packet[15] = checksum;

        return packet;
    }

    /**
     * Validates if a 16-byte packet has correct sync headers and matching checksum.
     */
    public static boolean isValidPacket(byte[] packet) {
        if (packet == null || packet.length != PACKET_LENGTH) {
            return false;
        }
        if (packet[0] != SYNC_1 || packet[1] != SYNC_2) {
            return false;
        }
        byte checksum = 0;
        for (int i = 2; i <= 14; i++) {
            checksum ^= packet[i];
        }
        return checksum == packet[15];
    }
}
