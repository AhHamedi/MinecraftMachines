package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import java.util.Arrays;
import java.util.Objects;

public record StandingDisturbance(
        String scenarioId,
        int standingStage,
        double pitchRadians,
        double rollRadians,
        double pitchAngularVelocityRadPerSecond,
        double rollAngularVelocityRadPerSecond,
        double baseHeightOffsetBlocks,
        double initialLinearImpulseForward,
        double initialLinearImpulseRight,
        double initialAngularImpulseRoll,
        double initialAngularImpulsePitch,
        int initialImpulseDelayControlSteps,
        int delayedImpulseControlStep,
        double delayedLinearImpulseForward,
        double delayedLinearImpulseRight,
        double delayedAngularImpulseRoll,
        double delayedAngularImpulsePitch,
        double[] jointActionOffsets
) {
    public static final StandingDisturbance NONE = new StandingDisturbance(
            "none",
            0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            -1,
            -1,
            0.0,
            0.0,
            0.0,
            0.0,
            new double[0]);

    public StandingDisturbance {
        scenarioId = Objects.requireNonNull(scenarioId, "scenarioId");
        if (scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId must not be blank");
        }
        if (standingStage < 0) {
            throw new IllegalArgumentException("standingStage must be non-negative");
        }
        requireFinite("pitchRadians", pitchRadians);
        requireFinite("rollRadians", rollRadians);
        requireFinite("pitchAngularVelocityRadPerSecond", pitchAngularVelocityRadPerSecond);
        requireFinite("rollAngularVelocityRadPerSecond", rollAngularVelocityRadPerSecond);
        requireFinite("baseHeightOffsetBlocks", baseHeightOffsetBlocks);
        requireFinite("initialLinearImpulseForward", initialLinearImpulseForward);
        requireFinite("initialLinearImpulseRight", initialLinearImpulseRight);
        requireFinite("initialAngularImpulseRoll", initialAngularImpulseRoll);
        requireFinite("initialAngularImpulsePitch", initialAngularImpulsePitch);
        requireFinite("delayedLinearImpulseForward", delayedLinearImpulseForward);
        requireFinite("delayedLinearImpulseRight", delayedLinearImpulseRight);
        requireFinite("delayedAngularImpulseRoll", delayedAngularImpulseRoll);
        requireFinite("delayedAngularImpulsePitch", delayedAngularImpulsePitch);
        jointActionOffsets = Arrays.copyOf(jointActionOffsets, jointActionOffsets.length);
        for (int i = 0; i < jointActionOffsets.length; i++) {
            requireFinite("jointActionOffsets[" + i + "]", jointActionOffsets[i]);
            jointActionOffsets[i] = clamp(jointActionOffsets[i], -1.0, 1.0);
        }
    }

    public boolean hasInitialImpulseAt(final int controlStep) {
        return this.initialImpulseDelayControlSteps >= 0 && controlStep == this.initialImpulseDelayControlSteps
                && (this.initialLinearImpulseForward != 0.0
                || this.initialLinearImpulseRight != 0.0
                || this.initialAngularImpulseRoll != 0.0
                || this.initialAngularImpulsePitch != 0.0
                || this.pitchAngularVelocityRadPerSecond != 0.0
                || this.rollAngularVelocityRadPerSecond != 0.0
                || this.pitchRadians != 0.0
                || this.rollRadians != 0.0);
    }

    public boolean hasDelayedImpulseAt(final int controlStep) {
        return this.delayedImpulseControlStep >= 0 && controlStep == this.delayedImpulseControlStep
                && (this.delayedLinearImpulseForward != 0.0
                || this.delayedLinearImpulseRight != 0.0
                || this.delayedAngularImpulseRoll != 0.0
                || this.delayedAngularImpulsePitch != 0.0);
    }

    @Override
    public double[] jointActionOffsets() {
        return Arrays.copyOf(this.jointActionOffsets, this.jointActionOffsets.length);
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
