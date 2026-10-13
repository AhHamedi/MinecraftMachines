package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedGaitPolicy;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class QuadrupedGaitPolicyTest {
    @Test void inverseKinematicsPlacesThePhysicalFoot() {
        for (double forward : new double[]{-0.4, 0, 0.4}) {
            for (double height : new double[]{2.9, 3.1, 3.3}) {
                double[] joints = QuadrupedGaitPolicy.inverseKinematics(forward, height);
                assertEquals(forward, 2 * Math.sin(joints[0]) + 1.5 * Math.sin(joints[0] + joints[1]), 1e-8);
                assertEquals(height, 2 * Math.cos(joints[0]) + 1.5 * Math.cos(joints[0] + joints[1]), 1e-8);
            }
        }
    }

    @Test void randomExplorationCannotProduceInvalidMotorTargets() {
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            double[] genome = random.doubles(10, -3, 3).toArray();
            double[] targets = QuadrupedGaitPolicy.action(genome, random.nextDouble() * 100,
                    random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() * 1.5);
            assertEquals(8, targets.length);
            for (double target : targets) assertTrue(Double.isFinite(target) && Math.abs(target) <= 1.3);
        }
    }

    @Test void invalidPolicyAndFeedbackFailBeforeActuation() {
        assertThrows(IllegalArgumentException.class, () -> QuadrupedGaitPolicy.action(new double[9], 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> QuadrupedGaitPolicy.action(new double[10], 0, Double.NaN, 0, 1));
        double[] genome = new double[10]; genome[3] = Double.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> QuadrupedGaitPolicy.action(genome, 0, 0, 0, 1));
    }
}
