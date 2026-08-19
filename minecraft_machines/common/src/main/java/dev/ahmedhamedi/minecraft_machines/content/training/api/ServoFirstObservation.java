package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.Arrays;

public record ServoFirstObservation(
        double[] values,
        boolean repaired
) {
    public ServoFirstObservation {
        values = Arrays.copyOf(values, values.length);
        for (final double value : values) {
            if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
                throw new IllegalArgumentException("observation values must be finite and normalized");
            }
        }
    }

    @Override
    public double[] values() {
        return Arrays.copyOf(this.values, this.values.length);
    }
}
