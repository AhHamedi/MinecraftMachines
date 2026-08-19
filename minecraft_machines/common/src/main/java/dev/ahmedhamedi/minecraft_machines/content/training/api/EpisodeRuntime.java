package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.Arrays;
import java.util.Map;

public record EpisodeRuntime(
        long episodeId,
        long seed,
        int controlStep,
        int elapsedServerTicks,
        double phaseRad,
        double deltaSeconds,
        double[] previousAction,
        double previousDistanceToTarget,
        double accumulatedReward,
        Map<String, Double> rewardTotals,
        int repairedObservationCount,
        double previousBalanceError,
        int stableSuccessControlSteps,
        int fallFailureControlSteps
) {
    public EpisodeRuntime(
            final long episodeId,
            final long seed,
            final int controlStep,
            final int elapsedServerTicks,
            final double phaseRad,
            final double deltaSeconds,
            final double[] previousAction,
            final double previousDistanceToTarget,
            final double accumulatedReward,
            final Map<String, Double> rewardTotals,
            final int repairedObservationCount
    ) {
        this(
                episodeId,
                seed,
                controlStep,
                elapsedServerTicks,
                phaseRad,
                deltaSeconds,
                previousAction,
                previousDistanceToTarget,
                accumulatedReward,
                rewardTotals,
                repairedObservationCount,
                Double.NaN,
                0,
                0);
    }

    public EpisodeRuntime {
        if (controlStep < 0 || elapsedServerTicks < 0) {
            throw new IllegalArgumentException("elapsed counters must be non-negative");
        }
        requireFinite("phaseRad", phaseRad);
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and positive");
        }
        previousAction = Arrays.copyOf(previousAction, previousAction.length);
        requireFinite("previousDistanceToTarget", previousDistanceToTarget);
        requireFinite("accumulatedReward", accumulatedReward);
        rewardTotals = Map.copyOf(rewardTotals);
        if (repairedObservationCount < 0) {
            throw new IllegalArgumentException("repairedObservationCount must be non-negative");
        }
        if (!Double.isFinite(previousBalanceError) && !Double.isNaN(previousBalanceError)) {
            throw new IllegalArgumentException("previousBalanceError must be finite or NaN");
        }
        if (stableSuccessControlSteps < 0 || fallFailureControlSteps < 0) {
            throw new IllegalArgumentException("standing counters must be non-negative");
        }
    }

    @Override
    public double[] previousAction() {
        return Arrays.copyOf(this.previousAction, this.previousAction.length);
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
