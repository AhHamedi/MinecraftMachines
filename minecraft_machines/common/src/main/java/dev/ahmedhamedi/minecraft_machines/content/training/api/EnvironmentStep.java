package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.Arrays;
import java.util.Map;

public record EnvironmentStep(
        double[] observation,
        double reward,
        boolean terminated,
        boolean truncated,
        Map<String, Object> info
) {
    public EnvironmentStep {
        observation = Arrays.copyOf(observation, observation.length);
        if (!Double.isFinite(reward)) {
            throw new IllegalArgumentException("reward must be finite");
        }
        if (terminated && truncated) {
            throw new IllegalArgumentException("terminated and truncated cannot both be true");
        }
        info = Map.copyOf(info);
    }

    @Override
    public double[] observation() {
        return Arrays.copyOf(this.observation, this.observation.length);
    }
}
