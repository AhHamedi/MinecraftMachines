package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlock;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.TrainingMachineCollisionRegistry;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MinecraftMachinesDuopodSpawner {
    private static final double SERVO_LIMIT_DEGREES = 60.0;
    private static final double SERVO_MAX_SPEED_DEG_PER_SECOND = 90.0;
    private static final double SERVO_STIFFNESS = 800.0;
    private static final double SERVO_DAMPING = 350.0;
    private static final double SERVO_MAX_TORQUE = 75_000.0;
    private static final int LOWERED_LIMB_DROP_BLOCKS = 3;
    private static final int ASSEMBLY_GROUND_GAP_BLOCKS = 1;
    // Assemble clear of the terrain, then lower the complete constrained rig to a small, explicit
    // clearance. Exact contact can start Rapier with the honey colliders microscopically embedded in
    // the ground and turn penetration correction into a large, nondeterministic launch impulse.
    private static final double STANDING_SPAWN_GROUND_CLEARANCE_BLOCKS = 0.08;
    private static final int STANDING_SPAWN_CENTER_CLEARANCE_BLOCKS =
            LOWERED_LIMB_DROP_BLOCKS + 1;

    private MinecraftMachinesDuopodSpawner() {
    }

    static SpawnResult spawn(
            final ServerLevel level,
            final BlockPos centerPos,
            final Direction forwardDirection,
            final UUID batchId
    ) {
        if (forwardDirection.getAxis().isVertical()) {
            return SpawnResult.failure("Duopod forward direction must be horizontal.");
        }
        final boolean lowerIntoStandingContact = hasHoneyTipGroundSupport(level, centerPos, forwardDirection);
        final BlockPos assemblyCenterPos = lowerIntoStandingContact
                ? centerPos.above(ASSEMBLY_GROUND_GAP_BLOCKS)
                : centerPos;
        if (!canPlaceDuopodAt(level, centerPos, forwardDirection)
                || !canPlaceDuopodAt(level, assemblyCenterPos, forwardDirection)) {
            return SpawnResult.failure("Duopod target area is not clear near " + centerPos.toShortString());
        }

        final UUID machineId = UUID.randomUUID();
        final Direction rightDirection = forwardDirection.getClockWise();
        final Direction leftDirection = rightDirection.getOpposite();

        final BlockPos leftServoPos = assemblyCenterPos.relative(leftDirection, 2);
        final BlockPos rightServoPos = assemblyCenterPos.relative(rightDirection, 2);
        final BlockPos leftCogPos = leftServoPos.relative(leftDirection);
        final BlockPos rightCogPos = rightServoPos.relative(rightDirection);
        final BlockPos leftBearingPos = leftCogPos.relative(forwardDirection);
        final BlockPos rightBearingPos = rightCogPos.relative(forwardDirection);
        final BlockPos leftHoneyTipPos = honeyTipPosition(leftBearingPos, leftDirection);
        final BlockPos rightHoneyTipPos = honeyTipPosition(rightBearingPos, rightDirection);

        try (SpawnTransaction transaction = new SpawnTransaction(level, machineId)) {
            transaction.setBlockAndUpdate(leftServoPos, servoState(leftDirection));
            transaction.setBlockAndUpdate(rightServoPos, servoState(rightDirection));

            for (final MachineBlock block : createDuopodBlocks(assemblyCenterPos, forwardDirection)) {
                transaction.setBlockAndUpdate(block.pos(), block.state());
            }
            glueDuopodBase(transaction, assemblyCenterPos, forwardDirection);
            glueDuopodChild(transaction, leftBearingPos, forwardDirection, leftDirection);
            glueDuopodChild(transaction, rightBearingPos, forwardDirection, rightDirection);

            if (blockEntity(level, leftServoPos, RoboticServoJointBlockEntity.class) == null
                    || blockEntity(level, rightServoPos, RoboticServoJointBlockEntity.class) == null) {
                throw new SpawnFailure("Duopod servo block entities did not initialize.");
            }

            final UUID leftChildSubLevelId;
            final UUID rightChildSubLevelId;
            if (blockEntity(level, leftBearingPos, SwivelBearingBlockEntity.class) instanceof final SwivelBearingBlockEntity leftBearing) {
                try {
                    leftBearing.assemble();
                } finally {
                    transaction.trackSubLevel(leftBearing.getSubLevelID());
                }
                if (!leftBearing.isAssembled() || leftBearing.getSubLevelID() == null) {
                    throw new SpawnFailure("Left Duopod swivel bearing failed to assemble its limb.");
                }
                leftChildSubLevelId = leftBearing.getSubLevelID();
            } else {
                throw new SpawnFailure("Left Duopod swivel bearing block entity did not initialize.");
            }

            if (blockEntity(level, rightBearingPos, SwivelBearingBlockEntity.class) instanceof final SwivelBearingBlockEntity rightBearing) {
                try {
                    rightBearing.assemble();
                } finally {
                    transaction.trackSubLevel(rightBearing.getSubLevelID());
                }
                if (!rightBearing.isAssembled() || rightBearing.getSubLevelID() == null) {
                    throw new SpawnFailure("Right Duopod swivel bearing failed to assemble its limb.");
                }
                rightChildSubLevelId = rightBearing.getSubLevelID();
            } else {
                throw new SpawnFailure("Right Duopod swivel bearing block entity did not initialize.");
            }

            final SimAssemblyHelper.AssemblyResult result;
            try {
                result = SimAssemblyHelper.assembleFromSingleBlock(level, assemblyCenterPos, assemblyCenterPos, true, true);
            } catch (final AssemblyException e) {
                throw new SpawnFailure("Duopod base physics assembly failed: " + exceptionMessage(e), e);
            }

            if (result != null && result.subLevel() != null) {
                transaction.trackSubLevel(result.subLevel().getUniqueId());
            }
            if (result == null || !(result.subLevel() instanceof final ServerSubLevel baseSubLevel)) {
                throw new SpawnFailure("Duopod base physics assembly did not produce a Sable sublevel.");
            }
            baseSubLevel.setName("Minecraft Machines Duopod Base");

            final BlockPos movedLeftServoPos = leftServoPos.offset(result.offset());
            final BlockPos movedRightServoPos = rightServoPos.offset(result.offset());
            final RoboticServoJointBlockEntity leftServo = blockEntity(level, movedLeftServoPos, RoboticServoJointBlockEntity.class);
            final RoboticServoJointBlockEntity rightServo = blockEntity(level, movedRightServoPos, RoboticServoJointBlockEntity.class);
            if (leftServo == null || rightServo == null) {
                throw new SpawnFailure("Moved Duopod servo block entities did not initialize.");
            }
            configureTrainingServo(leftServo);
            configureTrainingServo(rightServo);
            final Vector3d leftHoneyTipLocalOffset = honeyTipLocalOffset(level, leftChildSubLevelId, leftHoneyTipPos);
            final Vector3d rightHoneyTipLocalOffset = honeyTipLocalOffset(level, rightChildSubLevelId, rightHoneyTipPos);

            if (lowerIntoStandingContact) {
                try {
                    lowerBodiesIntoStandingContact(
                            level,
                            List.of(baseSubLevel.getUniqueId(), leftChildSubLevelId, rightChildSubLevelId));
                } catch (final RuntimeException e) {
                    throw new SpawnFailure(
                            "Duopod bodies could not enter the contact-ready standing pose: " + exceptionMessage(e),
                            e);
                }
            }

            tagOwnedSubLevel(level, baseSubLevel.getUniqueId(), machineId, batchId, "base");
            tagOwnedSubLevel(level, leftChildSubLevelId, machineId, batchId, "left child");
            tagOwnedSubLevel(level, rightChildSubLevelId, machineId, batchId, "right child");

            final DuopodInstance duopod = new DuopodInstance(
                    machineId,
                    batchId,
                    baseSubLevel.getUniqueId(),
                    leftChildSubLevelId,
                    rightChildSubLevelId,
                    movedLeftServoPos,
                    movedRightServoPos,
                    leftServo.getServoInstanceId(),
                    rightServo.getServoInstanceId(),
                    leftHoneyTipLocalOffset,
                    rightHoneyTipLocalOffset,
                    JOMLConversion.toMojang(baseSubLevel.logicalPose().position()),
                    new Quaterniond(baseSubLevel.logicalPose().orientation()),
                    forwardDirection
            );
            transaction.commit();
            return SpawnResult.success(duopod, "Spawned Duopod at " + centerPos.toShortString());
        } catch (final SpawnFailure e) {
            return SpawnResult.failure(e.getMessage());
        } catch (final RuntimeException e) {
            MinecraftMachines.LOGGER.error(
                    "Unexpected Duopod spawn failure at {} for machine {}",
                    centerPos,
                    machineId,
                    e);
            return SpawnResult.failure("Unexpected Duopod spawn failure: " + exceptionMessage(e));
        }
    }

    private static <T> @Nullable T blockEntity(final ServerLevel level, final BlockPos pos, final Class<T> type) {
        final Object loaded = level.getBlockEntity(pos);
        if (type.isInstance(loaded)) {
            return type.cast(loaded);
        }
        final Object immediate = level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        return type.isInstance(immediate) ? type.cast(immediate) : null;
    }

    static @Nullable BlockPos findDuopodSpawn(final ServerLevel level, final BlockPos origin, final Direction forwardDirection) {
        final int top = Math.min(level.getMaxBuildHeight() - STANDING_SPAWN_CENTER_CLEARANCE_BLOCKS - 1, origin.getY() + 6);
        final int bottom = Math.max(level.getMinBuildHeight(), origin.getY() - 16);
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(origin.getX(), top, origin.getZ());
        for (int y = top; y >= bottom; y--) {
            mutable.setY(y);
            final BlockPos supportPos = mutable.immutable();
            final BlockPos centerPos = supportPos.above(STANDING_SPAWN_CENTER_CLEARANCE_BLOCKS);
            if (!level.getBlockState(supportPos).canBeReplaced()
                    && hasHoneyTipGroundSupport(level, centerPos, forwardDirection)
                    && canPlaceDuopodAt(level, centerPos, forwardDirection)
                    && canPlaceDuopodAt(level, centerPos.above(ASSEMBLY_GROUND_GAP_BLOCKS), forwardDirection)) {
                return centerPos;
            }
        }
        return null;
    }

    static @Nullable BlockPos findDuopodSpawnNear(
            final ServerLevel level,
            final BlockPos origin,
            final Direction forwardDirection,
            final int horizontalRadius
    ) {
        if (horizontalRadius < 0) {
            throw new IllegalArgumentException("horizontalRadius must be non-negative");
        }
        for (int radius = 0; radius <= horizontalRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    final BlockPos candidate = findDuopodSpawn(level, origin.offset(dx, 0, dz), forwardDirection);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    static void removeDuopod(final ServerLevel level, final DuopodInstance duopod) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        removeSubLevel(container, duopod.leftChildSubLevelId());
        removeSubLevel(container, duopod.rightChildSubLevelId());
        removeSubLevel(container, duopod.baseSubLevelId());
        container.processSubLevelRemovals();
        TrainingMachineCollisionRegistry.clearMachine(level, duopod.machineId());
    }

    static @Nullable ServerSubLevel getServerSubLevel(final ServerLevel level, final UUID subLevelId) {
        final SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(subLevelId);
        return subLevel instanceof ServerSubLevel serverSubLevel && !serverSubLevel.isRemoved() ? serverSubLevel : null;
    }

    static @Nullable Vec3 getBodyPosition(final ServerLevel level, final UUID subLevelId) {
        final ServerSubLevel subLevel = getServerSubLevel(level, subLevelId);
        return subLevel == null ? null : JOMLConversion.toMojang(subLevel.logicalPose().position());
    }

    static @Nullable Quaterniond getBodyOrientation(final ServerLevel level, final UUID subLevelId) {
        final ServerSubLevel subLevel = getServerSubLevel(level, subLevelId);
        return subLevel == null ? null : new Quaterniond(subLevel.logicalPose().orientation());
    }

    static Vector3d getLinearVelocity(final ServerLevel level, final UUID subLevelId) {
        final RigidBodyHandle handle = getBodyHandle(level, subLevelId);
        return handle == null || !handle.isValid() ? new Vector3d() : handle.getLinearVelocity(new Vector3d());
    }

    static Vector3d getAngularVelocity(final ServerLevel level, final UUID subLevelId) {
        final RigidBodyHandle handle = getBodyHandle(level, subLevelId);
        return handle == null || !handle.isValid() ? new Vector3d() : handle.getAngularVelocity(new Vector3d());
    }

    static boolean applyLinearAndAngularImpulse(
            final ServerLevel level,
            final UUID subLevelId,
            final Vector3d linearImpulse,
            final Vector3d angularImpulse
    ) {
        final RigidBodyHandle handle = getBodyHandle(level, subLevelId);
        if (handle == null || !handle.isValid()) {
            return false;
        }
        handle.applyLinearAndAngularImpulse(linearImpulse, angularImpulse);
        return true;
    }

    static boolean addLinearAndAngularVelocity(
            final ServerLevel level,
            final UUID subLevelId,
            final Vector3d linearVelocity,
            final Vector3d angularVelocity
    ) {
        final RigidBodyHandle handle = getBodyHandle(level, subLevelId);
        if (handle == null || !handle.isValid()) {
            return false;
        }
        handle.addLinearAndAngularVelocity(linearVelocity, angularVelocity);
        return true;
    }

    private static @Nullable RigidBodyHandle getBodyHandle(final ServerLevel level, final UUID subLevelId) {
        final ServerSubLevel subLevel = getServerSubLevel(level, subLevelId);
        if (subLevel == null) {
            return null;
        }
        final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
        return physicsSystem == null ? null : physicsSystem.getPhysicsHandle(subLevel);
    }

    private static void lowerBodiesIntoStandingContact(final ServerLevel level, final List<UUID> subLevelIds) {
        final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
        if (physicsSystem == null) {
            throw new IllegalStateException("physics system is unavailable");
        }
        final List<ServerSubLevel> bodies = new ArrayList<>(subLevelIds.size());
        final List<RigidBodyHandle> handles = new ArrayList<>(subLevelIds.size());
        final List<Vector3d> standingPositions = new ArrayList<>(subLevelIds.size());
        final List<Quaterniond> standingOrientations = new ArrayList<>(subLevelIds.size());
        for (final UUID subLevelId : subLevelIds) {
            final ServerSubLevel body = getServerSubLevel(level, subLevelId);
            final RigidBodyHandle handle = getBodyHandle(level, subLevelId);
            if (body == null || handle == null || !handle.isValid()) {
                throw new IllegalStateException("missing physics body " + subLevelId);
            }
            bodies.add(body);
            handles.add(handle);
            standingPositions.add(new Vector3d(body.logicalPose().position())
                    .sub(0.0, ASSEMBLY_GROUND_GAP_BLOCKS - STANDING_SPAWN_GROUND_CLEARANCE_BLOCKS, 0.0));
            standingOrientations.add(new Quaterniond(body.logicalPose().orientation()));
        }

        // Snapshot and move every constrained body before touching solver state. Translating and
        // resetting one body at a time briefly stretches the live joints and can inject constraint
        // energy whose size depends on sublevel iteration order.
        for (int index = 0; index < bodies.size(); index++) {
            handles.get(index).teleport(standingPositions.get(index), standingOrientations.get(index));
        }
        for (final ServerSubLevel body : bodies) {
            physicsSystem.getPipeline().resetVelocity(body);
        }
        for (final ServerSubLevel body : bodies) {
            body.updateLastPose();
        }
    }

    private static void configureTrainingServo(final RoboticServoJointBlockEntity servo) {
        servo.setEnabled(true);
        servo.setAngleLimitsDegrees(-SERVO_LIMIT_DEGREES, SERVO_LIMIT_DEGREES);
        servo.setMaxAngularSpeedDegreesPerSecond(SERVO_MAX_SPEED_DEG_PER_SECOND);
        servo.setServoGains(SERVO_STIFFNESS, SERVO_DAMPING);
        servo.setMaxTorque(SERVO_MAX_TORQUE);
        servo.setTargetAngleDegrees(0.0);
    }

    private static BlockPos honeyTipPosition(
            final BlockPos bearingPos,
            final Direction outwardDirection
    ) {
        return bearingPos.relative(outwardDirection).below(LOWERED_LIMB_DROP_BLOCKS);
    }

    private static Vector3d honeyTipLocalOffset(
            final ServerLevel level,
            final UUID childSubLevelId,
            final BlockPos honeyTipPos
    ) {
        final ServerSubLevel childSubLevel = getServerSubLevel(level, childSubLevelId);
        if (childSubLevel == null) {
            throw new SpawnFailure("Expected Duopod child sublevel " + childSubLevelId
                    + " disappeared while calculating its honey-tip offset.");
        }
        final Vector3d offset = new Vector3d(
                honeyTipPos.getX() + 0.5,
                honeyTipPos.getY() + 0.5,
                honeyTipPos.getZ() + 0.5);
        offset.sub(childSubLevel.logicalPose().position());
        new Quaterniond(childSubLevel.logicalPose().orientation()).invert().transform(offset);
        return offset;
    }

    private static void tagOwnedSubLevel(
            final ServerLevel level,
            final UUID subLevelId,
            final UUID machineId,
            final UUID batchId,
            final String role
    ) {
        final ServerSubLevel subLevel = getServerSubLevel(level, subLevelId);
        if (subLevel == null) {
            throw new SpawnFailure("Expected Duopod " + role + " sublevel " + subLevelId
                    + " disappeared before ownership tagging.");
        }
        TrainingMachineCollisionRegistry.register(subLevel, machineId, batchId, DuopodInstance.MORPHOLOGY_TYPE);
    }

    private static void removeSubLevel(final ServerSubLevelContainer container, final UUID subLevelId) {
        final SubLevel subLevel = container.getSubLevel(subLevelId);
        if (subLevel != null && !subLevel.isRemoved()) {
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
    }

    private static boolean canPlaceDuopodAt(final ServerLevel level, final BlockPos centerPos, final Direction forwardDirection) {
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Direction right = forwardDirection.getClockWise();
        final Direction left = right.getOpposite();
        blocks.put(centerPos.relative(left, 2), MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get().defaultBlockState());
        blocks.put(centerPos.relative(right, 2), MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get().defaultBlockState());
        for (final MachineBlock block : createDuopodBlocks(centerPos, forwardDirection)) {
            blocks.put(block.pos(), block.state());
        }
        for (final BlockPos pos : blocks.keySet()) {
            if (!level.getBlockState(pos).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasHoneyTipGroundSupport(
            final ServerLevel level,
            final BlockPos centerPos,
            final Direction forwardDirection
    ) {
        final Direction right = forwardDirection.getClockWise();
        final Direction left = right.getOpposite();
        final BlockPos leftBearingPos = centerPos.relative(left, 3).relative(forwardDirection);
        final BlockPos rightBearingPos = centerPos.relative(right, 3).relative(forwardDirection);
        final BlockPos leftSupport = honeyTipPosition(leftBearingPos, left).below();
        final BlockPos rightSupport = honeyTipPosition(rightBearingPos, right).below();
        return !level.getBlockState(leftSupport).canBeReplaced()
                && !level.getBlockState(rightSupport).canBeReplaced();
    }

    private static List<MachineBlock> createDuopodBlocks(final BlockPos centerPos, final Direction forwardDirection) {
        final Direction right = forwardDirection.getClockWise();
        final Direction left = right.getOpposite();
        final Direction back = forwardDirection.getOpposite();
        final BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
        final List<MachineBlock> blocks = new ArrayList<>();

        blocks.add(new MachineBlock(centerPos, iron));
        blocks.add(new MachineBlock(centerPos.relative(left), iron));
        blocks.add(new MachineBlock(centerPos.relative(right), iron));
        blocks.add(new MachineBlock(centerPos.relative(back), iron));
        blocks.add(new MachineBlock(centerPos.relative(back).relative(left), iron));
        blocks.add(new MachineBlock(centerPos.relative(back).relative(right), iron));

        addSideBlocks(blocks, centerPos.relative(left, 2), forwardDirection, left);
        addSideBlocks(blocks, centerPos.relative(right, 2), forwardDirection, right);
        return blocks;
    }

    private static void addSideBlocks(final List<MachineBlock> blocks, final BlockPos servoPos, final Direction forwardDirection, final Direction outwardDirection) {
        final BlockPos cogPos = servoPos.relative(outwardDirection);
        final BlockPos bearingPos = cogPos.relative(forwardDirection);
        final BlockPos childNear = bearingPos.relative(outwardDirection);
        final BlockPos childMid = childNear.below();
        final BlockPos childFar = childNear.below(2);
        final BlockPos childTip = childNear.below(LOWERED_LIMB_DROP_BLOCKS);

        blocks.add(new MachineBlock(cogPos, AllBlocks.COGWHEEL.getDefaultState().setValue(CogWheelBlock.AXIS, outwardDirection.getAxis())));
        blocks.add(new MachineBlock(bearingPos, SimBlocks.SWIVEL_BEARING.getDefaultState()
                .setValue(SwivelBearingBlock.FACING, outwardDirection)
                .setValue(SwivelBearingBlock.ASSEMBLED, false)
                .setValue(SwivelBearingBlock.POWERED, false)));
        blocks.add(new MachineBlock(childNear, Blocks.IRON_BLOCK.defaultBlockState()));
        blocks.add(new MachineBlock(childMid, Blocks.IRON_BLOCK.defaultBlockState()));
        blocks.add(new MachineBlock(childFar, Blocks.IRON_BLOCK.defaultBlockState()));
        blocks.add(new MachineBlock(childTip, Blocks.HONEY_BLOCK.defaultBlockState()));
    }

    private static void glueDuopodBase(
            final SpawnTransaction transaction,
            final BlockPos centerPos,
            final Direction forwardDirection
    ) {
        final Direction right = forwardDirection.getClockWise();
        final Direction left = right.getOpposite();
        final Direction back = forwardDirection.getOpposite();
        final BlockPos leftServoPos = centerPos.relative(left, 2);
        final BlockPos rightServoPos = centerPos.relative(right, 2);

        transaction.addGlue(centerPos, centerPos.relative(left));
        transaction.addGlue(centerPos, centerPos.relative(right));
        transaction.addGlue(centerPos, centerPos.relative(back));
        transaction.addGlue(centerPos.relative(left), leftServoPos);
        transaction.addGlue(centerPos.relative(right), rightServoPos);
        transaction.addGlue(centerPos.relative(back), centerPos.relative(back).relative(left));
        transaction.addGlue(centerPos.relative(back), centerPos.relative(back).relative(right));
        glueSideBase(transaction, leftServoPos, forwardDirection, left);
        glueSideBase(transaction, rightServoPos, forwardDirection, right);
    }

    private static void glueSideBase(
            final SpawnTransaction transaction,
            final BlockPos servoPos,
            final Direction forwardDirection,
            final Direction outwardDirection
    ) {
        final BlockPos cogPos = servoPos.relative(outwardDirection);
        final BlockPos bearingPos = cogPos.relative(forwardDirection);
        transaction.addGlue(servoPos, cogPos);
        transaction.addGlue(cogPos, bearingPos);
    }

    private static void glueDuopodChild(
            final SpawnTransaction transaction,
            final BlockPos bearingPos,
            final Direction forwardDirection,
            final Direction outwardDirection
    ) {
        final BlockPos childNear = bearingPos.relative(outwardDirection);
        final BlockPos childMid = childNear.below();
        final BlockPos childFar = childNear.below(2);
        final BlockPos childTip = childNear.below(LOWERED_LIMB_DROP_BLOCKS);
        transaction.addGlue(childNear, childMid);
        transaction.addGlue(childMid, childFar);
        transaction.addGlue(childFar, childTip);
    }

    private static BlockState servoState(final Direction facing) {
        return MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get()
                .defaultBlockState()
                .setValue(RoboticServoJointBlock.FACING, facing)
                .setValue(RoboticServoJointBlock.ASSEMBLED, false);
    }

    private static String exceptionMessage(final Throwable throwable) {
        final String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    /**
     * Tracks every world mutation performed while constructing one Duopod. Rollback removes only
     * the sublevels returned by this transaction's successful assembly steps. A level-wide UUID
     * diff is unsafe because another machine can be assembled while this transaction is active;
     * treating that unrelated body as rollback state would invalidate a live training slot.
     */
    private static final class SpawnTransaction implements AutoCloseable {
        private final ServerLevel level;
        private final UUID machineId;
        private final ServerSubLevelContainer subLevelContainer;
        private final Set<UUID> createdSubLevelIds = new LinkedHashSet<>();
        private final Map<BlockPos, BlockState> originalBlockStates = new LinkedHashMap<>();
        private final List<SuperGlueEntity> glueEntities = new ArrayList<>();
        private boolean committed;
        private boolean rolledBack;

        private SpawnTransaction(final ServerLevel level, final UUID machineId) {
            this.level = level;
            this.machineId = machineId;
            subLevelContainer = SubLevelContainer.getContainer(level);
        }

        private void trackSubLevel(final @Nullable UUID subLevelId) {
            if (subLevelId != null) {
                createdSubLevelIds.add(subLevelId);
            }
        }

        private void setBlockAndUpdate(final BlockPos pos, final BlockState state) {
            final BlockPos immutablePos = pos.immutable();
            originalBlockStates.putIfAbsent(immutablePos, level.getBlockState(immutablePos));
            if (!level.setBlockAndUpdate(immutablePos, state)) {
                throw new SpawnFailure("Could not place a Duopod block at " + immutablePos.toShortString() + '.');
            }
        }

        private void addGlue(final BlockPos first, final BlockPos second) {
            final SuperGlueEntity glue = new SuperGlueEntity(level, SuperGlueEntity.span(first, second));
            glueEntities.add(glue);
            if (!level.addFreshEntity(glue)) {
                throw new SpawnFailure("Could not place Duopod glue between " + first.toShortString()
                        + " and " + second.toShortString() + '.');
            }
        }

        private void commit() {
            committed = true;
        }

        @Override
        public void close() {
            if (!committed) {
                rollback();
            }
        }

        private void rollback() {
            if (rolledBack) {
                return;
            }
            rolledBack = true;

            for (final SuperGlueEntity glue : glueEntities) {
                try {
                    glue.discard();
                } catch (final RuntimeException e) {
                    MinecraftMachines.LOGGER.error(
                            "Failed to discard Duopod glue while rolling back machine {}",
                            machineId,
                            e);
                }
            }

            // Strip ownership metadata while every partially tagged sublevel is still
            // discoverable. If Sable later rejects a removal request, it must not leave a
            // surviving body marked as part of a live training batch.
            try {
                TrainingMachineCollisionRegistry.clearMachine(level, machineId);
            } catch (final RuntimeException e) {
                MinecraftMachines.LOGGER.error(
                        "Failed to clear collision ownership while rolling back Duopod machine {}",
                        machineId,
                        e);
            }

            try {
                for (final UUID subLevelId : createdSubLevelIds) {
                    try {
                        removeSubLevel(subLevelContainer, subLevelId);
                    } catch (final RuntimeException e) {
                        MinecraftMachines.LOGGER.error(
                                "Failed to queue Duopod sublevel {} for rollback of machine {}",
                                subLevelId,
                                machineId,
                                e);
                    }
                }
                subLevelContainer.processSubLevelRemovals();
            } catch (final RuntimeException e) {
                MinecraftMachines.LOGGER.error(
                        "Failed to enumerate or process Duopod sublevels while rolling back machine {}",
                        machineId,
                        e);
            }

            final List<Map.Entry<BlockPos, BlockState>> originalBlocks =
                    new ArrayList<>(originalBlockStates.entrySet());
            for (int index = originalBlocks.size() - 1; index >= 0; index--) {
                final Map.Entry<BlockPos, BlockState> originalBlock = originalBlocks.get(index);
                try {
                    if (!level.setBlockAndUpdate(originalBlock.getKey(), originalBlock.getValue())
                            && !level.getBlockState(originalBlock.getKey()).equals(originalBlock.getValue())) {
                        MinecraftMachines.LOGGER.error(
                                "Could not restore block {} while rolling back Duopod machine {}",
                                originalBlock.getKey(),
                                machineId);
                    }
                } catch (final RuntimeException e) {
                    MinecraftMachines.LOGGER.error(
                            "Failed to restore block {} while rolling back Duopod machine {}",
                            originalBlock.getKey(),
                            machineId,
                            e);
                }
            }
        }

    }

    private static final class SpawnFailure extends RuntimeException {
        private SpawnFailure(final String message) {
            super(message);
        }

        private SpawnFailure(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    record SpawnResult(boolean success, String message, @Nullable DuopodInstance duopod) {
        static SpawnResult success(final DuopodInstance duopod, final String message) {
            return new SpawnResult(true, message, duopod);
        }

        static SpawnResult failure(final String message) {
            return new SpawnResult(false, message, null);
        }
    }

    private record MachineBlock(BlockPos pos, BlockState state) {
    }
}
