package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPredatorPlanner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Per-plan collision cache. Foliage obstructs a route but cannot become a phantom climbing ramp.
 */
final class MinecraftMachinesLeaperPredatorMap implements LeaperPredatorPlanner.Terrain {
    final ServerLevel level;
    final double ceiling, bodyHeight, sweepRadius;
    boolean terrainAwareSweep;
    double scale = 1;
    final Map<ColumnPoint, Double> heights = new HashMap<>();
    final Map<Long, List<AABB>> shapes = new HashMap<>();
    final Map<Long, Boolean> branches = new HashMap<>();

    private record Band(int x, int z, int low, int high, int fx, int fz) {}

    private record ColumnPoint(int x, int z, int fx, int fz) {}

    final Map<Band, List<Double>> layers = new HashMap<>();

    MinecraftMachinesLeaperPredatorMap(ServerLevel level, double ceiling) {
        this(level, ceiling, 2.7744, 8.4);
    }

    MinecraftMachinesLeaperPredatorMap(
            ServerLevel level, double ceiling, double bodyHeight, double sweepRadius) {
        this.level = level;
        this.ceiling = ceiling;
        this.bodyHeight = bodyHeight;
        this.sweepRadius = Math.max(5.8, sweepRadius);
    }

    public double bodyScale() { return scale; }

    private List<AABB> shape(BlockPos p) {
        return shapes.computeIfAbsent(
                p.asLong(), k -> level.getBlockState(p).getCollisionShape(level, p).toAabbs());
    }

    private boolean branch(BlockPos p) {
        return branches.computeIfAbsent(
                p.asLong(),
                k -> {
                    var s = level.getBlockState(p);
                    return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
                });
    }

    public List<Double> surfaces(double x, double z, double minimum, double maximum) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return List.of();
        var key =
                new Band(
                        bx,
                        bz,
                        Math.max(level.getMinBuildHeight(), (int) Math.floor(minimum) - 1),
                        Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(maximum)),
                        (int) Math.round((x - bx) * 1e6),
                        (int) Math.round((z - bz) * 1e6));
        var all =
                layers.computeIfAbsent(
                        key,
                        k -> {
                            var result = new ArrayList<Double>();
                            double lx = k.fx / 1e6, lz = k.fz / 1e6;
                            for (int y = k.low; y <= k.high; y++) {
                                var pos = new BlockPos(k.x, y, k.z);
                                var state = level.getBlockState(pos);
                                if (state.is(BlockTags.LEAVES) || !state.getFluidState().isEmpty())
                                    continue;
                                double h = Double.NEGATIVE_INFINITY;
                                for (var box : shape(pos))
                                    if (box.minX <= lx
                                            && box.maxX >= lx
                                            && box.minZ <= lz
                                            && box.maxZ >= lz) h = Math.max(h, y + box.maxY);
                                if (!Double.isFinite(h)) continue;
                                var above = new BlockPos(k.x, (int) Math.floor(h + .1), k.z);
                                if (!level.getBlockState(above).getFluidState().isEmpty()) continue;
                                boolean blocked = false;
                                for (var box : shape(above))
                                    if (box.minX <= lx
                                            && box.maxX >= lx
                                            && box.minZ <= lz
                                            && box.maxZ >= lz
                                            && box.maxY + above.getY() > h + .05
                                            && box.minY + above.getY() < h + .2) {
                                        blocked = true;
                                        break;
                                    }
                                if (!blocked) result.add(h);
                            }
                            return List.copyOf(result);
                        });
        return all.stream().filter(h -> h >= minimum - 1e-6 && h <= maximum + 1e-6).toList();
    }

    public double height(double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        var key =
                new ColumnPoint(
                        bx, bz, (int) Math.round((x - bx) * 1e6), (int) Math.round((z - bz) * 1e6));
        double lx = x - bx, lz = z - bz;
        return heights.computeIfAbsent(
                key,
                k -> {
                    if (!level.hasChunk(bx >> 4, bz >> 4)) return Double.NaN;
                    int top =
                            Math.min(
                                    (int) Math.floor(ceiling),
                                    level.getHeight(
                                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz));
                    // Start at the column's actual top: an uphill goal must not truncate the
                    // low ground beneath the robot to a fixed 24-block sensing window.
                    for (int y = top; y >= level.getMinBuildHeight(); y--) {
                        var pos = new BlockPos(bx, y, bz);
                        var state = level.getBlockState(pos);
                        if (!state.getFluidState().isEmpty()) return Double.NaN;
                        if (state.is(BlockTags.LEAVES)) continue;
                        double h = Double.NEGATIVE_INFINITY;
                        for (var box : shape(pos))
                            if (box.minX <= lx
                                    && box.maxX >= lx
                                    && box.minZ <= lz
                                    && box.maxZ >= lz
                                    && y + box.maxY <= ceiling + 1e-6)
                                h = Math.max(h, y + box.maxY);
                        if (!Double.isFinite(h)) continue;
                        var above = new BlockPos(bx, (int) Math.floor(h + .1), bz);
                        for (var box : shape(above))
                            if (box.minX <= lx
                                    && box.maxX >= lx
                                    && box.minZ <= lz
                                    && box.maxZ >= lz
                                    && box.maxY + above.getY() > h + .05
                                    && box.minY + above.getY() < h + .2) return Double.NaN;
                        return h;
                    }
                    return Double.NaN;
                });
    }

    public boolean bodyClear(double x, double floor, double z) {
        return bodyClear(x, floor, z, 0, 0);
    }

    public boolean bodyClear(double x, double floor, double z, double gx, double gz) {
        return MinecraftMachinesLeaperClearanceScan.measure(this, x, floor, z, gx, gz, false).clear();
    }

    // A continuous bank can be approached with folded legs; an overhang cannot.
    // The central chassis box and supported foothold-sector tests still apply.
    private boolean solidFromFloor(int x, int base, int z, int height) {
        for (int dy = 0; dy <= height; dy++) {
            var boxes = shape(new BlockPos(x, base + dy, z));
            boolean filled = false;
            for (var box : boxes)
                if (box.minY <= 0
                        && box.maxY >= 1
                        && box.minX <= .5
                        && box.maxX >= .5
                        && box.minZ <= .5
                        && box.maxZ >= .5) filled = true;
            if (!filled) return false;
        }
        return true;
    }
}
