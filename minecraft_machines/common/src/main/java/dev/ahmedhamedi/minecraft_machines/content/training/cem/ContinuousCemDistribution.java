package dev.ahmedhamedi.minecraft_machines.content.training.cem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

public record ContinuousCemDistribution(
        double[] mean,
        double[] standardDeviation,
        double[] lowerBounds,
        double[] upperBounds,
        double[] minimumStandardDeviation,
        double[] maximumStandardDeviation,
        long seed,
        int generation,
        double[] bestGenome,
        double bestFitness
) {
    public ContinuousCemDistribution(
            final double[] mean,
            final double[] standardDeviation,
            final double[] lowerBounds,
            final double[] upperBounds,
            final double[] minimumStandardDeviation,
            final long seed,
            final int generation,
            final double[] bestGenome,
            final double bestFitness
    ) {
        this(
                mean,
                standardDeviation,
                lowerBounds,
                upperBounds,
                minimumStandardDeviation,
                defaultMaximumStandardDeviation(lowerBounds, upperBounds),
                seed,
                generation,
                bestGenome,
                bestFitness);
    }

    public ContinuousCemDistribution {
        final int size = mean.length;
        if (size < 1) {
            throw new IllegalArgumentException("mean must not be empty");
        }
        standardDeviation = checkedLength("standardDeviation", standardDeviation, size);
        lowerBounds = checkedLength("lowerBounds", lowerBounds, size);
        upperBounds = checkedLength("upperBounds", upperBounds, size);
        minimumStandardDeviation = checkedLength("minimumStandardDeviation", minimumStandardDeviation, size);
        maximumStandardDeviation = checkedLength("maximumStandardDeviation", maximumStandardDeviation, size);
        bestGenome = checkedLength("bestGenome", bestGenome, size);
        mean = Arrays.copyOf(mean, size);
        for (int i = 0; i < size; i++) {
            requireFinite("mean[" + i + "]", mean[i]);
            requireFinite("standardDeviation[" + i + "]", standardDeviation[i]);
            requireFinite("lowerBounds[" + i + "]", lowerBounds[i]);
            requireFinite("upperBounds[" + i + "]", upperBounds[i]);
            requireFinite("minimumStandardDeviation[" + i + "]", minimumStandardDeviation[i]);
            requireFinite("maximumStandardDeviation[" + i + "]", maximumStandardDeviation[i]);
            requireFinite("bestGenome[" + i + "]", bestGenome[i]);
            if (lowerBounds[i] >= upperBounds[i]) {
                throw new IllegalArgumentException("bounds must be ordered at index " + i);
            }
            if (standardDeviation[i] < 0.0 || minimumStandardDeviation[i] < 0.0 || maximumStandardDeviation[i] < 0.0) {
                throw new IllegalArgumentException("standard deviations must be non-negative");
            }
            if (maximumStandardDeviation[i] < minimumStandardDeviation[i]) {
                throw new IllegalArgumentException("maximum standard deviation must be >= minimum at index " + i);
            }
            mean[i] = clamp(mean[i], lowerBounds[i], upperBounds[i]);
            standardDeviation[i] = clamp(standardDeviation[i], minimumStandardDeviation[i], maximumStandardDeviation[i]);
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        if (!Double.isFinite(bestFitness) && bestFitness != Double.NEGATIVE_INFINITY) {
            throw new IllegalArgumentException("bestFitness must be finite");
        }
    }

    public static ContinuousCemDistribution initial(
            final int genomeSize,
            final double weightStd,
            final double biasStd,
            final int observationSize,
            final double lowerBound,
            final double upperBound,
            final double minimumStandardDeviation,
            final long seed
    ) {
        return initial(
                genomeSize,
                weightStd,
                biasStd,
                observationSize,
                lowerBound,
                upperBound,
                minimumStandardDeviation,
                upperBound - lowerBound,
                seed);
    }

    public static ContinuousCemDistribution initial(
            final int genomeSize,
            final double weightStd,
            final double biasStd,
            final int observationSize,
            final double lowerBound,
            final double upperBound,
            final double minimumStandardDeviation,
            final double maximumStandardDeviation,
            final long seed
    ) {
        if (genomeSize < 1 || observationSize < 1) {
            throw new IllegalArgumentException("sizes must be positive");
        }
        final double[] mean = new double[genomeSize];
        final double[] std = new double[genomeSize];
        final double[] lower = new double[genomeSize];
        final double[] upper = new double[genomeSize];
        final double[] minStd = new double[genomeSize];
        final double[] maxStd = new double[genomeSize];
        Arrays.fill(lower, lowerBound);
        Arrays.fill(upper, upperBound);
        Arrays.fill(minStd, minimumStandardDeviation);
        Arrays.fill(maxStd, maximumStandardDeviation);
        for (int i = 0; i < genomeSize; i++) {
            final boolean bias = (i + 1) % (observationSize + 1) == 0;
            std[i] = bias ? biasStd : weightStd;
        }
        return new ContinuousCemDistribution(mean, std, lower, upper, minStd, maxStd, seed, 0, mean, Double.NEGATIVE_INFINITY);
    }

    public List<double[]> sampleAntithetic(final int populationSize, final RandomGenerator random) {
        if (populationSize < 1) {
            throw new IllegalArgumentException("populationSize must be positive");
        }
        final List<double[]> samples = new ArrayList<>(populationSize);
        while (samples.size() < populationSize) {
            final double[] epsilon = new double[this.mean.length];
            for (int i = 0; i < epsilon.length; i++) {
                epsilon[i] = random.nextGaussian();
            }
            samples.add(sample(epsilon, 1.0));
            if (samples.size() < populationSize) {
                samples.add(sample(epsilon, -1.0));
            }
        }
        return samples;
    }

    /**
     * Samples a generation while reserving candidate zero for the current
     * distribution mean. The anchor prevents a widened or changed-protocol
     * restart from losing its only known viable controller in one generation.
     */
    public List<double[]> sampleAntitheticWithMeanAnchor(
            final int populationSize,
            final RandomGenerator random
    ) {
        if (populationSize < 1) {
            throw new IllegalArgumentException("populationSize must be positive");
        }
        final List<double[]> samples = new ArrayList<>(populationSize);
        samples.add(mean());
        if (populationSize > 1) {
            samples.addAll(sampleAntithetic(populationSize - 1, random));
        }
        return samples;
    }

    public ContinuousCemDistribution update(
            final List<ScoredGenome> scored,
            final int eliteCount,
            final double smoothingOld,
            final double smoothingElite
    ) {
        if (eliteCount < 1 || eliteCount > scored.size()) {
            throw new IllegalArgumentException("eliteCount must be in 1..population");
        }
        if (!Double.isFinite(smoothingOld) || !Double.isFinite(smoothingElite) || smoothingOld < 0.0 || smoothingElite < 0.0) {
            throw new IllegalArgumentException("smoothing factors must be finite and non-negative");
        }
        final double smoothingTotal = smoothingOld + smoothingElite;
        if (smoothingTotal <= 0.0) {
            throw new IllegalArgumentException("smoothing factors cannot both be zero");
        }
        final double keep = smoothingOld / smoothingTotal;
        final double eliteWeight = smoothingElite / smoothingTotal;
        final List<ScoredGenome> elites = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredGenome::aggregateFitness).reversed())
                .limit(eliteCount)
                .toList();

        final int size = this.mean.length;
        final double[] eliteMean = new double[size];
        for (final ScoredGenome elite : elites) {
            final double[] genome = elite.genome();
            if (genome.length != size) {
                throw new IllegalArgumentException("genome length mismatch");
            }
            for (int i = 0; i < size; i++) {
                eliteMean[i] += genome[i];
            }
        }
        for (int i = 0; i < size; i++) {
            eliteMean[i] /= elites.size();
        }

        final double[] eliteStd = new double[size];
        for (final ScoredGenome elite : elites) {
            final double[] genome = elite.genome();
            for (int i = 0; i < size; i++) {
                final double delta = genome[i] - eliteMean[i];
                eliteStd[i] += delta * delta;
            }
        }
        for (int i = 0; i < size; i++) {
            eliteStd[i] = Math.sqrt(eliteStd[i] / elites.size());
        }

        final double[] newMean = new double[size];
        final double[] newStd = new double[size];
        for (int i = 0; i < size; i++) {
            newMean[i] = clamp(this.mean[i] * keep + eliteMean[i] * eliteWeight, this.lowerBounds[i], this.upperBounds[i]);
            newStd[i] = Math.max(this.minimumStandardDeviation[i], this.standardDeviation[i] * keep + eliteStd[i] * eliteWeight);
            newStd[i] = Math.min(this.maximumStandardDeviation[i], newStd[i]);
        }

        final ScoredGenome best = scored.stream()
                .max(Comparator.comparingDouble(ScoredGenome::aggregateFitness))
                .orElseThrow();
        final boolean improved = best.aggregateFitness() > this.bestFitness;
        return new ContinuousCemDistribution(
                newMean,
                newStd,
                this.lowerBounds,
                this.upperBounds,
                this.minimumStandardDeviation,
                this.maximumStandardDeviation,
                this.seed,
                this.generation + 1,
                improved ? best.genome() : this.bestGenome,
                improved ? best.aggregateFitness() : this.bestFitness);
    }

    public ContinuousCemDistribution expandStandardDeviation(final double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be finite and >= 1");
        }
        final double[] expanded = new double[this.standardDeviation.length];
        for (int i = 0; i < expanded.length; i++) {
            expanded[i] = clamp(
                    this.standardDeviation[i] * multiplier,
                    this.minimumStandardDeviation[i],
                    this.maximumStandardDeviation[i]);
        }
        return new ContinuousCemDistribution(
                this.mean,
                expanded,
                this.lowerBounds,
                this.upperBounds,
                this.minimumStandardDeviation,
                this.maximumStandardDeviation,
                this.seed,
                this.generation,
                this.bestGenome,
                this.bestFitness);
    }

    private double[] sample(final double[] epsilon, final double sign) {
        final double[] genome = new double[this.mean.length];
        for (int i = 0; i < genome.length; i++) {
            final double value = this.mean[i] + sign * this.standardDeviation[i] * epsilon[i];
            genome[i] = clamp(Double.isFinite(value) ? value : this.mean[i], this.lowerBounds[i], this.upperBounds[i]);
        }
        return genome;
    }

    @Override
    public double[] mean() {
        return Arrays.copyOf(this.mean, this.mean.length);
    }

    @Override
    public double[] standardDeviation() {
        return Arrays.copyOf(this.standardDeviation, this.standardDeviation.length);
    }

    @Override
    public double[] lowerBounds() {
        return Arrays.copyOf(this.lowerBounds, this.lowerBounds.length);
    }

    @Override
    public double[] upperBounds() {
        return Arrays.copyOf(this.upperBounds, this.upperBounds.length);
    }

    @Override
    public double[] minimumStandardDeviation() {
        return Arrays.copyOf(this.minimumStandardDeviation, this.minimumStandardDeviation.length);
    }

    @Override
    public double[] maximumStandardDeviation() {
        return Arrays.copyOf(this.maximumStandardDeviation, this.maximumStandardDeviation.length);
    }

    @Override
    public double[] bestGenome() {
        return Arrays.copyOf(this.bestGenome, this.bestGenome.length);
    }

    private static double[] checkedLength(final String name, final double[] values, final int expected) {
        if (values.length != expected) {
            throw new IllegalArgumentException(name + " length mismatch");
        }
        return Arrays.copyOf(values, values.length);
    }

    private static double[] defaultMaximumStandardDeviation(final double[] lowerBounds, final double[] upperBounds) {
        if (lowerBounds.length != upperBounds.length) {
            throw new IllegalArgumentException("bounds length mismatch");
        }
        final double[] max = new double[lowerBounds.length];
        for (int i = 0; i < max.length; i++) {
            max[i] = Math.abs(upperBounds[i] - lowerBounds[i]);
        }
        return max;
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
