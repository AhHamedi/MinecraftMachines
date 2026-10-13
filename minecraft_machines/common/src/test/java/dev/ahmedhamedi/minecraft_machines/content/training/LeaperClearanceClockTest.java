package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperClearanceClock;

import org.junit.jupiter.api.Test;

class LeaperClearanceClockTest {
    private final double[] low = {0, 0, 0, 0}, high = {3, 3, 3, 3}, clear = {3.3, 3.3, 3.3, 3.3};

    @Test
    void clearOrFlatFeetKeepTheRequestedCadence() {
        var clock = new LeaperClearanceClock();
        assertEquals(.73, clock.advance(.68, .05, .54, 0, 0, clear, low, high, .3), 1e-12);
        assertEquals(.73, clock.advance(.68, .05, .54, 0, 0, low, low, low, .3), 1e-12);
        assertEquals(0, clock.heldTicks());
    }

    @Test
    void unclearedRiserDelaysTravelButNeverFreezesTheClock() {
        var clock = new LeaperClearanceClock();
        double next = clock.advance(.68, .05, .54, 0, 0, low, low, high, .3);
        assertTrue(next > .68 && next < .73);
        assertEquals(1, clock.heldTicks());
    }

    @Test
    void unreachableRiserExhaustsItsBudgetWithinTheSameStroke() {
        var clock = new LeaperClearanceClock();
        double phase = .68;
        for (int i = 0; i < 4; i++)
            phase = clock.advance(phase, .05, .54, 0, 0, low, low, high, .2);
        double before = phase;
        phase = clock.advance(phase, .05, .54, 0, 0, low, low, high, .2);
        assertEquals(.05, phase - before, 1e-12);
        assertEquals(4, clock.heldTicks());
    }

    @Test
    void disabledFeedbackAndLateLandingNeverPause() {
        var clock = new LeaperClearanceClock();
        assertEquals(.73, clock.advance(.68, .05, .54, 0, 0, low, low, high, 0), 1e-12);
        assertEquals(.97, clock.advance(.92, .05, .54, 0, 0, low, low, high, .5), 1e-12);
        assertEquals(0, clock.heldTicks());
    }
}
