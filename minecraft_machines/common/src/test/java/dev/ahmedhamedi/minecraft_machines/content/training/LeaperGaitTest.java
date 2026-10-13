package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;

import org.junit.jupiter.api.Test;

import java.util.Random;

class LeaperGaitTest {
    private static final double[] PREDATOR_MIN = {-.9, -1.05, -1.2};
    private static final double[] PREDATOR_MAX = {.9, 1.05, 1.2};

    @Test
    void rearFootCanReachLowerTreadWithPairedShoulderAndKneeExtension() {
        // A rear support point 7.5 blocks below the core while pitched uphill.
        // It is within the 8.5-block linkage length, but needs BOTH joint ranges.
        double pitch = Math.atan(.58), c = Math.cos(pitch), s = Math.sin(pitch);
        double[] min = {-.9, -1.4, -1.2}, max = {.9, 1.4, 1.65};
        for (int leg = 2; leg < 4; leg++) {
            double[] local = {(leg == 2 ? -1 : 1) * 3.77, c * -7.5 + s * .86, -s * -7.5 + c * .86};
            var q = LeaperGait.inverse(leg, local[0], local[1], local[2]);
            assertTrue(q[1] < PREDATOR_MIN[1], "Old shoulder limit blocks downward reach");
            assertTrue(q[2] > PREDATOR_MAX[2], "Old knee limit also blocks downward reach");
            for (int j = 0; j < 3; j++) assertTrue(q[j] >= min[j] && q[j] <= max[j]);
            assertArrayEquals(local, LeaperGait.forward(leg, q[0], q[1], q[2]), 1e-10);
            assertConverges(leg, new double[] {0, -.5, .8}, q, min, max, .06);
        }
    }

    @Test
    void sprintCycleRemainsContinuousAndInsidePhysicalJointLimits() {
        var p = LeaperGait.sprintSeed(LeaperGait.seed());
        double period = LeaperGait.period(p, true);
        double[] limits = {.60, .65, .85};
        for (double[] command :
                new double[][] {{1.25, 0, 0}, {-1, 0, 0}, {0, 1.25, 0}, {0, 0, .9}}) {
            double[] previous = null;
            for (int i = 0; i <= 10000; i++) {
                var q =
                        LeaperGait.action(
                                p,
                                i * period / 10000,
                                .7,
                                .03,
                                -.04,
                                command[0],
                                command[1],
                                command[2],
                                true);
                for (int j = 0; j < 12; j++) {
                    assertTrue(Double.isFinite(q[j]) && Math.abs(q[j]) <= limits[j % 3]);
                    if (previous != null) assertTrue(Math.abs(q[j] - previous[j]) < .003);
                }
                previous = q;
            }
            assertArrayEquals(
                    LeaperGait.action(
                            p, 0, .7, .03, -.04, command[0], command[1], command[2], true),
                    previous,
                    1e-10);
        }
    }

    @Test
    void inverseRecoversIndependentForwardKinematicsWithinJointEnvelope() {
        Random r = new Random(90612);
        for (int leg = 0; leg < 4; leg++)
            for (int i = 0; i < 1000; i++) {
                double[] q = {r.nextDouble(-.6, .6), r.nextDouble(-.5, .5), r.nextDouble(-.6, .6)};
                double[] foot = LeaperGait.forward(leg, q[0], q[1], q[2]);
                assertArrayEquals(q, LeaperGait.inverse(leg, foot[0], foot[1], foot[2]), 1e-10);
            }
    }

    @Test
    void neutralGeometryMatchesMeasuredBindPose() {
        double[] f = LeaperGait.forward(1, 0, 0, 0);
        assertEquals(5.3754428962546585, Math.hypot(f[0], f[2]), 1e-8);
        assertEquals(-2.0656728895, f[1], 1e-8);
    }

    @Test
    void bodyFeedbackIsInvariantToWorldRotation() {
        double a = 1.2, c = Math.cos(a), s = Math.sin(a), ux = .07, uz = -.04;
        assertArrayEquals(
                LeaperGait.action(LeaperGait.seed(), 2, .3, ux, uz, .7, .3),
                LeaperGait.action(
                        LeaperGait.seed(), 2, .3 + a, c * ux - s * uz, s * ux + c * uz, .7, .3),
                1e-12);
    }

    @Test
    void cartesianLiftEliminatesTheOldLimitersForwardExcursionAndReachesTheEndpoint() {
        // Packaged v9 rear-left liftoff, flat chassis, zero foothold shift.
        int leg = 2;
        double theta = Math.toRadians(-125);
        double x = 4.603459815421928 * Math.sin(theta);
        double y = -3.31697111456031;
        double z = -4.603459815421928 * Math.cos(theta) + 3.4445543435310473 / 2;
        double maxStep = 5.921763306193632 * .05;
        double[] previous = LeaperGait.inverse(leg, x, y, z);
        double[] requested = LeaperGait.inverse(leg, x, y + 3.7, z);
        double[] oldStep = previous.clone();
        for (int j = 0; j < 3; j++)
            oldStep[j] += Math.clamp(requested[j] - oldStep[j], -maxStep, maxStep);
        double[] oldToe = LeaperGait.forward(leg, oldStep[0], oldStep[1], oldStep[2]);
        assertEquals(
                -.265,
                oldToe[2] - z,
                .001,
                "Independent angular clipping advances the toe into a forward (-Z) wall");

        double lastHeight = y;
        for (int iteration = 0; iteration < 32; iteration++) {
            double[] next =
                    LeaperGait.rateLimitedCartesian(
                            leg, previous, requested, PREDATOR_MIN, PREDATOR_MAX, maxStep);
            assertFeasibleStep(previous, next, PREDATOR_MIN, PREDATOR_MAX, maxStep);
            double[] toe = LeaperGait.forward(leg, next[0], next[1], next[2]);
            assertEquals(x, toe[0], 1e-10, "Lift must not introduce lateral command motion");
            assertEquals(z, toe[2], 1e-10, "Lift must not advance into the wall");
            assertTrue(toe[1] >= lastHeight - 1e-10 && toe[1] <= y + 3.7 + 1e-10);
            if (iteration == 0) assertTrue(toe[1] > y + .1, "Lift must make useful progress");
            lastHeight = toe[1];
            previous = next;
        }
        assertArrayEquals(requested, previous, 1e-10);
        assertEquals(y + 3.7, lastHeight, 1e-10);
    }

    @Test
    void clampsRequestedEndpointWithoutMutatingInputsAndConvergesAtYawLimits() {
        for (double sign : new double[] {-1, 1}) {
            double[] previous = {sign * .85, .85, -.5};
            double[] requested = {sign * 2, .95, -.35};
            double[][] inputs = {previous, requested, PREDATOR_MIN.clone(), PREDATOR_MAX.clone()};
            double[][] before = new double[4][];
            for (int i = 0; i < inputs.length; i++) before[i] = inputs[i].clone();
            double[] next =
                    LeaperGait.rateLimitedCartesian(
                            0, inputs[0], inputs[1], inputs[2], inputs[3], .04);
            assertNotSame(previous, next);
            for (int i = 0; i < inputs.length; i++) assertArrayEquals(before[i], inputs[i], 0);
            assertConverges(0, previous, requested, inputs[2], inputs[3], .04);
        }
    }

    @Test
    void sharedJointFractionEscapesACartesianChordOutsideTheShoulderLimit() {
        double[] previous = {-.25, 1.05, -.6};
        double[] requested = {.25, 1.05, .6};
        double[] start = LeaperGait.forward(0, previous[0], previous[1], previous[2]);
        double[] end = LeaperGait.forward(0, requested[0], requested[1], requested[2]);
        double[] midpoint =
                LeaperGait.inverse(
                        0,
                        (start[0] + end[0]) / 2,
                        (start[1] + end[1]) / 2,
                        (start[2] + end[2]) / 2);
        assertTrue(
                midpoint[1] > PREDATOR_MAX[1] + .1,
                "Both endpoints fit, but their Cartesian chord leaves the joint box");
        double[] next =
                LeaperGait.rateLimitedCartesian(
                        0, previous, requested, PREDATOR_MIN, PREDATOR_MAX, .06);
        assertFeasibleStep(previous, next, PREDATOR_MIN, PREDATOR_MAX, .06);
        double yawFraction = (next[0] - previous[0]) / (requested[0] - previous[0]);
        double kneeFraction = (next[2] - previous[2]) / (requested[2] - previous[2]);
        assertTrue(kneeFraction > .04, "Boundary fallback must make useful progress");
        assertEquals(yawFraction, kneeFraction, 1e-10, "Use one fraction, not separate clips");
        assertEquals(1.05, next[1], 0);
        double[] toe = LeaperGait.forward(0, next[0], next[1], next[2]);
        double chordError = 0;
        for (int j = 0; j < 3; j++)
            chordError += Math.pow(toe[j] - (start[j] + kneeFraction * (end[j] - start[j])), 2);
        assertTrue(chordError > 1e-6, "The documented fallback deliberately leaves the chord");
        assertConverges(0, previous, requested, PREDATOR_MIN, PREDATOR_MAX, .06);
    }

    @Test
    void inverseBranchRemainsContinuousAcrossYawWrapAndForFoldedLegs() {
        assertConverges(
                1,
                new double[] {3.1, -.2, 0},
                new double[] {3.3, -.12, .1},
                new double[] {2.5, -1.05, -1.2},
                new double[] {4, 1.05, 1.2},
                .025);
        // Negative signed radial reach: using only hypot in IK would flip yaw by pi.
        assertConverges(
                2,
                new double[] {0, -.8, -1.19},
                new double[] {.12, -.65, -1.17},
                PREDATOR_MIN,
                PREDATOR_MAX,
                .025);
        // The public helper also preserves the other elbow branch when limits allow it.
        assertConverges(
                3,
                new double[] {0, -.5, 2.3},
                new double[] {.12, -.45, 2.5},
                new double[] {-.9, -1.05, 2},
                new double[] {.9, 1.05, 2.8},
                .025);
    }

    @Test
    void zeroStepReturnsAnIndependentUnchangedPose() {
        double[] previous = {.2, -.3, .4};
        double[] next =
                LeaperGait.rateLimitedCartesian(
                        0, previous, new double[] {.4, .2, .6}, PREDATOR_MIN, PREDATOR_MAX, 0);
        assertArrayEquals(previous, next, 0);
        assertNotSame(previous, next);
    }

    @Test
    void rejectsNonfiniteValuesAndInvalidJointContracts() {
        for (double invalid :
                new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            for (int array = 0; array < 4; array++) {
                for (int joint = 0; joint < 3; joint++) {
                    double[][] inputs = {
                        new double[3], new double[3], PREDATOR_MIN.clone(), PREDATOR_MAX.clone()
                    };
                    inputs[array][joint] = invalid;
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    LeaperGait.rateLimitedCartesian(
                                            0, inputs[0], inputs[1], inputs[2], inputs[3], .1));
                }
            }
        }
        for (double step :
                new double[] {-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            LeaperGait.rateLimitedCartesian(
                                    0,
                                    new double[3],
                                    new double[3],
                                    PREDATOR_MIN,
                                    PREDATOR_MAX,
                                    step));
        for (int leg : new int[] {-1, 4})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            LeaperGait.rateLimitedCartesian(
                                    leg,
                                    new double[3],
                                    new double[3],
                                    PREDATOR_MIN,
                                    PREDATOR_MAX,
                                    .1));
        for (int array = 0; array < 4; array++) {
            for (double[] invalid : new double[][] {null, new double[2], new double[4]}) {
                double[][] inputs = {
                    new double[3], new double[3], PREDATOR_MIN.clone(), PREDATOR_MAX.clone()
                };
                inputs[array] = invalid;
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                LeaperGait.rateLimitedCartesian(
                                        0, inputs[0], inputs[1], inputs[2], inputs[3], .1));
            }
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LeaperGait.rateLimitedCartesian(
                                0,
                                new double[] {1, 0, 0},
                                new double[3],
                                PREDATOR_MIN,
                                PREDATOR_MAX,
                                .1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LeaperGait.rateLimitedCartesian(
                                0, new double[3], new double[3], PREDATOR_MAX, PREDATOR_MIN, .1));
    }

    private static void assertFeasibleStep(
            double[] previous, double[] next, double[] min, double[] max, double maxStep) {
        assertEquals(3, next.length);
        for (int j = 0; j < 3; j++) {
            assertTrue(Double.isFinite(next[j]));
            assertTrue(next[j] >= min[j] && next[j] <= max[j], "Joint " + j + " outside limits");
            assertTrue(Math.abs(next[j] - previous[j]) <= maxStep, "Joint " + j + " exceeds slew");
        }
    }

    private static void assertConverges(
            int leg,
            double[] previous,
            double[] requested,
            double[] min,
            double[] max,
            double step) {
        double[] goal = requested.clone();
        for (int j = 0; j < 3; j++) goal[j] = Math.clamp(goal[j], min[j], max[j]);
        for (int iteration = 0; iteration < 200; iteration++) {
            double[] next =
                    LeaperGait.rateLimitedCartesian(leg, previous, requested, min, max, step);
            assertFeasibleStep(previous, next, min, max, step);
            previous = next;
        }
        assertArrayEquals(
                goal, previous, 1e-10, "Must reach the bounded endpoint without stalling");
    }
}
