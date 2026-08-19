package dev.ahmedhamedi.minecraft_machines.test;

import com.simibubi.create.content.contraptions.AssemblyException;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

public final class PhysicsSpawnTest {
    private PhysicsSpawnTest() {
    }

    public static SpawnResult spawnIronBlock(final ServerLevel level, final BlockPos pos) {
        if (!level.getBlockState(pos).canBeReplaced()) {
            return SpawnResult.failure("Target position is not empty: " + pos.toShortString());
        }

        level.setBlock(pos, Blocks.IRON_BLOCK.defaultBlockState(), 3);

        try {
            final SimAssemblyHelper.AssemblyResult result =
                    SimAssemblyHelper.assembleFromSingleBlock(level, pos, pos, true, false);

            if (result == null) {
                level.removeBlock(pos, false);
                return SpawnResult.failure("Assembly did not produce a Sable sublevel");
            }

            final SubLevel subLevel = result.subLevel();
            if (!(subLevel instanceof final ServerSubLevel serverSubLevel)) {
                return SpawnResult.failure("Assembly produced a non-server sublevel");
            }

            serverSubLevel.setName("Minecraft Machines Spawn Test");
            return SpawnResult.success(serverSubLevel.getUniqueId(), result.offset());
        } catch (final AssemblyException e) {
            level.removeBlock(pos, false);
            return SpawnResult.failure(e.getMessage());
        }
    }

    public record SpawnResult(boolean success, String message, UUID subLevelId, BlockPos offset) {
        public static SpawnResult success(final UUID subLevelId, final BlockPos offset) {
            return new SpawnResult(true, "Spawned assembled iron block", subLevelId, offset);
        }

        public static SpawnResult failure(final String message) {
            return new SpawnResult(false, message, null, BlockPos.ZERO);
        }
    }
}
