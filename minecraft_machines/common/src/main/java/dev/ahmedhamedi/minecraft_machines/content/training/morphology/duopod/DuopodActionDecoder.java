package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public final class DuopodActionDecoder {
    private DuopodActionDecoder() {
    }

    public static double targetAngleDegrees(final double normalizedAction, final ServoAngleLimits limits) {
        final double action = DuopodObservationNormalization.clamp(normalizedAction);
        final double alpha = (action + 1.0) * 0.5;
        return limits.minimumDegrees() + alpha * (limits.maximumDegrees() - limits.minimumDegrees());
    }

    public static DecodedDuopodAction decode(
            final double[] normalizedAction,
            final ServoAngleLimits leftLimits,
            final ServoAngleLimits rightLimits
    ) {
        if (normalizedAction.length != DuopodSchemas.actionSpec().size()) {
            throw new IllegalArgumentException("expected duopod action length " + DuopodSchemas.actionSpec().size());
        }
        final double left = DuopodObservationNormalization.clamp(normalizedAction[0]);
        final double right = DuopodObservationNormalization.clamp(normalizedAction[1]);
        return new DecodedDuopodAction(
                left,
                right,
                targetAngleDegrees(left, leftLimits),
                targetAngleDegrees(right, rightLimits));
    }

    public record DecodedDuopodAction(
            double requestedLeftNormalized,
            double requestedRightNormalized,
            double leftTargetDegrees,
            double rightTargetDegrees
    ) {
    }
}
