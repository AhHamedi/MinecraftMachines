package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPursuitTarget;

import org.junit.jupiter.api.Test;

class LeaperPursuitTargetTest {
    @Test
    void leadsRunningPreyAndImmediatelyDropsPredictionOnStop() {
        var target = new LeaperPursuitTarget();
        target.observe(0, 12, 0);
        target.observe(0, 13.12, 4);
        var aim = target.aim(0, 0, 12, 4);
        assertTrue(aim.z() > 17);
        assertTrue(aim.z() - 13.12 <= 6);
        target.observe(0, 13.12, 8);
        assertFalse(target.moving(8));
        assertEquals(13.12, target.aim(0, 0, 12, 8).z());
    }

    @Test
    void turnsWithThePreyInsteadOfContinuingThePreviousPrediction() {
        var target = new LeaperPursuitTarget();
        target.observe(10, 0, 0);
        target.observe(11, 0, 4);
        target.observe(10, 0, 8);
        assertTrue(target.aim(0, 0, 12, 8).x() < 10);
    }

    @Test
    void boundsUncatchableDiagonalMotionAndRejectsTeleportVelocity() {
        var target = new LeaperPursuitTarget();
        target.observe(0, 0, 0);
        target.observe(2, 2, 4);
        var aim = target.aim(-20, -20, 4, 4);
        assertEquals(6, Math.hypot(aim.x() - 2, aim.z() - 2), 1e-9);
        target.observe(100, 100, 8);
        assertEquals(new LeaperPursuitTarget.Aim(100, 100), target.aim(0, 0, 12, 8));
        assertFalse(target.observe(Double.NaN, 0, 9));
        assertEquals(new LeaperPursuitTarget.Aim(100, 100), target.aim(0, 0, 12, 9));
    }

    @Test
    void expiresMissingObservationsAndClearsMotionAcrossHunts() {
        var target = new LeaperPursuitTarget();
        target.observe(0, 0, 0);
        target.observe(1, 0, 4);
        assertTrue(target.moving(4));
        assertFalse(target.moving(15));
        target.clear();
        target.observe(20, 20, 16);
        assertFalse(target.moving(16));
    }

    @Test
    void approachingPreyGetsLessLeadThanFleeingPrey() {
        var approaching = new LeaperPursuitTarget();
        approaching.observe(6, 0, 0);
        approaching.observe(5, 0, 4);
        var fleeing = new LeaperPursuitTarget();
        fleeing.observe(4, 0, 0);
        fleeing.observe(5, 0, 4);
        assertTrue(5 - approaching.aim(0, 0, 12, 4).x() < fleeing.aim(0, 0, 12, 4).x() - 5);
    }
}
