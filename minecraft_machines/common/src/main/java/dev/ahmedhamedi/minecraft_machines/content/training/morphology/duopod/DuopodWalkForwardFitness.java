package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

/**
 * Terminal fitness shaping for the publication-facing walk-forward curriculum.
 *
 * <p>The dense command-tracking return remains useful for learning within an
 * episode, but it is not sufficient evidence of a publishable gait. This
 * terminal term therefore mirrors the benchmark's causal requirements: forward
 * displacement is measured in the fixed spawn frame relative to the post-warmup
 * handoff, positive displacement is worth no credit after an unstable episode,
 * and success requires every benchmark stability invariant used during training.</p>
 *
 * <p>Each shaped per-speed return is passed to {@code ScoredGenome}; its minimum
 * receives the explicit worst-scenario weight in addition to the population
 * mean. Consequently, a controller cannot hide one backwards or unstable speed
 * behind stronger performance at the other speeds.</p>
 */
public final class DuopodWalkForwardFitness {
    public static final double REQUIRED_MINIMUM_BODY_UP =
            DuopodWalkForwardBenchmarkAcceptance.REQUIRED_MINIMUM_BODY_UP;
    /**
     * Neutral spawn settling observed in the controlled arena peaks near 2.07 blocks,
     * while the rejected ballistic controller reached roughly 7 blocks. Three blocks
     * leaves measured settling headroom without allowing launch-driven translation to
     * qualify as locomotion.
     */
    public static final double MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS =
            DuopodWalkForwardBenchmarkAcceptance.MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS;
    public static final double TERMINAL_FORWARD_WEIGHT = 4.0;
    public static final double TERMINAL_SUCCESS_BONUS = 4.0;
    public static final double TERMINAL_INSTABILITY_PENALTY = 6.0;
    public static final double TERMINAL_UPRIGHT_SHORTFALL_WEIGHT = 10.0;
    public static final double TERMINAL_VERTICAL_EXCESS_WEIGHT = 10.0;
    public static final double TERMINAL_ARENA_ESCAPE_PENALTY = 20.0;

    private DuopodWalkForwardFitness() {
    }

    public static EpisodeFitness assessEpisode(
            final double denseReturn,
            final double terminalPostWarmupForwardDisplacement,
            final double minimumBodyUp,
            final double peakVerticalExcursionBlocks,
            final boolean machineFailure,
            final boolean arenaEscape
    ) {
        requireFinite("denseReturn", denseReturn);
        requireFinite("terminalPostWarmupForwardDisplacement", terminalPostWarmupForwardDisplacement);
        requireFinite("minimumBodyUp", minimumBodyUp);
        requireFinite("peakVerticalExcursionBlocks", peakVerticalExcursionBlocks);
        if (minimumBodyUp < -1.0 || minimumBodyUp > 1.0) {
            throw new IllegalArgumentException("minimumBodyUp must be in [-1, 1]");
        }
        if (peakVerticalExcursionBlocks < 0.0) {
            throw new IllegalArgumentException("peakVerticalExcursionBlocks must be non-negative");
        }

        final double verticalExcess = Math.max(
                0.0,
                peakVerticalExcursionBlocks - MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS);
        final boolean stable = !machineFailure
                && !arenaEscape
                && minimumBodyUp >= REQUIRED_MINIMUM_BODY_UP
                && verticalExcess == 0.0;
        final double stabilityAdjustedForward = stable
                ? terminalPostWarmupForwardDisplacement
                : Math.min(0.0, terminalPostWarmupForwardDisplacement);
        final boolean success = stable && terminalPostWarmupForwardDisplacement > 0.0;
        final double uprightShortfall = Math.max(0.0, REQUIRED_MINIMUM_BODY_UP - minimumBodyUp);
        final double terminalShaping = TERMINAL_FORWARD_WEIGHT * stabilityAdjustedForward
                + (success ? TERMINAL_SUCCESS_BONUS : 0.0)
                - (stable ? 0.0 : TERMINAL_INSTABILITY_PENALTY)
                - TERMINAL_UPRIGHT_SHORTFALL_WEIGHT * uprightShortfall
                - TERMINAL_VERTICAL_EXCESS_WEIGHT * verticalExcess
                - (arenaEscape ? TERMINAL_ARENA_ESCAPE_PENALTY : 0.0);
        return new EpisodeFitness(
                denseReturn + terminalShaping,
                terminalShaping,
                stabilityAdjustedForward,
                verticalExcess,
                stable,
                success);
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    public record EpisodeFitness(
            double selectionReturn,
            double terminalShaping,
            double stabilityAdjustedForwardDisplacement,
            double verticalExcessBlocks,
            boolean stable,
            boolean success
    ) {
    }
}
