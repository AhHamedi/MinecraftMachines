package dev.ahmedhamedi.minecraft_machines.content.training.cem;

import java.util.Arrays;

public final class LinearTanhPolicy implements GenomePolicy {
    private final int observationSize;
    private final int actionSize;

    public LinearTanhPolicy(final int observationSize, final int actionSize) {
        if (observationSize < 1) {
            throw new IllegalArgumentException("observationSize must be positive");
        }
        if (actionSize < 1) {
            throw new IllegalArgumentException("actionSize must be positive");
        }
        this.observationSize = observationSize;
        this.actionSize = actionSize;
    }

    @Override
    public int observationSize() {
        return this.observationSize;
    }

    @Override
    public int actionSize() {
        return this.actionSize;
    }

    @Override
    public int genomeSize() {
        return this.actionSize * (this.observationSize + 1);
    }

    @Override
    public double[] action(final double[] genome, final double[] observation) {
        validateGenome(genome);
        if (observation.length != this.observationSize) {
            throw new IllegalArgumentException("observation length mismatch");
        }
        final double[] action = new double[this.actionSize];
        for (int row = 0; row < this.actionSize; row++) {
            final int offset = row * (this.observationSize + 1);
            double sum = genome[offset + this.observationSize];
            for (int col = 0; col < this.observationSize; col++) {
                sum += genome[offset + col] * observation[col];
            }
            action[row] = Math.tanh(finiteOrZero(sum));
        }
        return action;
    }

    public double[] flatten(final double[][] weights, final double[] biases) {
        if (weights.length != this.actionSize || biases.length != this.actionSize) {
            throw new IllegalArgumentException("policy row count mismatch");
        }
        final double[] genome = new double[this.genomeSize()];
        for (int row = 0; row < this.actionSize; row++) {
            if (weights[row].length != this.observationSize) {
                throw new IllegalArgumentException("policy column count mismatch");
            }
            final int offset = row * (this.observationSize + 1);
            System.arraycopy(weights[row], 0, genome, offset, this.observationSize);
            genome[offset + this.observationSize] = biases[row];
        }
        validateGenome(genome);
        return genome;
    }

    public Unflattened unflatten(final double[] genome) {
        validateGenome(genome);
        final double[][] weights = new double[this.actionSize][this.observationSize];
        final double[] biases = new double[this.actionSize];
        for (int row = 0; row < this.actionSize; row++) {
            final int offset = row * (this.observationSize + 1);
            System.arraycopy(genome, offset, weights[row], 0, this.observationSize);
            biases[row] = genome[offset + this.observationSize];
        }
        return new Unflattened(weights, biases);
    }

    private void validateGenome(final double[] genome) {
        if (genome.length != this.genomeSize()) {
            throw new IllegalArgumentException("genome length mismatch: expected " + this.genomeSize());
        }
        for (final double value : genome) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("genome values must be finite");
            }
        }
    }

    private static double finiteOrZero(final double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    public record Unflattened(double[][] weights, double[] biases) {
        public Unflattened {
            weights = Arrays.stream(weights)
                    .map(row -> Arrays.copyOf(row, row.length))
                    .toArray(double[][]::new);
            biases = Arrays.copyOf(biases, biases.length);
        }

        @Override
        public double[][] weights() {
            return Arrays.stream(this.weights)
                    .map(row -> Arrays.copyOf(row, row.length))
                    .toArray(double[][]::new);
        }

        @Override
        public double[] biases() {
            return Arrays.copyOf(this.biases, this.biases.length);
        }
    }
}
