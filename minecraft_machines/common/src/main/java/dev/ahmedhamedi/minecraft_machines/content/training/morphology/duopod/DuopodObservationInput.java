package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoTelemetrySample;

import java.util.Objects;

public record DuopodObservationInput(
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
        ServoTelemetrySample leftServo,
        ServoTelemetrySample rightServo
) {
    public DuopodObservationInput {
        command = Objects.requireNonNull(command, "command");
        leftServo = Objects.requireNonNull(leftServo, "leftServo");
        rightServo = Objects.requireNonNull(rightServo, "rightServo");
        if (!"left".equals(leftServo.servoName()) || !"right".equals(rightServo.servoName())) {
            throw new IllegalArgumentException("Duopod V1 servo samples must be ordered as left, right");
        }
    }
}
