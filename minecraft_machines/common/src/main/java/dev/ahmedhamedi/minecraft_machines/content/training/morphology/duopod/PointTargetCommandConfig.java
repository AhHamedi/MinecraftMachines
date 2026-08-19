package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public record PointTargetCommandConfig(
        double headingGain,
        double maximumYawRate,
        double maximumForwardVelocity,
        double slowingDistance,
        double successRadius
) {
    public static final PointTargetCommandConfig DEFAULT = new PointTargetCommandConfig(
            1.75,
            2.0,
            2.5,
            6.0,
            0.75
    );

    public PointTargetCommandConfig {
        requireFinitePositive("maximumYawRate", maximumYawRate);
        requireFinitePositive("maximumForwardVelocity", maximumForwardVelocity);
        requireFinitePositive("slowingDistance", slowingDistance);
        requireFinitePositive("successRadius", successRadius);
        if (!Double.isFinite(headingGain) || headingGain < 0.0) {
            throw new IllegalArgumentException("headingGain must be finite and non-negative");
        }
    }

    private static void requireFinitePositive(final String name, final double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
