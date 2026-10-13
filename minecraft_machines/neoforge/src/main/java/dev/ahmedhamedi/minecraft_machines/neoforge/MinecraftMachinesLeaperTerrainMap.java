package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Collision-surface sensing. No test-course labels or synthetic heights enter control. */
final class MinecraftMachinesLeaperTerrainMap {
    private final ServerLevel level;
    private final double coordinateScale;
    double padScale = 1;
    boolean exposedSurfaces = false;

    MinecraftMachinesLeaperTerrainMap(ServerLevel level) {
        this(level, 1);
    }

    MinecraftMachinesLeaperTerrainMap(ServerLevel level, double coordinateScale) {
        this.level = level;
        this.coordinateScale = coordinateScale;
    }

    ServerLevel level() { return level; }

    double height(double x, double z, double ceiling) {
        return height(x, z, ceiling, 24);
    }

    double height(double x, double z, double ceiling, int depth) {
        return worldHeight(x * coordinateScale, z * coordinateScale, ceiling * coordinateScale,
                (int) Math.ceil(depth * coordinateScale)) / coordinateScale;
    }

    private double worldHeight(double x, double z, double ceiling, int depth) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        double lx = x - bx, lz = z - bz;
        if (!level.hasChunk(bx >> 4, bz >> 4)) return Double.NaN;
        int top = Math.min(level.getMaxBuildHeight() - 1, (int) Math.floor(ceiling));
        for (int y = top; y >= Math.max(level.getMinBuildHeight(), top - depth); y--) {
            var pos = new BlockPos(bx, y, bz);
            var state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) return Double.NaN;
            if (state.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            var shape = state.getCollisionShape(level, pos);
            double found = Double.NEGATIVE_INFINITY;
            for (var box : shape.toAabbs())
                if (lx >= box.minX - 1e-6
                        && lx <= box.maxX + 1e-6
                        && lz >= box.minZ - 1e-6
                        && lz <= box.maxZ + 1e-6
                        && y + box.maxY <= ceiling + 1e-6
                        && (!exposedSurfaces || exposed(bx, bz, lx, lz, y + box.maxY)))
                    found = Math.max(found, y + box.maxY);
            if (Double.isFinite(found)) return found;
        }
        return Double.NaN;
    }

    private boolean exposed(int bx, int bz, double lx, double lz, double surface) {
        double probe = surface + 1e-5;
        int above = (int) Math.floor(probe);
        // Check the same column at the actual sub-block position. Include the cell below:
        // fence/wall collision shapes can extend beyond their owning block's upper face.
        for (int y = above - 1; y <= above; y++) {
            var pos = new BlockPos(bx, y, bz);
            var state = level.getBlockState(pos);
            if (y == above && !state.getFluidState().isEmpty()) return false;
            if (state.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            for (var box : state.getCollisionShape(level, pos).toAabbs())
                if (lx >= box.minX - 1e-6
                        && lx <= box.maxX + 1e-6
                        && lz >= box.minZ - 1e-6
                        && lz <= box.maxZ + 1e-6
                        && probe >= y + box.minY
                        && probe < y + box.maxY) return false;
        }
        return true;
    }

    double sweepHeight(double ax, double az, double bx, double bz, double ceiling) {
        int n = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az) / .3));
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            for (double dx : new double[] {-.26 * padScale, 0, .26 * padScale})
                for (double dz : new double[] {-.26 * padScale, 0, .26 * padScale}) {
                    double h = height(ax + (bx - ax) * t + dx, az + (bz - az) * t + dz, ceiling);
                    if (Double.isFinite(h)) top = Math.max(top, h);
                }
        }
        return top;
    }

    double padHeight(double x, double z, double ceiling) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double dx : new double[] {-.26 * padScale, 0, .26 * padScale})
            for (double dz : new double[] {-.26 * padScale, 0, .26 * padScale}) {
                double h = height(x + dx, z + dz, ceiling);
                if (!Double.isFinite(h)) return Double.NaN;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        return max - min > .55 ? Double.NaN : max;
    }
}
