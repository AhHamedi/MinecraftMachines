package dev.ahmedhamedi.minecraft_machines.content.worm.training;

public record WormCemConfig(
        int populationSize,
        int eliteCount,
        int episodeLengthTicks,
        int maxGenerations,
        int spacingBlocks
) {
    public static final WormCemConfig DEFAULT = new WormCemConfig(32, 6, 160, 20, 0);

    public WormCemConfig {
        if (populationSize < 1) {
            throw new IllegalArgumentException("populationSize must be positive");
        }
        if (eliteCount < 1 || eliteCount > populationSize) {
            throw new IllegalArgumentException("eliteCount must be in 1..populationSize");
        }
        if (episodeLengthTicks < 1) {
            throw new IllegalArgumentException("episodeLengthTicks must be positive");
        }
        if (maxGenerations < 1) {
            throw new IllegalArgumentException("maxGenerations must be positive");
        }
        if (spacingBlocks < 0) {
            throw new IllegalArgumentException("spacingBlocks must be non-negative");
        }
    }

    public WormCemConfig withPopulationSize(final int populationSize) {
        return new WormCemConfig(populationSize, Math.min(this.eliteCount, populationSize), this.episodeLengthTicks, this.maxGenerations, this.spacingBlocks);
    }

    public WormCemConfig withMaxGenerations(final int maxGenerations) {
        return new WormCemConfig(this.populationSize, this.eliteCount, this.episodeLengthTicks, maxGenerations, this.spacingBlocks);
    }

    public WormCemConfig withEpisodeLengthTicks(final int episodeLengthTicks) {
        return new WormCemConfig(this.populationSize, this.eliteCount, episodeLengthTicks, this.maxGenerations, this.spacingBlocks);
    }

    public WormCemConfig withSpacingBlocks(final int spacingBlocks) {
        return new WormCemConfig(this.populationSize, this.eliteCount, this.episodeLengthTicks, this.maxGenerations, spacingBlocks);
    }
}
