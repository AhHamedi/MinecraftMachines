package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Keeps the bounded corridor used by a vectorized physics task loaded for the
 * lifetime of that task. These use the same runtime ticket path as
 * {@code /forceload}, but never touch {@code ForcedChunksSavedData}, so crashes
 * cannot pollute operator-owned world state.
 */
final class MinecraftMachinesTrainingChunkLease implements AutoCloseable {
    private static final int SPAWN_AND_FOOTPRINT_PADDING_BLOCKS = 20;
    private static final double MAXIMUM_COMMAND_SPEED_BLOCKS_PER_SECOND = 1.1;

    private final ServerLevel level;
    private final List<ChunkPos> coveredChunks = new ArrayList<>();
    private final List<ChunkPos> addedForcedChunks = new ArrayList<>();
    private final long acquiredGameTime;
    private boolean closed;

    private MinecraftMachinesTrainingChunkLease(
            final ServerLevel level,
            final BlockPos origin,
            final Direction forwardDirection,
            final int slotCount,
            final int spacingBlocks,
            final int maximumControlSteps,
            final int controlTicks,
            final boolean groupedWalkForwardLayout
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.acquiredGameTime = this.level.getGameTime();
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(forwardDirection, "forwardDirection");
        if (forwardDirection.getAxis().isVertical()) {
            throw new IllegalArgumentException("training corridor direction must be horizontal");
        }
        if (slotCount < 1 || spacingBlocks < 0 || maximumControlSteps < 1 || controlTicks < 1) {
            throw new IllegalArgumentException("slotCount, maximumControlSteps, and controlTicks must be positive; spacingBlocks must be non-negative");
        }

        final Direction right = forwardDirection.getClockWise();
        final int intendedForwardTravelBlocks = (int) Math.ceil(
                MAXIMUM_COMMAND_SPEED_BLOCKS_PER_SECOND * maximumControlSteps * controlTicks / 20.0);
        final int minimumRightOffset = -SPAWN_AND_FOOTPRINT_PADDING_BLOCKS;
        final int maximumRightOffset = (groupedWalkForwardLayout
                ? MinecraftMachinesDuopodFlatArena.maximumRightOffsetForSlots(slotCount, spacingBlocks)
                : Math.multiplyExact(slotCount - 1, spacingBlocks))
                + SPAWN_AND_FOOTPRINT_PADDING_BLOCKS;
        final int minimumForwardOffset = -SPAWN_AND_FOOTPRINT_PADDING_BLOCKS;
        final int maximumForwardOffset = SPAWN_AND_FOOTPRINT_PADDING_BLOCKS + intendedForwardTravelBlocks;
        final BlockPos[] corners = new BlockPos[]{
                offset(origin, right, minimumRightOffset, forwardDirection, minimumForwardOffset),
                offset(origin, right, minimumRightOffset, forwardDirection, maximumForwardOffset),
                offset(origin, right, maximumRightOffset, forwardDirection, minimumForwardOffset),
                offset(origin, right, maximumRightOffset, forwardDirection, maximumForwardOffset)
        };
        int minimumBlockX = Integer.MAX_VALUE;
        int maximumBlockX = Integer.MIN_VALUE;
        int minimumBlockZ = Integer.MAX_VALUE;
        int maximumBlockZ = Integer.MIN_VALUE;
        for (final BlockPos corner : corners) {
            minimumBlockX = Math.min(minimumBlockX, corner.getX());
            maximumBlockX = Math.max(maximumBlockX, corner.getX());
            minimumBlockZ = Math.min(minimumBlockZ, corner.getZ());
            maximumBlockZ = Math.max(maximumBlockZ, corner.getZ());
        }
        final int minimumChunkX = Math.floorDiv(minimumBlockX, 16);
        final int maximumChunkX = Math.floorDiv(maximumBlockX, 16);
        final int minimumChunkZ = Math.floorDiv(minimumBlockZ, 16);
        final int maximumChunkZ = Math.floorDiv(maximumBlockZ, 16);
        try {
            for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
                for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                    final ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                    this.coveredChunks.add(chunk);
                    if (!this.level.getForcedChunks().contains(chunk.toLong())) {
                        // This is the runtime half of ServerLevel#setChunkForced only. Using
                        // ServerChunkCache directly deliberately avoids persistent saved data.
                        this.level.getChunkSource().updateChunkForced(chunk, true);
                        this.addedForcedChunks.add(chunk);
                    }
                }
            }
            // Resolve the full chunks now, then let one normal level tick propagate their
            // transient ticket levels before callers assemble moved block entities.
            for (final ChunkPos chunk : this.coveredChunks) {
                this.level.getChunk(chunk.x, chunk.z);
            }
        } catch (final RuntimeException e) {
            this.close();
            throw e;
        }
    }

    static MinecraftMachinesTrainingChunkLease acquire(
            final ServerLevel level,
            final BlockPos origin,
            final Direction forwardDirection,
            final int slotCount,
            final int spacingBlocks,
            final int maximumControlSteps,
            final int controlTicks,
            final boolean groupedWalkForwardLayout
    ) {
        return new MinecraftMachinesTrainingChunkLease(
                level,
                origin,
                forwardDirection,
                slotCount,
                spacingBlocks,
                maximumControlSteps,
                controlTicks,
                groupedWalkForwardLayout);
    }

    private static BlockPos offset(
            final BlockPos origin,
            final Direction right,
            final int rightBlocks,
            final Direction forward,
            final int forwardBlocks
    ) {
        return origin.relative(right, rightBlocks).relative(forward, forwardBlocks);
    }

    int coveredChunkCount() {
        return this.coveredChunks.size();
    }

    /**
     * A loaded LevelChunk is not necessarily block-entity-ticking in the command tick that
     * added its region ticket. Waiting for game time to advance proves the level completed a
     * normal tick in which the distance manager could propagate this lease.
     */
    boolean readyForSpawn() {
        if (this.closed || this.level.getGameTime() <= this.acquiredGameTime) {
            return false;
        }
        for (final ChunkPos chunk : this.coveredChunks) {
            if (!this.level.getChunkSource().isPositionTicking(chunk.toLong())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (int i = this.addedForcedChunks.size() - 1; i >= 0; i--) {
            this.level.getChunkSource().updateChunkForced(this.addedForcedChunks.get(i), false);
        }
        this.addedForcedChunks.clear();
        this.coveredChunks.clear();
    }
}
