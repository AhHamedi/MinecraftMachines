package dev.ahmedhamedi.minecraft_machines.content.training.environment;

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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

public final class TrainingMachineCollisionRegistry {
    private static final String TAG_TRAINING_MACHINE = MinecraftMachines.MOD_ID + ":training_machine";
    private static final String TAG_MACHINE_ID = "MachineId";
    private static final String TAG_BATCH_ID = "BatchId";
    private static final String TAG_MORPHOLOGY = "Morphology";

    private static final Map<ServerLevel, Long2ObjectMap<List<ServerSubLevel>>> PLOT_CHUNKS = new WeakHashMap<>();

    private TrainingMachineCollisionRegistry() {
    }

    public static void register(
            final ServerSubLevel subLevel,
            final UUID machineId,
            final UUID batchId,
            final String morphologyType
    ) {
        Objects.requireNonNull(subLevel, "subLevel");
        Objects.requireNonNull(machineId, "machineId");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(morphologyType, "morphologyType");

        CompoundTag root = subLevel.getUserDataTag();
        if (root == null) {
            root = new CompoundTag();
        }
        final CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_MACHINE_ID, machineId);
        tag.putUUID(TAG_BATCH_ID, batchId);
        tag.putString(TAG_MORPHOLOGY, morphologyType);
        root.put(TAG_TRAINING_MACHINE, tag);
        subLevel.setUserDataTag(root);
        cache(subLevel);
    }

    public static @Nullable Entry entry(final ServerSubLevel subLevel) {
        if (subLevel == null || subLevel.isRemoved()) {
            return null;
        }
        final CompoundTag root = subLevel.getUserDataTag();
        if (root == null || !root.contains(TAG_TRAINING_MACHINE)) {
            return null;
        }
        final CompoundTag tag = root.getCompound(TAG_TRAINING_MACHINE);
        if (!tag.hasUUID(TAG_MACHINE_ID) || !tag.hasUUID(TAG_BATCH_ID)) {
            return null;
        }
        return new Entry(subLevel.getUniqueId(), tag.getUUID(TAG_MACHINE_ID), tag.getUUID(TAG_BATCH_ID), tag.getString(TAG_MORPHOLOGY));
    }

    public static @Nullable Entry findEntryContaining(final ServerLevel level, final BlockPos plotPos) {
        final List<Entry> entries = findEntriesContaining(level, plotPos);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    public static boolean shouldSuppressCollision(final ServerLevel level, final BlockPos firstPos, final BlockPos secondPos) {
        if (firstPos == null || secondPos == null) {
            return false;
        }
        final List<Entry> firstEntries = findEntriesContaining(level, firstPos);
        if (firstEntries.isEmpty()) {
            return false;
        }
        final List<Entry> secondEntries = findEntriesContaining(level, secondPos);
        for (final Entry first : firstEntries) {
            for (final Entry second : secondEntries) {
                if (first.batchId().equals(second.batchId())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void clearMachine(final ServerLevel level, final UUID machineId) {
        final var container = SubLevelContainer.getContainer(level);
        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            final Entry entry = entry(subLevel);
            if (entry == null || !entry.machineId().equals(machineId)) {
                continue;
            }
            CompoundTag root = subLevel.getUserDataTag();
            if (root != null) {
                root.remove(TAG_TRAINING_MACHINE);
                subLevel.setUserDataTag(root);
            }
        }
        PLOT_CHUNKS.remove(level);
    }

    public static void clearLevel(final ServerLevel level) {
        PLOT_CHUNKS.remove(level);
    }

    public static boolean sameBatch(final Entry first, final Entry second) {
        return first != null && second != null && first.batchId().equals(second.batchId());
    }

    private static List<Entry> findEntriesContaining(final ServerLevel level, final BlockPos plotPos) {
        final List<Entry> entries = new ArrayList<>();
        for (final ServerSubLevel subLevel : findSubLevelsContaining(level, plotPos)) {
            final Entry entry = entry(subLevel);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static List<ServerSubLevel> findSubLevelsContaining(final ServerLevel level, final BlockPos plotPos) {
        final List<ServerSubLevel> matches = new ArrayList<>();
        final long plotChunk = ChunkPos.asLong(plotPos.getX() >> 4, plotPos.getZ() >> 4);
        final Long2ObjectMap<List<ServerSubLevel>> cached = PLOT_CHUNKS.get(level);
        if (cached != null) {
            final List<ServerSubLevel> candidates = cached.get(plotChunk);
            if (candidates != null) {
                candidates.removeIf(subLevel -> entry(subLevel) == null);
                for (final ServerSubLevel subLevel : candidates) {
                    if (containsPlotBlock(subLevel, plotPos)) {
                        matches.add(subLevel);
                    }
                }
                if (!matches.isEmpty()) {
                    return matches;
                }
            }
        }

        final var container = SubLevelContainer.getContainer(level);
        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (entry(subLevel) == null || !containsPlotBlock(subLevel, plotPos)) {
                continue;
            }
            cache(subLevel);
            matches.add(subLevel);
        }
        return matches;
    }

    private static boolean containsPlotBlock(final ServerSubLevel subLevel, final BlockPos plotPos) {
        if (subLevel == null || subLevel.isRemoved() || !subLevel.getPlot().contains(plotPos.getX(), plotPos.getZ())) {
            return false;
        }

        final BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        return bounds != null && bounds.contains(plotPos.getX(), plotPos.getY(), plotPos.getZ());
    }

    private static void cache(final ServerSubLevel subLevel) {
        if (subLevel == null || subLevel.isRemoved()) {
            return;
        }

        final Long2ObjectMap<List<ServerSubLevel>> cache = PLOT_CHUNKS.computeIfAbsent(subLevel.getLevel(), ignored -> new Long2ObjectOpenHashMap<>());
        final ChunkPos min = subLevel.getPlot().getChunkMin();
        final ChunkPos max = subLevel.getPlot().getChunkMax();
        for (int x = min.x; x <= max.x; x++) {
            for (int z = min.z; z <= max.z; z++) {
                final long chunkKey = ChunkPos.asLong(x, z);
                List<ServerSubLevel> subLevels = cache.get(chunkKey);
                if (subLevels == null) {
                    subLevels = new ArrayList<>();
                    cache.put(chunkKey, subLevels);
                }
                if (!subLevels.contains(subLevel)) {
                    subLevels.add(subLevel);
                }
            }
        }
    }

    public record Entry(
            UUID subLevelId,
            UUID machineId,
            UUID batchId,
            String morphologyType
    ) {
    }
}
