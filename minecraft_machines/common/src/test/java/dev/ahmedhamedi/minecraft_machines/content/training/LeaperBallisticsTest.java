package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperBallistics;

import org.junit.jupiter.api.Test;

class LeaperBallisticsTest {
    @Test
    void reachesRaisedLoweredAndDiagonalDestinationsAtDescendingIntersection() {
        for (double g : new double[] {9.81, 20, 32})
            for (double dy : new double[] {-4, 0, 4}) {
                var a = LeaperBallistics.solve(3, 10, -2, 15, 10 + dy, 8, g, 3);
                assertArrayEquals(new double[] {15, 10 + dy, 8}, a.at(a.seconds()), 1e-10);
                assertEquals(Math.max(10, 10 + dy) + 3, a.at(a.vy() / g)[1], 1e-10);
                assertTrue(a.vy() - g * a.seconds() < 0);
            }
    }

    @Test
    void rejectsInvalidGravityAndNonfiniteTargets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LeaperBallistics.solve(0, 0, 0, 10, 0, 0, 0, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> LeaperBallistics.solve(0, 0, 0, Double.NaN, 0, 0, 10, 3));
    }

    @org.junit.jupiter.api.Test
    void dampedArcsReachElevatedAndDescendingLandings() {
        for (double drag : new double[] {.01, .09, .5})
            for (double dy : new double[] {-3, 0, 3}) {
                var arc = LeaperBallistics.solve(0, 10, 0, 18, 10 + dy, -6, 11, 2.5, drag);
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        new double[] {18, 10 + dy, -6}, arc.at(arc.seconds()), 1e-8);
                double top = Math.log1p(drag * arc.vy() / 11) / drag;
                org.junit.jupiter.api.Assertions.assertEquals(
                        Math.max(10, 10 + dy) + 2.5, arc.at(top)[1], 1e-8);
            }
    }

    @Test
    void timedPouncesCoverExtremeDistanceWithActualDrag() {
        for (double length : new double[] {16, 40, 72})
            for (double dy : new double[] {-3, 0, 3}) {
                var a =
                        LeaperBallistics.timed(
                                0, 10, 0, length, 10 + dy, 0, 11, .09, Math.max(1, length / 38));
                assertArrayEquals(new double[] {length, 10 + dy, 0}, a.at(a.seconds()), 1e-8);
            }
    }
}
