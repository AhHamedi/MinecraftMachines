package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;

import org.junit.jupiter.api.Test;

class LeaperNavigationTest {
    @Test
    void navigationSurvivesRotationTranslationAndRetargeting() {
        double angle = 2.4, c = Math.cos(angle), s = Math.sin(angle), gx = 14, gz = -9;
        var a = new LeaperPointController(new double[10], gx, gz);
        var b =
                new LeaperPointController(
                        new double[10], c * gx - s * gz + 500, s * gx + c * gz - 300);
        for (int i = 0; i < 100; i++) {
            if (i == 50) {
                gx = -2;
                gz = 1;
                a.setGoal(gx, gz);
                b.setGoal(c * gx - s * gz + 500, s * gx + c * gz - 300);
            }
            double x = i * .06, z = -i * .08, h = i * .01;
            var ca = a.update(x, z, h, .1);
            var cb = b.update(c * x - s * z + 500, s * x + c * z - 300, h + angle, .1);
            assertEquals(ca.forward(), cb.forward(), 1e-9);
            assertEquals(ca.strafe(), cb.strafe(), 1e-9);
            assertEquals(ca.turn(), cb.turn(), 1e-9);
        }
    }

    @Test
    void goalBehindProducesReverseMotionWithoutWaitingForTurn() {
        var c = new LeaperPointController(new double[10], 0, 10);
        assertTrue(c.update(0, 0, 0, .1).forward() < 0);
    }

    @Test
    void arrivalRequiresRestAndDisturbanceReleasesHold() {
        var c = new LeaperPointController(new double[10], 0, 0);
        assertFalse(c.update(0, 0, 0, .1).arrived());
        for (int i = 0; i < 30; i++) c.update(0, 0, 0, .1);
        assertTrue(c.update(0, 0, 0, .1).arrived());
        var displaced = c.update(1, 0, 0, .1);
        assertFalse(displaced.arrived());
        assertTrue(displaced.strafe() < 0);
    }

    @Test
    void invalidInputsNeverReachMotors() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LeaperPointController(new double[9], 0, 0));
        var c = new LeaperPointController(new double[10], 0, 0);
        assertThrows(IllegalArgumentException.class, () -> c.update(0, 0, Double.NaN, .1));
        assertThrows(IllegalArgumentException.class, () -> c.setGoal(Double.POSITIVE_INFINITY, 0));
    }
}
