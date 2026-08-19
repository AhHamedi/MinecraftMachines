package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;

public final class PointTargetCommandMath {
    private PointTargetCommandMath() {
    }

    public static LocomotionCommand commandForLocalOffset(
            final PointTargetCommandConfig config,
            final double localForward,
            final double localRight
    ) {
        final double distance = Math.hypot(localForward, localRight);
        if (distance <= config.successRadius()) {
            return LocomotionCommand.ZERO;
        }

        final double headingError = DuopodPlanarMath.normalizeSignedAngle(Math.atan2(localRight, localForward));
        final double desiredYawRate = clamp(
                config.headingGain() * headingError,
                -config.maximumYawRate(),
                config.maximumYawRate());
        final double distanceScale = clamp(
                (distance - config.successRadius()) / Math.max(0.001, config.slowingDistance() - config.successRadius()),
                0.0,
                1.0);
        final double alignmentScale = clamp(Math.cos(headingError), 0.0, 1.0);
        final double desiredForwardVelocity = config.maximumForwardVelocity() * distanceScale * alignmentScale;
        return new LocomotionCommand(desiredForwardVelocity, 0.0, desiredYawRate);
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
