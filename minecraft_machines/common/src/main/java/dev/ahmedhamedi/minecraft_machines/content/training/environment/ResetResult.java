package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import java.util.Arrays;

public record ResetResult<M>(
        M machine,
        double[] observation,
        double previousDistanceToTarget,
        boolean respawned
) {
    public ResetResult {
        observation = Arrays.copyOf(observation, observation.length);
        if (!Double.isFinite(previousDistanceToTarget) || previousDistanceToTarget < 0.0) {
            throw new IllegalArgumentException("previousDistanceToTarget must be finite and non-negative");
        }
    }

    @Override
    public double[] observation() {
        return Arrays.copyOf(this.observation, this.observation.length);
    }
}
