package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperSupportWindow;

import org.junit.jupiter.api.Test;

class LeaperSupportWindowTest {
    @Test
    void anyFootCanEstablishTakeoffSupport() {
        for (int feet = 1; feet <= 4; feet++) {
            var w = new LeaperSupportWindow();
            w.update(feet, 1.8, .8, .03);
            assertFalse(w.ready());
            w.update(feet, -1.8, .8, .03);
            assertTrue(w.ready());
        }
    }

    @Test
    void briefLossHasGraceButFreefallCannotCreateSupport() {
        var w = new LeaperSupportWindow();
        for (int i = 0; i < 20; i++) w.update(0, 0, 1, 0);
        assertFalse(w.ready());
        w.update(1, 0, 1, 0);
        w.update(1, 0, 1, 0);
        for (int i = 0; i < 2; i++) {
            w.update(0, -1, 1, 0);
            assertTrue(w.ready());
        }
        w.update(0, -1, 1, 0);
        assertFalse(w.ready());
    }

    @Test
    void launchConsumesSupportAndUnsafeMotionClearsIt() {
        var w = new LeaperSupportWindow();
        for (double[] bad :
                new double[][] {{-6, 1, 0}, {0, .5, 0}, {0, 1, .2}, {Double.NaN, 1, 0}}) {
            w.update(1, 0, 1, 0);
            w.update(1, 0, 1, 0);
            assertTrue(w.ready());
            w.update(1, bad[0], bad[1], bad[2]);
            assertFalse(w.ready());
        }
        w.update(1, 0, 1, 0);
        w.update(1, 0, 1, 0);
        w.reset();
        w.update(0, 0, 1, 0);
        assertFalse(w.ready());
    }
}
