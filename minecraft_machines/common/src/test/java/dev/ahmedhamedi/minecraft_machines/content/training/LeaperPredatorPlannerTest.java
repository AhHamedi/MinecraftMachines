package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPredatorPlanner;

import org.junit.jupiter.api.Test;

class LeaperPredatorPlannerTest {
    @Test
    void enlargedStanceRejectsAnIslandThatOnlySupportsNormalFeet() {
        for (double scale : new double[] {1, 2}) {
            var terrain = new LeaperPredatorPlanner.Terrain() {
                public double bodyScale() { return scale; }
                public double height(double x, double z) {
                    return Math.hypot(x, z) <= 7 ? 0 : Double.NaN;
                }
                public boolean bodyClear(double x, double y, double z) { return true; }
            };
            boolean supported = Double.isFinite(new LeaperPredatorPlanner(terrain, 0, 0).stanceFloor(0, 0, 0));
            assertEquals(scale == 1, supported);
        }
    }

    @Test
    void imminentCorridorCheckFindsANewObstacleEvenWithADistantGoal() {
        double[] wallX = {50};
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return 0;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return x < wallX[0];
                    }
                };
        assertTrue(new LeaperPredatorPlanner(terrain, 0, 0).segmentAheadTo(96, 0, 0, 6));
        wallX[0] = 4;
        assertFalse(new LeaperPredatorPlanner(terrain, 0, 0).segmentAheadTo(96, 0, 0, 6));
    }

    @Test
    void directCorridorUsesFewerBodyQueriesThanFlatGroundAStar() {
        int[] measured = {0};
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return 0;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        measured[0]++;
                        return true;
                    }
                };
        assertTrue(new LeaperPredatorPlanner(terrain, 0, 0).plan(96, 0, 0).found());
        int graphQueries = measured[0];
        measured[0] = 0;
        var search = new LeaperPredatorPlanner(terrain, 0, 0).searchDirectFirst(96, 0, 0);
        while (!search.done()) search.advance(128, Long.MAX_VALUE);
        assertTrue(search.result().found());
        assertTrue(
                measured[0] * 2 < graphQueries, "Direct=" + measured[0] + ", A*=" + graphQueries);
    }

    @Test
    void directCorridorResumesWithinNodeBudgetAndAvoidsGraphExpansion() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return 0;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return true;
                    }
                };
        var search = new LeaperPredatorPlanner(terrain, 0, 0).searchDirectFirst(96, 0, 0);
        assertNull(search.advance(128, System.nanoTime() - 1));
        for (int i = 0; i < 95; i++) assertNull(search.advance(1, Long.MAX_VALUE));
        var result = search.advance(1, Long.MAX_VALUE);
        assertTrue(result.found());
        assertEquals(0, result.expanded());
        assertEquals(2, result.points().size());
        assertEquals(new LeaperPredatorPlanner.Point(96, 0, 0), result.points().getLast());
    }

    @Test
    void directCorridorFallsBackToGraphAroundAnObstruction() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return 0;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return x < 10 || x > 14 || Math.abs(z) > 5;
                    }
                };
        var planner = new LeaperPredatorPlanner(terrain, 0, 0);
        var search = planner.searchDirectFirst(24, 0, 0);
        for (int i = 0; !search.done() && i < 24000; i++) search.advance(1, Long.MAX_VALUE);
        assertTrue(search.result().found());
        assertTrue(search.result().expanded() > 0);
        assertTrue(search.result().points().stream().anyMatch(p -> Math.abs(p.z()) > 5));
        for (int i = 1; i < search.result().points().size(); i++) {
            var a = search.result().points().get(i - 1);
            var b = search.result().points().get(i);
            assertTrue(planner.segment(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
        }
    }

    @Test
    void raisesTheBodyBeforeTheSecondNarrowTreadWhileRetainingRearFootholds() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return 2 * Math.clamp(Math.floor(x - 11), 0, 3);
                    }

                    public boolean adaptiveStanceSampling() {
                        return true;
                    }

                    public double chassisStepClearance() {
                        return 1.8;
                    }

                    public boolean bodyClear(double x, double floor, double z) {
                        return height(x + 1.35, z) <= floor + 3.3 - 1.25;
                    }
                };
        var planner = new LeaperPredatorPlanner(terrain, .5, 0, .5);
        assertTrue(Double.isFinite(planner.stanceFloor(11.8, 0, .5)));
        assertTrue(Double.isFinite(planner.stanceFloor(12.8, 2, .5)));
        assertTrue(planner.plan(30.5, 6, .5).found());
    }

    @Test
    void resolvesOneBlockTreadsWithTwoBlockRisers() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return 2 * Math.clamp(Math.floor(x - 11), 0, 3);
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return true;
                    }

                    public boolean adaptiveStanceSampling() {
                        return true;
                    }
                };
        var planner = new LeaperPredatorPlanner(terrain, .5, 0, .5);
        var route = planner.plan(30.5, 6, .5);
        assertTrue(route.found(), route.reason());
        assertEquals(new LeaperPredatorPlanner.Point(30.5, 6, .5), route.points().getLast());
    }

    @Test
    void incrementalSearchPreservesValidatedRouteAcrossManyWorkSlices() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return Math.hypot(x - 16, z - 5) < 2.5 ? -12 : 0;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return true;
                    }
                };
        var synchronous = new LeaperPredatorPlanner(terrain, 0, 0).plan(32, 0, 0);
        var search = new LeaperPredatorPlanner(terrain, 0, 0).search(32, 0, 0);
        assertNull(search.advance(0, Long.MAX_VALUE));
        int slices = 0;
        while (!search.done() && slices++ < 24001) search.advance(1, Long.MAX_VALUE);
        assertTrue(slices > 1);
        assertEquals(synchronous, search.result());
        assertSame(search.result(), search.advance(1, Long.MAX_VALUE));
    }

    @Test
    void clickedLowBlockCanHaveAHigherBodySupportPlane() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return Math.abs(x) < .75 && Math.abs(z) < .75 ? 0 : 3;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return true;
                    }
                };
        var planner = new LeaperPredatorPlanner(terrain, 0, 0, 0);
        assertEquals(2.5, planner.stanceFloor(0, 0, 0), 1e-9);
    }

    @Test
    void connectsThreeBlockRisersButStillRejectsFourBlockWalls() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return x > 10 ? 3 : 0;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return true;
                    }
                };
        var planner = new LeaperPredatorPlanner(terrain, 0, 0, 0);
        assertTrue(planner.plan(24, 3, 0).found());
    }

    @Test
    void smoothingPreservesTheDetourThatKeepsFeetAwayFromADeepPit() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return Math.hypot(x - 16, z - 5) < 2.5 ? -12 : 0;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return true;
                    }
                };
        var planner = new LeaperPredatorPlanner(terrain, 0, 0);
        var route = planner.plan(32, 0, 0);
        assertTrue(route.found(), route.reason());
        // The one-block lattice may find a closer safe detour than the old even grid.
        assertTrue(route.points().stream().anyMatch(p -> p.z() < -2.5), route.points().toString());
        for (int i = 1; i < route.points().size(); i++) {
            var a = route.points().get(i - 1);
            var b = route.points().get(i);
            assertTrue(planner.segment(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
        }
        assertFalse(planner.shortcutTo(32, 0, 0));
    }

    @Test
    void connectsTheExactClickedSurfaceWhenTheNearestGridCellHasAnotherHeight() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return x > 20.6 ? 1 : 0;
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return true;
                    }
                };
        var planner = new LeaperPredatorPlanner(terrain, .5, .5);
        var result = planner.plan(21, 1, .5);
        assertTrue(result.found(), result.reason());
        assertEquals(new LeaperPredatorPlanner.Point(21, 1, .5), result.points().getLast());
    }

    @Test
    void keepsTunnelFloorSeparateFromTheRoofAndFindsTheRampOutside() {
        var terrain =
                new LeaperPredatorPlanner.Terrain() {
                    public double height(double x, double z) {
                        return 10;
                    }

                    public java.util.List<Double> surfaces(
                            double x, double z, double min, double max) {
                        var values =
                                java.util.List.of(
                                        Math.clamp(Math.floor((Math.abs(z) - 10) / 3), 0, 10), 10.);
                        return values.stream().filter(h -> h >= min && h <= max).toList();
                    }

                    public boolean bodyClear(double x, double y, double z) {
                        return true;
                    }
                };
        var lower = new LeaperPredatorPlanner(terrain, 0, .3, 0);
        var tunnel = lower.plan(20, 0, 0);
        assertTrue(tunnel.found(), tunnel.reason());
        assertTrue(tunnel.points().stream().allMatch(p -> p.y() == 0));
        assertFalse(lower.segmentTo(20, 10, 0));
        var ascent = lower.plan(20, 10, 0);
        assertTrue(ascent.found(), ascent.reason());
        assertTrue(ascent.points().stream().anyMatch(p -> Math.abs(p.z()) >= 20));
    }

    @Test
    void replanningCannotSnapTheRobotOntoAnOverhangAboveIt() {
        var planner =
                new LeaperPredatorPlanner(
                        new LeaperPredatorPlanner.Terrain() {
                            public double height(double x, double z) {
                                return 8;
                            }

                            public boolean bodyClear(double x, double y, double z) {
                                return true;
                            }
                        },
                        0,
                        0,
                        0);
        assertFalse(planner.plan(20, 8, 0).found());
        assertFalse(planner.segment(0, 0, 4, 0));
    }

    @Test
    void wideFootprintCannotInventARampUpAFourBlockWall() {
        var planner =
                new LeaperPredatorPlanner(
                        new LeaperPredatorPlanner.Terrain() {
                            public double height(double x, double z) {
                                return x > 10 ? 4 : 0;
                            }

                            public boolean bodyClear(double x, double y, double z) {
                                return true;
                            }
                        },
                        0,
                        0);
        assertFalse(planner.plan(30, 4, 0).found());
    }

    @Test
    void climbsFullBlockStairsWithoutRequiringUniformSupport() {
        var p =
                new LeaperPredatorPlanner(
                        new LeaperPredatorPlanner.Terrain() {
                            public double height(double x, double z) {
                                return Math.clamp(Math.floor((x - 8) / 3), 0, 6);
                            }

                            public boolean bodyClear(double x, double y, double z) {
                                return true;
                            }
                        },
                        0,
                        0);
        var result = p.plan(34, 6, 0);
        assertTrue(result.found(), result.reason());
        assertEquals(6, result.points().getLast().y());
    }

    @Test
    void routesAroundTreesUsingBodyClearance() {
        var p =
                new LeaperPredatorPlanner(
                        new LeaperPredatorPlanner.Terrain() {
                            public double height(double x, double z) {
                                return 0;
                            }

                            public boolean bodyClear(double x, double y, double z) {
                                return !(x > 10 && x < 18 && Math.abs(z) < 4);
                            }
                        },
                        0,
                        0);
        var route = p.plan(30, 0, 0);
        assertTrue(route.found(), route.reason());
        assertTrue(route.points().stream().anyMatch(v -> Math.abs(v.z()) >= 4));
    }

    @Test
    void avoidsWideWaterAndRejectsWaterDestination() {
        var p =
                new LeaperPredatorPlanner(
                        new LeaperPredatorPlanner.Terrain() {
                            public double height(double x, double z) {
                                return x > 10 && x < 24 && Math.abs(z) < 10 ? Double.NaN : 0;
                            }

                            public boolean bodyClear(double x, double y, double z) {
                                return true;
                            }
                        },
                        0,
                        0);
        var route = p.plan(34, 0, 0);
        assertTrue(route.found(), route.reason());
        assertTrue(route.points().stream().anyMatch(v -> Math.abs(v.z()) >= 8));
        assertFalse(p.plan(18, 0, 0).found());
    }

    @Test
    void cannotClimbAnUnbrokenVerticalCliff() {
        var p =
                new LeaperPredatorPlanner(
                        new LeaperPredatorPlanner.Terrain() {
                            public double height(double x, double z) {
                                return x > 10 ? 9 : 0;
                            }

                            public boolean bodyClear(double x, double y, double z) {
                                return true;
                            }
                        },
                        0,
                        0);
        assertFalse(p.plan(24, 9, 0).found());
    }
}
