package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.server.level.ServerLevel;

import java.util.*;

/** Higher contact resolution while predators exist; preserves the world's prior setting. */
final class MinecraftMachinesLeaperPhysicsLease implements AutoCloseable {
    private static final Map<ServerLevel, State> STATES = new IdentityHashMap<>();

    private static class State {
        final NavigableMap<Integer, Integer> demands = new TreeMap<>();
        final int before;

        State(int before) {
            this.before = before;
        }
    }

    private final ServerLevel level;
    private boolean closed;
    private final int requested;

    MinecraftMachinesLeaperPhysicsLease(ServerLevel level) {
        this(level, 1);
    }

    MinecraftMachinesLeaperPhysicsLease(ServerLevel level, double scale) {
        this.level = level;
        var system = SubLevelPhysicsSystem.get(level);
        int configured = Integer.getInteger("minecraft_machines.leaperSubsteps", 4);
        if (configured < 2 || configured > 8)
            throw new IllegalArgumentException("Predator physics substeps 2..8");
        requested = scale == 1 ? configured : Math.min(8, Math.max(configured, (int) Math.ceil(4 * scale)));
        var state =
                STATES.computeIfAbsent(level, l -> new State(system.getConfig().substepsPerTick));
        state.demands.merge(requested, 1, Integer::sum);
        int value = Math.max(state.before, state.demands.lastKey());
        if (system.getConfig().substepsPerTick != value) {
            system.getConfig().substepsPerTick = value;
            system.onConfigUpdated();
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        var state = STATES.get(level);
        if (state != null) {
            state.demands.computeIfPresent(requested, (k, n) -> n == 1 ? null : n - 1);
            var system = SubLevelPhysicsSystem.get(level);
            int value = state.demands.isEmpty() ? state.before : Math.max(state.before, state.demands.lastKey());
            if (system.getConfig().substepsPerTick != value) {
                system.getConfig().substepsPerTick = value;
                system.onConfigUpdated();
            }
            if (state.demands.isEmpty()) STATES.remove(level);
        }
    }
}
