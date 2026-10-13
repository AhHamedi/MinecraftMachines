package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperApexDrive;

import org.junit.jupiter.api.Test;

class LeaperApexDriveTest {
    @Test
    void accelerationCannotBypassItsForceBudget() {
        var push = LeaperApexDrive.accelerate(0, 0, 1000, 1000);
        assertEquals(LeaperApexDrive.ACCELERATION * .05, Math.hypot(push.x(), push.z()), 1e-9);
    }

    @Test
    void brakingRemovesEnergyWithoutReversingMotion() {
        for (double speed : new double[] {.1, 1, 12, 27}) {
            var push = LeaperApexDrive.accelerate(speed, -speed, 0, 0);
            assertTrue(push.x() <= 0 && push.z() >= 0);
            assertTrue(speed + push.x() >= 0 && -speed + push.z() <= 0);
            assertTrue(Math.hypot(push.x(), push.z()) <= LeaperApexDrive.BRAKING * .05 + 1e-9);
            assertTrue(Math.hypot(speed + push.x(), -speed + push.z()) < Math.hypot(speed, speed));
        }
    }

    @Test
    void arrivalApproachCannotDemandFullCruiseInsideStoppingDistance() {
        assertEquals(0, LeaperApexDrive.approachSpeed(1.6, 27));
        assertTrue(LeaperApexDrive.approachSpeed(2, 27) < 2);
        assertEquals(27, LeaperApexDrive.approachSpeed(30, 27));
        for (double d = 2; d < 30; d += .1) {
            double v = LeaperApexDrive.approachSpeed(d, 27);
            assertTrue(v * v / (2 * LeaperApexDrive.BRAKING) <= d - 1.6 + 1e-9);
        }
    }

    @Test
    void lookaheadCoversLatencyAndBrakingAtFullCruise() {
        double v = LeaperApexDrive.CRUISE;
        assertTrue(LeaperApexDrive.lookahead(v) >= v * .3 + v * v / (2 * LeaperApexDrive.BRAKING));
        assertEquals(6, LeaperApexDrive.lookahead(0));
    }
}
