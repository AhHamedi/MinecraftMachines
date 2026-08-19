package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public record EnvironmentBatchStep(
        double[][] observations,
        double[] rewards,
        boolean[] terminated,
        boolean[] truncated,
        List<Map<String, Object>> infos,
        double[][] resetObservations
) {
    public EnvironmentBatchStep {
        observations = copy(observations);
        rewards = Arrays.copyOf(rewards, rewards.length);
        terminated = Arrays.copyOf(terminated, terminated.length);
        truncated = Arrays.copyOf(truncated, truncated.length);
        infos = infos.stream().map(Map::copyOf).toList();
        resetObservations = copy(resetObservations);
        final int size = observations.length;
        if (rewards.length != size || terminated.length != size || truncated.length != size || infos.size() != size || resetObservations.length != size) {
            throw new IllegalArgumentException("batch step dimensions mismatch");
        }
    }

    @Override
    public double[][] observations() {
        return copy(this.observations);
    }

    @Override
    public double[] rewards() {
        return Arrays.copyOf(this.rewards, this.rewards.length);
    }

    @Override
    public boolean[] terminated() {
        return Arrays.copyOf(this.terminated, this.terminated.length);
    }

    @Override
    public boolean[] truncated() {
        return Arrays.copyOf(this.truncated, this.truncated.length);
    }

    @Override
    public double[][] resetObservations() {
        return copy(this.resetObservations);
    }

    private static double[][] copy(final double[][] source) {
        final double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }
}
