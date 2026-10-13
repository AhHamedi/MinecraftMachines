package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Separates actual chassis/headroom hazards from the conservative, circular leg allowance. */
final class MinecraftMachinesLeaperClearanceScan {
    private static final MinecraftMachinesLeaperClearance.Result BLOCKED =
            new MinecraftMachinesLeaperClearance.Result(false, Map.of(), Set.of());
    private static List<AABB> shapes(MinecraftMachinesLeaperPredatorMap map, BlockPos pos) {
        return map.shapes.computeIfAbsent(pos.asLong(),
                k -> map.level.getBlockState(pos).getCollisionShape(map.level, pos).toAabbs());
    }

    static MinecraftMachinesLeaperClearance.Result measure(MinecraftMachinesLeaperPredatorMap map,
            double x, double floor, double z, double gx, double gz, boolean collect) {
        boolean hardClear = true;
        var peripheral = new HashMap<BlockPos, Double>();
        var collisions = new HashSet<BlockPos>();
        var level = map.level;
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, px = x + (5.8 * map.scale) * Math.cos(a), pz = z + (5.8 * map.scale) * Math.sin(a);
            var pos = BlockPos.containing(px, floor + .1, pz);
            if (!level.hasChunkAt(pos)) { if (!collect) return BLOCKED; hardClear = false; continue; }
            // Only exposed fluid is a hazard. A solid supporting surface shields water below it.
            for (int dy = 0; dy >= -3; dy--) {
                var at = pos.offset(0, dy, 0);
                if (!level.getBlockState(at).getFluidState().isEmpty()) { if (!collect) return BLOCKED; hardClear = false; break; }
                double lx = px - at.getX(), lz = pz - at.getZ();
                if (shapes(map, at).stream().anyMatch(b -> b.minX <= lx && b.maxX >= lx
                        && b.minZ <= lz && b.maxZ >= lz && b.maxY > 0)) break;
            }
        }
        int base = (int) Math.floor(floor + .1);
        for (int bx = (int) Math.floor(x - map.sweepRadius); bx <= Math.floor(x + map.sweepRadius); bx++)
            for (int bz = (int) Math.floor(z - map.sweepRadius); bz <= Math.floor(z + map.sweepRadius); bz++) {
                double dx = Math.max(0, Math.abs(bx + .5 - x) - .5);
                double dz = Math.max(0, Math.abs(bz + .5 - z) - .5);
                double distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > map.sweepRadius * map.sweepRadius) continue;
                if (!level.hasChunk(bx >> 4, bz >> 4)) { if (!collect) return BLOCKED; hardClear = false; continue; }
                double slope = Math.clamp(.6 * (gx * (bx + .5 - x) + gz * (bz + .5 - z)), -3, 3);
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, bx, bz);
                for (int dy = 0; dy <= map.bodyHeight + (4.3 * map.scale) + slope && base + dy < top; dy++) {
                    var pos = new BlockPos(bx, base + dy, bz);
                    boolean branch = map.branches.computeIfAbsent(pos.asLong(), k -> {
                        var state = level.getBlockState(pos);
                        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
                    });
                    boolean overhead = dy >= map.bodyHeight + (2.3 * map.scale) + slope && distanceSquared <= (5.8 * map.scale) * (5.8 * map.scale)
                            && !(map.terrainAwareSweep && solidFromFloor(map, bx, base, bz, dy));
                    if (!(branch || overhead) || shapes(map, pos).isEmpty()) continue;
                    if (!collect) return BLOCKED;
                    collisions.add(pos);
                    if (branch) peripheral.put(pos, Math.sqrt(distanceSquared));
                    else hardClear = false;
                }
            }
        var body = new AABB(x - (1.35 * map.scale), floor + map.bodyHeight - (1.25 * map.scale), z - (1.35 * map.scale),
                x + (1.35 * map.scale), floor + map.bodyHeight + (1.4 * map.scale), z + (1.35 * map.scale));
        for (int bx = (int) Math.floor(body.minX); bx <= Math.floor(body.maxX); bx++)
            for (int by = (int) Math.floor(body.minY); by <= Math.floor(body.maxY); by++)
                for (int bz = (int) Math.floor(body.minZ); bz <= Math.floor(body.maxZ); bz++) {
                    var pos = new BlockPos(bx, by, bz);
                    if (!level.hasChunkAt(pos)) { if (!collect) return BLOCKED; hardClear = false; continue; }
                    for (var box : shapes(map, pos)) if (box.move(bx, by, bz).intersects(body)) {
                        hardClear = false;
                        if (!collect) return BLOCKED;
                        collisions.add(pos);
                    }
                }
        return new MinecraftMachinesLeaperClearance.Result(hardClear, peripheral, collisions);
    }

    private static boolean solidFromFloor(MinecraftMachinesLeaperPredatorMap map, int x, int base, int z, int height) {
        for (int dy = 0; dy <= height; dy++) {
            boolean filled = shapes(map, new BlockPos(x, base + dy, z)).stream().anyMatch(b ->
                    b.minY <= 0 && b.maxY >= 1 && b.minX <= .5 && b.maxX >= .5 && b.minZ <= .5 && b.maxZ >= .5);
            if (!filled) return false;
        }
        return true;
    }
}
