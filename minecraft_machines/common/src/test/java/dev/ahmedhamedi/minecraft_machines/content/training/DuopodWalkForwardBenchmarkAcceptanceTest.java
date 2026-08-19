package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodSchemas;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodWalkForwardBaselines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodWalkForwardBenchmarkAcceptance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuopodWalkForwardBenchmarkAcceptanceTest {
    @Test
    void acceptsOnlyACompleteStableLearnedAdvantage() {
        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(acceptedEpisodes());

        assertTrue(assessment.accepted());
        assertTrue(assessment.coverageValid());
        assertTrue(assessment.invariantsValid());
        assertEquals(0.8, assessment.learnedMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.1, assessment.neutralRawMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.2, assessment.scriptedRawMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.1, assessment.neutralStabilityAdjustedComparisonMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.2, assessment.scriptedStabilityAdjustedComparisonMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.2, assessment.strongerBaselineStabilityAdjustedComparisonMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.7, assessment.learnedNeutralMarginBlocks(), 1.0e-10);
        assertEquals(0.6, assessment.learnedScriptedMarginBlocks(), 1.0e-10);
        assertEquals(0.6, assessment.learnedMarginBlocks(), 1.0e-10);
        assertEquals(0.2, assessment.learnedMaximumPeakVerticalExcursionBlocks(), 1.0e-10);
        assertEquals(0, assessment.learnedArenaEscapeCount());
        assertTrue(assessment.failedCriteria().isEmpty());
    }

    @Test
    void rejectsBallisticPeakVerticalExcursionFromLearnedController() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.set(0, episode(
                DuopodWalkForwardBenchmarkAcceptance.LEARNED_CONTROLLER,
                0.50,
                0.7,
                false,
                0.70,
                0.0,
                true,
                3.01,
                false));

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertFalse(assessment.accepted());
        assertEquals(3.01, assessment.learnedMaximumPeakVerticalExcursionBlocks(), 1.0e-10);
        assertFalse(assessment.criteria().get("learned_anti_ballistic_peak_vertical_excursion"));
    }

    @Test
    void rejectsLearnedEpisodeThatEscapesItsControlledArenaLane() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.set(1, episode(
                DuopodWalkForwardBenchmarkAcceptance.LEARNED_CONTROLLER,
                0.80,
                0.8,
                false,
                0.75,
                0.0,
                true,
                0.2,
                true));

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertFalse(assessment.accepted());
        assertTrue(assessment.invariantsValid());
        assertEquals(1, assessment.learnedArenaEscapeCount());
        assertFalse(assessment.criteria().get("no_learned_arena_escape"));
    }

    @Test
    void escapedBaselineCannotClaimPositiveForwardCreditButDoesNotVetoLearnedPolicy() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.set(6, episode(
                DuopodWalkForwardBenchmarkAcceptance.SCRIPTED_CONTROLLER,
                0.50,
                4.0,
                false,
                0.70,
                0.0,
                true,
                0.2,
                true));

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertTrue(assessment.accepted());
        assertEquals(4.4 / 3.0, assessment.scriptedRawMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.4 / 3.0, assessment.scriptedStabilityAdjustedComparisonMeanForwardBlocks(), 1.0e-10);
        assertTrue(assessment.criteria().get("learned_anti_ballistic_peak_vertical_excursion"));
        assertTrue(assessment.criteria().get("no_learned_arena_escape"));
    }

    @Test
    void ballisticOrLowPostureBaselineCannotClaimPositiveForwardCredit() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.set(3, episode(
                DuopodWalkForwardBenchmarkAcceptance.NEUTRAL_CONTROLLER,
                0.50,
                4.0,
                false,
                0.59,
                0.0,
                true,
                0.2,
                false));
        episodes.set(6, episode(
                DuopodWalkForwardBenchmarkAcceptance.SCRIPTED_CONTROLLER,
                0.50,
                5.0,
                false,
                0.70,
                0.0,
                true,
                3.01,
                false));

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertTrue(assessment.accepted());
        assertEquals(0.2 / 3.0, assessment.neutralStabilityAdjustedComparisonMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.4 / 3.0, assessment.scriptedStabilityAdjustedComparisonMeanForwardBlocks(), 1.0e-10);
    }

    @Test
    void requiresMarginOverTheStrongerBaseline() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        for (int index = 6; index < 9; index++) {
            final double speed = DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.get(index - 6);
            episodes.set(index, episode(
                    DuopodWalkForwardBenchmarkAcceptance.SCRIPTED_CONTROLLER,
                    speed,
                    0.4,
                    false,
                    0.70,
                    0.0,
                    true));
        }

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertEquals(0.7, assessment.learnedNeutralMarginBlocks(), 1.0e-10);
        assertEquals(0.4, assessment.learnedScriptedMarginBlocks(), 1.0e-10);
        assertFalse(assessment.accepted());
        assertFalse(assessment.criteria().get("learned_forward_margin_over_stronger_baseline"));
    }

    @Test
    void failedBaselinesCannotClaimPositiveForwardCredit() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.set(3, episode(
                DuopodWalkForwardBenchmarkAcceptance.NEUTRAL_CONTROLLER,
                0.50,
                2.0,
                true,
                0.70,
                0.0,
                true));
        episodes.set(6, episode(
                DuopodWalkForwardBenchmarkAcceptance.SCRIPTED_CONTROLLER,
                0.50,
                3.0,
                true,
                0.70,
                0.0,
                true));

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertEquals(2.2 / 3.0, assessment.neutralRawMeanForwardBlocks(), 1.0e-10);
        assertEquals(3.4 / 3.0, assessment.scriptedRawMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.2 / 3.0, assessment.neutralStabilityAdjustedComparisonMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.4 / 3.0, assessment.scriptedStabilityAdjustedComparisonMeanForwardBlocks(), 1.0e-10);
        assertEquals(0.4 / 3.0,
                assessment.strongerBaselineStabilityAdjustedComparisonMeanForwardBlocks(),
                1.0e-10);
        assertEquals(0.8 - 0.4 / 3.0, assessment.learnedMarginBlocks(), 1.0e-10);
        assertTrue(assessment.criteria().get("learned_forward_margin_over_stronger_baseline"));
        assertTrue(assessment.accepted());
    }

    @Test
    void failedBaselineRetainsNegativeForwardPenalty() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.set(6, episode(
                DuopodWalkForwardBenchmarkAcceptance.SCRIPTED_CONTROLLER,
                0.50,
                -0.9,
                true,
                0.70,
                0.0,
                true));

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertEquals(-0.5 / 3.0, assessment.scriptedRawMeanForwardBlocks(), 1.0e-10);
        assertEquals(-0.5 / 3.0,
                assessment.scriptedStabilityAdjustedComparisonMeanForwardBlocks(),
                1.0e-10);
        assertEquals(0.1,
                assessment.strongerBaselineStabilityAdjustedComparisonMeanForwardBlocks(),
                1.0e-10);
        assertEquals(0.7, assessment.learnedMarginBlocks(), 1.0e-10);
        assertTrue(assessment.accepted());
    }

    @Test
    void learnedFailureRemainsRawAndStillRejectsAcceptance() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.set(0, episode(
                DuopodWalkForwardBenchmarkAcceptance.LEARNED_CONTROLLER,
                0.50,
                4.0,
                true,
                0.75,
                0.0,
                true));

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertEquals(5.7 / 3.0, assessment.learnedMeanForwardBlocks(), 1.0e-10);
        assertEquals(1, assessment.learnedFailures());
        assertFalse(assessment.criteria().get("learned_no_failures"));
        assertFalse(assessment.accepted());
    }

    @Test
    void rejectsMissingCoverageEvenWhenRemainingRowsLookStrong() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.remove(0);

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertFalse(assessment.accepted());
        assertFalse(assessment.coverageValid());
        assertTrue(assessment.failedCriteria().contains("coverage_complete"));
    }

    @Test
    void rejectsFailureBadPostureAndInvalidMetrics() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>(acceptedEpisodes());
        episodes.set(0, episode(
                DuopodWalkForwardBenchmarkAcceptance.LEARNED_CONTROLLER,
                0.50,
                0.9,
                true,
                0.40,
                Double.NaN,
                true));

        final var assessment = DuopodWalkForwardBenchmarkAcceptance.assess(episodes);

        assertFalse(assessment.accepted());
        assertFalse(assessment.invariantsValid());
        assertFalse(assessment.criteria().get("learned_no_failures"));
        assertFalse(assessment.criteria().get("learned_minimum_body_up"));
        assertFalse(assessment.criteria().get("metrics_finite_and_bounded"));
    }

    @Test
    void alternatingSineBaselineIsBoundedAndOpposed() {
        final double[] observation = new double[DuopodSchemas.observationSpec().size()];
        observation[0] = 1.0;
        observation[1] = 0.0;

        assertArrayEquals(new double[]{0.65, -0.65},
                DuopodWalkForwardBaselines.alternatingSineAction(observation),
                1.0e-10);
        assertArrayEquals(new double[]{0.0, 0.0}, DuopodWalkForwardBaselines.neutralAction(), 1.0e-10);
        assertThrows(IllegalArgumentException.class,
                () -> DuopodWalkForwardBaselines.alternatingSineAction(new double[2]));
    }

    private static List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> acceptedEpisodes() {
        final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> episodes = new ArrayList<>();
        for (int speed = 0; speed < DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.size(); speed++) {
            episodes.add(episode(
                    DuopodWalkForwardBenchmarkAcceptance.LEARNED_CONTROLLER,
                    DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.get(speed),
                    0.7 + speed * 0.1,
                    false,
                    0.75,
                    0.0,
                    true));
        }
        for (final double speed : DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS) {
            episodes.add(episode(
                    DuopodWalkForwardBenchmarkAcceptance.NEUTRAL_CONTROLLER,
                    speed,
                    0.1,
                    false,
                    0.90,
                    0.0,
                    true));
        }
        for (final double speed : DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS) {
            episodes.add(episode(
                    DuopodWalkForwardBenchmarkAcceptance.SCRIPTED_CONTROLLER,
                    speed,
                    0.2,
                    false,
                    0.70,
                    0.0,
                    true));
        }
        return episodes;
    }

    private static DuopodWalkForwardBenchmarkAcceptance.EpisodeResult episode(
            final String controller,
            final double speed,
            final double forward,
            final boolean failure,
            final double minimumBodyUp,
            final double saturation,
            final boolean terminalCaptured
    ) {
        return episode(
                controller,
                speed,
                forward,
                failure,
                minimumBodyUp,
                saturation,
                terminalCaptured,
                0.2,
                false);
    }

    private static DuopodWalkForwardBenchmarkAcceptance.EpisodeResult episode(
            final String controller,
            final double speed,
            final double forward,
            final boolean failure,
            final double minimumBodyUp,
            final double saturation,
            final boolean terminalCaptured,
            final double peakVerticalExcursion,
            final boolean arenaEscape
    ) {
        return new DuopodWalkForwardBenchmarkAcceptance.EpisodeResult(
                controller,
                speed,
                100,
                3.0,
                forward,
                0.05,
                minimumBodyUp,
                peakVerticalExcursion,
                2.0,
                saturation,
                failure,
                arenaEscape,
                terminalCaptured);
    }
}
