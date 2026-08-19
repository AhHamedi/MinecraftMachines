package dev.ahmedhamedi.minecraft_machines.content.worm.training;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormCemTrainingTest {
    @Test
    void defaultTrainingSpawnsOverlappingPopulation() {
        assertEquals(0, WormCemConfig.DEFAULT.spacingBlocks());
    }

    @Test
    void candidateTargetUsesBiasAmplitudeFrequencyAndPhase() {
        final WormCemCandidate candidate = new WormCemCandidate(30.0, 1.0, 0.0, 10.0);

        assertEquals(10.0, candidate.targetAngleDegrees(0.0), 1.0e-10);
        assertEquals(40.0, candidate.targetAngleDegrees(0.25), 1.0e-10);
        assertEquals(10.0, candidate.targetAngleDegrees(0.5), 1.0e-10);
    }

    @Test
    void clampedCandidateStaysInsideSafeSamplingRange() {
        final WormCemCandidate candidate = WormCemCandidate.clamped(200.0, -10.0, Math.PI * 4.0, -200.0);

        assertEquals(WormCemCandidate.MAX_AMPLITUDE_DEG, candidate.amplitudeDeg());
        assertEquals(WormCemCandidate.MIN_FREQUENCY_HZ, candidate.frequencyHz());
        assertEquals(WormCemCandidate.MAX_PHASE_RAD, candidate.phaseRad());
        assertEquals(WormCemCandidate.MIN_BIAS_DEG, candidate.biasDeg());
    }

    @Test
    void initialDistributionSamplesClampedCandidates() {
        final WormCemDistribution distribution = WormCemDistribution.initial();
        final Random random = new Random(42L);

        for (int i = 0; i < 500; i++) {
            final WormCemCandidate candidate = distribution.sample(random);
            assertTrue(candidate.amplitudeDeg() >= WormCemCandidate.MIN_AMPLITUDE_DEG);
            assertTrue(candidate.amplitudeDeg() <= WormCemCandidate.MAX_AMPLITUDE_DEG);
            assertTrue(candidate.frequencyHz() >= WormCemCandidate.MIN_FREQUENCY_HZ);
            assertTrue(candidate.frequencyHz() <= WormCemCandidate.MAX_FREQUENCY_HZ);
            assertTrue(candidate.phaseRad() >= WormCemCandidate.MIN_PHASE_RAD);
            assertTrue(candidate.phaseRad() <= WormCemCandidate.MAX_PHASE_RAD);
            assertTrue(candidate.biasDeg() >= WormCemCandidate.MIN_BIAS_DEG);
            assertTrue(candidate.biasDeg() <= WormCemCandidate.MAX_BIAS_DEG);
        }
    }

    @Test
    void distributionMovesTowardEliteCandidates() {
        final WormCemDistribution distribution = WormCemDistribution.initial();
        final List<ScoredWormCandidate> scored = List.of(
                new ScoredWormCandidate(new WormCemCandidate(70.0, 2.0, 5.0, 30.0), 10.0, 10.0, 0.0),
                new ScoredWormCandidate(new WormCemCandidate(60.0, 1.8, 4.0, 20.0), 9.0, 9.0, 0.0),
                new ScoredWormCandidate(new WormCemCandidate(5.0, 0.2, 0.2, -50.0), -1.0, -1.0, 0.0)
        );

        final WormCemDistribution updated = distribution.update(scored, 2);

        assertEquals(54.5, updated.meanAmplitudeDeg(), 1.0e-10);
        assertEquals(1.585, updated.meanFrequencyHz(), 1.0e-10);
        assertEquals(4.024557428756427, updated.meanPhaseRad(), 1.0e-10);
        assertEquals(16.25, updated.meanBiasDeg(), 1.0e-10);
        assertTrue(updated.stdAmplitudeDeg() >= 2.5);
        assertTrue(updated.stdFrequencyHz() >= 0.05);
        assertTrue(updated.stdPhaseRad() >= 0.05);
        assertTrue(updated.stdBiasDeg() >= 2.5);
    }
}
