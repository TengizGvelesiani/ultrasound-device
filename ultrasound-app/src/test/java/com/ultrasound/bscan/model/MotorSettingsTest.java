package com.ultrasound.bscan.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MotorSettingsTest {

    @Test
    @DisplayName("Converts mm to steps and steps to mm accurately for 1/16 microstepping")
    void testUnitConversions() {
        MotorSettings settings = new MotorSettings();
        settings.setStepsPerMmX(400.0);
        settings.setStepsPerMmY(400.0);

        // 1 mm = 400 steps
        assertEquals(400L, settings.mmToStepsX(1.0));
        assertEquals(400L, settings.mmToStepsY(1.0));

        // 25.4 mm (1 inch) = 10160 steps
        assertEquals(10160L, settings.mmToStepsX(25.4));

        // 800 steps = 2.0 mm
        assertEquals(2.0, settings.stepsToMmX(800L), 0.001);
        assertEquals(2.0, settings.stepsToMmY(800L), 0.001);
    }

    @Test
    @DisplayName("Calculates total scan lines based on chamber length and line spacing")
    void testTotalScanLines() {
        MotorSettings settings = new MotorSettings();
        settings.setChamberLengthY(100.0);
        settings.setLineSpacingY(1.0);

        // 100 mm / 1 mm = 100 + 1 = 101 scan lines
        assertEquals(101, settings.getTotalScanLines());

        settings.setLineSpacingY(2.0);
        // 100 mm / 2 mm = 50 + 1 = 51 scan lines
        assertEquals(51, settings.getTotalScanLines());
    }

    @Test
    @DisplayName("Enforces boundary constraints on chamber dimensions and speed")
    void testConstraints() {
        MotorSettings settings = new MotorSettings();
        settings.setChamberWidthX(-10.0);
        assertTrue(settings.getChamberWidthX() >= 5.0);

        settings.setChamberLengthY(0.0);
        assertTrue(settings.getChamberLengthY() >= 5.0);

        settings.setSpeedMmPerSec(-5.0);
        assertTrue(settings.getSpeedMmPerSec() >= 0.5);

        settings.setLineSpacingY(0.001);
        assertTrue(settings.getLineSpacingY() >= 0.05);
    }
}
