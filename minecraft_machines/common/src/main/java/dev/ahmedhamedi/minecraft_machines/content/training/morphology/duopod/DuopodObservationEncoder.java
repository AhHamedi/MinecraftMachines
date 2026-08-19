package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoFirstObservation;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoFirstObservationEncoder;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoFirstObservationInput;

import java.util.List;

public final class DuopodObservationEncoder {
    private DuopodObservationEncoder() {
    }

    public static DuopodObservation encode(final DuopodObservationInput input) {
        final ServoFirstObservation observation = ServoFirstObservationEncoder.encode(new ServoFirstObservationInput(
                input.phaseRad(),
                input.command(),
                input.localForwardVelocity(),
                input.localLateralVelocity(),
                input.localVerticalVelocity(),
                input.localRollRate(),
                input.localPitchRate(),
                input.localYawRate(),
                input.projectedGravityForward(),
                input.projectedGravityRight(),
                input.standingHeightError(),
                input.centerOfMassHeightDelta(),
                input.centerOfMassForwardDrift(),
                input.centerOfMassLateralDrift(),
                input.supportComForwardError(),
                input.supportComLateralError(),
                List.of(input.leftServo(), input.rightServo())
        ), DuopodSchemas.SERVO_NAMES);
        return new DuopodObservation(observation.values(), observation.repaired());
    }
}
