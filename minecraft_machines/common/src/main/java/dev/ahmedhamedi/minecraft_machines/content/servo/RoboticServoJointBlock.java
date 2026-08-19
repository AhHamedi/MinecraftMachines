package dev.ahmedhamedi.minecraft_machines.content.servo;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlockEntityTypes;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class RoboticServoJointBlock extends DirectionalKineticBlock implements IBE<RoboticServoJointBlockEntity>, IWrenchable, BlockSubLevelAssemblyListener {
    public static final MapCodec<RoboticServoJointBlock> CODEC = simpleCodec(RoboticServoJointBlock::new);
    public static final DirectionProperty FACING = DirectionalKineticBlock.FACING;
    public static final BooleanProperty ASSEMBLED = BooleanProperty.create("assembled");

    public RoboticServoJointBlock(final Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(ASSEMBLED, false));
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        final BlockState placedState = super.getStateForPlacement(context);
        return (placedState == null ? this.defaultBlockState() : placedState)
                .setValue(ASSEMBLED, false);
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(ASSEMBLED));
    }

    @Override
    protected MapCodec<? extends DirectionalKineticBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return AllShapes.MOTOR_BLOCK.get(state.getValue(FACING));
    }

    @Override
    public Direction.Axis getRotationAxis(final BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(final LevelReader world, final BlockPos pos, final BlockState state, final Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public boolean hideStressImpact() {
        return true;
    }

    @Override
    public InteractionResult onWrenched(final BlockState state, final UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPos pos = context.getClickedPos();
        BlockState rotated = this.getRotatedBlockState(state, context.getClickedFace());
        if (!rotated.canSurvive(level, pos)) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide) {
            this.withBlockEntityDo(level, pos, RoboticServoJointBlockEntity::disassemble);
        }

        rotated = this.getRotatedBlockState(level.getBlockState(pos), context.getClickedFace());
        KineticBlockEntity.switchToBlockState(level, pos, this.updateAfterWrenched(rotated, context));

        if (level.getBlockState(pos) != state) {
            IWrenchable.playRotateSound(level, pos);
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public void beforeMove(final ServerLevel originLevel, final ServerLevel resultingLevel, final BlockState newState, final BlockPos oldPos, final BlockPos newPos) {
        this.withBlockEntityDo(originLevel, oldPos, RoboticServoJointBlockEntity::beforeAssemblyMove);
    }

    @Override
    public void afterMove(final ServerLevel originLevel, final ServerLevel resultingLevel, final BlockState newState, final BlockPos oldPos, final BlockPos newPos) {
        this.withBlockEntityDo(resultingLevel, newPos, RoboticServoJointBlockEntity::afterAssemblyMove);
    }

    @Override
    public Class<RoboticServoJointBlockEntity> getBlockEntityClass() {
        return RoboticServoJointBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RoboticServoJointBlockEntity> getBlockEntityType() {
        return MinecraftMachinesBlockEntityTypes.ROBOTIC_SERVO_JOINT.get();
    }
}
