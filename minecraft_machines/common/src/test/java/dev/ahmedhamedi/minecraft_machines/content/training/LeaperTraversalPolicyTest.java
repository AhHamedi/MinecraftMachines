package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperTraversalPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeaperTraversalPolicyTest {
    @Test void nearbyAndMediumDistancePursuitWalks() {
        for (double distance : new double[] {0, 3, 5, 12, 14, 20, 31.99})
            assertFalse(LeaperTraversalPolicy.shouldJump(distance, 0, false));
    }
    @Test void longDistanceTravelCanJump() {
        assertTrue(LeaperTraversalPolicy.shouldJump(32, 0, false));
        assertTrue(LeaperTraversalPolicy.shouldJump(80, 0, false));
    }
    @Test void meaningfulAscentAndDescentCanJump() {
        assertTrue(LeaperTraversalPolicy.shouldJump(12, 3, false));
        assertTrue(LeaperTraversalPolicy.shouldJump(12, -3, false));
        assertFalse(LeaperTraversalPolicy.shouldJump(12, 2, false));
        assertFalse(LeaperTraversalPolicy.shouldJump(12, -2, false));
    }
    @Test void closeHighLedgeMayRequireBackingUpForLaunchRoom() {
        assertTrue(LeaperTraversalPolicy.shouldJump(2, 6, false));
        assertFalse(LeaperTraversalPolicy.shouldJump(0, 6, false));
    }
    @Test void realGapRemainsJumpableAtShorterDistance() {
        assertTrue(LeaperTraversalPolicy.shouldJump(12, 0, true));
        assertFalse(LeaperTraversalPolicy.shouldJump(4, 0, true));
    }
    @Test void invalidCoordinatesNeverStartAJump() {
        assertFalse(LeaperTraversalPolicy.shouldJump(Double.NaN, 5, true));
        assertFalse(LeaperTraversalPolicy.shouldJump(40, Double.NaN, true));
        assertFalse(LeaperTraversalPolicy.shouldJump(-40, 0, true));
    }
}
