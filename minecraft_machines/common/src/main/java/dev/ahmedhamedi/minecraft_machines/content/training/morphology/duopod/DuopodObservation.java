package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import java.util.Arrays;

public record DuopodObservation(
        double[] values,
        boolean repaired
) {
    public DuopodObservation {
        values = Arrays.copyOf(values, values.length);
        if (values.length != DuopodSchemas.observationSpec().size()) {
            throw new IllegalArgumentException("duopod observation must have length " + DuopodSchemas.observationSpec().size());
        }
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
