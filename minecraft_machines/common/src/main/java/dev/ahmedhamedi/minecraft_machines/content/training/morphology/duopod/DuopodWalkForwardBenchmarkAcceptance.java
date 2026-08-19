package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic acceptance rules for the fixed walk-forward benchmark.
 *
 * <p>The benchmark runner owns the Minecraft measurements; this class owns the
 * publishability gate so a malformed or incomplete artifact can never be
 * reported as accepted.</p>
 */
public final class DuopodWalkForwardBenchmarkAcceptance {
    public static final String LEARNED_CONTROLLER = "learned";
    public static final String NEUTRAL_CONTROLLER = "neutral";
    public static final String SCRIPTED_CONTROLLER = "scripted_alternating_sine";
    public static final List<String> CONTROLLERS = List.of(
            LEARNED_CONTROLLER,
            NEUTRAL_CONTROLLER,
            SCRIPTED_CONTROLLER);
    public static final List<Double> FORWARD_SPEEDS = List.of(0.50, 0.80, 1.10);
    public static final double REQUIRED_LEARNED_MARGIN_BLOCKS = 0.50;
    public static final double REQUIRED_MINIMUM_BODY_UP = 0.60;
    public static final double MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS = 3.0;

    private static final double SPEED_TOLERANCE = 1.0e-9;
    private static final double UNIT_INTERVAL_TOLERANCE = 1.0e-9;

    private DuopodWalkForwardBenchmarkAcceptance() {
    }

    public static Assessment assess(final List<EpisodeResult> episodes) {
        final List<EpisodeResult> safeEpisodes = episodes == null ? List.of() : List.copyOf(episodes);
        final boolean coverageValid = hasExactCoverage(safeEpisodes);
        final boolean invariantsValid = safeEpisodes.size() == CONTROLLERS.size() * FORWARD_SPEEDS.size()
                && safeEpisodes.stream().allMatch(DuopodWalkForwardBenchmarkAcceptance::validEpisode);

        final List<EpisodeResult> learned = byController(safeEpisodes, LEARNED_CONTROLLER);
        final List<EpisodeResult> neutral = byController(safeEpisodes, NEUTRAL_CONTROLLER);
        final List<EpisodeResult> scripted = byController(safeEpisodes, SCRIPTED_CONTROLLER);
        final double learnedMeanForward = meanForwardOrNaN(learned);
        final double neutralRawMeanForward = meanForwardOrNaN(neutral);
        final double scriptedRawMeanForward = meanForwardOrNaN(scripted);
        final double neutralComparisonMeanForward = stabilityAdjustedBaselineMeanForwardOrNaN(neutral);
        final double scriptedComparisonMeanForward = stabilityAdjustedBaselineMeanForwardOrNaN(scripted);
        final double learnedNeutralMargin = learnedMeanForward - neutralComparisonMeanForward;
        final double learnedScriptedMargin = learnedMeanForward - scriptedComparisonMeanForward;
        final double strongerBaselineMean = Math.max(neutralComparisonMeanForward, scriptedComparisonMeanForward);
        final double learnedMargin = learnedMeanForward - strongerBaselineMean;
        final int learnedFailures = (int) learned.stream().filter(EpisodeResult::machineFailure).count();
        final double learnedMinimumBodyUp = learned.stream()
                .mapToDouble(EpisodeResult::minimumBodyUp)
                .min()
                .orElse(Double.NaN);
        final double learnedMaximumPeakVerticalExcursion = learned.stream()
                .mapToDouble(EpisodeResult::peakVerticalExcursionBlocks)
                .max()
                .orElse(Double.NaN);
        final int learnedArenaEscapeCount = (int) learned.stream()
                .filter(EpisodeResult::arenaEscape)
                .count();

        final Map<String, Boolean> criteria = new LinkedHashMap<>();
        criteria.put("coverage_complete", coverageValid);
        criteria.put("metrics_finite_and_bounded", invariantsValid);
        criteria.put("learned_forward_margin_over_stronger_baseline", coverageValid
                && Double.isFinite(learnedMargin)
                && learnedMargin >= REQUIRED_LEARNED_MARGIN_BLOCKS);
        criteria.put("learned_no_failures", coverageValid && learnedFailures == 0);
        criteria.put("learned_positive_at_each_speed", coverageValid
                && learned.size() == FORWARD_SPEEDS.size()
                && learned.stream().allMatch(result -> result.forwardDisplacementBlocks() > 0.0));
        criteria.put("learned_minimum_body_up", coverageValid
                && Double.isFinite(learnedMinimumBodyUp)
                && learnedMinimumBodyUp >= REQUIRED_MINIMUM_BODY_UP);
        criteria.put("learned_anti_ballistic_peak_vertical_excursion", coverageValid
                && Double.isFinite(learnedMaximumPeakVerticalExcursion)
                && learnedMaximumPeakVerticalExcursion <= MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS);
        criteria.put("no_learned_arena_escape", coverageValid && learnedArenaEscapeCount == 0);

        final List<String> failedCriteria = criteria.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .toList();
        return new Assessment(
                failedCriteria.isEmpty(),
                coverageValid,
                invariantsValid,
                learnedMeanForward,
                neutralRawMeanForward,
                scriptedRawMeanForward,
                neutralComparisonMeanForward,
                scriptedComparisonMeanForward,
                learnedNeutralMargin,
                learnedScriptedMargin,
                strongerBaselineMean,
                learnedMargin,
                learnedFailures,
                learnedMinimumBodyUp,
                learnedMaximumPeakVerticalExcursion,
                learnedArenaEscapeCount,
                criteria,
                failedCriteria);
    }

    private static boolean hasExactCoverage(final List<EpisodeResult> episodes) {
        if (episodes.size() != CONTROLLERS.size() * FORWARD_SPEEDS.size()) {
            return false;
        }
        for (final String controller : CONTROLLERS) {
            for (final double speed : FORWARD_SPEEDS) {
                final long matches = episodes.stream()
                        .filter(result -> controller.equals(result.controller()))
                        .filter(result -> Math.abs(result.requestedForwardSpeed() - speed) <= SPEED_TOLERANCE)
                        .count();
                if (matches != 1L) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validEpisode(final EpisodeResult result) {
        return result != null
                && CONTROLLERS.contains(result.controller())
                && FORWARD_SPEEDS.stream().anyMatch(speed -> Math.abs(result.requestedForwardSpeed() - speed) <= SPEED_TOLERANCE)
                && result.controlSteps() > 0
                && result.terminalCaptured()
                && finite(
                result.requestedForwardSpeed(),
                result.episodeReturn(),
                result.forwardDisplacementBlocks(),
                result.lateralDisplacementBlocks(),
                result.minimumBodyUp(),
                result.peakVerticalExcursionBlocks(),
                result.actionTotalVariation(),
                result.actionSaturationFraction())
                && result.minimumBodyUp() >= -1.0 - UNIT_INTERVAL_TOLERANCE
                && result.minimumBodyUp() <= 1.0 + UNIT_INTERVAL_TOLERANCE
                && result.peakVerticalExcursionBlocks() >= 0.0
                && result.actionTotalVariation() >= 0.0
                && result.actionSaturationFraction() >= 0.0
                && result.actionSaturationFraction() <= 1.0 + UNIT_INTERVAL_TOLERANCE;
    }

    private static List<EpisodeResult> byController(
            final List<EpisodeResult> episodes,
            final String controller
    ) {
        final List<EpisodeResult> matches = new ArrayList<>();
        for (final EpisodeResult result : episodes) {
            if (result != null && controller.equals(result.controller())) {
                matches.add(result);
            }
        }
        return matches;
    }

    private static double meanForwardOrNaN(final List<EpisodeResult> episodes) {
        if (episodes.isEmpty()) {
            return Double.NaN;
        }
        return episodes.stream().mapToDouble(EpisodeResult::forwardDisplacementBlocks).average().orElse(Double.NaN);
    }

    /**
     * Computes the baseline value used by the learned-policy comparison.
     *
     * <p>A baseline episode that fails any publication stability invariant
     * (machine health, arena containment, minimum posture, or anti-ballistic
     * vertical excursion) cannot claim positive forward progress: its
     * contribution is capped at zero. Negative progress remains a penalty.
     * Raw episode and summary measurements are left intact.</p>
     */
    private static double stabilityAdjustedBaselineMeanForwardOrNaN(final List<EpisodeResult> episodes) {
        if (episodes.isEmpty()) {
            return Double.NaN;
        }
        return episodes.stream()
                .mapToDouble(result -> !publicationStable(result)
                        ? Math.min(0.0, result.forwardDisplacementBlocks())
                        : result.forwardDisplacementBlocks())
                .average()
                .orElse(Double.NaN);
    }

    private static boolean publicationStable(final EpisodeResult result) {
        return !result.machineFailure()
                && !result.arenaEscape()
                && result.minimumBodyUp() >= REQUIRED_MINIMUM_BODY_UP
                && result.peakVerticalExcursionBlocks() <= MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS;
    }

    private static boolean finite(final double... values) {
        for (final double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    public record EpisodeResult(
            String controller,
            double requestedForwardSpeed,
            int controlSteps,
            double episodeReturn,
            double forwardDisplacementBlocks,
            double lateralDisplacementBlocks,
            double minimumBodyUp,
            double peakVerticalExcursionBlocks,
            double actionTotalVariation,
            double actionSaturationFraction,
            boolean machineFailure,
            boolean arenaEscape,
            boolean terminalCaptured
    ) {
    }

    public record Assessment(
            boolean accepted,
            boolean coverageValid,
            boolean invariantsValid,
            double learnedMeanForwardBlocks,
            double neutralRawMeanForwardBlocks,
            double scriptedRawMeanForwardBlocks,
            double neutralStabilityAdjustedComparisonMeanForwardBlocks,
            double scriptedStabilityAdjustedComparisonMeanForwardBlocks,
            double learnedNeutralMarginBlocks,
            double learnedScriptedMarginBlocks,
            double strongerBaselineStabilityAdjustedComparisonMeanForwardBlocks,
            double learnedMarginBlocks,
            int learnedFailures,
            double learnedMinimumBodyUp,
            double learnedMaximumPeakVerticalExcursionBlocks,
            int learnedArenaEscapeCount,
            Map<String, Boolean> criteria,
            List<String> failedCriteria
    ) {
        public Assessment {
            criteria = Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
            failedCriteria = List.copyOf(failedCriteria);
        }
    }
}
