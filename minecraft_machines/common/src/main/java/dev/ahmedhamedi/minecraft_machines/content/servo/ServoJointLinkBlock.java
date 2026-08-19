package dev.ahmedhamedi.minecraft_machines.content.servo;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlockEntityTypes;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import dev.simulated_team.simulated.index.SimBlockShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ServoJointLinkBlock extends DirectionalBlock implements IBE<ServoJointLinkBlockEntity>, BlockSubLevelAssemblyListener {
    public static final MapCodec<ServoJointLinkBlock> CODEC = simpleCodec(ServoJointLinkBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public ServoJointLinkBlock(final Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(final BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SimBlockShapes.SWIVEL_BEARING_PLATE.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SimBlockShapes.SWIVEL_BEARING_PLATE_COLLISION.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getBlockSupportShape(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return SimBlockShapes.SWIVEL_BEARING_PLATE.get(state.getValue(FACING));
    }

    @Override
    public void beforeMove(final ServerLevel originLevel, final ServerLevel resultingLevel, final BlockState newState, final BlockPos oldPos, final BlockPos newPos) {
        this.withBlockEntityDo(originLevel, oldPos, ServoJointLinkBlockEntity::beforeAssemblyMove);
    }

    @Override
    public void afterMove(final ServerLevel originLevel, final ServerLevel resultingLevel, final BlockState newState, final BlockPos oldPos, final BlockPos newPos) {
        this.withBlockEntityDo(resultingLevel, newPos, ServoJointLinkBlockEntity::afterAssemblyMove);
    }

    @Override
    public ItemStack getCloneItemStack(final LevelReader level, final BlockPos pos, final BlockState state) {
        return ItemStack.EMPTY;
    }

    @Override
    public Class<ServoJointLinkBlockEntity> getBlockEntityClass() {
        return ServoJointLinkBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ServoJointLinkBlockEntity> getBlockEntityType() {
        return MinecraftMachinesBlockEntityTypes.SERVO_JOINT_LINK.get();
    }
}
