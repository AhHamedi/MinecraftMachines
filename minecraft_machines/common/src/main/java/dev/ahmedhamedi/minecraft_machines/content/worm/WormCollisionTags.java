package dev.ahmedhamedi.minecraft_machines.content.worm;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.WeakHashMap;

public final class WormCollisionTags {
    private static final String TAG_WORM = MinecraftMachines.MOD_ID + ":worm";
    private static final Map<ServerLevel, Long2ObjectMap<ServerSubLevel>> WORM_PLOT_CHUNKS = new WeakHashMap<>();

    private WormCollisionTags() {
    }

    public static void markWormSubLevel(final ServerSubLevel subLevel) {
        CompoundTag tag = subLevel.getUserDataTag();
        if (tag == null) {
            tag = new CompoundTag();
        }
        tag.putBoolean(TAG_WORM, true);
        subLevel.setUserDataTag(tag);
        cacheWormSubLevel(subLevel);
    }

    public static boolean isWormSubLevel(final ServerSubLevel subLevel) {
        if (subLevel == null) {
            return false;
        }

        final CompoundTag tag = subLevel.getUserDataTag();
        return tag != null && tag.getBoolean(TAG_WORM);
    }

    public static ServerSubLevel findWormSubLevelContaining(final ServerLevel level, final BlockPos plotPos) {
        final long plotChunk = ChunkPos.asLong(plotPos.getX() >> 4, plotPos.getZ() >> 4);
        final Long2ObjectMap<ServerSubLevel> cached = WORM_PLOT_CHUNKS.get(level);
        if (cached != null) {
            final ServerSubLevel subLevel = cached.get(plotChunk);
            if (isWormSubLevel(subLevel) && containsPlotBlock(subLevel, plotPos)) {
                return subLevel;
            }
        }

        final var container = SubLevelContainer.getContainer(level);
        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (!isWormSubLevel(subLevel) || !containsPlotBlock(subLevel, plotPos)) {
                continue;
            }
            cacheWormSubLevel(subLevel);
            return subLevel;
        }
        return null;
    }

    private static boolean containsPlotBlock(final ServerSubLevel subLevel, final BlockPos plotPos) {
        if (subLevel.isRemoved() || !subLevel.getPlot().contains(plotPos.getX(), plotPos.getZ())) {
            return false;
        }

        final BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        return bounds != null && bounds.contains(plotPos.getX(), plotPos.getY(), plotPos.getZ());
    }

    private static void cacheWormSubLevel(final ServerSubLevel subLevel) {
        if (subLevel.isRemoved()) {
            return;
        }

        final Long2ObjectMap<ServerSubLevel> cache = WORM_PLOT_CHUNKS.computeIfAbsent(subLevel.getLevel(), ignored -> new Long2ObjectOpenHashMap<>());
        final ChunkPos min = subLevel.getPlot().getChunkMin();
        final ChunkPos max = subLevel.getPlot().getChunkMax();
        for (int x = min.x; x <= max.x; x++) {
            for (int z = min.z; z <= max.z; z++) {
                cache.put(ChunkPos.asLong(x, z), subLevel);
            }
        }
    }
}
