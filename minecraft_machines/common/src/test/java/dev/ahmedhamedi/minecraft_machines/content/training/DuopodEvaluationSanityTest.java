package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodEvaluationSanity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DuopodEvaluationSanityTest {
    @Test
    void acceptsPhysicalProgressAndBoundaryRates() {
        assertEquals(0.0, DuopodEvaluationSanity.requireUnitInterval("success rate", 0.0));
        assertEquals(1.0, DuopodEvaluationSanity.requireUnitInterval("success rate", 1.0));
        assertEquals(4.0, DuopodEvaluationSanity.requireMatchingInitialDistance(4.0, 4.0, 1.0e-6));
        assertEquals(3.0, DuopodEvaluationSanity.requireReachableProgress(8.0, 5.0, 3.0, 1.0e-6));
        assertEquals(-2.0, DuopodEvaluationSanity.requireReachableProgress(8.0, 10.0, 0.5, 1.0e-6));
    }

    @Test
    void rejectsOutOfRangeRatesAndImpossibleProgress() {
        assertThrows(IllegalStateException.class,
                () -> DuopodEvaluationSanity.requireUnitInterval("success rate", -0.01));
        assertThrows(IllegalStateException.class,
                () -> DuopodEvaluationSanity.requireUnitInterval("success rate", 1.01));
        assertThrows(IllegalStateException.class,
                () -> DuopodEvaluationSanity.requireMatchingInitialDistance(8.0, 7.5, 0.01));
        assertThrows(IllegalStateException.class,
                () -> DuopodEvaluationSanity.requireReachableProgress(12.0, 1.0, 2.8, 0.001));
    }

    @Test
    void allowsOnlyTheConfiguredNumericalTolerance() {
        assertEquals(3.0005,
                DuopodEvaluationSanity.requireReachableProgress(8.0, 4.9995, 3.0, 0.001),
                1.0e-10);
        assertThrows(IllegalStateException.class,
                () -> DuopodEvaluationSanity.requireReachableProgress(8.0, 4.998, 3.0, 0.001));
        assertThrows(IllegalArgumentException.class,
                () -> DuopodEvaluationSanity.requireReachableProgress(8.0, 5.0, 3.0, -1.0));
    }
}
