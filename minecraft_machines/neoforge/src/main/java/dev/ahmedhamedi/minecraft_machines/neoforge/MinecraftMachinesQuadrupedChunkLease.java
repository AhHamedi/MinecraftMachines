package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import org.joml.Vector3d;

import java.util.Comparator;
import java.util.UUID;

/**
 * A robot-owned, transient ticket follows its physical chassis and never changes saved /forceload
 * state.
 */
final class MinecraftMachinesQuadrupedChunkLease implements AutoCloseable {
    private static final TicketType<UUID> TYPE =
            TicketType.create("minecraft_machines_quadruped", Comparator.<UUID>naturalOrder());
    private final ServerLevel level;
    private final UUID token = UUID.randomUUID();
    private ChunkPos chunk;

    MinecraftMachinesQuadrupedChunkLease(ServerLevel level) {
        this.level = level;
    }

    void follow(Vector3d position) {
        var next =
                new ChunkPos(
                        Math.floorDiv((int) Math.floor(position.x), 16),
                        Math.floorDiv((int) Math.floor(position.z), 16));
        if (next.equals(chunk)) return;
        level.getChunkSource().addRegionTicket(TYPE, next, 3, token, true);
        level.getChunk(next.x, next.z);
        if (chunk != null) level.getChunkSource().removeRegionTicket(TYPE, chunk, 3, token, true);
        chunk = next;
    }

    @Override
    public void close() {
        if (chunk != null) {
            level.getChunkSource().removeRegionTicket(TYPE, chunk, 3, token, true);
            chunk = null;
        }
    }
}
