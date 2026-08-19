package dev.ahmedhamedi.minecraft_machines.content.training.cem;

import java.util.Arrays;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public record CemScenarioSeeds(long[] seeds) {
    public CemScenarioSeeds {
        seeds = Arrays.copyOf(seeds, seeds.length);
        if (seeds.length < 1) {
            throw new IllegalArgumentException("at least one scenario seed is required");
        }
    }

    public static CemScenarioSeeds forGeneration(final long baseSeed, final int generation, final int scenarioCount) {
        if (scenarioCount < 1) {
            throw new IllegalArgumentException("scenarioCount must be positive");
        }
        final RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom")
                .create(baseSeed ^ (0x9E3779B97F4A7C15L * (generation + 1L)));
        final long[] seeds = new long[scenarioCount];
        for (int i = 0; i < scenarioCount; i++) {
            seeds[i] = random.nextLong();
        }
        return new CemScenarioSeeds(seeds);
    }

    @Override
    public long[] seeds() {
        return Arrays.copyOf(this.seeds, this.seeds.length);
    }
}
