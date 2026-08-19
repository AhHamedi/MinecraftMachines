package dev.ahmedhamedi.minecraft_machines.content.training.cem;

import java.util.Arrays;
import java.util.List;

public final class CemExplorationDiagnostics {
    private CemExplorationDiagnostics() {
    }

    public static DistributionStats distributionStats(
            final ContinuousCemDistribution distribution,
            final int observationSize
    ) {
        final double[] std = distribution.standardDeviation();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double total = 0.0;
        double weightTotal = 0.0;
        int weightCount = 0;
        double biasTotal = 0.0;
        int biasCount = 0;
        for (int i = 0; i < std.length; i++) {
            min = Math.min(min, std[i]);
            max = Math.max(max, std[i]);
            total += std[i];
            final boolean bias = observationSize > 0 && (i + 1) % (observationSize + 1) == 0;
            if (bias) {
                biasTotal += std[i];
                biasCount++;
            } else {
                weightTotal += std[i];
                weightCount++;
            }
        }
        return new DistributionStats(
                std.length == 0 ? 0.0 : total / std.length,
                std.length == 0 ? 0.0 : min,
                std.length == 0 ? 0.0 : max,
                weightCount == 0 ? 0.0 : weightTotal / weightCount,
                biasCount == 0 ? 0.0 : biasTotal / biasCount);
    }

    public static ActionStats actionStats(
            final List<double[]> actions,
            final double saturationThreshold
    ) {
        if (actions.isEmpty()) {
            return new ActionStats(0.0, 0.0, 0.0);
        }
        int values = 0;
        int saturated = 0;
        double magnitudeTotal = 0.0;
        double total = 0.0;
        double totalSquare = 0.0;
        for (final double[] action : actions) {
            for (final double value : action) {
                if (!Double.isFinite(value)) {
                    continue;
                }
                values++;
                magnitudeTotal += Math.abs(value);
                total += value;
                totalSquare += value * value;
                if (Math.abs(value) >= saturationThreshold) {
                    saturated++;
                }
            }
        }
        if (values == 0) {
            return new ActionStats(0.0, 0.0, 0.0);
        }
        final double mean = total / values;
        final double variance = Math.max(0.0, totalSquare / values - mean * mean);
        return new ActionStats(magnitudeTotal / values, Math.sqrt(variance), saturated / (double) values);
    }

    public static double nearIdenticalCandidateFraction(
            final double[][] candidateMeanActions,
            final int[] candidateActionCounts,
            final double distanceThreshold
    ) {
        int active = 0;
        int nearIdentical = 0;
        for (int i = 0; i < candidateMeanActions.length; i++) {
            if (candidateActionCounts[i] <= 0) {
                continue;
            }
            active++;
            for (int j = 0; j < candidateMeanActions.length; j++) {
                if (i == j || candidateActionCounts[j] <= 0) {
                    continue;
                }
                if (distance(candidateMeanActions[i], candidateMeanActions[j]) <= distanceThreshold) {
                    nearIdentical++;
                    break;
                }
            }
        }
        return active == 0 ? 0.0 : nearIdentical / (double) active;
    }

    public static boolean shouldExpandExploration(
            final double[] bestFitnessHistory,
            final double minimumImprovement,
            final double actionDiversity,
            final double minimumActionDiversity,
            final double saturationFraction,
            final double maximumSaturationFraction
    ) {
        return hasStagnated(bestFitnessHistory, minimumImprovement)
                && actionDiversity < minimumActionDiversity
                && saturationFraction < maximumSaturationFraction;
    }

    public static boolean hasStagnated(
            final double[] bestFitnessHistory,
            final double minimumImprovement
    ) {
        if (bestFitnessHistory.length < 2 || !Double.isFinite(minimumImprovement) || minimumImprovement < 0.0) {
            return false;
        }
        for (final double value : bestFitnessHistory) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        final double improvement = Arrays.stream(bestFitnessHistory).max().orElse(bestFitnessHistory[0])
                - bestFitnessHistory[0];
        return improvement < minimumImprovement;
    }

    private static double distance(final double[] a, final double[] b) {
        double total = 0.0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            final double delta = a[i] - b[i];
            total += delta * delta;
        }
        return Math.sqrt(total);
    }

    public record DistributionStats(
            double meanStandardDeviation,
            double minimumStandardDeviation,
            double maximumStandardDeviation,
            double weightStandardDeviation,
            double biasStandardDeviation
    ) {
    }

    public record ActionStats(
            double meanActionMagnitude,
            double actionStandardDeviation,
            double saturatedActionFraction
    ) {
    }
}
