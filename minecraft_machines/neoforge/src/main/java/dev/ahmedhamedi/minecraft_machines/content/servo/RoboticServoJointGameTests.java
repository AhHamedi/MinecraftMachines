package dev.ahmedhamedi.minecraft_machines.content.servo;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.worm.WormCollisionCallback;
import dev.ahmedhamedi.minecraft_machines.content.worm.WormCollisionTags;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

@GameTestHolder(MinecraftMachines.MOD_ID)
@PrefixGameTestTemplate(false)
public class RoboticServoJointGameTests {
    private static final BlockPos SERVO_POS = new BlockPos(2, 2, 2);
    private static final BlockPos FRONT_POS = SERVO_POS.relative(Direction.EAST);

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void servoExposesSingleOutputShaft(final GameTestHelper helper) {
        final BlockState state = servoState(Direction.EAST);
        helper.setBlock(SERVO_POS, state);

        final RoboticServoJointBlock block = MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get();
        if (block.getRotationAxis(state) != Direction.Axis.X) {
            throw new GameTestAssertException("Expected east-facing servo to rotate on the X axis");
        }
        if (!block.hasShaftTowards(helper.getLevel(), helper.absolutePos(SERVO_POS), state, Direction.EAST)) {
            throw new GameTestAssertException("Expected servo to expose a shaft on its output face");
        }
        if (block.hasShaftTowards(helper.getLevel(), helper.absolutePos(SERVO_POS), state, Direction.WEST)) {
            throw new GameTestAssertException("Expected servo to behave like a creative motor, not a pass-through shaft");
        }
        if (block.hasShaftTowards(helper.getLevel(), helper.absolutePos(SERVO_POS), state, Direction.NORTH)) {
            throw new GameTestAssertException("Expected servo not to expose off-axis shaft connections");
        }

        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void servoGeneratesControlledRotationToTarget(final GameTestHelper helper) {
        helper.setBlock(SERVO_POS, servoState(Direction.UP));

        final RoboticServoJointBlockEntity servo = helper.getBlockEntity(SERVO_POS);
        if (!(servo instanceof GeneratingKineticBlockEntity)) {
            throw new GameTestAssertException("Expected servo block entity to generate Create kinetics");
        }
        servo.setAngleLimitsDegrees(-180.0, 180.0);
        servo.setMaxAngularSpeedDegreesPerSecond(360.0);
        servo.setTargetAngleDegrees(90.0);

        helper.startSequence()
                .thenExecuteAfter(3, () -> {
                    if (Math.abs(servo.getGeneratedSpeed()) < 0.001f) {
                        throw new GameTestAssertException("Expected servo to generate shaft speed while moving toward target");
                    }
                    if (servo.getActualAngleDegrees() <= 0.0) {
                        throw new GameTestAssertException("Expected servo telemetry angle to advance toward target");
                    }
                })
                .thenExecuteAfter(20, () -> {
                    if (Math.abs(servo.getActualAngleDegrees() - 90.0) > 0.5) {
                        throw new GameTestAssertException("Expected servo to settle at 90 degrees, got %.2f".formatted(servo.getActualAngleDegrees()));
                    }
                    if (Math.abs(servo.getGeneratedSpeed()) > 0.001f) {
                        throw new GameTestAssertException("Expected servo to stop generating speed at the target angle");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void servoKeepsGeneratingWhenMovedIntoPhysicsSublevel(final GameTestHelper helper) {
        helper.setBlock(SERVO_POS, servoState(Direction.EAST));
        helper.setBlock(FRONT_POS, AllBlocks.SHAFT.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));

        final BlockPos absoluteServoPos = helper.absolutePos(SERVO_POS);
        final BlockPos absoluteFrontPos = helper.absolutePos(FRONT_POS);
        helper.getLevel().addFreshEntity(new SuperGlueEntity(helper.getLevel(), SuperGlueEntity.span(absoluteServoPos, absoluteFrontPos)));

        final BlockPos[] movedServoPos = new BlockPos[1];
        final BlockPos[] movedShaftPos = new BlockPos[1];
        final ServerSubLevel[] assembledBody = new ServerSubLevel[1];

        helper.startSequence()
                .thenExecute(() -> {
                    final SimAssemblyHelper.AssemblyResult result;
                    try {
                        result = SimAssemblyHelper.assembleFromSingleBlock(helper.getLevel(), absoluteServoPos, absoluteServoPos, true, true);
                    } catch (final AssemblyException e) {
                        throw new GameTestAssertException("Expected servo to assemble into a physics sublevel: " + e.getMessage());
                    }

                    if (result == null) {
                        throw new GameTestAssertException("Expected servo assembly to create a Sable sublevel");
                    }
                    if (!(result.subLevel() instanceof final ServerSubLevel subLevel)) {
                        throw new GameTestAssertException("Expected servo assembly to create a server Sable sublevel");
                    }

                    assembledBody[0] = subLevel;
                    movedServoPos[0] = absoluteServoPos.offset(result.offset());
                    movedShaftPos[0] = absoluteFrontPos.offset(result.offset());
                })
                .thenExecuteAfter(20, () -> {
                    final RoboticServoJointBlockEntity servo = helper.getLevel().getBlockEntity(movedServoPos[0]) instanceof final RoboticServoJointBlockEntity be
                            ? be
                            : null;
                    if (servo == null) {
                        throw new GameTestAssertException("Expected moved servo block entity at " + movedServoPos[0].toShortString());
                    }
                    if (Sable.HELPER.getContaining(servo) == null) {
                        throw new GameTestAssertException("Expected moved servo to be inside a Sable sublevel");
                    }

                    servo.setAngleLimitsDegrees(-180.0, 180.0);
                    servo.setMaxAngularSpeedDegreesPerSecond(360.0);
                    servo.setTargetAngleDegrees(180.0);
                })
                .thenExecuteAfter(5, () -> {
                    try {
                        final RoboticServoJointBlockEntity servo = (RoboticServoJointBlockEntity) helper.getLevel().getBlockEntity(movedServoPos[0]);
                        if (Math.abs(servo.getGeneratedSpeed()) < 0.001f) {
                            throw new GameTestAssertException("Expected moved physics servo to keep generating shaft speed");
                        }
                        if (!(helper.getLevel().getBlockEntity(movedShaftPos[0]) instanceof final KineticBlockEntity shaft) || Math.abs(shaft.getSpeed()) < 0.001f) {
                            throw new GameTestAssertException("Expected moved shaft to receive speed from the moved servo");
                        }
                    } finally {
                        removeSubLevelsParentFirst(helper, assembledBody);
                    }
                })
                .thenExecuteAfter(1, () -> assertSubLevelsRemoved(helper, assembledBody))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void servoDrivesSwivelBearingThroughSideCog(final GameTestHelper helper) {
        final Direction bearingFacing = Direction.EAST;
        final Direction cogSide = Direction.SOUTH;
        final BlockPos servoPos = SERVO_POS;
        final BlockPos cogPos = servoPos.relative(bearingFacing);
        final BlockPos bearingPos = cogPos.relative(cogSide);
        final BlockPos childPos = bearingPos.relative(bearingFacing);

        helper.setBlock(servoPos, servoState(bearingFacing));
        helper.setBlock(cogPos, AllBlocks.COGWHEEL.getDefaultState().setValue(CogWheelBlock.AXIS, bearingFacing.getAxis()));
        helper.setBlock(bearingPos, SimBlocks.SWIVEL_BEARING.getDefaultState()
                .setValue(SwivelBearingBlock.FACING, bearingFacing)
                .setValue(SwivelBearingBlock.ASSEMBLED, false)
                .setValue(SwivelBearingBlock.POWERED, false));
        helper.setBlock(childPos, Blocks.IRON_BLOCK.defaultBlockState());

        final RoboticServoJointBlockEntity servo = helper.getBlockEntity(servoPos);
        final SwivelBearingBlockEntity bearing = helper.getBlockEntity(bearingPos);
        final ServerSubLevel[] childBody = new ServerSubLevel[1];
        servo.setAngleLimitsDegrees(-180.0, 180.0);
        servo.setMaxAngularSpeedDegreesPerSecond(180.0);
        servo.setTargetAngleDegrees(180.0);
        bearing.assembleNextTick = true;

        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    if (Math.abs(servo.getGeneratedSpeed()) < 0.001f) {
                        throw new GameTestAssertException("Expected servo to generate speed into the worm cog");
                    }
                    if (Math.abs(bearing.getExtraKinetics().getSpeed()) < 0.001f) {
                        throw new GameTestAssertException("Expected swivel bearing side cog to receive speed from the servo-driven cog");
                    }
                })
                .thenExecuteAfter(20, () -> {
                    try {
                        if (!bearing.isAssembled() || bearing.getSubLevelID() == null || bearing.getPlatePos() == null) {
                            throw new GameTestAssertException("Expected side-driven swivel bearing to assemble the front iron as a physics object");
                        }
                        childBody[0] = getServerSubLevel(helper, bearing.getSubLevelID(), "side-driven swivel child");
                    } finally {
                        // Detach the world-space parent before removing its child body so the
                        // bearing cannot retry a constraint against a now-empty plot slot.
                        bearing.disassemble();
                        removeSubLevelsParentFirst(helper, childBody);
                    }
                })
                .thenExecuteAfter(1, () -> assertSubLevelsRemoved(helper, childBody))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void wormStartsWithBaseAndBearingChildAsSeparatePhysicsBodies(final GameTestHelper helper) {
        final Direction rowDirection = Direction.EAST;
        final Direction columnDirection = Direction.SOUTH;
        final Direction leftDirection = columnDirection.getOpposite();
        final BlockPos servoPos = SERVO_POS;
        final BlockPos cogPos = servoPos.relative(rowDirection);
        final BlockPos bearingPos = cogPos.relative(columnDirection);
        final BlockPos childNear = bearingPos.relative(rowDirection);
        final BlockPos childMid = childNear.relative(columnDirection);
        final BlockPos childFar = childNear.relative(columnDirection, 2);

        helper.setBlock(servoPos.relative(leftDirection, 3), Blocks.IRON_BLOCK.defaultBlockState());
        helper.setBlock(servoPos.relative(leftDirection, 2), Blocks.IRON_BLOCK.defaultBlockState());
        helper.setBlock(servoPos.relative(leftDirection), Blocks.IRON_BLOCK.defaultBlockState());
        helper.setBlock(servoPos, servoState(rowDirection));
        helper.setBlock(cogPos, AllBlocks.COGWHEEL.getDefaultState().setValue(CogWheelBlock.AXIS, rowDirection.getAxis()));
        helper.setBlock(bearingPos, SimBlocks.SWIVEL_BEARING.getDefaultState()
                .setValue(SwivelBearingBlock.FACING, rowDirection)
                .setValue(SwivelBearingBlock.ASSEMBLED, false)
                .setValue(SwivelBearingBlock.POWERED, false));
        helper.setBlock(childNear, Blocks.IRON_BLOCK.defaultBlockState());
        helper.setBlock(childMid, Blocks.IRON_BLOCK.defaultBlockState());
        helper.setBlock(childFar, Blocks.IRON_BLOCK.defaultBlockState());

        addGlue(helper, servoPos.relative(leftDirection, 3), servoPos.relative(leftDirection, 2));
        addGlue(helper, servoPos.relative(leftDirection, 2), servoPos.relative(leftDirection));
        addGlue(helper, servoPos.relative(leftDirection), servoPos);
        addGlue(helper, servoPos, cogPos);
        addGlue(helper, cogPos, bearingPos);
        addGlue(helper, childNear, childMid);
        addGlue(helper, childMid, childFar);

        final BlockPos absoluteServoPos = helper.absolutePos(servoPos);
        final BlockPos absoluteBearingPos = helper.absolutePos(bearingPos);
        final BlockPos[] movedServoPos = new BlockPos[1];
        final BlockPos[] movedBearingPos = new BlockPos[1];
        final ServerSubLevel[] nestedBodiesParentFirst = new ServerSubLevel[2];

        helper.startSequence()
                .thenExecute(() -> {
                    final SwivelBearingBlockEntity bearing = helper.getBlockEntity(bearingPos);
                    bearing.assemble();
                    if (!bearing.isAssembled() || bearing.getSubLevelID() == null) {
                        throw new GameTestAssertException("Expected worm bearing child row to assemble first");
                    }
                    nestedBodiesParentFirst[1] = getServerSubLevel(helper, bearing.getSubLevelID(), "worm bearing child");

                    final SimAssemblyHelper.AssemblyResult result;
                    try {
                        result = SimAssemblyHelper.assembleFromSingleBlock(helper.getLevel(), absoluteServoPos, absoluteServoPos, true, true);
                    } catch (final AssemblyException e) {
                        throw new GameTestAssertException("Expected worm base to assemble into physics: " + e.getMessage());
                    }
                    if (result == null) {
                        throw new GameTestAssertException("Expected worm base assembly to create a Sable sublevel");
                    }
                    if (!(result.subLevel() instanceof final ServerSubLevel baseBody)) {
                        throw new GameTestAssertException("Expected worm base assembly to create a server Sable sublevel");
                    }

                    nestedBodiesParentFirst[0] = baseBody;
                    movedServoPos[0] = absoluteServoPos.offset(result.offset());
                    movedBearingPos[0] = absoluteBearingPos.offset(result.offset());
                })
                .thenExecuteAfter(20, () -> {
                    try {
                        final RoboticServoJointBlockEntity servo = helper.getLevel().getBlockEntity(movedServoPos[0]) instanceof final RoboticServoJointBlockEntity be
                                ? be
                                : null;
                        final SwivelBearingBlockEntity bearing = helper.getLevel().getBlockEntity(movedBearingPos[0]) instanceof final SwivelBearingBlockEntity be
                                ? be
                                : null;
                        if (servo == null || bearing == null) {
                            throw new GameTestAssertException("Expected moved worm servo and bearing block entities to exist");
                        }

                        final var baseBody = Sable.HELPER.getContaining(servo);
                        if (baseBody == null) {
                            throw new GameTestAssertException("Expected worm base to start as a Sable physics body");
                        }
                        if (Sable.HELPER.getContaining(bearing) != baseBody) {
                            throw new GameTestAssertException("Expected worm swivel bearing to be part of the base physics body");
                        }
                        if (!bearing.isAssembled() || bearing.getSubLevelID() == null || bearing.getPlatePos() == null) {
                            throw new GameTestAssertException("Expected worm bearing child to remain assembled");
                        }

                        final var childBody = SubLevelContainer.getContainer(helper.getLevel()).getSubLevel(bearing.getSubLevelID());
                        if (childBody == null || childBody == baseBody) {
                            throw new GameTestAssertException("Expected worm bearing child to be a separate physics body");
                        }
                    } finally {
                        // The base owns the ticking bearing. Unload it before the referenced child
                        // so no intermediate tick can observe a plot-grid anchor with a null body.
                        removeSubLevelsParentFirst(helper, nestedBodiesParentFirst);
                    }
                })
                .thenExecuteAfter(1, () -> assertSubLevelsRemoved(helper, nestedBodiesParentFirst))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void wormCollisionFilterRemovesOnlyWormPairs(final GameTestHelper helper) {
        final BlockPos firstPos = new BlockPos(2, 2, 2);
        final BlockPos secondPos = new BlockPos(5, 2, 2);
        final BlockPos nonWormPos = new BlockPos(8, 2, 2);
        final ServerSubLevel[] assembledBodies = new ServerSubLevel[3];

        helper.startSequence()
                .thenExecute(() -> {
                    final PhysicsBlock first = assembleIronSubLevel(helper, firstPos, true);
                    final PhysicsBlock second = assembleIronSubLevel(helper, secondPos, true);
                    final PhysicsBlock nonWorm = assembleIronSubLevel(helper, nonWormPos, false);
                    assembledBodies[0] = first.subLevel();
                    assembledBodies[1] = second.subLevel();
                    assembledBodies[2] = nonWorm.subLevel();
                    final BlockSubLevelCollisionCallback callback = WormCollisionCallback.wrap(null);

                    final SubLevelPhysicsSystem previous = SubLevelPhysicsSystem.currentlySteppingSystem;
                    SubLevelPhysicsSystem.currentlySteppingSystem = SubLevelContainer.getContainer(helper.getLevel()).physicsSystem();
                    try {
                        if (!callback.sable$onCollision(first.plotPos(), second.plotPos(), new Vector3d(), 1.0).removeCollision()) {
                            throw new GameTestAssertException("Expected worm-vs-worm sublevel contacts to be removed");
                        }
                        if (callback.sable$onCollision(first.plotPos(), null, new Vector3d(), 1.0).removeCollision()) {
                            throw new GameTestAssertException("Expected worm-vs-world contacts to keep normal collision");
                        }
                        if (callback.sable$onCollision(first.plotPos(), nonWorm.plotPos(), new Vector3d(), 1.0).removeCollision()) {
                            throw new GameTestAssertException("Expected worm-vs-non-worm physics contacts to keep normal collision");
                        }
                    } finally {
                        SubLevelPhysicsSystem.currentlySteppingSystem = previous;
                        removeSubLevelsParentFirst(helper, assembledBodies);
                    }
                })
                .thenExecuteAfter(1, () -> assertSubLevelsRemoved(helper, assembledBodies))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void servoDoesNotAutoAssembleFrontBlock(final GameTestHelper helper) {
        helper.setBlock(SERVO_POS, servoState(Direction.EAST));
        helper.setBlock(FRONT_POS, Blocks.IRON_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(40, () -> {
                    final RoboticServoJointBlockEntity servo = helper.getBlockEntity(SERVO_POS);
                    if (servo.isAssembled()) {
                        throw new GameTestAssertException("Expected servo to stay unassembled without swivel-bearing-style activation");
                    }
                    if (servo.getAttachedSubLevelId() != null || servo.getInternalLinkPos() != null) {
                        throw new GameTestAssertException("Expected servo not to create an internal Sable link for the front block");
                    }
                    if (!helper.getLevel().getBlockState(helper.absolutePos(FRONT_POS)).is(Blocks.IRON_BLOCK)) {
                        throw new GameTestAssertException("Expected front block to remain a normal world block");
                    }
                })
                .thenSucceed();
    }

    private static BlockState servoState(final Direction facing) {
        return MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get()
                .defaultBlockState()
                .setValue(RoboticServoJointBlock.FACING, facing)
                .setValue(RoboticServoJointBlock.ASSEMBLED, false);
    }

    private static void addGlue(final GameTestHelper helper, final BlockPos first, final BlockPos second) {
        helper.getLevel().addFreshEntity(new SuperGlueEntity(helper.getLevel(), SuperGlueEntity.span(helper.absolutePos(first), helper.absolutePos(second))));
    }

    private static PhysicsBlock assembleIronSubLevel(final GameTestHelper helper, final BlockPos localPos, final boolean worm) {
        helper.setBlock(localPos, Blocks.IRON_BLOCK.defaultBlockState());
        final BlockPos absolutePos = helper.absolutePos(localPos);

        final SimAssemblyHelper.AssemblyResult result;
        try {
            result = SimAssemblyHelper.assembleFromSingleBlock(helper.getLevel(), absolutePos, absolutePos, true, true);
        } catch (final AssemblyException e) {
            throw new GameTestAssertException("Expected iron block to assemble into a Sable sublevel: " + e.getMessage());
        }
        if (result == null || !(result.subLevel() instanceof final ServerSubLevel subLevel)) {
            throw new GameTestAssertException("Expected iron block assembly to create a server Sable sublevel");
        }
        if (worm) {
            WormCollisionTags.markWormSubLevel(subLevel);
        }
        return new PhysicsBlock(subLevel, absolutePos.offset(result.offset()));
    }

    private static ServerSubLevel getServerSubLevel(
            final GameTestHelper helper,
            final java.util.UUID subLevelId,
            final String description
    ) {
        final var subLevel = SubLevelContainer.getContainer(helper.getLevel()).getSubLevel(subLevelId);
        if (!(subLevel instanceof final ServerSubLevel serverSubLevel) || serverSubLevel.isRemoved()) {
            throw new GameTestAssertException("Expected " + description + " Sable sublevel to exist");
        }
        return serverSubLevel;
    }

    private static void removeSubLevelsParentFirst(
            final GameTestHelper helper,
            final ServerSubLevel... subLevels
    ) {
        final var container = SubLevelContainer.getContainer(helper.getLevel());
        for (final ServerSubLevel subLevel : subLevels) {
            if (subLevel == null) {
                continue;
            }
            final var current = container.getSubLevel(subLevel.getUniqueId());
            if (current != null && !current.isRemoved()) {
                container.removeSubLevel(current, SubLevelRemovalReason.REMOVED);
            }
        }
        container.processSubLevelRemovals();
    }

    private static void assertSubLevelsRemoved(
            final GameTestHelper helper,
            final ServerSubLevel... subLevels
    ) {
        final var container = SubLevelContainer.getContainer(helper.getLevel());
        for (final ServerSubLevel subLevel : subLevels) {
            if (subLevel != null && container.getSubLevel(subLevel.getUniqueId()) != null) {
                throw new GameTestAssertException("Expected Sable sublevel " + subLevel.getUniqueId() + " to be removed before test success");
            }
        }
    }

    private record PhysicsBlock(ServerSubLevel subLevel, BlockPos plotPos) {
    }
}
