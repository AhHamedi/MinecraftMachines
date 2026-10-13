package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperTravelWindow;

import org.junit.jupiter.api.Test;

class LeaperTravelWindowTest {
    @Test
    void detectsRepeatedFailedAdvancesDespiteLargeMovement() {
        var window = new LeaperTravelWindow();
        for (int i = 0; i <= 100; i++) window.update(5 * Math.sin(i * Math.PI / 50), 0);
        assertTrue(window.looping());
    }

    @Test
    void permitsARealDetourAroundAnObstacle() {
        var window = new LeaperTravelWindow();
        for (int i = 0; i <= 100; i++)
            window.update(8 * Math.cos(i * Math.PI / 100), 8 * Math.sin(i * Math.PI / 100));
        assertFalse(window.looping());
    }

    @Test
    void doesNotConfuseContactJitterWithFailedTravel() {
        var window = new LeaperTravelWindow();
        for (int i = 0; i <= 100; i++) window.update(9000 + .01 * Math.sin(i), 3000);
        assertFalse(window.looping());
    }
}
