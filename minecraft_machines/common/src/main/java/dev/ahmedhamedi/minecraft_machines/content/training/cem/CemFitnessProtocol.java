package dev.ahmedhamedi.minecraft_machines.content.training.cem;

/**
 * The subset of a CEM run configuration that changes the scale or meaning of
 * candidate fitness. Scores from different protocols must not compete as if
 * they came from the same objective.
 */
public record CemFitnessProtocol(
        int episodeTicks,
        int controlTicks,
        int episodesPerCandidate,
        int spawnWarmupTicks,
        double pointGoalNearDistanceBlocks,
        double pointGoalFarDistanceBlocks,
        int spacingBlocks,
        String environmentContract
) {
    public CemFitnessProtocol {
        if (episodeTicks < 1 || controlTicks < 1 || episodesPerCandidate < 1) {
            throw new IllegalArgumentException("episodeTicks, controlTicks, and episodesPerCandidate must be positive");
        }
        if (spawnWarmupTicks < 0) {
            throw new IllegalArgumentException("spawnWarmupTicks must be non-negative");
        }
        if (spacingBlocks < 0) {
            throw new IllegalArgumentException("spacingBlocks must be non-negative");
        }
        if (environmentContract == null || environmentContract.isBlank()) {
            throw new IllegalArgumentException("environmentContract is required");
        }
        if (!Double.isFinite(pointGoalNearDistanceBlocks)
                || !Double.isFinite(pointGoalFarDistanceBlocks)
                || pointGoalNearDistanceBlocks <= 0.0
                || pointGoalFarDistanceBlocks < pointGoalNearDistanceBlocks) {
            throw new IllegalArgumentException("point-goal distances must be finite, positive, and ordered");
        }
    }

    public boolean isCompatibleWith(final CemFitnessProtocol other) {
        return this.equals(other);
    }
}
