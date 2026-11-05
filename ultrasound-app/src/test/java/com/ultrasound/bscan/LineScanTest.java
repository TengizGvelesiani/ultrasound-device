package com.ultrasound.bscan;

import com.ultrasound.bscan.controller.MotorControllerService;
import com.ultrasound.bscan.io.DataAcquisitionSource;
import com.ultrasound.bscan.io.FpgaCommandProtocol;
import com.ultrasound.bscan.model.LineScanConfig;
import com.ultrasound.bscan.model.MotorSettings;
import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class LineScanTest {

    @Test
    @DisplayName("LineScanConfig computes accurate line counts and geometry")
    public void testLineScanConfigGeometry() {
        LineScanConfig cfg = new LineScanConfig();
        assertEquals(LineScanConfig.ScanAxis.X, cfg.getAxis());
        assertEquals(10.0, cfg.getDistanceMm(), 1e-4);
        assertEquals(0.10, cfg.getStepDeltaMm(), 1e-4);
        assertTrue(cfg.isPositiveDir());
        assertTrue(cfg.isAutoReturn());
        assertEquals(100, cfg.getTotalScanLines());
        assertEquals(1.0, cfg.getEstimatedSweepSeconds(), 1e-4);

        cfg.setAxis(LineScanConfig.ScanAxis.Y);
        cfg.setDistanceMm(40.0);
        cfg.setStepDeltaMm(0.10);
        assertEquals(400, cfg.getTotalScanLines());
        assertEquals(4.0, cfg.getEstimatedSweepSeconds(), 1e-4);
    }

    @Test
    @DisplayName("ScanBuffer allocates exact capacity for 2D line scan and caps columns")
    public void testScanBufferLineScan() {
        int numSamples = 1024;
        ScanBuffer buffer = new ScanBuffer(numSamples);

        buffer.prepareLineScan(100, 10.0, "X", 0.0, 0.10);

        assertTrue(buffer.isLineScanActive());
        assertEquals(100, buffer.getSweepCapacity());
        assertEquals(0, buffer.getSweepIndex());
        assertEquals("X", buffer.getLineScanAxisLabel());
        assertEquals(10.0, buffer.getLineScanLengthMm(), 1e-4);
        assertEquals(0.10, buffer.getLineScanStepMm(), 1e-4);

        byte[] raw = new byte[numSamples];
        float[] proc = new float[numSamples];
        raw[50] = 120;
        proc[50] = 120.0f;

        // Add 100 lines
        for (int i = 0; i < 100; i++) {
            buffer.addSweepLine(raw, proc);
        }
        assertEquals(100, buffer.getSweepIndex());
        assertEquals(100, buffer.getSweepCapacity());

        // Attempting to add past 100 should be capped without reallocating
        buffer.addSweepLine(raw, proc);
        assertEquals(100, buffer.getSweepIndex());
        assertEquals(100, buffer.getSweepCapacity());

        assertEquals(120.0f, buffer.getSample(ScanMode.SWEEP, 50, 50), 1e-4);
    }

    @Test
    @DisplayName("FpgaCommandProtocol creates compliant packets for X and Y line scans")
    public void testFpgaLineScanPackets() {
        long halfPeriod = 25000L;

        // X-Axis Line Scan (uses CMD_CHAMBER_SCAN with totalYSteps=0 for bitstream compatibility)
        byte[] xPacket = FpgaCommandProtocol.buildLineScanPacket(
                FpgaCommandProtocol.AXIS_X, true, 4000L, 40, halfPeriod);

        assertEquals(16, xPacket.length);
        assertEquals(FpgaCommandProtocol.SYNC_1, xPacket[0]);
        assertEquals(FpgaCommandProtocol.SYNC_2, xPacket[1]);
        assertEquals(FpgaCommandProtocol.CMD_CHAMBER_SCAN, xPacket[2]);
        assertEquals((byte) 40, xPacket[3]); // deltaXSteps
        assertEquals((byte) 0x01, xPacket[4]); // +X dir

        // Verify checksum is valid
        byte chk = 0;
        for (int i = 2; i < 15; i++) {
            chk ^= xPacket[i];
        }
        assertEquals(chk, xPacket[15]);

        // Y-Axis Line Scan (uses CMD_LINE_SCAN)
        byte[] yPacket = FpgaCommandProtocol.buildLineScanPacket(
                FpgaCommandProtocol.AXIS_Y, false, 16000L, 40, halfPeriod);

        assertEquals(16, yPacket.length);
        assertEquals(FpgaCommandProtocol.CMD_LINE_SCAN, yPacket[2]);
        assertEquals(FpgaCommandProtocol.AXIS_Y, yPacket[3]);
        assertEquals(FpgaCommandProtocol.DIR_NEGATIVE, yPacket[4]);

        chk = 0;
        for (int i = 2; i < 15; i++) {
            chk ^= yPacket[i];
        }
        assertEquals(chk, yPacket[15]);
    }

    @Test
    @DisplayName("MotorControllerService triggers line scan and fires pre-scan action")
    public void testMotorServiceLineScan() throws Exception {
        MotorSettings settings = new MotorSettings();
        MotorControllerService service = new MotorControllerService(settings);

        List<byte[]> sentPackets = new ArrayList<>();
        service.setAcquisitionSource(new DataAcquisitionSource() {
            @Override
            public String getSourceName() { return "MockSource"; }
            @Override
            public void start(java.util.function.Consumer<byte[]> frameConsumer, java.util.function.Consumer<Throwable> errorHandler) {}
            @Override
            public void stop() {}
            @Override
            public boolean isRunning() { return true; }
            @Override
            public boolean sendCommand(byte[] packet) {
                sentPackets.add(packet);
                return true;
            }
        });

        AtomicBoolean preScanExecuted = new AtomicBoolean(false);
        service.setPreScanAction(() -> preScanExecuted.set(true));

        LineScanConfig cfg = new LineScanConfig();
        cfg.setAxis(LineScanConfig.ScanAxis.X);
        cfg.setDistanceMm(5.0);
        cfg.setSpeedMmPerSec(50.0); // fast for unit test
        cfg.setAutoReturn(false);

        service.triggerLineScan(cfg);

        assertTrue(preScanExecuted.get(), "Pre-scan action should be executed synchronously");
        assertTrue(service.isLineScanning());
        assertTrue(service.isBusy());

        // Halt line scan
        service.halt();
        assertFalse(service.isLineScanning());
        assertFalse(service.isBusy());
    }
}
