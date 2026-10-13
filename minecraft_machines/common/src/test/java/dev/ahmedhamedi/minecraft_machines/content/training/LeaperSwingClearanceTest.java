package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperSwingClearance;

import org.junit.jupiter.api.Test;

class LeaperSwingClearanceTest {
    @Test
    void flatAndLowObstaclesLeaveBothBaselinesUnchanged() {
        for (double obstacle : new double[] {-1, 0, .1, .3, .35}) {
            for (double t : new double[] {-.1, 0, .075, .15, .2, .5, .7, .85, 1, 1.1}) {
                var target = LeaperSwingClearance.shape(t, 0, 0, obstacle, .43, -.17);
                assertEquals(.43, target.height(), 0);
                assertEquals(-.17, target.sweep(), 0);
                assertEquals(0, target.blend(), 0);
            }
        }
    }

    @Test
    void twoBlockRiseLiftsBeforeAdvancingHoldsAboveTheObstacleThenLands() {
        assertTarget(LeaperSwingClearance.shape(0, 5, 7, 7, 5, .1), 5, -.5, 1);
        // Halfway through the .15 lift interval: smoothstep is exactly one half.
        assertTarget(LeaperSwingClearance.shape(.075, 5, 7, 7, 5, .1), 6.6, -.5, 1);
        for (double t : new double[] {.15, .175, .20})
            assertTarget(LeaperSwingClearance.shape(t, 5, 7, 7, 5, .1), 8.2, -.5, 1);
        for (double t : new double[] {.25, .4, .6, .7}) {
            var target = LeaperSwingClearance.shape(t, 5, 7, 7, 5, .1);
            assertEquals(8.2, target.height(), 1e-12);
            assertTrue(target.sweep() > -.5 && target.sweep() < .5);
        }
        assertEquals(0, LeaperSwingClearance.shape(.6, 5, 7, 7, 5, .1).sweep(), 1e-12);
        assertEquals(7.6, LeaperSwingClearance.shape(.85, 5, 7, 7, 5, .1).height(), 1e-12);
        assertTarget(LeaperSwingClearance.shape(1, 5, 7, 7, 5, .1), 7, .5, 1);
    }

    @Test
    void plateauClearsBothTheObstacleAndAHigherLanding() {
        assertEquals(6.2, LeaperSwingClearance.shape(.4, 2, 5, 4, 2, 0).height(), 1e-12);
        assertEquals(7.2, LeaperSwingClearance.shape(.4, 2, 5, 6, 2, 0).height(), 1e-12);
    }

    @Test
    void timeOutsideTheSwingUsesTheEndpointProfile() {
        for (double obstacle : new double[] {.75, 2}) {
            assertEquals(
                    LeaperSwingClearance.shape(0, 0, 2, obstacle, .8, .22),
                    LeaperSwingClearance.shape(-3, 0, 2, obstacle, .8, .22));
            assertEquals(
                    LeaperSwingClearance.shape(1, 0, 2, obstacle, .8, .22),
                    LeaperSwingClearance.shape(3, 0, 2, obstacle, .8, .22));
        }
    }

    @Test
    void liftSweepAndDescentBoundariesAreContinuousForFullAndPartialBlends() {
        double epsilon = 1e-7;
        for (double obstacle : new double[] {.75, 2}) {
            for (double t : new double[] {0, .15, .2, .7, 1}) {
                var left = LeaperSwingClearance.shape(t - epsilon, 0, 2, obstacle, .8, .22);
                var center = LeaperSwingClearance.shape(t, 0, 2, obstacle, .8, .22);
                var right = LeaperSwingClearance.shape(t + epsilon, 0, 2, obstacle, .8, .22);
                assertEquals(center.height(), left.height(), 1e-6);
                assertEquals(center.height(), right.height(), 1e-6);
                assertEquals(center.sweep(), left.sweep(), 1e-6);
                assertEquals(center.sweep(), right.sweep(), 1e-6);
                assertEquals(left.blend(), right.blend(), 0);
            }
        }
    }

    @Test
    void reducedBlendInterpolatesBothBaselinesAndHasNoThresholdJumps() {
        for (double t : new double[] {0, .075, .15, .2, .4, .7, .85, 1}) {
            // Landing dominates top in both calls, so only the blend changes.
            var half = LeaperSwingClearance.shape(t, 0, 2, .75, .8, .22);
            var full = LeaperSwingClearance.shape(t, 0, 2, 1.2, .8, .22);
            assertEquals(.5, half.blend(), 1e-12);
            assertEquals(1, full.blend(), 0);
            assertEquals(.8 + .5 * (full.height() - .8), half.height(), 1e-12);
            assertEquals(.22 + .5 * (full.sweep() - .22), half.sweep(), 1e-12);
        }
        for (double threshold : new double[] {.35, 1.15}) {
            var left = LeaperSwingClearance.shape(.4, 0, 2, threshold - 1e-7, .8, .22);
            var right = LeaperSwingClearance.shape(.4, 0, 2, threshold + 1e-7, .8, .22);
            assertEquals(left.height(), right.height(), 1e-6);
            assertEquals(left.sweep(), right.sweep(), 1e-6);
            assertTrue(left.blend() >= 0 && right.blend() <= 1);
            assertTrue(left.blend() <= right.blend());
        }
    }

    @Test
    void torsoSagCannotCancelPeakClearanceOrIntroduceEndpointJumps() {
        assertEquals(0, LeaperSwingClearance.sagCompensation(0, -2), 0);
        assertEquals(0, LeaperSwingClearance.sagCompensation(1, -2), 0);
        for (double t : new double[] {.15, .3, .5, .7})
            assertEquals(2, LeaperSwingClearance.sagCompensation(t, -2), 1e-12);
        for (double t : new double[] {-.1, 0, .15, .7, 1, 1.1})
            assertEquals(0, LeaperSwingClearance.sagCompensation(t, 1), 0);
        for (double boundary : new double[] {0, .15, .7, 1})
            assertEquals(
                    LeaperSwingClearance.sagCompensation(boundary - 1e-7, -2),
                    LeaperSwingClearance.sagCompensation(boundary + 1e-7, -2),
                    1e-5);
    }

    private static void assertTarget(
            LeaperSwingClearance.Target target, double height, double sweep, double blend) {
        assertEquals(height, target.height(), 1e-12);
        assertEquals(sweep, target.sweep(), 1e-12);
        assertEquals(blend, target.blend(), 1e-12);
    }
}
