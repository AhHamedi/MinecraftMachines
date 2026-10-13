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
final class MinecraftMachinesLeaperClearance {
    record Result(boolean hardClear, Map<BlockPos, Double> peripheral, Set<BlockPos> collisions) {
        boolean clear() { return hardClear && peripheral.isEmpty(); }

        boolean recedingFrom(Result origin) {
            return hardClear && peripheral.entrySet().stream().allMatch(e ->
                    origin.peripheral.containsKey(e.getKey())
                            && e.getValue() >= origin.peripheral.get(e.getKey()) - .01);
        }
    }

    private static List<AABB> shapes(MinecraftMachinesLeaperPredatorMap map, BlockPos pos) {
        return map.shapes.computeIfAbsent(pos.asLong(),
                k -> map.level.getBlockState(pos).getCollisionShape(map.level, pos).toAabbs());
    }

    static Result measure(MinecraftMachinesLeaperPredatorMap map,
            double x, double floor, double z, double gx, double gz) {
        return MinecraftMachinesLeaperClearanceScan.measure(map, x, floor, z, gx, gz, true);
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
