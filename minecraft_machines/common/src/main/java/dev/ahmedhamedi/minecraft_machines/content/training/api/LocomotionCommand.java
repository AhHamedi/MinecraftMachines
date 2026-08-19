package dev.ahmedhamedi.minecraft_machines.content.training.api;

public record LocomotionCommand(
        double desiredForwardVelocity,
        double desiredLateralVelocity,
        double desiredYawRate
) {
    public static final LocomotionCommand ZERO = new LocomotionCommand(0.0, 0.0, 0.0);

    public LocomotionCommand {
        requireFinite("desiredForwardVelocity", desiredForwardVelocity);
        requireFinite("desiredLateralVelocity", desiredLateralVelocity);
        requireFinite("desiredYawRate", desiredYawRate);
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
