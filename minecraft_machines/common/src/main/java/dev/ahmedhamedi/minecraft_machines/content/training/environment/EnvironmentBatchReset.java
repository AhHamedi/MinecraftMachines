package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public record EnvironmentBatchReset(
        double[][] observations,
        List<Map<String, Object>> infos
) {
    public EnvironmentBatchReset {
        observations = copy(observations);
        infos = infos.stream().map(Map::copyOf).toList();
        if (observations.length != infos.size()) {
            throw new IllegalArgumentException("observations and infos size mismatch");
        }
    }

    @Override
    public double[][] observations() {
        return copy(this.observations);
    }

    private static double[][] copy(final double[][] source) {
        final double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }
}
