package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlock;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ahmedhamedi.minecraft_machines.content.worm.WormCollisionTags;
import dev.ahmedhamedi.minecraft_machines.content.worm.training.WormInstance;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class MinecraftMachinesWormSpawner {
    private static final double DEMO_MAX_ANGULAR_SPEED_DEG_PER_SECOND = 360.0;

    private MinecraftMachinesWormSpawner() {
    }

    static SpawnResult spawn(
            final ServerLevel level,
            final BlockPos servoPos,
            final Direction rowDirection,
            final Direction columnDirection,
            final boolean startDemo
    ) {
        if (!canPlaceWormAt(level, servoPos, rowDirection, columnDirection)) {
            return SpawnResult.failure("Worm target area is not clear at " + servoPos.toShortString());
        }

        final BlockPos cogPos = servoPos.relative(rowDirection);
        final BlockPos bearingPos = cogPos.relative(columnDirection);
        final List<DemoBlock> blocks = createWormBlocks(servoPos, rowDirection, columnDirection);

        final BlockState servoState = MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get()
                .defaultBlockState()
                .setValue(RoboticServoJointBlock.FACING, rowDirection)
                .setValue(RoboticServoJointBlock.ASSEMBLED, false);
        level.setBlockAndUpdate(servoPos, servoState);

        for (final DemoBlock block : blocks) {
            level.setBlockAndUpdate(block.pos(), block.state());
        }

        glueWormBase(level, servoPos, rowDirection, columnDirection);
        glueWormBearingChild(level, bearingPos, rowDirection, columnDirection);

        if (!(level.getBlockEntity(servoPos) instanceof RoboticServoJointBlockEntity)) {
            return SpawnResult.failure("Worm servo block entity did not initialize at " + servoPos.toShortString());
        }

        final BlockPos currentServoPos;
        final BlockPos currentBearingPos;
        final UUID baseSubLevelId;
        final UUID childSubLevelId;
        if (level.getBlockEntity(bearingPos) instanceof final SwivelBearingBlockEntity bearing) {
            bearing.assemble();
            if (!bearing.isAssembled() || bearing.getSubLevelID() == null) {
                return SpawnResult.failure("Worm swivel bearing failed to assemble its front iron row at " + bearingPos.toShortString());
            }
            tagWormSubLevel(level, bearing.getSubLevelID());
            childSubLevelId = bearing.getSubLevelID();

            final SimAssemblyHelper.AssemblyResult result;
            try {
                result = SimAssemblyHelper.assembleFromSingleBlock(level, servoPos, servoPos, true, true);
            } catch (final AssemblyException e) {
                return SpawnResult.failure("Worm base physics assembly failed: " + e.getMessage());
            }

            if (result == null || !(result.subLevel() instanceof final ServerSubLevel baseSubLevel)) {
                return SpawnResult.failure("Worm base physics assembly did not produce a Sable sublevel.");
            }

            WormCollisionTags.markWormSubLevel(baseSubLevel);
            baseSubLevelId = baseSubLevel.getUniqueId();
            baseSubLevel.setName("Minecraft Machines Worm Base");

            currentServoPos = servoPos.offset(result.offset());
            currentBearingPos = bearingPos.offset(result.offset());
        } else {
            return SpawnResult.failure("Worm swivel bearing block entity did not initialize at " + bearingPos.toShortString());
        }

        final UUID servoInstanceId;
        if (level.getBlockEntity(currentServoPos) instanceof final RoboticServoJointBlockEntity servo) {
            configureServoForDemo(servo);
            servoInstanceId = servo.getServoInstanceId();
        } else {
            return SpawnResult.failure("Moved worm servo block entity did not initialize at " + currentServoPos.toShortString());
        }

        if (!(level.getBlockEntity(currentBearingPos) instanceof final SwivelBearingBlockEntity movedBearing) || !movedBearing.isAssembled()) {
            return SpawnResult.failure("Moved worm swivel bearing did not remain assembled at " + currentBearingPos.toShortString());
        }

        tagWormSubLevel(level, childSubLevelId);
        if (startDemo) {
            MinecraftMachinesServoDemo.start(level, currentServoPos);
        }

        final WormInstance worm = new WormInstance(
                UUID.randomUUID(),
                currentServoPos.immutable(),
                servoInstanceId,
                baseSubLevelId,
                childSubLevelId,
                rowDirection,
                columnDirection
        );
        return SpawnResult.success(worm, "Spawned physics worm at " + currentServoPos.toShortString());
    }

    static @Nullable BlockPos findWormSpawn(final ServerLevel level, final BlockPos origin, final Direction rowDirection, final Direction columnDirection) {
        final int top = Math.min(level.getMaxBuildHeight() - 4, origin.getY() + 6);
        final int bottom = Math.max(level.getMinBuildHeight() + 1, origin.getY() - 16);
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(origin.getX(), top, origin.getZ());
        for (int y = top; y >= bottom; y--) {
            mutable.setY(y);
            final BlockPos supportPos = mutable.immutable();
            final BlockPos servoPos = supportPos.above();
            if (!level.getBlockState(supportPos).canBeReplaced() && canPlaceWormAt(level, servoPos, rowDirection, columnDirection)) {
                return servoPos;
            }
        }
        return null;
    }

    static @Nullable Vec3 getBodyPosition(final ServerLevel level, final UUID subLevelId) {
        final SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(subLevelId);
        if (subLevel == null || subLevel.isRemoved()) {
            return null;
        }
        return JOMLConversion.toMojang(subLevel.logicalPose().position());
    }

    static void removeWorm(final ServerLevel level, final WormInstance worm) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        removeSubLevel(container, worm.childSubLevelId());
        removeSubLevel(container, worm.baseSubLevelId());
        container.processSubLevelRemovals();
    }

    private static void configureServoForDemo(final RoboticServoJointBlockEntity servo) {
        servo.setEnabled(true);
        servo.setAngleLimitsDegrees(-180.0, 180.0);
        servo.setMaxAngularSpeedDegreesPerSecond(DEMO_MAX_ANGULAR_SPEED_DEG_PER_SECOND);
        servo.setTargetAngleDegrees(0.0);
    }

    private static void removeSubLevel(final ServerSubLevelContainer container, final UUID subLevelId) {
        final SubLevel subLevel = container.getSubLevel(subLevelId);
        if (subLevel != null && !subLevel.isRemoved()) {
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
    }

    private static boolean canPlaceWormAt(final ServerLevel level, final BlockPos servoPos, final Direction rowDirection, final Direction columnDirection) {
        if (!level.getBlockState(servoPos).canBeReplaced()) {
            return false;
        }

        for (final DemoBlock block : createWormBlocks(servoPos, rowDirection, columnDirection)) {
            if (!level.getBlockState(block.pos()).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    private static void tagWormSubLevel(final ServerLevel level, final UUID subLevelId) {
        if (subLevelId == null) {
            return;
        }

        final SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(subLevelId);
        if (subLevel instanceof final ServerSubLevel serverSubLevel) {
            WormCollisionTags.markWormSubLevel(serverSubLevel);
        }
    }

    private static List<DemoBlock> createWormBlocks(final BlockPos servoPos, final Direction rowDirection, final Direction columnDirection) {
        final BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
        final List<DemoBlock> blocks = new ArrayList<>();
        final Direction leftDirection = columnDirection.getOpposite();
        final BlockPos cogPos = servoPos.relative(rowDirection);
        final BlockPos bearingPos = cogPos.relative(columnDirection);
        final BlockPos childNear = bearingPos.relative(rowDirection);

        blocks.add(new DemoBlock(servoPos.relative(leftDirection, 3), iron));
        blocks.add(new DemoBlock(servoPos.relative(leftDirection, 2), iron));
        blocks.add(new DemoBlock(servoPos.relative(leftDirection), iron));
        blocks.add(new DemoBlock(cogPos, cogState(rowDirection.getAxis())));
        blocks.add(new DemoBlock(bearingPos, SimBlocks.SWIVEL_BEARING.getDefaultState()
                .setValue(SwivelBearingBlock.FACING, rowDirection)
                .setValue(SwivelBearingBlock.ASSEMBLED, false)
                .setValue(SwivelBearingBlock.POWERED, false)));
        blocks.add(new DemoBlock(childNear, iron));
        blocks.add(new DemoBlock(childNear.relative(columnDirection), iron));
        blocks.add(new DemoBlock(childNear.relative(columnDirection, 2), iron));
        return blocks;
    }

    private static void glueWormBase(final ServerLevel level, final BlockPos servoPos, final Direction rowDirection, final Direction columnDirection) {
        final Direction leftDirection = columnDirection.getOpposite();
        final BlockPos parentFar = servoPos.relative(leftDirection, 3);
        final BlockPos parentMid = servoPos.relative(leftDirection, 2);
        final BlockPos parentNear = servoPos.relative(leftDirection);
        final BlockPos cogPos = servoPos.relative(rowDirection);
        final BlockPos bearingPos = cogPos.relative(columnDirection);

        addGlue(level, parentFar, parentMid);
        addGlue(level, parentMid, parentNear);
        addGlue(level, parentNear, servoPos);
        addGlue(level, servoPos, cogPos);
        addGlue(level, cogPos, bearingPos);
    }

    private static void glueWormBearingChild(final ServerLevel level, final BlockPos bearingPos, final Direction rowDirection, final Direction columnDirection) {
        final BlockPos childNear = bearingPos.relative(rowDirection);
        final BlockPos childMid = childNear.relative(columnDirection);
        final BlockPos childFar = childNear.relative(columnDirection, 2);

        addGlue(level, childNear, childMid);
        addGlue(level, childMid, childFar);
    }

    private static void addGlue(final ServerLevel level, final BlockPos first, final BlockPos second) {
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(first, second)));
    }

    private static BlockState cogState(final Direction.Axis axis) {
        return AllBlocks.COGWHEEL.getDefaultState().setValue(CogWheelBlock.AXIS, axis);
    }

    record SpawnResult(boolean success, String message, @Nullable WormInstance worm) {
        static SpawnResult success(final WormInstance worm, final String message) {
            return new SpawnResult(true, message, worm);
        }

        static SpawnResult failure(final String message) {
            return new SpawnResult(false, message, null);
        }
    }

    private record DemoBlock(BlockPos pos, BlockState state) {
    }
}
