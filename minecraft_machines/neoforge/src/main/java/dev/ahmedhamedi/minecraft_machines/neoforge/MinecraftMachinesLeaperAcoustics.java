package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;

/** Dry, loaded air connectivity shared by direct sound and bounded detours. */
final class MinecraftMachinesLeaperAcoustics {
    static boolean dryLine(ServerLevel level, Vec3 from, Vec3 to) {
        return BlockGetter.traverseBlocks(from, to, level,
                (world, pos) -> airCell(world, pos) ? null : Boolean.FALSE,
                world -> Boolean.TRUE);
    }

    static boolean airCell(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) return false;
        var state = level.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && (state.getCollisionShape(level, pos).isEmpty()
                        || state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN));
    }
}
