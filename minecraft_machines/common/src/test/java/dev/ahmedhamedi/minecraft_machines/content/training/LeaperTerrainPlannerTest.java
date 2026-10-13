package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperTerrainPlanner;

import org.junit.jupiter.api.Test;

class LeaperTerrainPlannerTest {
    @Test
    void climbsAndDescendsStairsKeepingSelectedElevation() {
        var p =
                new LeaperTerrainPlanner(
                        (x, z) -> Math.clamp(Math.floor((-z - 10) / 6), 0, 4), .5, .5);
        var r = p.plan(.5, 4, -42.5);
        assertTrue(r.found(), r.reason());
        assertEquals(4, r.points().getLast().y());
        assertTrue(
                new LeaperTerrainPlanner(
                                (x, z) -> 4 - Math.clamp(Math.floor((-z - 10) / 6), 0, 4), .5, .5)
                        .plan(.5, 0, -42.5)
                        .found());
    }

    @Test
    void routesWideBodyAroundWall() {
        var p =
                new LeaperTerrainPlanner(
                        (x, z) -> Math.abs(x) < 6 && z < -12 && z > -18 ? 6 : 0, .5, .5);
        var r = p.plan(.5, 0, -32);
        assertTrue(r.found(), r.reason());
        assertTrue(r.points().stream().anyMatch(q -> Math.abs(q.x()) >= 12));
    }

    @Test
    void refusesCliffWaterAndNarrowBridge() {
        assertFalse(
                new LeaperTerrainPlanner((x, z) -> z < -12 ? -5 : 0, 0, 0)
                        .plan(0, -5, -32)
                        .found());
        assertFalse(
                new LeaperTerrainPlanner((x, z) -> z < -12 ? Double.NaN : 0, 0, 0)
                        .plan(0, 0, -32)
                        .found());
        assertFalse(
                new LeaperTerrainPlanner(
                                (x, z) -> z < -8 && z > -28 && Math.abs(x) > 2 ? Double.NaN : 0,
                                0,
                                0)
                        .plan(0, 0, -36)
                        .found());
    }
}
