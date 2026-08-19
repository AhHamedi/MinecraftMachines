package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.api.ActionSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EpisodeRuntime;
import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import dev.ahmedhamedi.minecraft_machines.content.training.api.MachineHealth;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ObservationSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.RewardBreakdown;
import dev.ahmedhamedi.minecraft_machines.content.training.api.TerminationReason;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeResetContext;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.ResetResult;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.StandingDisturbance;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.TrainableMorphology;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.TrainingSpawnContext;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodKinematics;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodObservation;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodObservationEncoder;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodObservationInput;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodRewardCalculator;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodRewardConfig;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodRewardInput;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodSchemas;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodStandingBalanceConfig;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodTrainingScenarios;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PointTargetCommandConfig;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PointTargetCommandMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class MinecraftMachinesDuopodTrainingMorphology implements TrainableMorphology<MinecraftMachinesLiveDuopod> {
    private static final double FALL_FAILURE_DISTANCE = 8.0;
    private static final double HONEY_TIP_HALF_EXTENT_BLOCKS = 0.5;
    private static final double SERVO_HALF_EXTENT_BLOCKS = 0.5;
    private static final double SWIVEL_BEARING_CONTACT_CLEARANCE_BLOCKS = 0.08;
    private static final int HONEY_GROUND_SEARCH_BLOCKS = 6;
    private static final int SPAWN_SEARCH_RADIUS_BLOCKS = 16;
    private static final int TRAINING_SPAWN_LIFT_BLOCKS = 0;
    private static final PointTargetCommandConfig POINT_TARGET_COMMAND_CONFIG = PointTargetCommandConfig.DEFAULT;

    private final ServerLevel level;
    private final BlockPos origin;
    private final Direction forwardDirection;
    private final Direction rightDirection;
    private final UUID batchId;
    private final int spacingBlocks;
    private final boolean isolatedWalkForwardLaneGroups;
    private final DuopodRewardCalculator rewardCalculator;
    private final DuopodStandingBalanceConfig standingConfig;

    MinecraftMachinesDuopodTrainingMorphology(
            final ServerLevel level,
            final BlockPos origin,
            final Direction forwardDirection,
            final UUID batchId,
            final int spacingBlocks
    ) {
        this(level, origin, forwardDirection, batchId, spacingBlocks, false);
    }

    MinecraftMachinesDuopodTrainingMorphology(
            final ServerLevel level,
            final BlockPos origin,
            final Direction forwardDirection,
            final UUID batchId,
            final int spacingBlocks,
            final boolean isolatedWalkForwardLaneGroups
    ) {
        if (forwardDirection.getAxis().isVertical()) {
            throw new IllegalArgumentException("Duopod forward direction must be horizontal");
        }
        if (spacingBlocks < 0) {
            throw new IllegalArgumentException("spacingBlocks must be non-negative");
        }
        this.level = Objects.requireNonNull(level, "level");
        this.origin = origin.immutable();
        this.forwardDirection = forwardDirection;
        this.rightDirection = forwardDirection.getClockWise();
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.spacingBlocks = spacingBlocks;
        this.isolatedWalkForwardLaneGroups = isolatedWalkForwardLaneGroups;
        this.rewardCalculator = new DuopodRewardCalculator(DuopodRewardConfig.DEFAULT);
        this.standingConfig = DuopodRewardCalculator.STANDING;
    }

    @Override
    public String id() {
        return DuopodInstance.MORPHOLOGY_TYPE;
    }

    @Override
    public ObservationSpec observationSpec() {
        return DuopodSchemas.observationSpec();
    }

    @Override
    public ActionSpec actionSpec() {
        return DuopodSchemas.actionSpec();
    }

    @Override
    public MinecraftMachinesLiveDuopod spawn(final TrainingSpawnContext context) {
        final BlockPos requested = this.origin.relative(
                this.rightDirection,
                requestedRightOffsetForSlot(
                        context.slotIndex(),
                        this.spacingBlocks,
                        this.isolatedWalkForwardLaneGroups));
        final BlockPos center = MinecraftMachinesDuopodSpawner.findDuopodSpawnNear(
                this.level,
                requested,
                this.forwardDirection,
                SPAWN_SEARCH_RADIUS_BLOCKS);
        if (center == null) {
            throw new IllegalStateException("Could not find clear surface space for Duopod slot " + context.slotIndex()
                    + " within " + SPAWN_SEARCH_RADIUS_BLOCKS + " blocks of " + requested.toShortString());
        }
        if (this.isolatedWalkForwardLaneGroups && !center.equals(requested.above(3))) {
            throw new IllegalStateException(
                    "Controlled Duopod slot " + context.slotIndex()
                            + " resolved to " + center.toShortString()
                            + " instead of its exact prepared lane center " + requested.above(3).toShortString());
        }

        final BlockPos spawnCenter = center.above(TRAINING_SPAWN_LIFT_BLOCKS);
        final MinecraftMachinesDuopodSpawner.SpawnResult result = MinecraftMachinesDuopodSpawner.spawn(
                this.level,
                spawnCenter,
                this.forwardDirection,
                this.batchId);
        if (!result.success() || result.duopod() == null) {
            throw new IllegalStateException("Failed to spawn Duopod slot " + context.slotIndex() + ": " + result.message());
        }
        return new MinecraftMachinesLiveDuopod(
                context.slotIndex(),
                this.batchId,
                new MinecraftMachinesDuopodControl(this.level, result.duopod()));
    }

    static int requestedRightOffsetForSlot(
            final int slot,
            final int spacingBlocks,
            final boolean isolatedWalkForwardLaneGroups
    ) {
        if (slot < 0 || spacingBlocks < 0) {
            throw new IllegalArgumentException("slot and spacing must be non-negative");
        }
        return isolatedWalkForwardLaneGroups
                ? MinecraftMachinesDuopodFlatArena.rightOffsetForSlot(slot, spacingBlocks)
                : Math.multiplyExact(slot, spacingBlocks);
    }

    @Override
    public void beforeControlStep(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime
    ) {
        if (episode.taskMode() != EnvironmentTaskMode.BALANCE) {
            return;
        }
        final StandingDisturbance disturbance = episode.standingDisturbance();
        if (disturbance.hasInitialImpulseAt(runtime.controlStep())) {
            machine.control().applyStandingDisturbance(disturbance, false);
        }
        if (disturbance.hasDelayedImpulseAt(runtime.controlStep())) {
            machine.control().applyStandingDisturbance(disturbance, true);
        }
    }

    @Override
    public void onEpisodeUpdated(final MinecraftMachinesLiveDuopod machine, final EpisodeDefinition episode) {
        machine.calibrateStandingHeightReference();
    }

    @Override
    public void applyAction(final MinecraftMachinesLiveDuopod machine, final double[] normalizedAction) {
        if (normalizedAction.length != DuopodSchemas.actionSpec().size()) {
            throw new IllegalArgumentException("Duopod action length mismatch");
        }
        machine.control().setNormalizedAction(normalizedAction[0], normalizedAction[1]);
    }

    @Override
    public double[] observe(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime
    ) {
        return this.encodedObservation(machine, episode, runtime.previousAction(), runtime.phaseRad()).values();
    }

    @Override
    public RewardBreakdown calculateReward(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime,
            final double[] previousAction,
            final double[] currentAction
    ) {
        final MotionSnapshot snapshot = this.isWalkForwardStage(episode)
                ? this.spawnFrameMotionSnapshot(machine)
                : this.motionSnapshot(machine);
        final boolean heldTaskFailure = this.taskFailureCondition(machine, episode, runtime)
                && runtime.fallFailureControlSteps() >= this.taskFailureHoldControlSteps(episode);
        final boolean failure = !machine.control().isValid()
                || this.fellTooFar(machine)
                || heldTaskFailure;
        final double currentDistance = this.distanceToTarget(machine, episode, runtime);
        final double leftLoad = finiteOrZero(machine.control().getLeftLoad());
        final double rightLoad = finiteOrZero(machine.control().getRightLoad());
        final double leftActualAngleDegrees = finiteOrZero(machine.control().getLeftActualAngleDegrees());
        final double rightActualAngleDegrees = finiteOrZero(machine.control().getRightActualAngleDegrees());
        final Vec3 basePosition = machine.control().getBasePosition();
        final Vec3 leftHoneyTipPosition = machine.control().getLeftHoneyTipPosition();
        final Vec3 rightHoneyTipPosition = machine.control().getRightHoneyTipPosition();
        final Vec3 centerOfMassPosition = machine.control().getAggregateCenterOfMassPosition();
        final CenterOfMassBalanceMetrics centerOfMassMetrics = this.centerOfMassBalanceMetrics(machine, centerOfMassPosition);
        final BalanceSupportMetrics supportMetrics = this.balanceSupportMetrics(
                machine,
                leftHoneyTipPosition,
                rightHoneyTipPosition,
                centerOfMassPosition);
        final Vec3 leftServoPosition = machine.control().getLeftServoPosition();
        final Vec3 rightServoPosition = machine.control().getRightServoPosition();
        final double taskHeightAboveReference = this.heightAboveTaskReference(machine, episode);
        final double leftHoneyDistanceFromBase = horizontalDistance(basePosition, leftHoneyTipPosition);
        final double rightHoneyDistanceFromBase = horizontalDistance(basePosition, rightHoneyTipPosition);
        final boolean balanceSuccess = episode.taskMode() == EnvironmentTaskMode.BALANCE
                && this.taskSuccessCondition(machine, episode, runtime)
                && runtime.stableSuccessControlSteps() + 1 >= this.taskSuccessHoldControlSteps(episode);
        final DuopodRewardInput input = new DuopodRewardInput(
                this.effectiveCommand(machine, episode),
                snapshot.motion().forwardVelocity(),
                snapshot.motion().lateralVelocity(),
                snapshot.motion().verticalVelocity(),
                snapshot.motion().rollRate(),
                snapshot.motion().pitchRate(),
                snapshot.motion().yawRate(),
                taskHeightAboveReference,
                this.horizontalDistanceFromSpawn(machine),
                this.limbHeightDifference(machine),
                this.honeyGroundClearance(leftHoneyTipPosition),
                this.honeyGroundClearance(rightHoneyTipPosition),
                this.servoGroundClearance(leftServoPosition),
                this.servoGroundClearance(rightServoPosition),
                leftLoad,
                rightLoad,
                leftActualAngleDegrees,
                rightActualAngleDegrees,
                (leftHoneyDistanceFromBase + rightHoneyDistanceFromBase) * 0.5,
                horizontalDistance(leftHoneyTipPosition, rightHoneyTipPosition),
                snapshot.gravity().forward(),
                snapshot.gravity().right(),
                snapshot.bodyUpDotWorldUp(),
                supportMetrics.supportDistanceBlocks(),
                supportMetrics.supportForwardErrorBlocks(),
                supportMetrics.supportLateralErrorBlocks(),
                centerOfMassMetrics.heightDeltaBlocks(),
                centerOfMassMetrics.forwardDriftBlocks(),
                centerOfMassMetrics.lateralDriftBlocks(),
                this.standingConfig.targetStandingHeightAboveSpawnBlocks(),
                runtime.previousBalanceError(),
                runtime.deltaSeconds(),
                runtime.previousDistanceToTarget(),
                currentDistance,
                previousAction,
                currentAction,
                this.targetReached(episode, currentDistance) || balanceSuccess,
                failure);
        return switch (episode.taskMode()) {
            case POINT_GOAL -> this.rewardCalculator.pointGoal(input);
            case BALANCE -> this.isCenterOfMassBalanceStage(episode)
                    ? this.rewardCalculator.balanceCenterOfMass(input)
                    : this.rewardCalculator.balanceStand(input);
            case COMMAND_TRACKING -> this.rewardCalculator.commandTracking(input);
        };
    }

    @Override
    public MachineHealth inspectHealth(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime
    ) {
        if (!machine.control().isValid()) {
            return MachineHealth.failure(TerminationReason.MACHINE_FAILURE, "Duopod control object is no longer valid");
        }
        if (this.fellTooFar(machine)) {
            return MachineHealth.failure(TerminationReason.MACHINE_FAILURE, "Duopod fell below its spawn height");
        }
        if (episode.taskMode() == EnvironmentTaskMode.BALANCE) {
            final MotionSnapshot snapshot = this.motionSnapshot(machine);
            if (!Double.isFinite(snapshot.bodyUpDotWorldUp())
                    || !Double.isFinite(snapshot.motion().rollRate())
                    || !Double.isFinite(snapshot.motion().pitchRate())
                    || !Double.isFinite(snapshot.motion().forwardVelocity())) {
                return MachineHealth.failure(
                        TerminationReason.MACHINE_FAILURE,
                        "Duopod balance pose or velocity became non-finite");
            }
        }
        return MachineHealth.VALID;
    }

    @Override
    public boolean taskFailureCondition(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime
    ) {
        if (episode.taskMode() == EnvironmentTaskMode.BALANCE || !machine.control().isValid()) {
            return false;
        }
        final double bodyUp = this.motionSnapshot(machine).bodyUpDotWorldUp();
        final double centerOfMassDrop = machine.standingCenterOfMassReference().y
                - machine.control().getAggregateCenterOfMassPosition().y;
        return bodyUp < 0.25 && centerOfMassDrop > 0.50;
    }

    @Override
    public int taskFailureHoldControlSteps(final EpisodeDefinition episode) {
        return episode.taskMode() == EnvironmentTaskMode.BALANCE ? 1 : 3;
    }

    @Override
    public String taskFailureMessage(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime
    ) {
        return "Duopod remained tipped with a collapsed center of mass";
    }

    @Override
    public double distanceToTarget(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime
    ) {
        return episode.taskMode() == EnvironmentTaskMode.POINT_GOAL
                ? this.currentPointTargetOffset(machine, episode).horizontalDistance()
                : 0.0;
    }

    @Override
    public boolean taskSuccessCondition(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime
    ) {
        if (episode.taskMode() != EnvironmentTaskMode.BALANCE || !machine.control().isValid()) {
            return false;
        }
        final MotionSnapshot snapshot = this.motionSnapshot(machine);
        final double heightError = Math.abs(this.heightAboveTaskReference(machine, episode)
                - this.standingConfig.targetStandingHeightAboveSpawnBlocks());
        final double horizontalSpeed = Math.hypot(snapshot.motion().forwardVelocity(), snapshot.motion().lateralVelocity());
        final Vec3 leftHoneyTipPosition = machine.control().getLeftHoneyTipPosition();
        final Vec3 rightHoneyTipPosition = machine.control().getRightHoneyTipPosition();
        final Vec3 centerOfMassPosition = machine.control().getAggregateCenterOfMassPosition();
        final CenterOfMassBalanceMetrics centerOfMassMetrics = this.centerOfMassBalanceMetrics(machine, centerOfMassPosition);
        if (this.isCenterOfMassBalanceStage(episode)) {
            final double drift = Math.hypot(centerOfMassMetrics.forwardDriftBlocks(), centerOfMassMetrics.lateralDriftBlocks());
            return centerOfMassMetrics.heightDeltaBlocks() >= -this.standingConfig.successHeightToleranceBlocks()
                    && drift <= this.standingConfig.successSupportDistanceToleranceBlocks()
                    && Math.abs(snapshot.motion().verticalVelocity()) <= this.standingConfig.successHorizontalSpeedBlocksPerSecond()
                    && horizontalSpeed <= this.standingConfig.successHorizontalSpeedBlocksPerSecond()
                    && Math.abs(snapshot.motion().rollRate()) <= this.standingConfig.successRollPitchRateRadPerSecond()
                    && Math.abs(snapshot.motion().pitchRate()) <= this.standingConfig.successRollPitchRateRadPerSecond();
        }
        final BalanceSupportMetrics supportMetrics = this.balanceSupportMetrics(
                machine,
                leftHoneyTipPosition,
                rightHoneyTipPosition,
                centerOfMassPosition);
        final double leftHoneyGroundClearance = Math.abs(this.honeyGroundClearance(leftHoneyTipPosition));
        final double rightHoneyGroundClearance = Math.abs(this.honeyGroundClearance(rightHoneyTipPosition));
        return snapshot.bodyUpDotWorldUp() >= this.standingConfig.successUpDot()
                && heightError <= this.standingConfig.successHeightToleranceBlocks()
                && supportMetrics.supportDistanceBlocks() <= this.standingConfig.successSupportDistanceToleranceBlocks()
                && leftHoneyGroundClearance <= this.standingConfig.successHoneyGroundClearanceToleranceBlocks()
                && rightHoneyGroundClearance <= this.standingConfig.successHoneyGroundClearanceToleranceBlocks()
                && Math.abs(snapshot.motion().rollRate()) <= this.standingConfig.successRollPitchRateRadPerSecond()
                && Math.abs(snapshot.motion().pitchRate()) <= this.standingConfig.successRollPitchRateRadPerSecond()
                && horizontalSpeed <= this.standingConfig.successHorizontalSpeedBlocksPerSecond();
    }

    @Override
    public int taskSuccessHoldControlSteps(final EpisodeDefinition episode) {
        return episode.taskMode() == EnvironmentTaskMode.BALANCE
                ? this.standingConfig.successHoldControlSteps()
                : TrainableMorphology.super.taskSuccessHoldControlSteps(episode);
    }

    @Override
    public double balanceError(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime
    ) {
        if (episode.taskMode() != EnvironmentTaskMode.BALANCE) {
            return Double.NaN;
        }
        final MotionSnapshot snapshot = this.motionSnapshot(machine);
        final Vec3 leftHoneyTipPosition = machine.control().getLeftHoneyTipPosition();
        final Vec3 rightHoneyTipPosition = machine.control().getRightHoneyTipPosition();
        final Vec3 leftServoPosition = machine.control().getLeftServoPosition();
        final Vec3 rightServoPosition = machine.control().getRightServoPosition();
        final Vec3 centerOfMassPosition = machine.control().getAggregateCenterOfMassPosition();
        final CenterOfMassBalanceMetrics centerOfMassMetrics = this.centerOfMassBalanceMetrics(machine, centerOfMassPosition);
        final BalanceSupportMetrics supportMetrics = this.balanceSupportMetrics(
                machine,
                leftHoneyTipPosition,
                rightHoneyTipPosition,
                centerOfMassPosition);
        final DuopodRewardInput input = new DuopodRewardInput(
                LocomotionCommand.ZERO,
                snapshot.motion().forwardVelocity(),
                snapshot.motion().lateralVelocity(),
                snapshot.motion().verticalVelocity(),
                snapshot.motion().rollRate(),
                snapshot.motion().pitchRate(),
                snapshot.motion().yawRate(),
                this.heightAboveTaskReference(machine, episode),
                this.horizontalDistanceFromSpawn(machine),
                this.limbHeightDifference(machine),
                this.honeyGroundClearance(leftHoneyTipPosition),
                this.honeyGroundClearance(rightHoneyTipPosition),
                this.servoGroundClearance(leftServoPosition),
                this.servoGroundClearance(rightServoPosition),
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                snapshot.gravity().forward(),
                snapshot.gravity().right(),
                snapshot.bodyUpDotWorldUp(),
                supportMetrics.supportDistanceBlocks(),
                supportMetrics.supportForwardErrorBlocks(),
                supportMetrics.supportLateralErrorBlocks(),
                centerOfMassMetrics.heightDeltaBlocks(),
                centerOfMassMetrics.forwardDriftBlocks(),
                centerOfMassMetrics.lateralDriftBlocks(),
                this.standingConfig.targetStandingHeightAboveSpawnBlocks(),
                runtime.previousBalanceError(),
                runtime.deltaSeconds(),
                0.0,
                0.0,
                new double[DuopodSchemas.actionSpec().size()],
                new double[DuopodSchemas.actionSpec().size()],
                false,
                false);
        return this.isCenterOfMassBalanceStage(episode)
                ? DuopodRewardCalculator.centerOfMassBalanceError(input)
                : DuopodRewardCalculator.standingBalanceError(input);
    }

    @Override
    public Map<String, Object> diagnosticInfo(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final EpisodeRuntime runtime,
            final double[] previousAction,
            final double[] currentAction
    ) {
        final MotionSnapshot snapshot = this.motionSnapshot(machine);
        final LocomotionCommand command = this.effectiveCommand(machine, episode);
        final Vec3 basePosition = machine.control().getBasePosition();
        final Vec3 leftChildPosition = machine.control().getLeftChildPosition();
        final Vec3 rightChildPosition = machine.control().getRightChildPosition();
        final Vec3 leftHoneyTipPosition = machine.control().getLeftHoneyTipPosition();
        final Vec3 rightHoneyTipPosition = machine.control().getRightHoneyTipPosition();
        final Vec3 centerOfMassPosition = machine.control().getAggregateCenterOfMassPosition();
        final CenterOfMassBalanceMetrics centerOfMassMetrics = this.centerOfMassBalanceMetrics(machine, centerOfMassPosition);
        final BalanceSupportMetrics supportMetrics = this.balanceSupportMetrics(
                machine,
                leftHoneyTipPosition,
                rightHoneyTipPosition,
                centerOfMassPosition);
        final Vec3 leftServoPosition = machine.control().getLeftServoPosition();
        final Vec3 rightServoPosition = machine.control().getRightServoPosition();
        final double heightAboveSpawn = this.heightAboveSpawn(machine);
        final double taskHeightAboveReference = this.heightAboveTaskReference(machine, episode);
        final double horizontalDistanceFromSpawn = this.horizontalDistanceFromSpawn(machine);
        final DuopodKinematics.LocalOffset displacementFromSpawn = DuopodKinematics.horizontalLocalOffset(
                machine.control().duopod().spawnPosition(),
                machine.control().duopod().spawnOrientationView(),
                machine.control().duopod().modelForwardDirection(),
                basePosition);
        final DuopodKinematics.LocalBodyMotion spawnFrameMotion = this.spawnFrameMotionSnapshot(machine).motion();
        final double limbHeightDifference = Math.abs(finiteOrZero(leftChildPosition.y - rightChildPosition.y));
        final double leftHoneyGroundClearance = this.honeyGroundClearance(leftHoneyTipPosition);
        final double rightHoneyGroundClearance = this.honeyGroundClearance(rightHoneyTipPosition);
        final double leftServoGroundClearance = this.servoGroundClearance(leftServoPosition);
        final double rightServoGroundClearance = this.servoGroundClearance(rightServoPosition);
        final double leftLoad = finiteOrZero(machine.control().getLeftLoad());
        final double rightLoad = finiteOrZero(machine.control().getRightLoad());
        final double leftActualAngleDegrees = finiteOrZero(machine.control().getLeftActualAngleDegrees());
        final double rightActualAngleDegrees = finiteOrZero(machine.control().getRightActualAngleDegrees());
        final double leftAngularVelocity = finiteOrZero(machine.control().getLeftAngularVelocityRadPerSecond());
        final double rightAngularVelocity = finiteOrZero(machine.control().getRightAngularVelocityRadPerSecond());
        final double leftHoneyDistanceFromBase = horizontalDistance(basePosition, leftHoneyTipPosition);
        final double rightHoneyDistanceFromBase = horizontalDistance(basePosition, rightHoneyTipPosition);
        final double honeyHorizontalSpan = horizontalDistance(leftHoneyTipPosition, rightHoneyTipPosition);
        final DuopodKinematics.Axes axes = DuopodKinematics.worldAxes(
                machine.control().duopod().modelForwardDirection(),
                machine.control().getBaseOrientation());
        final Vector3dc bodyForward = axes.forward();
        final Vector3dc bodyRight = axes.right();
        final Map<String, Object> info = new LinkedHashMap<>();
        info.put("machine_id", machine.control().duopod().machineId().toString());
        info.put("diagnostic_task_mode", episode.taskMode().name());
        info.put("base_position_x", finiteOrZero(basePosition.x));
        info.put("base_position_y", finiteOrZero(basePosition.y));
        info.put("base_position_z", finiteOrZero(basePosition.z));
        info.put("body_forward_x", finiteOrZero(bodyForward.x()));
        info.put("body_forward_y", finiteOrZero(bodyForward.y()));
        info.put("body_forward_z", finiteOrZero(bodyForward.z()));
        info.put("body_right_x", finiteOrZero(bodyRight.x()));
        info.put("body_right_y", finiteOrZero(bodyRight.y()));
        info.put("body_right_z", finiteOrZero(bodyRight.z()));
        info.put("center_of_mass_position_x", finiteOrZero(centerOfMassPosition.x));
        info.put("center_of_mass_position_y", finiteOrZero(centerOfMassPosition.y));
        info.put("center_of_mass_position_z", finiteOrZero(centerOfMassPosition.z));
        info.put("center_of_mass_reference_x", finiteOrZero(machine.standingCenterOfMassReference().x));
        info.put("center_of_mass_reference_y", finiteOrZero(machine.standingCenterOfMassReference().y));
        info.put("center_of_mass_reference_z", finiteOrZero(machine.standingCenterOfMassReference().z));
        info.put("center_of_mass_height_delta_blocks", centerOfMassMetrics.heightDeltaBlocks());
        info.put("center_of_mass_forward_drift_blocks", centerOfMassMetrics.forwardDriftBlocks());
        info.put("center_of_mass_lateral_drift_blocks", centerOfMassMetrics.lateralDriftBlocks());
        info.put("center_of_mass_horizontal_drift_blocks", centerOfMassMetrics.horizontalDriftBlocks());
        info.put("support_com_distance_blocks", supportMetrics.supportDistanceBlocks());
        info.put("support_com_forward_error_blocks", supportMetrics.supportForwardErrorBlocks());
        info.put("support_com_lateral_error_blocks", supportMetrics.supportLateralErrorBlocks());
        info.put("support_com_segment_t", supportMetrics.segmentParameter());
        info.put("height_above_spawn_blocks", heightAboveSpawn);
        info.put("height_above_task_reference_blocks", taskHeightAboveReference);
        info.put("standing_height_reference_y", finiteOrZero(machine.standingHeightReferenceY()));
        info.put("horizontal_distance_from_spawn_blocks", horizontalDistanceFromSpawn);
        info.put("forward_displacement_from_spawn_blocks", finiteOrZero(displacementFromSpawn.forward()));
        info.put("lateral_displacement_from_spawn_blocks", finiteOrZero(displacementFromSpawn.right()));
        info.put("spawn_frame_forward_velocity", finiteOrZero(spawnFrameMotion.forwardVelocity()));
        info.put("spawn_frame_lateral_velocity", finiteOrZero(spawnFrameMotion.lateralVelocity()));
        info.put("left_limb_position_y", finiteOrZero(leftChildPosition.y));
        info.put("right_limb_position_y", finiteOrZero(rightChildPosition.y));
        info.put("limb_height_difference_blocks", limbHeightDifference);
        info.put("left_honey_tip_position_x", finiteOrZero(leftHoneyTipPosition.x));
        info.put("left_honey_tip_position_y", finiteOrZero(leftHoneyTipPosition.y));
        info.put("left_honey_tip_position_z", finiteOrZero(leftHoneyTipPosition.z));
        info.put("right_honey_tip_position_x", finiteOrZero(rightHoneyTipPosition.x));
        info.put("right_honey_tip_position_y", finiteOrZero(rightHoneyTipPosition.y));
        info.put("right_honey_tip_position_z", finiteOrZero(rightHoneyTipPosition.z));
        info.put("left_honey_ground_clearance_blocks", leftHoneyGroundClearance);
        info.put("right_honey_ground_clearance_blocks", rightHoneyGroundClearance);
        info.put("left_servo_position_x", finiteOrZero(leftServoPosition.x));
        info.put("left_servo_position_y", finiteOrZero(leftServoPosition.y));
        info.put("left_servo_position_z", finiteOrZero(leftServoPosition.z));
        info.put("right_servo_position_x", finiteOrZero(rightServoPosition.x));
        info.put("right_servo_position_y", finiteOrZero(rightServoPosition.y));
        info.put("right_servo_position_z", finiteOrZero(rightServoPosition.z));
        info.put("left_servo_ground_clearance_blocks", leftServoGroundClearance);
        info.put("right_servo_ground_clearance_blocks", rightServoGroundClearance);
        info.put("left_swivel_bearing_floor_contact", leftServoGroundClearance <= SWIVEL_BEARING_CONTACT_CLEARANCE_BLOCKS);
        info.put("right_swivel_bearing_floor_contact", rightServoGroundClearance <= SWIVEL_BEARING_CONTACT_CLEARANCE_BLOCKS);
        info.put("left_honey_horizontal_distance_from_base_blocks", leftHoneyDistanceFromBase);
        info.put("right_honey_horizontal_distance_from_base_blocks", rightHoneyDistanceFromBase);
        info.put("mean_honey_horizontal_distance_from_base_blocks", (leftHoneyDistanceFromBase + rightHoneyDistanceFromBase) * 0.5);
        info.put("honey_horizontal_span_blocks", honeyHorizontalSpan);
        info.put("desired_forward_velocity", command.desiredForwardVelocity());
        info.put("desired_lateral_velocity", command.desiredLateralVelocity());
        info.put("desired_yaw_rate", command.desiredYawRate());
        info.put("local_forward_velocity", snapshot.motion().forwardVelocity());
        info.put("local_lateral_velocity", snapshot.motion().lateralVelocity());
        info.put("local_vertical_velocity", snapshot.motion().verticalVelocity());
        info.put("local_roll_rate", snapshot.motion().rollRate());
        info.put("local_pitch_rate", snapshot.motion().pitchRate());
        info.put("local_yaw_rate", snapshot.motion().yawRate());
        info.put("projected_gravity_forward", snapshot.gravity().forward());
        info.put("projected_gravity_right", snapshot.gravity().right());
        info.put("body_up_dot_world_up", snapshot.bodyUpDotWorldUp());
        info.put("standing_target_height_above_spawn_blocks", this.standingConfig.targetStandingHeightAboveSpawnBlocks());
        info.put("standing_target_height_above_task_reference_blocks", this.standingConfig.targetStandingHeightAboveSpawnBlocks());
        info.put("standing_height_error_blocks", taskHeightAboveReference - this.standingConfig.targetStandingHeightAboveSpawnBlocks());
        if (episode.taskMode() == EnvironmentTaskMode.BALANCE) {
            final double tiltMagnitude = this.balanceTiltMagnitude(snapshot);
            final StandingDisturbance disturbance = episode.standingDisturbance();
            info.put("balance_tilt_magnitude", tiltMagnitude);
            info.put("balance_error", this.balanceError(machine, episode, runtime));
            info.put("standing_success_condition", this.taskSuccessCondition(machine, episode, runtime));
            info.put("standing_success_hold_required", this.taskSuccessHoldControlSteps(episode));
            info.put("standing_success_height_tolerance_blocks", this.standingConfig.successHeightToleranceBlocks());
            info.put("standing_success_support_distance_tolerance_blocks", this.standingConfig.successSupportDistanceToleranceBlocks());
            info.put("standing_success_honey_ground_clearance_tolerance_blocks", this.standingConfig.successHoneyGroundClearanceToleranceBlocks());
            info.put("standing_disturbance_scenario", disturbance.scenarioId());
            info.put("standing_disturbance_stage", disturbance.standingStage());
            info.put("standing_disturbance_pitch_radians", disturbance.pitchRadians());
            info.put("standing_disturbance_roll_radians", disturbance.rollRadians());
            info.put("standing_disturbance_pitch_rate", disturbance.pitchAngularVelocityRadPerSecond());
            info.put("standing_disturbance_roll_rate", disturbance.rollAngularVelocityRadPerSecond());
            info.put("standing_disturbance_delayed_impulse_step", disturbance.delayedImpulseControlStep());
            info.put("balance_limb_height_difference_blocks", limbHeightDifference);
            info.put("balance_honey_ground_difference_blocks", Math.abs(leftHoneyGroundClearance - rightHoneyGroundClearance));
            info.put("balance_honey_planted_score", Math.exp(-square(leftHoneyGroundClearance / 0.28))
                    * Math.exp(-square(rightHoneyGroundClearance / 0.28)));
        }
        info.put("left_servo_load", leftLoad);
        info.put("right_servo_load", rightLoad);
        info.put("left_actual_angle_degrees", leftActualAngleDegrees);
        info.put("right_actual_angle_degrees", rightActualAngleDegrees);
        info.put("mean_servo_load", (Math.abs(leftLoad) + Math.abs(rightLoad)) * 0.5);
        info.put("peak_servo_load", Math.max(Math.abs(leftLoad), Math.abs(rightLoad)));
        info.put("left_joint_angular_velocity", leftAngularVelocity);
        info.put("right_joint_angular_velocity", rightAngularVelocity);
        info.put("applied_left_action", currentAction.length > 0 ? finiteOrZero(currentAction[0]) : 0.0);
        info.put("applied_right_action", currentAction.length > 1 ? finiteOrZero(currentAction[1]) : 0.0);
        if (episode.taskMode() == EnvironmentTaskMode.POINT_GOAL) {
            final Vec3 targetPosition = this.pointTargetWorldPosition(machine, episode);
            info.put("target_position_x", finiteOrZero(targetPosition.x));
            info.put("target_position_y", finiteOrZero(targetPosition.y));
            info.put("target_position_z", finiteOrZero(targetPosition.z));
            info.put("target_local_forward_blocks", episode.pointTarget().localForwardBlocks());
            info.put("target_local_right_blocks", episode.pointTarget().localRightBlocks());
            info.put("target_success_radius_blocks", episode.pointTarget().successRadiusBlocks());
        }
        return info;
    }

    @Override
    public ResetResult<MinecraftMachinesLiveDuopod> reset(
            MinecraftMachinesLiveDuopod machine,
            final EpisodeResetContext context
    ) {
        boolean respawned = false;
        if (!machine.freshFromSpawn()) {
            this.destroy(machine);
            machine = this.spawn(new TrainingSpawnContext(
                    context.slotIndex(),
                    context.episodeDefinition().episodeId(),
                    context.episodeDefinition().seed(),
                    context.episodeDefinition().curriculumStage(),
                    context.episodeDefinition().terrainProfile()));
            respawned = true;
        }

        machine.markResetConsumed();
        machine.control().commandNeutral();
        if (context.episodeDefinition().taskMode() == EnvironmentTaskMode.BALANCE) {
            final double[] jointOffsets = context.episodeDefinition().standingDisturbance().jointActionOffsets();
            if (jointOffsets.length >= 2) {
                machine.control().setNormalizedAction(jointOffsets[0], jointOffsets[1]);
            }
        }
        this.onEpisodeUpdated(machine, context.episodeDefinition());
        final double[] zeroAction = new double[DuopodSchemas.actionSpec().size()];
        final double initialDistanceToTarget = context.episodeDefinition().taskMode() == EnvironmentTaskMode.POINT_GOAL
                ? this.distanceToTarget(machine, context.episodeDefinition(), null)
                : context.episodeDefinition().initialDistanceToTarget();
        final EpisodeRuntime resetRuntime = new EpisodeRuntime(
                context.episodeDefinition().episodeId(),
                context.episodeDefinition().seed(),
                0,
                0,
                0.0,
                1.0 / 20.0,
                zeroAction,
                initialDistanceToTarget,
                0.0,
                Map.of(),
                0);
        return new ResetResult<>(
                machine,
                this.observe(machine, context.episodeDefinition(), resetRuntime),
                initialDistanceToTarget,
                respawned);
    }

    @Override
    public void destroy(final MinecraftMachinesLiveDuopod machine) {
        if (machine != null && machine.control() != null) {
            machine.control().commandNeutral();
            machine.control().destroy();
        }
    }

    int activeTerrainLeaseCount() {
        return 0;
    }

    DuopodObservation encodedObservation(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode,
            final double[] previousAction,
            final double phaseRad
    ) {
        final MotionSnapshot snapshot = this.motionSnapshot(machine);
        final double leftPreviousAction = previousAction.length > 0 ? previousAction[0] : 0.0;
        final double rightPreviousAction = previousAction.length > 1 ? previousAction[1] : 0.0;
        final Vec3 centerOfMassPosition = machine.control().getAggregateCenterOfMassPosition();
        final CenterOfMassBalanceMetrics centerOfMassMetrics = this.centerOfMassBalanceMetrics(machine, centerOfMassPosition);
        final BalanceSupportMetrics supportMetrics = this.balanceSupportMetrics(machine);
        return DuopodObservationEncoder.encode(new DuopodObservationInput(
                phaseRad,
                this.effectiveCommand(machine, episode),
                snapshot.motion().forwardVelocity(),
                snapshot.motion().lateralVelocity(),
                snapshot.motion().verticalVelocity(),
                snapshot.motion().rollRate(),
                snapshot.motion().pitchRate(),
                snapshot.motion().yawRate(),
                snapshot.gravity().forward(),
                snapshot.gravity().right(),
                this.heightAboveTaskReference(machine, episode) - this.standingConfig.targetStandingHeightAboveSpawnBlocks(),
                centerOfMassMetrics.heightDeltaBlocks(),
                centerOfMassMetrics.forwardDriftBlocks(),
                centerOfMassMetrics.lateralDriftBlocks(),
                supportMetrics.supportForwardErrorBlocks(),
                supportMetrics.supportLateralErrorBlocks(),
                machine.control().getLeftTelemetrySample(leftPreviousAction),
                machine.control().getRightTelemetrySample(rightPreviousAction)));
    }

    private MotionSnapshot motionSnapshot(final MinecraftMachinesLiveDuopod machine) {
        return this.motionSnapshot(machine, machine.control().getBaseOrientation());
    }

    private MotionSnapshot spawnFrameMotionSnapshot(final MinecraftMachinesLiveDuopod machine) {
        return this.motionSnapshot(machine, machine.control().duopod().spawnOrientationView());
    }

    private MotionSnapshot motionSnapshot(
            final MinecraftMachinesLiveDuopod machine,
            final Quaterniondc motionFrameOrientation
    ) {
        final Quaterniondc orientation = machine.control().getBaseOrientation();
        final Vector3dc linearVelocity = machine.control().getBaseLinearVelocity();
        final Vector3dc angularVelocity = machine.control().getBaseAngularVelocity();
        return new MotionSnapshot(
                DuopodKinematics.localMotion(
                        linearVelocity,
                        angularVelocity,
                        motionFrameOrientation,
                        machine.control().duopod().modelForwardDirection()),
                DuopodKinematics.projectedGravity(
                        orientation,
                        machine.control().duopod().modelForwardDirection()),
                DuopodKinematics.bodyUpDotWorldUp(
                        orientation,
                        machine.control().duopod().modelForwardDirection()));
    }

    private boolean isWalkForwardStage(final EpisodeDefinition episode) {
        return DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(
                DuopodTrainingScenarios.normalizeCurriculumStage(episode.curriculumStage().id()));
    }

    private boolean isCenterOfMassBalanceStage(final EpisodeDefinition episode) {
        return DuopodTrainingScenarios.BALANCE_CENTER_OF_MASS_STAGE.equals(
                DuopodTrainingScenarios.normalizeCurriculumStage(episode.curriculumStage().id()));
    }

    private CenterOfMassBalanceMetrics centerOfMassBalanceMetrics(
            final MinecraftMachinesLiveDuopod machine,
            final Vec3 centerOfMassPosition
    ) {
        final Vec3 reference = machine.standingCenterOfMassReference();
        final DuopodKinematics.LocalOffset drift = DuopodKinematics.horizontalLocalOffset(
                reference,
                machine.control().getBaseOrientation(),
                machine.control().duopod().modelForwardDirection(),
                centerOfMassPosition);
        return new CenterOfMassBalanceMetrics(
                finiteOrZero(centerOfMassPosition.y - reference.y),
                finiteOrZero(drift.forward()),
                finiteOrZero(drift.right()));
    }

    private BalanceSupportMetrics balanceSupportMetrics(final MinecraftMachinesLiveDuopod machine) {
        return this.balanceSupportMetrics(
                machine,
                machine.control().getLeftHoneyTipPosition(),
                machine.control().getRightHoneyTipPosition(),
                machine.control().getAggregateCenterOfMassPosition());
    }

    private BalanceSupportMetrics balanceSupportMetrics(
            final MinecraftMachinesLiveDuopod machine,
            final Vec3 leftHoneyTipPosition,
            final Vec3 rightHoneyTipPosition,
            final Vec3 centerOfMassPosition
    ) {
        return balanceSupportMetrics(
                leftHoneyTipPosition,
                rightHoneyTipPosition,
                centerOfMassPosition,
                machine.control().getBaseOrientation(),
                machine.control().duopod().modelForwardDirection());
    }

    private static BalanceSupportMetrics balanceSupportMetrics(
            final Vec3 leftHoneyTipPosition,
            final Vec3 rightHoneyTipPosition,
            final Vec3 centerOfMassPosition,
            final Quaterniondc baseOrientation,
            final Direction modelForwardDirection
    ) {
        final double leftX = finiteOrZero(leftHoneyTipPosition.x);
        final double leftZ = finiteOrZero(leftHoneyTipPosition.z);
        final double rightX = finiteOrZero(rightHoneyTipPosition.x);
        final double rightZ = finiteOrZero(rightHoneyTipPosition.z);
        final double segmentX = rightX - leftX;
        final double segmentZ = rightZ - leftZ;
        final double segmentLengthSquared = segmentX * segmentX + segmentZ * segmentZ;
        final double centerX = finiteOrZero(centerOfMassPosition.x);
        final double centerZ = finiteOrZero(centerOfMassPosition.z);
        double segmentParameter = 0.5;
        if (segmentLengthSquared > 1.0e-8) {
            segmentParameter = clamp(
                    ((centerX - leftX) * segmentX + (centerZ - leftZ) * segmentZ)
                            / segmentLengthSquared,
                    0.0,
                    1.0);
        }
        final double closestX = finiteOrZero(leftX + segmentX * segmentParameter);
        final double closestZ = finiteOrZero(leftZ + segmentZ * segmentParameter);
        final double errorX = centerX - closestX;
        final double errorZ = centerZ - closestZ;
        final DuopodKinematics.LocalOffset localError = DuopodKinematics.horizontalLocalOffset(
                new Vec3(closestX, centerOfMassPosition.y, closestZ),
                baseOrientation,
                modelForwardDirection,
                new Vec3(centerX, centerOfMassPosition.y, centerZ));
        return new BalanceSupportMetrics(
                finiteOrZero(Math.hypot(errorX, errorZ)),
                finiteOrZero(localError.forward()),
                finiteOrZero(localError.right()),
                finiteOrZero(segmentParameter));
    }

    private LocomotionCommand effectiveCommand(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode
    ) {
        if (episode.taskMode() != EnvironmentTaskMode.POINT_GOAL) {
            return episode.command();
        }
        final DuopodKinematics.LocalOffset offset = this.currentPointTargetOffset(machine, episode);
        return PointTargetCommandMath.commandForLocalOffset(
                POINT_TARGET_COMMAND_CONFIG,
                offset.forward(),
                offset.right());
    }

    private DuopodKinematics.LocalOffset currentPointTargetOffset(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode
    ) {
        return DuopodKinematics.horizontalLocalOffset(
                machine.control().getBasePosition(),
                machine.control().getBaseOrientation(),
                machine.control().duopod().modelForwardDirection(),
                this.pointTargetWorldPosition(machine, episode));
    }

    private Vec3 pointTargetWorldPosition(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode
    ) {
        final DuopodInstance duopod = machine.control().duopod();
        return DuopodKinematics.horizontalTargetPosition(
                duopod.spawnPosition(),
                duopod.spawnOrientationView(),
                duopod.modelForwardDirection(),
                episode.pointTarget().localForwardBlocks(),
                episode.pointTarget().localRightBlocks());
    }

    private boolean targetReached(final EpisodeDefinition episode, final double currentDistanceToTarget) {
        return episode.taskMode() == EnvironmentTaskMode.POINT_GOAL
                && episode.pointTarget().reached(currentDistanceToTarget);
    }

    private boolean fellTooFar(final MinecraftMachinesLiveDuopod machine) {
        final Vec3 position = machine.control().getBasePosition();
        final Vec3 spawn = machine.control().duopod().spawnPosition();
        return position.y < spawn.y - FALL_FAILURE_DISTANCE;
    }

    private double balanceTiltMagnitude(final MotionSnapshot snapshot) {
        return finiteOrZero(Math.hypot(snapshot.gravity().forward(), snapshot.gravity().right()));
    }

    private double heightAboveSpawn(final MinecraftMachinesLiveDuopod machine) {
        final Vec3 position = machine.control().getBasePosition();
        final Vec3 spawn = machine.control().duopod().spawnPosition();
        return finiteOrZero(position.y - spawn.y);
    }

    private double heightAboveTaskReference(
            final MinecraftMachinesLiveDuopod machine,
            final EpisodeDefinition episode
    ) {
        if (episode.taskMode() != EnvironmentTaskMode.BALANCE) {
            return this.heightAboveSpawn(machine);
        }
        final Vec3 position = machine.control().getBasePosition();
        return finiteOrZero(position.y - machine.standingHeightReferenceY());
    }

    private double horizontalDistanceFromSpawn(final MinecraftMachinesLiveDuopod machine) {
        final Vec3 position = machine.control().getBasePosition();
        final Vec3 spawn = machine.control().duopod().spawnPosition();
        return finiteOrZero(Math.hypot(position.x - spawn.x, position.z - spawn.z));
    }

    private static double horizontalDistance(final Vec3 a, final Vec3 b) {
        return finiteOrZero(Math.hypot(a.x - b.x, a.z - b.z));
    }

    private double limbHeightDifference(final MinecraftMachinesLiveDuopod machine) {
        final Vec3 left = machine.control().getLeftChildPosition();
        final Vec3 right = machine.control().getRightChildPosition();
        return Math.abs(finiteOrZero(left.y - right.y));
    }

    private double honeyGroundClearance(final Vec3 honeyTipPosition) {
        return this.blockGroundClearance(honeyTipPosition, HONEY_TIP_HALF_EXTENT_BLOCKS);
    }

    private double servoGroundClearance(final Vec3 servoPosition) {
        return this.blockGroundClearance(servoPosition, SERVO_HALF_EXTENT_BLOCKS);
    }

    private double blockGroundClearance(final Vec3 blockCenter, final double halfExtentBlocks) {
        final int x = (int) Math.floor(blockCenter.x);
        final int z = (int) Math.floor(blockCenter.z);
        final int startY = Math.min(this.level.getMaxBuildHeight() - 1, (int) Math.floor(blockCenter.y));
        final int bottomY = Math.max(this.level.getMinBuildHeight(), startY - HONEY_GROUND_SEARCH_BLOCKS);
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(x, startY, z);
        for (int y = startY; y >= bottomY; y--) {
            mutable.setY(y);
            if (!this.level.getBlockState(mutable).canBeReplaced()) {
                final double blockBottomY = blockCenter.y - halfExtentBlocks;
                final double groundTopY = y + 1.0;
                return finiteOrZero(blockBottomY - groundTopY);
            }
        }
        return HONEY_GROUND_SEARCH_BLOCKS;
    }

    private static double finiteOrZero(final double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double square(final double value) {
        return value * value;
    }

    private record MotionSnapshot(
            DuopodKinematics.LocalBodyMotion motion,
            DuopodKinematics.ProjectedGravity gravity,
            double bodyUpDotWorldUp
    ) {
    }

    private record CenterOfMassBalanceMetrics(
            double heightDeltaBlocks,
            double forwardDriftBlocks,
            double lateralDriftBlocks
    ) {
        double horizontalDriftBlocks() {
            return finiteOrZero(Math.hypot(this.forwardDriftBlocks, this.lateralDriftBlocks));
        }
    }

    private record BalanceSupportMetrics(
            double supportDistanceBlocks,
            double supportForwardErrorBlocks,
            double supportLateralErrorBlocks,
            double segmentParameter
    ) {
    }

}
