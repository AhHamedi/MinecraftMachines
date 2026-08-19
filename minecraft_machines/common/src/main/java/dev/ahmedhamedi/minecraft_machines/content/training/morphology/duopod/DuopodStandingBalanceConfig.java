package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import java.util.List;

public record DuopodStandingBalanceConfig(
        double targetStandingHeightAboveSpawnBlocks,
        double angularVelocityScaleRadPerSecond,
        double linearVelocityScaleBlocksPerSecond,
        double supportDistanceScaleBlocks,
        double honeyGroundClearanceScaleBlocks,
        double orientationErrorWeight,
        double angularErrorWeight,
        double supportErrorWeight,
        double honeyGroundErrorWeight,
        double angularErrorCap,
        double supportErrorCap,
        double honeyGroundErrorCap,
        double recoveryProgressClamp,
        double uprightSharpness,
        double supportSharpness,
        double honeyGroundSharpness,
        double angularSettledScale,
        double linearSettledScale,
        double uprightRewardCoefficient,
        double supportRewardCoefficient,
        double honeyContactRewardCoefficient,
        double settledRewardCoefficient,
        double survivalRewardPerSecond,
        double recoveryProgressCoefficient,
        double actionRateCoefficient,
        double loadCoefficient,
        double successBonus,
        double successUpDot,
        double successHeightToleranceBlocks,
        double successSupportDistanceToleranceBlocks,
        double successHoneyGroundClearanceToleranceBlocks,
        double successRollPitchRateRadPerSecond,
        double successHorizontalSpeedBlocksPerSecond,
        int successHoldControlSteps,
        List<DisturbanceStage> disturbanceStages
) {
    public static final DuopodStandingBalanceConfig DEFAULT = new DuopodStandingBalanceConfig(
            0.0,
            1.0,
            3.0,
            0.45,
            0.22,
            2.0,
            0.75,
            1.50,
            0.50,
            4.0,
            9.0,
            9.0,
            1.0,
            6.0,
            4.0,
            2.5,
            0.60,
            0.15,
            2.50,
            1.75,
            0.75,
            0.50,
            0.02,
            2.00,
            0.001,
            0.0001,
            20.0,
            0.95,
            0.20,
            0.35,
            0.30,
            0.25,
            0.35,
            20,
            List.of(
                    new DisturbanceStage(0, Math.toRadians(3.0), 0.10, 0.00, 0.02, 0.02, -1),
                    new DisturbanceStage(1, Math.toRadians(8.0), 0.35, 0.20, 0.08, 0.04, 5),
                    new DisturbanceStage(2, Math.toRadians(15.0), 0.70, 0.45, 0.15, 0.08, 5),
                    new DisturbanceStage(3, Math.toRadians(25.0), 1.00, 0.80, 0.25, 0.12, 4)));

    public DuopodStandingBalanceConfig {
        if (targetStandingHeightAboveSpawnBlocks < -16.0 || targetStandingHeightAboveSpawnBlocks > 16.0) {
            throw new IllegalArgumentException("targetStandingHeightAboveSpawnBlocks is outside a practical range");
        }
        requirePositive("angularVelocityScaleRadPerSecond", angularVelocityScaleRadPerSecond);
        requirePositive("linearVelocityScaleBlocksPerSecond", linearVelocityScaleBlocksPerSecond);
        requirePositive("supportDistanceScaleBlocks", supportDistanceScaleBlocks);
        requirePositive("honeyGroundClearanceScaleBlocks", honeyGroundClearanceScaleBlocks);
        requireNonNegative("orientationErrorWeight", orientationErrorWeight);
        requireNonNegative("angularErrorWeight", angularErrorWeight);
        requireNonNegative("supportErrorWeight", supportErrorWeight);
        requireNonNegative("honeyGroundErrorWeight", honeyGroundErrorWeight);
        requirePositive("angularErrorCap", angularErrorCap);
        requirePositive("supportErrorCap", supportErrorCap);
        requirePositive("honeyGroundErrorCap", honeyGroundErrorCap);
        requirePositive("recoveryProgressClamp", recoveryProgressClamp);
        requirePositive("uprightSharpness", uprightSharpness);
        requirePositive("supportSharpness", supportSharpness);
        requirePositive("honeyGroundSharpness", honeyGroundSharpness);
        requireNonNegative("angularSettledScale", angularSettledScale);
        requireNonNegative("linearSettledScale", linearSettledScale);
        requireNonNegative("uprightRewardCoefficient", uprightRewardCoefficient);
        requireNonNegative("supportRewardCoefficient", supportRewardCoefficient);
        requireNonNegative("honeyContactRewardCoefficient", honeyContactRewardCoefficient);
        requireNonNegative("settledRewardCoefficient", settledRewardCoefficient);
        requireNonNegative("survivalRewardPerSecond", survivalRewardPerSecond);
        requireNonNegative("recoveryProgressCoefficient", recoveryProgressCoefficient);
        requireNonNegative("actionRateCoefficient", actionRateCoefficient);
        requireNonNegative("loadCoefficient", loadCoefficient);
        requireNonNegative("successBonus", successBonus);
        requireNonNegative("successUpDot", successUpDot);
        requireNonNegative("successHeightToleranceBlocks", successHeightToleranceBlocks);
        requireNonNegative("successSupportDistanceToleranceBlocks", successSupportDistanceToleranceBlocks);
        requireNonNegative("successHoneyGroundClearanceToleranceBlocks", successHoneyGroundClearanceToleranceBlocks);
        requirePositive("successRollPitchRateRadPerSecond", successRollPitchRateRadPerSecond);
        requirePositive("successHorizontalSpeedBlocksPerSecond", successHorizontalSpeedBlocksPerSecond);
        if (successHoldControlSteps < 1) {
            throw new IllegalArgumentException("successHoldControlSteps must be positive");
        }
        disturbanceStages = List.copyOf(disturbanceStages);
        if (disturbanceStages.isEmpty()) {
            throw new IllegalArgumentException("at least one disturbance stage is required");
        }
    }

    public DisturbanceStage disturbanceStage(final int stageIndex) {
        return this.disturbanceStages.get(Math.min(Math.max(0, stageIndex), this.disturbanceStages.size() - 1));
    }

    public record DisturbanceStage(
            int index,
            double maxTiltRadians,
            double maxAngularVelocityRadPerSecond,
            double maxLinearImpulse,
            double maxAngularImpulse,
            double maxJointActionOffset,
            int delayedImpulseControlStep
    ) {
        public DisturbanceStage {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
            requireNonNegative("maxTiltRadians", maxTiltRadians);
            requireNonNegative("maxAngularVelocityRadPerSecond", maxAngularVelocityRadPerSecond);
            requireNonNegative("maxLinearImpulse", maxLinearImpulse);
            requireNonNegative("maxAngularImpulse", maxAngularImpulse);
            requireNonNegative("maxJointActionOffset", maxJointActionOffset);
        }
    }

    private static void requirePositive(final String name, final double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireNonNegative(final String name, final double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
