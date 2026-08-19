package dev.ahmedhamedi.minecraft_machines.content.servo;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class ServoJointLinkBlockEntity extends SmartBlockEntity implements BlockEntitySubLevelActor {
    private static final String TAG_PARENT_POS = "ParentPos";
    private static final String TAG_PARENT_SUB_LEVEL_ID = "ParentSubLevelId";

    @Nullable
    private BlockPos parentPos;
    @Nullable
    private UUID parentSubLevelId;
    private boolean assembling;

    public ServoJointLinkBlockEntity(final BlockEntityType<?> type, final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
    }

    public void beforeAssemblyMove() {
        this.assembling = true;
    }

    public void afterAssemblyMove() {
        this.assembling = false;
        this.fixParentLinkingWhenMoved();
    }

    public void setParent(final RoboticServoJointBlockEntity parent) {
        final SubLevel parentSubLevel = Sable.HELPER.getContaining(parent);

        this.parentPos = parent.getBlockPos();
        this.parentSubLevelId = parentSubLevel != null ? parentSubLevel.getUniqueId() : null;
        this.setChanged();
        this.sendData();
    }

    @Override
    public void remove() {
        if (this.level != null && !this.level.isClientSide && !this.assembling) {
            this.notifyParentLinkRemoved();
        }
        super.remove();
    }

    @Override
    public void sable$physicsTick(final ServerSubLevel subLevel, final RigidBodyHandle handle, final double timeStep) {
        final RoboticServoJointBlockEntity parent = this.getParent();
        if (parent != null) {
            parent.onServoPhysicsStep(subLevel, handle, timeStep);
        }
    }

    @Override
    public @Nullable Iterable<@NotNull SubLevel> sable$getConnectionDependencies() {
        if (this.level == null || this.parentSubLevelId == null) {
            return null;
        }

        final SubLevel parentSubLevel = SubLevelContainer.getContainer(this.level).getSubLevel(this.parentSubLevelId);
        return parentSubLevel == null ? null : List.of(parentSubLevel);
    }

    @Override
    protected void write(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        if (this.parentPos != null) {
            compound.put(TAG_PARENT_POS, NbtUtils.writeBlockPos(this.parentPos));
        }
        if (this.parentSubLevelId != null) {
            compound.putUUID(TAG_PARENT_SUB_LEVEL_ID, this.parentSubLevelId);
        }
    }

    @Override
    protected void read(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        this.parentPos = compound.contains(TAG_PARENT_POS) ? NbtUtils.readBlockPos(compound, TAG_PARENT_POS).orElse(null) : null;
        this.parentSubLevelId = compound.hasUUID(TAG_PARENT_SUB_LEVEL_ID) ? compound.getUUID(TAG_PARENT_SUB_LEVEL_ID) : null;
    }

    private void notifyParentLinkRemoved() {
        final RoboticServoJointBlockEntity parent = this.getParent();
        if (parent != null) {
            parent.onLinkRemoved(this.getBlockPos());
        }
    }

    private void fixParentLinkingWhenMoved() {
        final RoboticServoJointBlockEntity parent = this.getParent();
        if (parent == null) {
            return;
        }

        final ServerSubLevel newSubLevel = Sable.HELPER.getContaining(this) instanceof final ServerSubLevel serverSubLevel ? serverSubLevel : null;
        parent.onLinkMoved(this.getBlockPos(), newSubLevel != null ? newSubLevel.getUniqueId() : null);
    }

    private @Nullable RoboticServoJointBlockEntity getParent() {
        if (this.level == null || this.parentPos == null || !this.level.getBlockState(this.parentPos).is(MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get())) {
            return null;
        }

        final BlockEntity blockEntity = this.level.getBlockEntity(this.parentPos);
        return blockEntity instanceof RoboticServoJointBlockEntity parent ? parent : null;
    }
}
