package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.RewardBreakdown;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DuopodRewardCalculator {
    public static final DuopodStandingBalanceConfig STANDING = DuopodStandingBalanceConfig.DEFAULT;
    private static final double CENTER_OF_MASS_HEIGHT_SCALE_BLOCKS = 1.0;
    private static final double CENTER_OF_MASS_DRIFT_SCALE_BLOCKS = 0.45;
    private static final double CENTER_OF_MASS_MOTION_WEIGHT = 0.25;
    private static final double CENTER_OF_MASS_REWARD_COEFFICIENT = 3.0;
    private static final double CENTER_OF_MASS_ACTION_MAGNITUDE_COEFFICIENT = 0.08;
    private static final double CENTER_OF_MASS_ACTION_DISAGREEMENT_COEFFICIENT = 0.08;
    private static final double STANDING_CENTER_OF_MASS_HEIGHT_REWARD_COEFFICIENT = 1.25;
    private static final double STANDING_ANGULAR_MOTION_PENALTY_COEFFICIENT = 0.08;
    private static final double STANDING_LINEAR_MOTION_PENALTY_COEFFICIENT = 0.03;
    private static final double STANDING_SERVO_GROUND_RISK_PENALTY_COEFFICIENT = 0.75;
    private static final double SERVO_GROUND_RISK_CLEARANCE_BLOCKS = 0.18;

    private final DuopodRewardConfig config;

    public DuopodRewardCalculator(final DuopodRewardConfig config) {
        this.config = config;
    }

    public RewardBreakdown commandTracking(final DuopodRewardInput input) {
        final double forwardTracking = forwardTracking(input);
        final double lateralTracking = lateralTracking(input);
        final double yawTracking = yawTracking(input);
        final double upright = upright(input);
        final double actionRate = actionRate(input);
        final double launch = launchPenalty(input);
        final double failure = failurePenalty(input);
        final double forwardProgress = commandForwardProgress(input);
        final double forwardTrackingReward = input.deltaSeconds() * 0.35 * forwardTracking;
        final double lateralTrackingReward = input.deltaSeconds() * 0.20 * lateralTracking;
        final double yawTrackingReward = input.deltaSeconds() * 0.30 * yawTracking;
        final double uprightReward = input.deltaSeconds() * 0.10 * (upright - 1.0);
        final double alivePenalty = input.deltaSeconds() * this.config.commandAlivePenalty();
        final double actionRatePenalty = this.config.commandActionRatePenalty() * actionRate;
        final double total = forwardProgress
                + forwardTrackingReward
                + lateralTrackingReward
                + yawTrackingReward
                + uprightReward
                - alivePenalty
                - actionRatePenalty
                - launch
                - failure;

        final Map<String, Double> components = new LinkedHashMap<>();
        components.put("forward_progress", forwardProgress);
        components.put("forward_tracking", forwardTrackingReward);
        components.put("lateral_tracking", lateralTrackingReward);
        components.put("yaw_tracking", yawTrackingReward);
        components.put("upright", uprightReward);
        components.put("action_rate", actionRate);
        components.put("action_rate_penalty", -actionRatePenalty);
        components.put("alive_penalty", -alivePenalty);
        components.put("launch_penalty", -launch);
        components.put("machine_failure_penalty", -failure);
        return new RewardBreakdown(total, components);
    }

    public RewardBreakdown pointGoal(final DuopodRewardInput input) {
        final double forwardTracking = forwardTracking(input);
        final double lateralTracking = lateralTracking(input);
        final double yawTracking = yawTracking(input);
        final double upright = upright(input);
        final double actionRate = actionRate(input);
        final double launch = launchPenalty(input);
        final double progress = input.previousDistanceToTarget() - input.currentDistanceToTarget();
        final double success = input.targetReached() ? this.config.successBonus() : 0.0;
        final double failure = failurePenalty(input);
        final double total = 4.0 * progress
                + input.deltaSeconds() * (
                0.25 * forwardTracking
                        + 0.10 * lateralTracking
                        + 0.15 * yawTracking
                        + 0.02 * (upright - 1.0)
                        - this.config.pointAlivePenalty())
                - this.config.pointActionRatePenalty() * actionRate
                - launch
                + success
                - failure;

        final Map<String, Double> components = new LinkedHashMap<>();
        components.put("forward_tracking", input.deltaSeconds() * 0.25 * forwardTracking);
        components.put("lateral_tracking", input.deltaSeconds() * 0.10 * lateralTracking);
        components.put("yaw_tracking", input.deltaSeconds() * 0.15 * yawTracking);
        components.put("upright", input.deltaSeconds() * 0.02 * (upright - 1.0));
        components.put("action_rate", actionRate);
        components.put("action_rate_penalty", -this.config.pointActionRatePenalty() * actionRate);
        components.put("progress", 4.0 * progress);
        components.put("alive_penalty", -input.deltaSeconds() * this.config.pointAlivePenalty());
        components.put("launch_penalty", -launch);
        components.put("success_bonus", success);
        components.put("machine_failure_penalty", -failure);
        return new RewardBreakdown(total, components);
    }

    public RewardBreakdown balanceStand(final DuopodRewardInput input) {
        final double balanceError = standingBalanceError(input);
        final double previousBalanceError = Double.isFinite(input.previousBalanceError())
                ? input.previousBalanceError()
                : balanceError;
        final double recoveryProgress = clamp(
                previousBalanceError - balanceError,
                -STANDING.recoveryProgressClamp(),
                STANDING.recoveryProgressClamp());
        final double orientationError = orientationError(input.bodyUpDotWorldUp());
        final double uprightReward = Math.exp(-STANDING.uprightSharpness() * orientationError);
        final double supportError = normalizedSupportError(input);
        final double supportReward = denseErrorReward(supportError, STANDING.supportSharpness());
        final double honeyGroundError = normalizedHoneyGroundError(input);
        final double honeyContactReward = denseErrorReward(honeyGroundError, STANDING.honeyGroundSharpness());
        final double centerOfMassHeightScore = standingCenterOfMassHeightScore(input);
        final double normalizedAngularMotion = normalizedAngularMotion(input);
        final double normalizedLinearMotion = normalizedLinearMotion(input);
        final double settledReward = Math.exp(
                -STANDING.angularSettledScale() * normalizedAngularMotion
                        - STANDING.linearSettledScale() * normalizedLinearMotion);
        final double actionRate = actionRate(input);
        final double loadPenalty = normalizedLoadPenalty(input);
        final double servoGroundRisk = normalizedServoGroundRisk(input);
        final double successBonus = input.targetReached() ? STANDING.successBonus() : 0.0;
        final double failure = failurePenalty(input);
        final double total = input.deltaSeconds() * (
                STANDING.uprightRewardCoefficient() * uprightReward
                        + STANDING.supportRewardCoefficient() * supportReward
                        + STANDING.honeyContactRewardCoefficient() * honeyContactReward
                        + STANDING_CENTER_OF_MASS_HEIGHT_REWARD_COEFFICIENT * centerOfMassHeightScore
                        + STANDING.settledRewardCoefficient() * settledReward
                        + STANDING.survivalRewardPerSecond())
                + STANDING.recoveryProgressCoefficient() * recoveryProgress
                - STANDING.actionRateCoefficient() * actionRate
                - STANDING_ANGULAR_MOTION_PENALTY_COEFFICIENT * normalizedAngularMotion
                - STANDING_LINEAR_MOTION_PENALTY_COEFFICIENT * normalizedLinearMotion
                - STANDING_SERVO_GROUND_RISK_PENALTY_COEFFICIENT * servoGroundRisk
                - STANDING.loadCoefficient() * loadPenalty
                + successBonus
                - failure;

        final Map<String, Double> components = new LinkedHashMap<>();
        components.put("orientation_error", orientationError);
        components.put("support_error", supportError);
        components.put("honey_ground_error", honeyGroundError);
        components.put("angular_error", normalizedAngularMotion);
        components.put("linear_motion_error", normalizedLinearMotion);
        components.put("balance_error", balanceError);
        components.put("recovery_progress", recoveryProgress);
        components.put("center_of_mass_height_delta", input.centerOfMassHeightDeltaBlocks());
        components.put("center_of_mass_height_score", centerOfMassHeightScore);
        components.put("upright_reward", input.deltaSeconds() * STANDING.uprightRewardCoefficient() * uprightReward);
        components.put("support_reward", input.deltaSeconds() * STANDING.supportRewardCoefficient() * supportReward);
        components.put("honey_contact_reward", input.deltaSeconds() * STANDING.honeyContactRewardCoefficient() * honeyContactReward);
        components.put("center_of_mass_height_reward", input.deltaSeconds() * STANDING_CENTER_OF_MASS_HEIGHT_REWARD_COEFFICIENT * centerOfMassHeightScore);
        components.put("settled_reward", input.deltaSeconds() * STANDING.settledRewardCoefficient() * settledReward);
        components.put("survival_reward", input.deltaSeconds() * STANDING.survivalRewardPerSecond());
        components.put("recovery_progress_reward", STANDING.recoveryProgressCoefficient() * recoveryProgress);
        components.put("action_rate", actionRate);
        components.put("action_rate_penalty", -STANDING.actionRateCoefficient() * actionRate);
        components.put("angular_motion_penalty", -STANDING_ANGULAR_MOTION_PENALTY_COEFFICIENT * normalizedAngularMotion);
        components.put("linear_motion_penalty", -STANDING_LINEAR_MOTION_PENALTY_COEFFICIENT * normalizedLinearMotion);
        components.put("servo_ground_risk", servoGroundRisk);
        components.put("servo_ground_risk_penalty", -STANDING_SERVO_GROUND_RISK_PENALTY_COEFFICIENT * servoGroundRisk);
        components.put("load_penalty", -STANDING.loadCoefficient() * loadPenalty);
        components.put("success_bonus", successBonus);
        components.put("machine_failure_penalty", -failure);
        return new RewardBreakdown(total, components);
    }

    public RewardBreakdown balanceCenterOfMass(final DuopodRewardInput input) {
        final double balanceError = centerOfMassBalanceError(input);
        final double previousBalanceError = Double.isFinite(input.previousBalanceError())
                ? input.previousBalanceError()
                : balanceError;
        final double recoveryProgress = clamp(
                previousBalanceError - balanceError,
                -STANDING.recoveryProgressClamp(),
                STANDING.recoveryProgressClamp());
        final double heightScore = clamp(
                1.0 + input.centerOfMassHeightDeltaBlocks() / CENTER_OF_MASS_HEIGHT_SCALE_BLOCKS,
                0.0,
                2.0);
        final double driftError = normalizedCenterOfMassDrift(input);
        final double normalizedAngularMotion = normalizedAngularMotion(input);
        final double normalizedLinearMotion = normalizedLinearMotion(input);
        final double stillnessReward = Math.exp(
                -driftError
                        - CENTER_OF_MASS_MOTION_WEIGHT * normalizedAngularMotion
                        - CENTER_OF_MASS_MOTION_WEIGHT * normalizedLinearMotion);
        final double centerOfMassReward = heightScore * stillnessReward;
        final double actionRate = actionRate(input);
        final double actionMagnitude = actionMagnitude(input.currentAction());
        final double actionDisagreement = actionDisagreement(input.currentAction());
        final double loadPenalty = normalizedLoadPenalty(input);
        final double successBonus = input.targetReached() ? STANDING.successBonus() : 0.0;
        final double failure = failurePenalty(input);
        final double total = input.deltaSeconds() * (
                CENTER_OF_MASS_REWARD_COEFFICIENT * centerOfMassReward
                        + STANDING.survivalRewardPerSecond())
                + STANDING.recoveryProgressCoefficient() * recoveryProgress
                - STANDING.actionRateCoefficient() * actionRate
                - CENTER_OF_MASS_ACTION_MAGNITUDE_COEFFICIENT * actionMagnitude
                - CENTER_OF_MASS_ACTION_DISAGREEMENT_COEFFICIENT * actionDisagreement
                - STANDING.loadCoefficient() * loadPenalty
                + successBonus
                - failure;

        final Map<String, Double> components = new LinkedHashMap<>();
        components.put("center_of_mass_height_delta", input.centerOfMassHeightDeltaBlocks());
        components.put("center_of_mass_drift_error", driftError);
        components.put("angular_error", normalizedAngularMotion);
        components.put("linear_motion_error", normalizedLinearMotion);
        components.put("balance_error", balanceError);
        components.put("recovery_progress", recoveryProgress);
        components.put("center_of_mass_height_score", heightScore);
        components.put("center_of_mass_stillness_reward", input.deltaSeconds() * stillnessReward);
        components.put("center_of_mass_reward", input.deltaSeconds() * CENTER_OF_MASS_REWARD_COEFFICIENT * centerOfMassReward);
        components.put("survival_reward", input.deltaSeconds() * STANDING.survivalRewardPerSecond());
        components.put("recovery_progress_reward", STANDING.recoveryProgressCoefficient() * recoveryProgress);
        components.put("action_rate", actionRate);
        components.put("action_rate_penalty", -STANDING.actionRateCoefficient() * actionRate);
        components.put("action_magnitude", actionMagnitude);
        components.put("action_magnitude_penalty", -CENTER_OF_MASS_ACTION_MAGNITUDE_COEFFICIENT * actionMagnitude);
        components.put("action_disagreement", actionDisagreement);
        components.put("action_disagreement_penalty", -CENTER_OF_MASS_ACTION_DISAGREEMENT_COEFFICIENT * actionDisagreement);
        components.put("load_penalty", -STANDING.loadCoefficient() * loadPenalty);
        components.put("success_bonus", successBonus);
        components.put("machine_failure_penalty", -failure);
        return new RewardBreakdown(total, components);
    }

    public DuopodRewardConfig config() {
        return this.config;
    }

    private double forwardTracking(final DuopodRewardInput input) {
        final double error = input.actualLocalForwardVelocity() - input.command().desiredForwardVelocity();
        return centeredTracking(
                error,
                input.command().desiredForwardVelocity(),
                this.config.forwardTrackingScale());
    }

    private double lateralTracking(final DuopodRewardInput input) {
        final double error = input.actualLocalLateralVelocity() - input.command().desiredLateralVelocity();
        return centeredTracking(
                error,
                input.command().desiredLateralVelocity(),
                this.config.lateralTrackingScale());
    }

    private double yawTracking(final DuopodRewardInput input) {
        final double error = input.actualLocalYawRate() - input.command().desiredYawRate();
        return centeredTracking(
                error,
                input.command().desiredYawRate(),
                this.config.yawTrackingScale());
    }

    private double upright(final DuopodRewardInput input) {
        return Math.exp(-this.config.uprightScale() * (
                square(input.projectedGravityForward()) + square(input.projectedGravityRight())));
    }

    private static double actionRate(final DuopodRewardInput input) {
        double total = 0.0;
        for (int i = 0; i < input.currentAction().length; i++) {
            total += square(input.currentAction()[i] - input.previousAction()[i]);
        }
        return input.currentAction().length == 0 ? 0.0 : total / input.currentAction().length;
    }

    private static double actionMagnitude(final double[] action) {
        if (action.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (final double value : action) {
            total += square(value);
        }
        return total / action.length;
    }

    private static double actionDisagreement(final double[] action) {
        if (action.length < 2) {
            return 0.0;
        }
        return square((action[0] - action[1]) * 0.5);
    }

    private double failurePenalty(final DuopodRewardInput input) {
        return input.machineFailure() ? this.config.machineFailurePenalty() : 0.0;
    }

    private double launchPenalty(final DuopodRewardInput input) {
        final double upwardVelocity = Math.max(
                0.0,
                input.actualLocalVerticalVelocity() - this.config.allowedUpwardVelocityBlocksPerSecond());
        final double excessHeight = Math.max(0.0, input.heightAboveSpawn() - this.config.allowedHeightAboveSpawnBlocks());
        return input.deltaSeconds() * (
                this.config.launchVelocityPenalty() * square(upwardVelocity)
                        + this.config.launchHeightPenalty() * square(excessHeight));
    }

    private static double centeredTracking(final double error, final double stationaryError, final double scale) {
        return Math.exp(-square(error / scale)) - Math.exp(-square(stationaryError / scale));
    }

    private static double commandForwardProgress(final DuopodRewardInput input) {
        final double desiredForward = input.command().desiredForwardVelocity();
        if (Math.abs(desiredForward) <= 1.0e-9) {
            return 0.0;
        }
        final double directedVelocity = Math.copySign(1.0, desiredForward) * input.actualLocalForwardVelocity();
        final double commandScale = clamp(Math.abs(desiredForward) / 0.50, 0.0, 1.0);
        final double uprightGate = clamp(input.bodyUpDotWorldUp(), 0.0, 1.0);
        return 2.0 * input.deltaSeconds() * clamp(directedVelocity, -3.0, 3.0) * commandScale * uprightGate;
    }

    private static double square(final double value) {
        return value * value;
    }

    public static double standingBalanceError(final DuopodRewardInput input) {
        final double orientationError = orientationError(input.bodyUpDotWorldUp());
        final double angularError = square(input.actualLocalRollRate() / STANDING.angularVelocityScaleRadPerSecond())
                + square(input.actualLocalPitchRate() / STANDING.angularVelocityScaleRadPerSecond());
        final double supportError = normalizedSupportError(input);
        final double honeyGroundError = normalizedHoneyGroundError(input);
        return STANDING.orientationErrorWeight() * orientationError
                + STANDING.angularErrorWeight() * clamp(angularError, 0.0, STANDING.angularErrorCap())
                + STANDING.supportErrorWeight() * clamp(supportError, 0.0, STANDING.supportErrorCap())
                + STANDING.honeyGroundErrorWeight() * clamp(honeyGroundError, 0.0, STANDING.honeyGroundErrorCap());
    }

    public static double centerOfMassBalanceError(final DuopodRewardInput input) {
        final double heightDropError = square(Math.max(0.0, -input.centerOfMassHeightDeltaBlocks()) / CENTER_OF_MASS_HEIGHT_SCALE_BLOCKS);
        final double driftError = normalizedCenterOfMassDrift(input);
        final double motionError = normalizedAngularMotion(input) + normalizedLinearMotion(input);
        return heightDropError
                + clamp(driftError, 0.0, STANDING.supportErrorCap())
                + CENTER_OF_MASS_MOTION_WEIGHT * clamp(motionError, 0.0, STANDING.angularErrorCap() + STANDING.supportErrorCap());
    }

    public static double orientationError(final double bodyUpDotWorldUp) {
        return 0.5 * (1.0 - clamp(bodyUpDotWorldUp, -1.0, 1.0));
    }

    private static double normalizedSupportError(final DuopodRewardInput input) {
        return square(input.centerOfMassSupportDistanceBlocks() / STANDING.supportDistanceScaleBlocks());
    }

    private static double normalizedHoneyGroundError(final DuopodRewardInput input) {
        return 0.5 * (
                square(input.leftHoneyGroundClearance() / STANDING.honeyGroundClearanceScaleBlocks())
                        + square(input.rightHoneyGroundClearance() / STANDING.honeyGroundClearanceScaleBlocks()));
    }

    private static double denseErrorReward(final double normalizedSquaredError, final double sharpness) {
        return 1.0 / (1.0 + sharpness * Math.sqrt(Math.max(0.0, normalizedSquaredError)));
    }

    private static double standingCenterOfMassHeightScore(final DuopodRewardInput input) {
        return clamp(
                1.0 + input.centerOfMassHeightDeltaBlocks() / CENTER_OF_MASS_HEIGHT_SCALE_BLOCKS,
                0.0,
                1.0);
    }

    private static double normalizedCenterOfMassDrift(final DuopodRewardInput input) {
        return (square(input.centerOfMassForwardDriftBlocks() / CENTER_OF_MASS_DRIFT_SCALE_BLOCKS)
                + square(input.centerOfMassLateralDriftBlocks() / CENTER_OF_MASS_DRIFT_SCALE_BLOCKS));
    }

    private static double normalizedAngularMotion(final DuopodRewardInput input) {
        return clamp(
                square(input.actualLocalRollRate() / STANDING.angularVelocityScaleRadPerSecond())
                        + square(input.actualLocalPitchRate() / STANDING.angularVelocityScaleRadPerSecond()),
                0.0,
                STANDING.angularErrorCap());
    }

    private static double normalizedLinearMotion(final DuopodRewardInput input) {
        return clamp(
                square(input.actualLocalForwardVelocity() / STANDING.linearVelocityScaleBlocksPerSecond())
                        + square(input.actualLocalLateralVelocity() / STANDING.linearVelocityScaleBlocksPerSecond())
                        + square(input.actualLocalVerticalVelocity() / STANDING.linearVelocityScaleBlocksPerSecond()),
                0.0,
                STANDING.supportErrorCap());
    }

    private static double normalizedLoadPenalty(final DuopodRewardInput input) {
        return 0.5 * (clamp(Math.abs(input.leftServoLoad()) / 75_000.0, 0.0, 1.0)
                + clamp(Math.abs(input.rightServoLoad()) / 75_000.0, 0.0, 1.0));
    }

    private static double normalizedServoGroundRisk(final DuopodRewardInput input) {
        return 0.5 * (servoGroundRisk(input.leftServoGroundClearance())
                + servoGroundRisk(input.rightServoGroundClearance()));
    }

    private static double servoGroundRisk(final double clearanceBlocks) {
        final double normalized = Math.max(0.0, SERVO_GROUND_RISK_CLEARANCE_BLOCKS - clearanceBlocks)
                / SERVO_GROUND_RISK_CLEARANCE_BLOCKS;
        return square(clamp(normalized, 0.0, 2.0));
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
