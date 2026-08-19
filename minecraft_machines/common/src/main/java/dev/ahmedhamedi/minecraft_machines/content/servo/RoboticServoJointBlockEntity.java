package dev.ahmedhamedi.minecraft_machines.content.servo;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class RoboticServoJointBlockEntity extends GeneratingKineticBlockEntity implements BlockEntitySubLevelActor {
    private static final String TAG_ENABLED = "Enabled";
    private static final String TAG_KINETIC_ACTUAL_ANGLE = "KineticActualAngleRad";
    private static final String TAG_KINETIC_OUTPUT_SPEED = "KineticOutputSpeedRpm";
    private static final String TAG_REQUESTED_TARGET = "RequestedTargetAngleRad";
    private static final String TAG_EFFECTIVE_TARGET = "EffectiveTargetAngleRad";
    private static final String TAG_MINIMUM_ANGLE = "MinimumAngleRad";
    private static final String TAG_MAXIMUM_ANGLE = "MaximumAngleRad";
    private static final String TAG_MAX_SPEED = "MaxAngularSpeedRadPerSecond";
    private static final String TAG_MAX_TORQUE = "MaxTorque";
    private static final String TAG_STIFFNESS = "Stiffness";
    private static final String TAG_DAMPING = "Damping";
    private static final String TAG_PASSIVE_DAMPING = "PassiveDamping";
    private static final String TAG_SERVO_INSTANCE_ID = "ServoInstanceId";
    private static final String TAG_SUB_LEVEL_ID = "AttachedSubLevelId";
    private static final String TAG_LINK_POS = "LinkPos";
    private static final String TAG_REFERENCE = "AssemblyReferenceOrientation";
    private static final double LIMIT_EPSILON = 1.0e-4;
    private static final double MIN_CONTROL_INERTIA = 10.0;
    private static final double MAX_CONTROL_INERTIA = 1_000_000.0;

    @Nullable
    private UUID attachedSubLevelId;
    @Nullable
    private BlockPos linkPos;
    @Nullable
    private RotaryConstraintHandle constraintHandle;

    private boolean enabled = ServoJointDefaults.ENABLED;
    private double requestedTargetAngleRad = ServoJointDefaults.TARGET_ANGLE_RAD;
    private double effectiveTargetAngleRad = ServoJointDefaults.TARGET_ANGLE_RAD;
    private double minimumAngleRad = ServoJointDefaults.MINIMUM_ANGLE_RAD;
    private double maximumAngleRad = ServoJointDefaults.MAXIMUM_ANGLE_RAD;
    private double maxAngularSpeedRadPerSecond = ServoJointDefaults.MAX_ANGULAR_SPEED_RAD_PER_SECOND;
    private double maxTorque = ServoJointDefaults.MAX_TORQUE;
    private double stiffness = ServoJointDefaults.STIFFNESS;
    private double damping = ServoJointDefaults.DAMPING;
    private double passiveDamping = ServoJointDefaults.PASSIVE_DAMPING;

    private final Quaterniond assemblyReferenceOrientation = new Quaterniond();
    private double actualAngleRad;
    private double angularVelocityRadPerSecond;
    private double estimatedTorque;
    private double jointLoad;
    private float kineticOutputSpeedRpm;
    private double lastPhysicsTimeStep = 1.0 / 20.0;
    private double lastControlInertia = MIN_CONTROL_INERTIA;
    private ServoJointMotorCommand lastMotorCommand = ServoJointMotorCommand.passive(ServoJointDefaults.PASSIVE_DAMPING, ServoJointDefaults.MAX_TORQUE);
    private UUID servoInstanceId = UUID.randomUUID();
    private long lastSubLevelKineticTick = Long.MIN_VALUE;
    private boolean assembling;

    public RoboticServoJointBlockEntity(final BlockEntityType<?> type, final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    @Override
    public void initialize() {
        super.initialize();

        if (!this.hasSource() || Math.abs(this.getGeneratedSpeed()) > Math.abs(this.getTheoreticalSpeed())) {
            this.updateGeneratedRotation();
        }
    }

    @Override
    public float getGeneratedSpeed() {
        final BlockState state = this.getBlockState();
        if (!state.is(MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get()) || !this.enabled) {
            return 0.0f;
        }

        return convertToDirection(this.kineticOutputSpeedRpm, state.getValue(RoboticServoJointBlock.FACING));
    }

    @Override
    public float calculateAddedStressCapacity() {
        this.lastCapacityProvided = 1_000_000.0f;
        return this.lastCapacityProvided;
    }

    @Override
    public float calculateStressApplied() {
        this.lastStressApplied = 0.0f;
        return 0.0f;
    }

    @Override
    public void tick() {
        super.tick();

        final Level level = this.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }

        if (this.hasInternalJointAssembly()) {
            if (this.checkPersistence()) {
                return;
            }
            this.clearInternalJointAssemblyState();
        }

        if (this.attachedSubLevelId != null || this.linkPos != null || this.constraintHandle != null || this.isAssembled()) {
            this.clearInternalJointAssemblyState();
        }

        this.tickKineticServo();
    }

    public void disassemble() {
        final Level level = this.getLevel();
        if (level == null || level.isClientSide || this.isRemoved()) {
            return;
        }

        this.removeConstraint();

        final SubLevel attached = this.getAttachedSubLevel();
        final BlockPos currentLinkPos = this.linkPos;
        if (currentLinkPos != null) {
            this.destroyLinkBlock(currentLinkPos);

            if (attached != null && !attached.isRemoved()) {
                if (Objects.equals(attached, this.getContainingSubLevel())) {
                    MinecraftMachines.LOGGER.warn("Robotic Servo Joint at {} refused to disassemble its own containing sublevel", this.getBlockPos());
                } else {
                    SimAssemblyHelper.disassembleSubLevel(level, attached, currentLinkPos, this.getBlockPos(), Rotation.NONE, true);
                }
            }
        }

        this.attachedSubLevelId = null;
        this.linkPos = null;
        this.effectiveTargetAngleRad = ServoJointMath.clamp(this.effectiveTargetAngleRad, this.minimumAngleRad, this.maximumAngleRad);
        this.setAssembledState(false);
        this.setChanged();
        this.sendData();
    }

    public void beforeAssemblyMove() {
        this.assembling = true;
        this.detachKinetics();
        this.removeSource();
    }

    public void afterAssemblyMove() {
        this.assembling = false;
        this.associateLinkWithParent();

        final ServerSubLevel attached = this.getAttachedServerSubLevel();
        if (attached != null) {
            this.attachConstraint(attached, true);
        }

        this.reActivateSource = true;
        this.updateGeneratedRotation();
        this.setChanged();
        this.sendData();
    }

    public void onLinkRemoved(final BlockPos removedLinkPos) {
        if (this.level == null || this.level.isClientSide || this.linkPos == null || !this.linkPos.equals(removedLinkPos)) {
            return;
        }

        this.removeConstraint();
        this.attachedSubLevelId = null;
        this.linkPos = null;
        this.setAssembledState(false);
        this.setChanged();
        this.sendData();
    }

    public void onLinkMoved(final BlockPos newLinkPos, @Nullable final UUID newSubLevelId) {
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        this.linkPos = newLinkPos;
        this.attachedSubLevelId = newSubLevelId;
        this.removeConstraint();
        final ServerSubLevel attached = this.getAttachedServerSubLevel();
        if (attached != null) {
            this.attachConstraint(attached, true);
        }
        this.setChanged();
        this.sendData();
    }

    public void onServoPhysicsStep(final ServerSubLevel attachedSubLevel, final RigidBodyHandle attachedHandle, final double timeStep) {
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        if (this.attachedSubLevelId == null || !this.attachedSubLevelId.equals(attachedSubLevel.getUniqueId())) {
            this.attachedSubLevelId = attachedSubLevel.getUniqueId();
        }

        this.lastPhysicsTimeStep = Double.isFinite(timeStep) && timeStep > 0.0 ? timeStep : this.lastPhysicsTimeStep;

        final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(this.level);
        if (physicsSystem != null) {
            physicsSystem.updatePose(attachedSubLevel);
            final SubLevel containing = this.getContainingSubLevel();
            if (containing instanceof final ServerSubLevel serverContaining) {
                physicsSystem.updatePose(serverContaining);
            }
        }

        this.validateConstraintHandle();
        if (this.constraintHandle == null) {
            this.attachConstraint(attachedSubLevel, true);
        }

        this.updateTelemetry(attachedSubLevel, attachedHandle, this.lastPhysicsTimeStep);
        this.stepEffectiveTarget(this.lastPhysicsTimeStep);
        this.applyMotor(this.lastPhysicsTimeStep);
    }

    public boolean isAssembled() {
        final BlockState state = this.getBlockState();
        return state.hasProperty(RoboticServoJointBlock.ASSEMBLED) && state.getValue(RoboticServoJointBlock.ASSEMBLED);
    }

    public boolean hasValidConstraint() {
        this.validateConstraintHandle();
        return this.constraintHandle != null;
    }

    public ServoJointTelemetry getTelemetry() {
        return new ServoJointTelemetry(
                this.isAssembled(),
                this.enabled,
                this.actualAngleRad,
                this.angularVelocityRadPerSecond,
                this.requestedTargetAngleRad,
                this.effectiveTargetAngleRad,
                this.getAngleErrorRad(),
                this.estimatedTorque,
                this.jointLoad,
                this.isAtMinimumLimit(),
                this.isAtMaximumLimit()
        );
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        this.applyMotor(this.lastPhysicsTimeStep);
        if (!enabled) {
            this.setKineticOutputSpeed(0.0f);
        }
        this.wakeBodies();
        this.syncConfig();
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setTargetAngleRad(final double targetAngleRad) {
        ServoJointMath.requireFinite("targetAngleRad", targetAngleRad);
        this.requestedTargetAngleRad = this.clampTargetAngle(targetAngleRad);
        this.wakeBodies();
        this.syncConfig();
    }

    public void setTargetAngleDegrees(final double targetAngleDegrees) {
        this.setTargetAngleRad(Math.toRadians(targetAngleDegrees));
    }

    public double getRequestedTargetAngleRad() {
        return this.requestedTargetAngleRad;
    }

    public double getTargetAngleRad() {
        return this.requestedTargetAngleRad;
    }

    public double getRequestedTargetAngleDegrees() {
        return Math.toDegrees(this.requestedTargetAngleRad);
    }

    public double getTargetAngleDegrees() {
        return this.getRequestedTargetAngleDegrees();
    }

    public double getEffectiveTargetAngleRad() {
        return this.effectiveTargetAngleRad;
    }

    public double getEffectiveTargetAngleDegrees() {
        return Math.toDegrees(this.effectiveTargetAngleRad);
    }

    public void setAngleLimitsRad(final double minimumAngleRad, final double maximumAngleRad) {
        ServoJointMath.requireFinite("minimumAngleRad", minimumAngleRad);
        ServoJointMath.requireFinite("maximumAngleRad", maximumAngleRad);
        if (minimumAngleRad >= maximumAngleRad) {
            throw new IllegalArgumentException("minimumAngleRad must be lower than maximumAngleRad");
        }

        this.minimumAngleRad = minimumAngleRad;
        this.maximumAngleRad = maximumAngleRad;
        this.requestedTargetAngleRad = this.clampTargetAngle(this.requestedTargetAngleRad);
        this.effectiveTargetAngleRad = ServoJointMath.clamp(this.effectiveTargetAngleRad, minimumAngleRad, maximumAngleRad);
        this.wakeBodies();
        this.syncConfig();
    }

    public void setAngleLimitsDegrees(final double minimumAngleDegrees, final double maximumAngleDegrees) {
        this.setAngleLimitsRad(Math.toRadians(minimumAngleDegrees), Math.toRadians(maximumAngleDegrees));
    }

    public double getMinimumAngleRad() {
        return this.minimumAngleRad;
    }

    public double getMinimumAngleDegrees() {
        return Math.toDegrees(this.minimumAngleRad);
    }

    public double getMaximumAngleRad() {
        return this.maximumAngleRad;
    }

    public double getMaximumAngleDegrees() {
        return Math.toDegrees(this.maximumAngleRad);
    }

    public void setMaxAngularSpeedRadPerSecond(final double maxAngularSpeedRadPerSecond) {
        this.maxAngularSpeedRadPerSecond = ServoJointMath.requireNonNegative("maxAngularSpeedRadPerSecond", maxAngularSpeedRadPerSecond);
        this.wakeBodies();
        this.syncConfig();
    }

    public void setMaxAngularSpeedDegreesPerSecond(final double maxAngularSpeedDegreesPerSecond) {
        this.setMaxAngularSpeedRadPerSecond(Math.toRadians(maxAngularSpeedDegreesPerSecond));
    }

    public double getMaxAngularSpeedRadPerSecond() {
        return this.maxAngularSpeedRadPerSecond;
    }

    public double getMaxAngularSpeedDegreesPerSecond() {
        return Math.toDegrees(this.maxAngularSpeedRadPerSecond);
    }

    public void setMaxTorque(final double maxTorque) {
        this.maxTorque = ServoJointMath.requireNonNegative("maxTorque", maxTorque);
        this.applyMotor(this.lastPhysicsTimeStep);
        this.wakeBodies();
        this.syncConfig();
    }

    public double getMaxTorque() {
        return this.maxTorque;
    }

    public double getControlInertia() {
        return this.lastControlInertia;
    }

    public double getAppliedMotorTargetAngleRad() {
        return this.lastMotorCommand.targetAngleRad();
    }

    public double getAppliedMotorTargetAngleDegrees() {
        return Math.toDegrees(this.lastMotorCommand.targetAngleRad());
    }

    public double getAppliedMotorStiffness() {
        return this.lastMotorCommand.stiffness();
    }

    public double getAppliedMotorDamping() {
        return this.lastMotorCommand.damping();
    }

    public double getAppliedMotorMaxTorque() {
        return this.lastMotorCommand.maxTorque();
    }

    public boolean isAppliedMotorForceLimited() {
        return this.lastMotorCommand.forceLimited();
    }

    public void setServoGains(final double stiffness, final double damping) {
        this.stiffness = ServoJointMath.requireNonNegative("stiffness", stiffness);
        this.damping = ServoJointMath.requireNonNegative("damping", damping);
        this.applyMotor(this.lastPhysicsTimeStep);
        this.wakeBodies();
        this.syncConfig();
    }

    public void setGains(final double stiffness, final double damping, final double passiveDamping) {
        this.stiffness = ServoJointMath.requireNonNegative("stiffness", stiffness);
        this.damping = ServoJointMath.requireNonNegative("damping", damping);
        this.passiveDamping = ServoJointMath.requireNonNegative("passiveDamping", passiveDamping);
        this.applyMotor(this.lastPhysicsTimeStep);
        this.wakeBodies();
        this.syncConfig();
    }

    public void setPassiveDamping(final double passiveDamping) {
        this.passiveDamping = ServoJointMath.requireNonNegative("passiveDamping", passiveDamping);
        this.applyMotor(this.lastPhysicsTimeStep);
        this.wakeBodies();
        this.syncConfig();
    }

    public double getStiffness() {
        return this.stiffness;
    }

    public double getDamping() {
        return this.damping;
    }

    public double getPassiveDamping() {
        return this.passiveDamping;
    }

    public double getActualAngleRad() {
        return this.actualAngleRad;
    }

    public double getActualAngleDegrees() {
        return Math.toDegrees(this.actualAngleRad);
    }

    public double getAngularVelocityRadPerSecond() {
        return this.angularVelocityRadPerSecond;
    }

    public double getAngularVelocityDegreesPerSecond() {
        return Math.toDegrees(this.angularVelocityRadPerSecond);
    }

    public double getAngleErrorRad() {
        return ServoJointMath.normalizeSignedAngle(this.effectiveTargetAngleRad - this.actualAngleRad);
    }

    public double getAngleErrorDegrees() {
        return Math.toDegrees(this.getAngleErrorRad());
    }

    public double getEstimatedTorque() {
        return this.estimatedTorque;
    }

    public double getJointLoad() {
        return this.jointLoad;
    }

    public UUID getServoInstanceId() {
        return this.servoInstanceId;
    }

    public boolean isAtMinimumLimit() {
        return this.actualAngleRad <= this.minimumAngleRad + LIMIT_EPSILON;
    }

    public boolean isAtMaximumLimit() {
        return this.actualAngleRad >= this.maximumAngleRad - LIMIT_EPSILON;
    }

    @Override
    protected void write(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        compound.putBoolean(TAG_ENABLED, this.enabled);
        compound.putDouble(TAG_KINETIC_ACTUAL_ANGLE, this.actualAngleRad);
        compound.putFloat(TAG_KINETIC_OUTPUT_SPEED, this.kineticOutputSpeedRpm);
        compound.putDouble(TAG_REQUESTED_TARGET, this.requestedTargetAngleRad);
        compound.putDouble(TAG_EFFECTIVE_TARGET, this.effectiveTargetAngleRad);
        compound.putDouble(TAG_MINIMUM_ANGLE, this.minimumAngleRad);
        compound.putDouble(TAG_MAXIMUM_ANGLE, this.maximumAngleRad);
        compound.putDouble(TAG_MAX_SPEED, this.maxAngularSpeedRadPerSecond);
        compound.putDouble(TAG_MAX_TORQUE, this.maxTorque);
        compound.putDouble(TAG_STIFFNESS, this.stiffness);
        compound.putDouble(TAG_DAMPING, this.damping);
        compound.putDouble(TAG_PASSIVE_DAMPING, this.passiveDamping);
        compound.putUUID(TAG_SERVO_INSTANCE_ID, this.servoInstanceId);
        compound.put(TAG_REFERENCE, writeQuaternion(this.assemblyReferenceOrientation));

        UUID subLevelId = this.attachedSubLevelId;
        BlockPos currentLinkPos = this.linkPos;

        final SubLevelSchematicSerializationContext schematicContext = SubLevelSchematicSerializationContext.getCurrentContext();
        if (subLevelId != null && schematicContext != null) {
            final SubLevelSchematicSerializationContext.SchematicMapping mapping = schematicContext.getMapping(subLevelId);
            if (mapping != null) {
                subLevelId = mapping.newUUID();
                currentLinkPos = currentLinkPos == null ? null : mapping.transform().apply(currentLinkPos);
            } else {
                subLevelId = null;
                currentLinkPos = null;
            }
        }

        if (subLevelId != null) {
            compound.putUUID(TAG_SUB_LEVEL_ID, subLevelId);
        }
        if (currentLinkPos != null) {
            compound.put(TAG_LINK_POS, NbtUtils.writeBlockPos(currentLinkPos));
        }
    }

    @Override
    protected void read(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        this.enabled = compound.contains(TAG_ENABLED) ? compound.getBoolean(TAG_ENABLED) : ServoJointDefaults.ENABLED;
        this.actualAngleRad = readFinite(compound, TAG_KINETIC_ACTUAL_ANGLE, this.actualAngleRad);
        this.kineticOutputSpeedRpm = compound.contains(TAG_KINETIC_OUTPUT_SPEED) ? compound.getFloat(TAG_KINETIC_OUTPUT_SPEED) : 0.0f;
        this.requestedTargetAngleRad = readFinite(compound, TAG_REQUESTED_TARGET, ServoJointDefaults.TARGET_ANGLE_RAD);
        this.effectiveTargetAngleRad = readFinite(compound, TAG_EFFECTIVE_TARGET, ServoJointDefaults.TARGET_ANGLE_RAD);
        this.minimumAngleRad = readFinite(compound, TAG_MINIMUM_ANGLE, ServoJointDefaults.MINIMUM_ANGLE_RAD);
        this.maximumAngleRad = readFinite(compound, TAG_MAXIMUM_ANGLE, ServoJointDefaults.MAXIMUM_ANGLE_RAD);
        if (this.minimumAngleRad >= this.maximumAngleRad) {
            this.minimumAngleRad = ServoJointDefaults.MINIMUM_ANGLE_RAD;
            this.maximumAngleRad = ServoJointDefaults.MAXIMUM_ANGLE_RAD;
        }
        this.maxAngularSpeedRadPerSecond = readNonNegative(compound, TAG_MAX_SPEED, ServoJointDefaults.MAX_ANGULAR_SPEED_RAD_PER_SECOND);
        this.maxTorque = readNonNegative(compound, TAG_MAX_TORQUE, ServoJointDefaults.MAX_TORQUE);
        this.stiffness = readNonNegative(compound, TAG_STIFFNESS, ServoJointDefaults.STIFFNESS);
        this.damping = readNonNegative(compound, TAG_DAMPING, ServoJointDefaults.DAMPING);
        this.passiveDamping = readNonNegative(compound, TAG_PASSIVE_DAMPING, ServoJointDefaults.PASSIVE_DAMPING);
        this.servoInstanceId = compound.hasUUID(TAG_SERVO_INSTANCE_ID) ? compound.getUUID(TAG_SERVO_INSTANCE_ID) : UUID.randomUUID();
        this.requestedTargetAngleRad = this.clampTargetAngle(this.requestedTargetAngleRad);
        this.effectiveTargetAngleRad = ServoJointMath.clamp(this.effectiveTargetAngleRad, this.minimumAngleRad, this.maximumAngleRad);

        this.attachedSubLevelId = compound.hasUUID(TAG_SUB_LEVEL_ID) ? compound.getUUID(TAG_SUB_LEVEL_ID) : null;
        this.linkPos = compound.contains(TAG_LINK_POS) ? NbtUtils.readBlockPos(compound, TAG_LINK_POS).orElse(null) : null;
        if (compound.contains(TAG_REFERENCE)) {
            readQuaternion(compound.getCompound(TAG_REFERENCE), this.assemblyReferenceOrientation);
        } else {
            this.assemblyReferenceOrientation.identity();
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        this.removeConstraint();
    }

    @Override
    public void remove() {
        if (this.level != null && !this.level.isClientSide && !this.assembling) {
            this.disassemble();
        }
        super.remove();
    }

    @Override
    public @Nullable Iterable<@NotNull SubLevel> sable$getConnectionDependencies() {
        final SubLevel attached = this.getAttachedSubLevel();
        return attached == null ? null : List.of(attached);
    }

    @Override
    public void sable$physicsTick(final ServerSubLevel subLevel, final RigidBodyHandle handle, final double timeStep) {
        this.tickSubLevelKineticServo(timeStep);
    }

    public void tickSubLevelKineticServo() {
        this.tickSubLevelKineticServo(1.0 / 20.0);
    }

    private void tickSubLevelKineticServo(final double timeStep) {
        if (this.level == null || this.level.isClientSide || this.isRemoved()) {
            return;
        }
        if (this.attachedSubLevelId != null || this.linkPos != null || this.constraintHandle != null) {
            return;
        }
        final long gameTime = this.level.getGameTime();
        if (this.lastSubLevelKineticTick == gameTime) {
            return;
        }
        this.lastSubLevelKineticTick = gameTime;
        this.tickKineticServo(timeStep);
    }

    private boolean checkPersistence() {
        final ServerSubLevel attached = this.getAttachedServerSubLevel();
        this.validateConstraintHandle();

        if (attached == null) {
            return false;
        }

        this.associateLinkWithParent();
        if (this.constraintHandle == null) {
            this.attachConstraint(attached, false);
        }
        return true;
    }

    private boolean hasInternalJointAssembly() {
        return this.attachedSubLevelId != null && this.linkPos != null;
    }

    private void clearInternalJointAssemblyState() {
        this.removeConstraint();
        this.attachedSubLevelId = null;
        this.linkPos = null;
        this.setAssembledState(false);
        this.setChanged();
        this.sendData();
    }

    private void tickKineticServo() {
        this.tickKineticServo(1.0 / 20.0);
    }

    private void tickKineticServo(final double tickTimeStep) {
        final double timeStep = Double.isFinite(tickTimeStep) && tickTimeStep > 0.0 ? tickTimeStep : 1.0 / 20.0;
        this.lastPhysicsTimeStep = timeStep;
        this.stepEffectiveTarget(timeStep);

        if (!this.enabled) {
            this.angularVelocityRadPerSecond = 0.0;
            this.estimatedTorque = 0.0;
            this.jointLoad = 0.0;
            this.lastMotorCommand = ServoJointMotorCommand.passive(this.passiveDamping, this.maxTorque);
            this.setKineticOutputSpeed(0.0f);
            return;
        }

        final double error = this.getAngleErrorRad();
        final double maxStep = this.maxAngularSpeedRadPerSecond * timeStep;
        final double angleStep = maxStep <= 0.0 || Math.abs(error) < LIMIT_EPSILON
                ? 0.0
                : ServoJointMath.clamp(error, -maxStep, maxStep);
        final double commandedVelocity = angleStep / timeStep;

        this.actualAngleRad = ServoJointMath.normalizeSignedAngle(this.actualAngleRad + angleStep);
        this.angularVelocityRadPerSecond = commandedVelocity;
        this.estimatedTorque = 0.0;
        this.jointLoad = 0.0;
        this.lastMotorCommand = ServoJointMotorCommand.active(this.effectiveTargetAngleRad, this.stiffness, this.damping, this.maxTorque);
        this.setKineticOutputSpeed((float) (commandedVelocity * 60.0 / (2.0 * Math.PI)));

        if (angleStep != 0.0) {
            this.setChanged();
        }
    }

    private void attachConstraint(final @Nullable ServerSubLevel attachedSubLevel, final boolean updateLinkParent) {
        if (this.level == null || !(this.level instanceof ServerLevel serverLevel) || attachedSubLevel == null || this.linkPos == null) {
            return;
        }

        this.removeConstraint();

        if (updateLinkParent) {
            this.associateLinkWithParent();
        }

        final BlockState linkState = this.level.getBlockState(this.linkPos);
        if (!linkState.is(MinecraftMachinesBlocks.SERVO_JOINT_LINK.get())) {
            return;
        }

        final Direction facing = this.getFacing();
        final Direction linkFacing = linkState.getValue(ServoJointLinkBlock.FACING);
        final Vector3d facingNormal = directionVector(facing);
        final Vector3d linkNormal = directionVector(linkFacing);
        final Vector3d anchorPos = JOMLConversion.toJOML(this.getBlockPos().relative(facing).getCenter());
        final Vector3d attachPos = JOMLConversion.toJOML(this.linkPos.relative(linkFacing).getCenter())
                .sub(new Vector3d(linkNormal).mul(0.001));

        final RotaryConstraintConfiguration constraint = new RotaryConstraintConfiguration(
                anchorPos,
                attachPos,
                facingNormal,
                linkNormal
        );

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        final SubLevel containing = this.getContainingSubLevel();
        final ServerSubLevel containingServerSubLevel = containing instanceof ServerSubLevel serverSubLevel ? serverSubLevel : null;

        if (containingServerSubLevel == attachedSubLevel) {
            return;
        }

        this.constraintHandle = pipeline.addConstraint(containingServerSubLevel, attachedSubLevel, constraint);
        this.applyMotor(this.lastPhysicsTimeStep);
    }

    private void applyMotor(final double timeStep) {
        this.validateConstraintHandle();
        if (this.constraintHandle == null) {
            return;
        }

        this.lastControlInertia = this.computeControlInertia();
        this.lastMotorCommand = this.enabled
                ? ServoJointMotorCommand.active(
                        this.effectiveTargetAngleRad,
                        this.stiffness * this.lastControlInertia,
                        this.damping * this.lastControlInertia,
                        this.maxTorque)
                : ServoJointMotorCommand.passive(this.passiveDamping, this.maxTorque);

        this.constraintHandle.setMotor(RotaryConstraintHandle.DEFAULT_AXIS,
                this.lastMotorCommand.targetAngleRad(),
                this.lastMotorCommand.stiffness(),
                this.lastMotorCommand.damping(),
                this.lastMotorCommand.forceLimited(),
                this.lastMotorCommand.maxTorque());
        this.constraintHandle.setContactsEnabled(false);

        if (this.enabled && Math.abs(this.getAngleErrorRad()) > 0.001 && timeStep > 0.0) {
            this.wakeBodies();
        }
    }

    private void setKineticOutputSpeed(final float speedRpm) {
        if (Math.abs(this.kineticOutputSpeedRpm - speedRpm) < 1.0e-4f) {
            return;
        }

        this.kineticOutputSpeedRpm = speedRpm;
        if (this.level != null && !this.level.isClientSide && !this.isRemoved()) {
            this.updateGeneratedRotation();
        }
        this.setChanged();
    }

    private double computeControlInertia() {
        final Direction facing = this.getFacing();
        final Vector3d temp = new Vector3d();

        double baseInertia = Double.MAX_VALUE;
        final SubLevel containing = this.getContainingSubLevel();
        if (containing instanceof final ServerSubLevel containingServerSubLevel) {
            baseInertia = this.projectedInertia(containingServerSubLevel.getMassTracker(), directionVector(facing), temp);
        }

        double childInertia = Double.MAX_VALUE;
        final ServerSubLevel attached = this.getAttachedServerSubLevel();
        if (attached != null) {
            childInertia = this.projectedInertia(attached.getMassTracker(), directionVector(this.getLinkFacing(facing)), temp);
        }

        final double inertia = containing != null && attached != null
                ? Math.max(baseInertia, childInertia)
                : Math.min(baseInertia, childInertia);

        if (!Double.isFinite(inertia) || inertia <= 0.0) {
            return MIN_CONTROL_INERTIA;
        }
        return ServoJointMath.clamp(inertia, MIN_CONTROL_INERTIA, MAX_CONTROL_INERTIA);
    }

    private double projectedInertia(final MassData massData, final Vector3d localAxis, final Vector3d temp) {
        if (massData == null || massData.isInvalid()) {
            return Double.MAX_VALUE;
        }

        final double inertia = massData.getInertiaTensor().transform(localAxis, temp).dot(localAxis);
        return Double.isFinite(inertia) && inertia > 0.0 ? inertia : Double.MAX_VALUE;
    }

    private void stepEffectiveTarget(final double timeStep) {
        // The public target is clamped immediately, but the effective target is rate-limited every physics substep.
        final double clampedTarget = this.clampTargetAngle(this.requestedTargetAngleRad);
        final double maxChange = this.maxAngularSpeedRadPerSecond * Math.max(0.0, timeStep);
        this.effectiveTargetAngleRad = ServoJointMath.moveTowards(
                ServoJointMath.clamp(this.effectiveTargetAngleRad, this.minimumAngleRad, this.maximumAngleRad),
                clampedTarget,
                maxChange
        );
    }

    private void updateTelemetry(final ServerSubLevel attachedSubLevel, final RigidBodyHandle attachedHandle, final double timeStep) {
        this.actualAngleRad = this.computeActualAngle(attachedSubLevel);

        final Vector3d worldAxis = this.getWorldHingeAxis(new Vector3d());
        final Vector3d childAngularVelocity = attachedHandle.getAngularVelocity(new Vector3d());
        final Vector3d baseAngularVelocity = new Vector3d();
        final SubLevel containing = this.getContainingSubLevel();
        if (containing instanceof final ServerSubLevel containingServerSubLevel) {
            final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(this.level);
            if (physicsSystem != null) {
                final RigidBodyHandle baseHandle = physicsSystem.getPhysicsHandle(containingServerSubLevel);
                if (baseHandle != null && baseHandle.isValid()) {
                    baseHandle.getAngularVelocity(baseAngularVelocity);
                }
            }
        }

        // Relative angular velocity is measured along the same world-space hinge axis as the angle sensor.
        this.angularVelocityRadPerSecond = finiteOrZero(childAngularVelocity.sub(baseAngularVelocity).dot(worldAxis));

        this.estimatedTorque = 0.0;
        this.jointLoad = 0.0;
        this.validateConstraintHandle();
        if (this.constraintHandle != null && timeStep > 0.0) {
            final Vector3d linearImpulse = new Vector3d();
            final Vector3d angularImpulse = new Vector3d();
            this.constraintHandle.getJointImpulses(linearImpulse, angularImpulse);
            // Sable exposes the last solver impulse; impulse divided by substep time is an estimated joint torque.
            this.estimatedTorque = finiteOrZero(angularImpulse.dot(worldAxis) / timeStep);
            this.jointLoad = Math.abs(this.estimatedTorque);
        }
    }

    /**
     * Computes the child body's signed angular displacement from the orientation captured at assembly.
     * The base body contributes identity orientation when the joint is mounted in the static world.
     * Matching the Swivel Bearing method, the facing directions are folded into the relative quaternion so
     * the local +Y component is the single hinge degree of freedom for every FACING value.
     */
    private double computeActualAngle(final SubLevel attachedSubLevel) {
        final Quaterniond current = this.computeOrientedRelativeOrientation(attachedSubLevel);
        final Quaterniond delta = new Quaterniond(this.assemblyReferenceOrientation).conjugate().mul(current).normalize();

        if (delta.w() < 0.0) {
            delta.set(-delta.x(), -delta.y(), -delta.z(), -delta.w());
        }

        return finiteOrZero(ServoJointMath.normalizeSignedAngle(-2.0 * Math.atan2(-delta.y(), delta.w())));
    }

    private void captureAssemblyReference(final SubLevel attachedSubLevel) {
        this.assemblyReferenceOrientation.set(this.computeOrientedRelativeOrientation(attachedSubLevel));
    }

    private Quaterniond computeOrientedRelativeOrientation(final SubLevel attachedSubLevel) {
        final Quaterniond baseOrientation = new Quaterniond();
        final SubLevel containing = this.getContainingSubLevel();
        if (containing != null) {
            baseOrientation.set(containing.logicalPose().orientation());
        }

        final Direction facing = this.getFacing();
        final Direction linkFacing = this.getLinkFacing(facing);
        final Quaterniond baseBlockOrientation = new Quaterniond(facing.getRotation());
        final Quaterniond childBlockOrientation = new Quaterniond(linkFacing.getRotation());
        final Quaterniond childOrientation = new Quaterniond(attachedSubLevel.logicalPose().orientation());

        return new Quaterniond(baseOrientation)
                .mul(baseBlockOrientation)
                .conjugate()
                .mul(childOrientation.mul(childBlockOrientation))
                .normalize();
    }

    private Vector3d getWorldHingeAxis(final Vector3d destination) {
        final Vector3d localAxis = directionVector(this.getFacing());
        final SubLevel containing = this.getContainingSubLevel();
        if (containing != null) {
            containing.logicalPose().transformNormal(localAxis, destination);
        } else {
            destination.set(localAxis);
        }
        return destination.normalize();
    }

    private void associateLinkWithParent() {
        if (this.level == null || this.linkPos == null) {
            return;
        }

        final BlockEntity blockEntity = this.level.getBlockEntity(this.linkPos);
        if (blockEntity instanceof final ServoJointLinkBlockEntity linkBlockEntity) {
            linkBlockEntity.setParent(this);
        }
    }

    private void destroyLinkBlock(final BlockPos pos) {
        if (this.level == null || !this.level.getBlockState(pos).is(MinecraftMachinesBlocks.SERVO_JOINT_LINK.get())) {
            return;
        }

        final BlockEntity blockEntity = this.level.getBlockEntity(pos);
        if (blockEntity instanceof final ServoJointLinkBlockEntity linkBlockEntity) {
            linkBlockEntity.beforeAssemblyMove();
        }
        this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
    }

    private void setAssembledState(final boolean assembled) {
        final Level level = this.getLevel();
        final BlockState state = this.getBlockState();
        if (level != null && state.hasProperty(RoboticServoJointBlock.ASSEMBLED) && state.getValue(RoboticServoJointBlock.ASSEMBLED) != assembled) {
            level.setBlockAndUpdate(this.getBlockPos(), state.setValue(RoboticServoJointBlock.ASSEMBLED, assembled));
        }
    }

    private void removeConstraint() {
        if (this.constraintHandle != null) {
            this.constraintHandle.remove();
            this.constraintHandle = null;
        }
    }

    private void validateConstraintHandle() {
        if (this.constraintHandle != null && !this.constraintHandle.isValid()) {
            this.constraintHandle = null;
        }
    }

    private void wakeBodies() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        final SubLevel containing = this.getContainingSubLevel();
        if (containing instanceof final ServerSubLevel serverContaining) {
            pipeline.wakeUp(serverContaining);
        }

        final ServerSubLevel attached = this.getAttachedServerSubLevel();
        if (attached != null) {
            pipeline.wakeUp(attached);
        }
    }

    private void syncConfig() {
        this.setChanged();
        this.sendData();
    }

    private Direction getFacing() {
        final BlockState state = this.getBlockState();
        return state.hasProperty(RoboticServoJointBlock.FACING) ? state.getValue(RoboticServoJointBlock.FACING) : Direction.NORTH;
    }

    private Direction getLinkFacing(final Direction fallback) {
        if (this.level == null || this.linkPos == null) {
            return fallback;
        }
        final BlockState linkState = this.level.getBlockState(this.linkPos);
        return linkState.hasProperty(ServoJointLinkBlock.FACING) ? linkState.getValue(ServoJointLinkBlock.FACING) : fallback;
    }

    private @Nullable SubLevel getAttachedSubLevel() {
        if (this.level == null || this.attachedSubLevelId == null) {
            return null;
        }
        return SubLevelContainer.getContainer(this.level).getSubLevel(this.attachedSubLevelId);
    }

    private @Nullable ServerSubLevel getAttachedServerSubLevel() {
        final SubLevel attached = this.getAttachedSubLevel();
        return attached instanceof ServerSubLevel serverSubLevel ? serverSubLevel : null;
    }

    private @Nullable SubLevel getContainingSubLevel() {
        return Sable.HELPER.getContaining(this);
    }

    private static Vector3d directionVector(final Direction direction) {
        final Vec3i normal = direction.getNormal();
        return new Vector3d(normal.getX(), normal.getY(), normal.getZ());
    }

    private static CompoundTag writeQuaternion(final Quaterniond quaternion) {
        final CompoundTag tag = new CompoundTag();
        tag.putDouble("X", quaternion.x());
        tag.putDouble("Y", quaternion.y());
        tag.putDouble("Z", quaternion.z());
        tag.putDouble("W", quaternion.w());
        return tag;
    }

    private static void readQuaternion(final CompoundTag tag, final Quaterniond destination) {
        final double x = readFinite(tag, "X", 0.0);
        final double y = readFinite(tag, "Y", 0.0);
        final double z = readFinite(tag, "Z", 0.0);
        final double w = readFinite(tag, "W", 1.0);
        destination.set(x, y, z, w);
        if (destination.lengthSquared() < 1.0e-12) {
            destination.identity();
        } else {
            destination.normalize();
        }
    }

    private static double readFinite(final CompoundTag compound, final String key, final double fallback) {
        if (!compound.contains(key)) {
            return fallback;
        }
        final double value = compound.getDouble(key);
        return Double.isFinite(value) ? value : fallback;
    }

    private static double readNonNegative(final CompoundTag compound, final String key, final double fallback) {
        final double value = readFinite(compound, key, fallback);
        return value >= 0.0 ? value : fallback;
    }

    private double clampTargetAngle(final double targetAngleRad) {
        return ServoJointMath.clamp(targetAngleRad, this.minimumAngleRad, this.maximumAngleRad);
    }

    private static double finiteOrZero(final double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    public @Nullable BlockPos getInternalLinkPos() {
        return this.linkPos;
    }

    public @Nullable UUID getAttachedSubLevelId() {
        return this.attachedSubLevelId;
    }

    ServoJointMotorCommand getLastMotorCommand() {
        return this.lastMotorCommand;
    }

    void removeConstraintForTesting() {
        this.removeConstraint();
    }
}
