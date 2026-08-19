package dev.ahmedhamedi.minecraft_machines.content.worm.training;

public record ScoredWormCandidate(
        WormCemCandidate candidate,
        double score,
        double forwardDisplacement,
        double sidewaysDisplacement
) {
    public ScoredWormCandidate {
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        if (!Double.isFinite(forwardDisplacement)) {
            throw new IllegalArgumentException("forwardDisplacement must be finite");
        }
        if (!Double.isFinite(sidewaysDisplacement)) {
            throw new IllegalArgumentException("sidewaysDisplacement must be finite");
        }
    }
}
