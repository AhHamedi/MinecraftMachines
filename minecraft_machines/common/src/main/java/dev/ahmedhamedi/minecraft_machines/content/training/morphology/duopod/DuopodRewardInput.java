package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;

import java.util.Arrays;

public record DuopodRewardInput(
        LocomotionCommand command,
        double actualLocalForwardVelocity,
        double actualLocalLateralVelocity,
        double actualLocalVerticalVelocity,
        double actualLocalRollRate,
        double actualLocalPitchRate,
        double actualLocalYawRate,
        double heightAboveSpawn,
        double horizontalDistanceFromSpawn,
        double limbHeightDifference,
        double leftHoneyGroundClearance,
        double rightHoneyGroundClearance,
        double leftServoGroundClearance,
        double rightServoGroundClearance,
        double leftServoLoad,
        double rightServoLoad,
        double leftActualAngleDegrees,
        double rightActualAngleDegrees,
        double meanHoneyHorizontalDistanceFromBase,
        double honeyHorizontalSpanBlocks,
        double projectedGravityForward,
        double projectedGravityRight,
        double bodyUpDotWorldUp,
        double centerOfMassSupportDistanceBlocks,
        double centerOfMassSupportForwardErrorBlocks,
        double centerOfMassSupportLateralErrorBlocks,
        double centerOfMassHeightDeltaBlocks,
        double centerOfMassForwardDriftBlocks,
        double centerOfMassLateralDriftBlocks,
        double targetStandingHeightAboveSpawn,
        double previousBalanceError,
        double deltaSeconds,
        double previousDistanceToTarget,
        double currentDistanceToTarget,
        double[] previousAction,
        double[] currentAction,
        boolean targetReached,
        boolean machineFailure
) {
    public DuopodRewardInput {
        previousAction = Arrays.copyOf(previousAction, previousAction.length);
        currentAction = Arrays.copyOf(currentAction, currentAction.length);
        if (previousAction.length != DuopodSchemas.actionSpec().size() || currentAction.length != DuopodSchemas.actionSpec().size()) {
            throw new IllegalArgumentException("duopod reward expects two-action vectors");
        }
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and positive");
        }
    }

    @Override
    public double[] previousAction() {
        return Arrays.copyOf(this.previousAction, this.previousAction.length);
    }

    @Override
    public double[] currentAction() {
        return Arrays.copyOf(this.currentAction, this.currentAction.length);
    }
}
