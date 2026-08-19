package dev.ahmedhamedi.minecraft_machines.content.training.cem;

public record CemSettings(
        int populationSize,
        int eliteCount,
        int maximumGenerations,
        int episodesPerCandidate,
        double smoothingOld,
        double smoothingElite
) {
    public static final CemSettings DUOPOD_DEFAULT = new CemSettings(64, 8, 50, 3, 0.35, 0.65);

    public CemSettings {
        if (populationSize < 1) {
            throw new IllegalArgumentException("populationSize must be positive");
        }
        if (eliteCount < 1 || eliteCount > populationSize) {
            throw new IllegalArgumentException("eliteCount must be in 1..populationSize");
        }
        if (maximumGenerations < 1) {
            throw new IllegalArgumentException("maximumGenerations must be positive");
        }
        if (episodesPerCandidate < 1) {
            throw new IllegalArgumentException("episodesPerCandidate must be positive");
        }
        if (!Double.isFinite(smoothingOld) || !Double.isFinite(smoothingElite) || smoothingOld < 0.0 || smoothingElite < 0.0) {
            throw new IllegalArgumentException("smoothing factors must be finite and non-negative");
        }
    }
}
