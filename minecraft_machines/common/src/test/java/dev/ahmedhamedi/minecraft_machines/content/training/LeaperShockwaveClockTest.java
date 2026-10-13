package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperShockwaveClock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperShockwaveClock.Event.*;

class LeaperShockwaveClockTest {
    private LeaperShockwaveClock armed() {
        var c = new LeaperShockwaveClock();
        for (int i = 0; i < 40; i++) assertEquals(NONE, c.tick(true, true));
        return c;
    }
    @Test void exactTenBlockBoundaryAndVerticalDistance() {
        assertTrue(LeaperShockwaveClock.inRange(6, 0, 8));
        assertTrue(LeaperShockwaveClock.inRange(0, 10, 0));
        assertFalse(LeaperShockwaveClock.inRange(10.001, 0, 0));
        assertFalse(LeaperShockwaveClock.inRange(6, .01, 8));
        assertFalse(LeaperShockwaveClock.inRange(Double.NaN, 0, 0));
    }
    @Test void cannotBeginWithoutNearbyPlayerOrWhileAirborne() {
        var c = armed();
        assertEquals(NONE, c.tick(false, true));
        assertEquals(NONE, c.tick(true, false));
        assertEquals(START, c.tick(true, true));
    }
    @Test void triggerAndTerrainReachTenBlocksWhileDamageRemainsFive() {
        assertTrue(LeaperShockwaveClock.inRange(8, 0, 0));
        assertFalse(LeaperShockwaveClock.inRange(8, 0, 0, LeaperShockwaveClock.DAMAGE_RADIUS));
        assertEquals(5, LeaperShockwaveClock.DAMAGE_RADIUS);
        assertTrue(LeaperShockwaveClock.inRange(6, 0, 8, LeaperShockwaveClock.LAUNCH_RADIUS));
        assertTrue(LeaperShockwaveClock.inRange(0, 10, 0, LeaperShockwaveClock.LAUNCH_RADIUS));
        assertFalse(LeaperShockwaveClock.inRange(10.001, 0, 0, LeaperShockwaveClock.LAUNCH_RADIUS));
        assertFalse(LeaperShockwaveClock.inRange(0, 10.001, 0, LeaperShockwaveClock.LAUNCH_RADIUS));
        assertTrue(LeaperShockwaveClock.inRange(6, 0, 8, LeaperShockwaveClock.BLOCK_RADIUS));
        assertEquals(80, LeaperShockwaveClock.COOLDOWN_TICKS);
        assertFalse(LeaperShockwaveClock.inRange(10.001, 0, 0, LeaperShockwaveClock.BLOCK_RADIUS));
        assertFalse(LeaperShockwaveClock.inRange(0, 0, 0, Double.NaN));
        assertFalse(LeaperShockwaveClock.inRange(0, 0, 0, -1));
    }
    @Test void committedAttackBlastsExactlyOnceEvenWhenPlayerEscapes() {
        var c = armed(); assertEquals(START, c.tick(true, true));
        for (int i = 1; i < 60; i++) assertEquals(NONE, c.tick(false, false));
        assertEquals(BLAST, c.tick(false, false)); assertEquals(60, c.age());
        for (int i = 61; i < 96; i++) assertEquals(NONE, c.tick(true, true));
        assertEquals(END, c.tick(true, true)); assertFalse(c.active());
        for (int i = 0; i < 80; i++) assertEquals(NONE, c.tick(true, true));
        assertEquals(START, c.tick(true, true));
    }
    @Test void removalDisposesThePendingBlast() {
        var c = armed(); c.tick(true, true);
        for (int i = 0; i < 59; i++) c.tick(true, true);
        c.cancel(); assertFalse(c.active());
        for (int i = 0; i < 80; i++) assertEquals(NONE, c.tick(true, true));
    }
    @Test void clocksAreIndependentForMultipleLeapers() {
        var a = armed(); var b = armed(); a.tick(true, true);
        assertEquals(-1, b.age()); assertEquals(START, b.tick(true, true));
        a.cancel(); assertTrue(b.active());
    }
}
