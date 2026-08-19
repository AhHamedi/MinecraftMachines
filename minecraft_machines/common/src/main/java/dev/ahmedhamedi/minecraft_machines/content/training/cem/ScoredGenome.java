package dev.ahmedhamedi.minecraft_machines.content.training.cem;

import java.util.Arrays;

public record ScoredGenome(
        double[] genome,
        double meanScore,
        double worstScore,
        double successRate,
        double failureRate
) {
    /** Explicit pressure applied to the weakest scenario/speed. */
    public static final double WORST_SCORE_WEIGHT = 0.25;
    public static final double SUCCESS_RATE_BONUS = 10.0;
    public static final double FAILURE_RATE_PENALTY = 50.0;

    public ScoredGenome(
            final double[] genome,
            final double meanScore,
            final double worstScore,
            final double successRate
    ) {
        this(genome, meanScore, worstScore, successRate, 0.0);
    }

    public ScoredGenome {
        genome = Arrays.copyOf(genome, genome.length);
        requireFinite("meanScore", meanScore);
        requireFinite("worstScore", worstScore);
        requireFinite("successRate", successRate);
        requireFinite("failureRate", failureRate);
        requireUnitInterval("successRate", successRate);
        requireUnitInterval("failureRate", failureRate);
    }

    public double aggregateFitness() {
        return this.meanScore
                + WORST_SCORE_WEIGHT * this.worstScore
                + SUCCESS_RATE_BONUS * this.successRate
                - FAILURE_RATE_PENALTY * this.failureRate;
    }

    @Override
    public double[] genome() {
        return Arrays.copyOf(this.genome, this.genome.length);
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireUnitInterval(final String name, final double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }
}
