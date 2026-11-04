package com.ultrasound.bscan.controller;

import com.ultrasound.bscan.io.DataAcquisitionSource;
import com.ultrasound.bscan.io.FpgaCommandProtocol;
import com.ultrasound.bscan.model.MotorSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MotorControllerServiceTest {

    private MotorSettings settings;
    private MotorControllerService service;
    private MockAcquisitionSource mockSource;

    static class MockAcquisitionSource implements DataAcquisitionSource {
        final List<byte[]> sentPackets = new ArrayList<>();

        @Override
        public void start(Consumer<byte[]> frameConsumer, Consumer<Throwable> errorHandler) {}

        @Override
        public void stop() {}

        @Override
        public boolean isRunning() { return true; }

        @Override
        public String getSourceName() { return "Mock"; }

        @Override
        public boolean sendCommand(byte[] commandBytes) {
            sentPackets.add(commandBytes);
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        settings = new MotorSettings();
        settings.setStepsPerMmX(400.0);
        settings.setStepsPerMmY(400.0);
        settings.setChamberWidthX(100.0);
        settings.setChamberLengthY(100.0);
        settings.setLineSpacingY(1.0);
        settings.setSpeedMmPerSec(10.0);

        service = new MotorControllerService(settings);
        mockSource = new MockAcquisitionSource();
        service.setAcquisitionSource(mockSource);
    }

    @Test
    @DisplayName("Jog X moves X axis by configured steps and tracks coordinates")
    void testJogX() {
        // Initial position is 0
        assertEquals(0.0, service.getCurrentPositionXMm(), 0.001);
        assertEquals(0L, service.getCurrentStepX());

        // Jog X +10 mm (4,000 steps)
        service.jogX(true, 10.0);

        assertEquals(10.0, service.getCurrentPositionXMm(), 0.001);
        assertEquals(4000L, service.getCurrentStepX());
        assertEquals(1, mockSource.sentPackets.size());

        byte[] packet = mockSource.sentPackets.getFirst();
        assertTrue(FpgaCommandProtocol.isValidPacket(packet));
        assertEquals(FpgaCommandProtocol.CMD_JOG, packet[2]);
        assertEquals(FpgaCommandProtocol.AXIS_X, packet[3]);
        assertEquals(FpgaCommandProtocol.DIR_POSITIVE, packet[4]);

        // Jog X -5 mm
        service.jogX(false, 5.0);
        assertEquals(5.0, service.getCurrentPositionXMm(), 0.001);
        assertEquals(2000L, service.getCurrentStepX());
    }

    @Test
    @DisplayName("Jog Y commands AXIS_Y strictly locking both bottom motors in identical direction")
    void testJogYDualMotorsLocked() {
        assertEquals(0.0, service.getCurrentPositionYMm(), 0.001);

        // Jog Y +20 mm
        service.jogY(true, 20.0);

        assertEquals(20.0, service.getCurrentPositionYMm(), 0.001);
        assertEquals(8000L, service.getCurrentStepY());

        byte[] packet = mockSource.sentPackets.getFirst();
        assertTrue(FpgaCommandProtocol.isValidPacket(packet));
        assertEquals(FpgaCommandProtocol.CMD_JOG, packet[2]);
        assertEquals(FpgaCommandProtocol.AXIS_Y, packet[3]); // Dual bottom motors locked
        assertEquals(FpgaCommandProtocol.DIR_POSITIVE, packet[4]);

        // Jog Y -10 mm
        service.jogY(false, 10.0);
        assertEquals(10.0, service.getCurrentPositionYMm(), 0.001);
        assertEquals(4000L, service.getCurrentStepY());
    }

    @Test
    @DisplayName("Zero position resets coordinates to (0, 0)")
    void testZeroPosition() {
        service.jogX(true, 15.0);
        service.jogY(true, 30.0);
        assertNotEquals(0L, service.getCurrentStepX());
        assertNotEquals(0L, service.getCurrentStepY());

        service.zeroPosition();
        assertEquals(0.0, service.getCurrentPositionXMm(), 0.001);
        assertEquals(0.0, service.getCurrentPositionYMm(), 0.001);
        assertEquals(0L, service.getCurrentStepX());
        assertEquals(0L, service.getCurrentStepY());
    }

    @Test
    @DisplayName("Halt cancels active scan and transmits emergency halt command")
    void testHalt() {
        service.halt();

        assertFalse(service.isChamberScanning());
        assertEquals(1, mockSource.sentPackets.size());

        byte[] packet = mockSource.sentPackets.getFirst();
        assertTrue(FpgaCommandProtocol.isValidPacket(packet));
        assertEquals(FpgaCommandProtocol.CMD_HALT, packet[2]);
    }

    @Test
    @DisplayName("One-click chamber scan transmits chamber configuration packet")
    void testTriggerChamberScan() {
        service.triggerChamberScan();

        assertTrue(service.isChamberScanning());
        assertEquals(2, mockSource.sentPackets.size());

        // Speed sync packet verified
        byte[] speedPacket = mockSource.sentPackets.get(0);
        assertTrue(FpgaCommandProtocol.isValidPacket(speedPacket));
        assertEquals(FpgaCommandProtocol.CMD_SET_SPEED, speedPacket[2]);

        // Chamber scan packet verified
        byte[] scanPacket = mockSource.sentPackets.get(1);
        assertTrue(FpgaCommandProtocol.isValidPacket(scanPacket));
        assertEquals(FpgaCommandProtocol.CMD_CHAMBER_SCAN, scanPacket[2]);
        assertEquals(40, scanPacket[3]); // 400 stp/mm * 0.1 mm = 40 microsteps position-trigger spacing

        // Halt scan
        service.halt();
        assertFalse(service.isChamberScanning());
    }
}
