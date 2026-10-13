package dev.ahmedhamedi.minecraft_machines.content.training;

import static org.junit.jupiter.api.Assertions.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedDirectionalGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedPointController;

import org.junit.jupiter.api.Test;

import java.util.Random;

class QuadrupedNavigationTest {
    @Test
    void navigationIsInvariantToWorldRotationAndTranslation() {
        double rotation = 2.4, c = Math.cos(rotation), s = Math.sin(rotation);
        double gx = 13, gz = -8;
        var a = new QuadrupedPointController(new double[10], gx, gz);
        var b =
                new QuadrupedPointController(
                        new double[10], c * gx - s * gz + 512, s * gx + c * gz - 384);
        for (int i = 0; i < 40; i++) {
            double x = i * .04, z = -i * .08, h = i * .006;
            var ca = a.update(x, z, h, .1);
            var cb = b.update(c * x - s * z + 512, s * x + c * z - 384, h + rotation, .1);
            assertEquals(ca.forward(), cb.forward(), 1e-10);
            assertEquals(ca.turn(), cb.turn(), 1e-10);
            assertEquals(ca.distance(), cb.distance(), 1e-10);
        }
    }

    @Test
    void balanceFeedbackRotatesWithTheBody() {
        double rotation = 1.8, c = Math.cos(rotation), s = Math.sin(rotation), ux = .08, uz = -.05;
        var a = QuadrupedDirectionalGait.action(new double[10], 3.3, .2, ux, uz, .8, -.4);
        var b =
                QuadrupedDirectionalGait.action(
                        new double[10],
                        3.3,
                        .2 + rotation,
                        c * ux - s * uz,
                        s * ux + c * uz,
                        .8,
                        -.4);
        assertArrayEquals(a, b, 1e-12);
    }

    @Test
    void arrivalRequiresSettlingAndRetargetingReleasesTheHold() {
        var controller = new QuadrupedPointController(new double[10], 0, 0);
        assertFalse(controller.update(0, 0, 0, .1).arrived());
        QuadrupedPointController.Command result = null;
        for (int i = 0; i < 15; i++) result = controller.update(0, 0, 0, .1);
        assertTrue(result.arrived());
        assertEquals(0, result.forward());
        assertEquals(0, result.turn());
        controller.setGoal(0, -10);
        result = controller.update(0, 0, 0, .1);
        assertFalse(result.arrived());
        assertTrue(result.forward() > 0);
    }

    @Test
    void signedGaitsStayWithinMotorLimitsAcrossExploration() {
        var random = new Random(1907);
        for (int i = 0; i < 1000; i++) {
            var genome = random.doubles(10, -3, 3).toArray();
            var targets =
                    QuadrupedDirectionalGait.action(
                            genome,
                            random.nextDouble() * 100,
                            random.nextDouble() * 6,
                            random.nextDouble() - .5,
                            random.nextDouble() - .5,
                            random.nextDouble() * 3 - 1.5,
                            random.nextDouble() * 3 - 1.5);
            for (double t : targets) assertTrue(Double.isFinite(t) && Math.abs(t) <= 1.3);
        }
    }

    @Test
    void malformedGoalsAndObservationsAreRejectedBeforeActuation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new QuadrupedPointController(new double[9], 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new QuadrupedPointController(new double[10], Double.NaN, 0));
        var controller = new QuadrupedPointController(new double[10], 0, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.update(0, 0, Double.POSITIVE_INFINITY, .1));
        assertThrows(IllegalArgumentException.class, () -> controller.update(0, 0, 0, 0));
    }

    @Test
    void closeGoalsBehindTheBodyUseReverseStepsAndLargeOffsetsReleaseTheHold() {
        var controller = new QuadrupedPointController(new double[10], 0, 2);
        assertTrue(controller.update(0, 0, 0, .1).forward() < 0);
        controller.setGoal(0, 0);
        for (int i = 0; i < 20; i++) controller.update(0, 0, 0, .1);
        var disturbed = controller.update(0, 1, 0, .1);
        assertFalse(disturbed.arrived());
        assertTrue(disturbed.forward() > 0);
    }
}
