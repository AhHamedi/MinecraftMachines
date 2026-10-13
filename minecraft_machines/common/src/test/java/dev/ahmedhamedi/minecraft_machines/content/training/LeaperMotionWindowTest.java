package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperMotionWindow;

import org.junit.jupiter.api.Test;

class LeaperMotionWindowTest {
    @Test
    void requiresAFullObservationWindow() {
        var w = new LeaperMotionWindow();
        for (int i = 0; i < 20; i++) w.update(0, 0);
        assertFalse(w.stationary());
        w.update(0, 0);
        assertTrue(w.stationary());
    }

    @Test
    void acceptsMillimetreContactJitterAtLargeCoordinates() {
        var w = new LeaperMotionWindow();
        for (int i = 0; i < 21; i++) w.update(9000 + Math.sin(i) * .001, 1700);
        assertTrue(w.stationary());
    }

    @Test
    void rejectsOscillationDespiteReturningToTheSamePoint() {
        var w = new LeaperMotionWindow();
        for (int i = 0; i < 21; i++) w.update(Math.sin(i * Math.PI / 10) * .3, 0);
        assertFalse(w.stationary());
    }

    @Test
    void rejectsTravelThroughTheTarget() {
        var w = new LeaperMotionWindow();
        for (int i = 0; i < 21; i++) w.update(i * .1, 0);
        assertFalse(w.stationary());
    }
}
