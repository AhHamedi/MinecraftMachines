package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPredatorPlanner;

import org.junit.jupiter.api.Test;

import java.util.function.DoubleBinaryOperator;

/** Pure support geometry: all fixtures deliberately permit body clearance. */
class LeaperAdaptiveStanceTest {
    private static final double START_X = .5, START_Z = .5, FLOOR = 7;
    private static final double CONTROLLER_CHASSIS_CLEARANCE = 1.816971;

    private static LeaperPredatorPlanner.Terrain terrain(
            boolean adaptive, DoubleBinaryOperator height) {
        return new LeaperPredatorPlanner.Terrain() {
            @Override
            public double chassisStepClearance() {
                return adaptive ? CONTROLLER_CHASSIS_CLEARANCE : 1;
            }

            @Override
            public boolean adaptiveStanceSampling() {
                return adaptive;
            }

            @Override
            public double height(double x, double z) {
                return height.applyAsDouble(x, z);
            }

            @Override
            public boolean bodyClear(double x, double floor, double z) {
                return true;
            }
        };
    }

    private static LeaperPredatorPlanner.Terrain stairs(boolean adaptive) {
        // Match TerrainCourse kind=0, rise=3, tread=2, angle=0 at native slot zero.
        // The builder samples integer block columns; the planner samples world coordinates.
        return terrain(
                adaptive,
                (x, z) ->
                        FLOOR
                                + 3 * Math.clamp(Math.floor((-Math.floor(z) - 10) / 2), 0, 4));
    }

    private static LeaperPredatorPlanner planner(LeaperPredatorPlanner.Terrain terrain) {
        return new LeaperPredatorPlanner(terrain, START_X, FLOOR, START_Z);
    }

    @Test
    void adaptiveSamplingRemainsOptIn() {
        var unchanged =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return FLOOR;
                    }

                    public boolean bodyClear(double x, double floor, double z) {
                        return true;
                    }
                };
        assertFalse(unchanged.adaptiveStanceSampling());
        assertEquals(1, unchanged.chassisStepClearance());
        assertTrue(planner(unchanged).segmentTo(START_X, FLOOR, -24.5));
    }

    @Test
    void adaptiveSamplingRecoversTheSecondTreadRejectedByTheFixedRing() {
        assertEquals(13, stairs(true).height(START_X, -13.5));
        assertTrue(Double.isNaN(planner(stairs(false)).stanceFloor(START_X, 13, -13.5)));
        assertTrue(Double.isFinite(planner(stairs(true)).stanceFloor(START_X, 13, -13.5)));
    }

    @Test
    void adaptiveSamplingMustAlsoSupportTheThirdTreadBeforeTheTopLanding() {
        assertEquals(16, stairs(true).height(START_X, -15.5));
        // The old one-block chassis clearance raised the pose floor to 18 and put
        // both downhill foothold sectors just outside their vertical search windows.
        // Use the controller's bodyHeight - 1.5 clearance, without adding more radii.
        assertTrue(
                Double.isFinite(planner(stairs(true)).stanceFloor(START_X, 16, -15.5)),
                "Adaptive sampling must retain support at the third 3-rise/2-tread step");
    }

    @Test
    void adaptiveSamplingMustConnectTheFullStaircaseWithBodyClearanceUnrestricted() {
        var fixed = planner(stairs(false)).plan(START_X, 19, -47.5);
        assertFalse(fixed.found(), "The fixed sampler should reproduce the planning failure");

        var adaptive = planner(stairs(true));
        var route = adaptive.plan(START_X, 19, -47.5);
        assertTrue(route.found(), route.reason());
        assertEquals(new LeaperPredatorPlanner.Point(START_X, 19, -47.5), route.points().getLast());
        // Exercise the returned, smoothed route as well as graph reachability.
        for (int i = 1; i < route.points().size(); i++) {
            var a = route.points().get(i - 1);
            var b = route.points().get(i);
            assertTrue(adaptive.segment(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()), a + " -> " + b);
        }
    }

    @Test
    void aSmallGapMayBeStraddledButCannotBeTheRequestedDestination() {
        var p =
                planner(
                        terrain(
                                true,
                                (x, z) -> z < -10 && z > -12 ? Double.NaN : FLOOR));
        assertTrue(p.segmentTo(START_X, FLOOR, -24.5));
        var unsupportedGoal = p.plan(START_X, FLOOR, -11);
        assertFalse(unsupportedGoal.found());
        assertEquals("Destination has no reachable dry stance", unsupportedGoal.reason());
    }

    @Test
    void adaptiveSamplingCannotBridgeAnUnboundedWideGap() {
        var p =
                planner(
                        terrain(
                                true,
                                (x, z) -> z < -10 && z > -26 ? Double.NaN : FLOOR));
        assertFalse(p.segmentTo(START_X, FLOOR, -40.5));
        assertFalse(p.plan(START_X, FLOOR, -40.5).found());
    }

    @Test
    void adaptiveSamplingCannotCreateSupportAtAnUnsupportedStart() {
        var p = planner(terrain(true, (x, z) -> z > -12 ? Double.NaN : FLOOR));
        assertTrue(Double.isNaN(p.stanceFloor(START_X, FLOOR, START_Z)));
        var route = p.plan(START_X, FLOOR, -30.5);
        assertFalse(route.found());
        assertEquals("No supported escape stance", route.reason());
    }

    @Test
    void adaptiveSamplingCannotBypassClimbAndDropLimits() {
        for (double change : new double[] {4, 9, -4, -9}) {
            var p = planner(terrain(true, (x, z) -> z < -10 ? FLOOR + change : FLOOR));
            assertFalse(p.segmentTo(START_X, FLOOR + change, -30.5), "cliff " + change);
            assertFalse(p.plan(START_X, FLOOR + change, -30.5).found(), "cliff " + change);
        }
    }
}
