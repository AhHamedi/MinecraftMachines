package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public record ServoAngleLimits(
        double minimumDegrees,
        double maximumDegrees
) {
    public ServoAngleLimits {
        if (!Double.isFinite(minimumDegrees) || !Double.isFinite(maximumDegrees) || minimumDegrees >= maximumDegrees) {
            throw new IllegalArgumentException("servo angle limits must be finite and ordered");
        }
    }
}
