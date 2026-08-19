package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.List;
import java.util.Objects;

public record ServoFirstObservationInput(
        double phaseRad,
        LocomotionCommand command,
        double localForwardVelocity,
        double localLateralVelocity,
        double localVerticalVelocity,
        double localRollRate,
        double localPitchRate,
        double localYawRate,
        double projectedGravityForward,
        double projectedGravityRight,
        double standingHeightError,
        double centerOfMassHeightDelta,
        double centerOfMassForwardDrift,
        double centerOfMassLateralDrift,
        double supportComForwardError,
        double supportComLateralError,
        List<ServoTelemetrySample> servos
) {
    public ServoFirstObservationInput {
        command = Objects.requireNonNull(command, "command");
        servos = List.copyOf(servos);
        if (servos.isEmpty()) {
            throw new IllegalArgumentException("at least one servo sample is required");
        }
    }
}
