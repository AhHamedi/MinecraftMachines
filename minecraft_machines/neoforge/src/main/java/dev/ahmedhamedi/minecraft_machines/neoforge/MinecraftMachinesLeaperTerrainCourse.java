package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.joml.Vector3d;

/** Fixture generation only. Controllers receive the resulting Minecraft collision geometry. */
record MinecraftMachinesLeaperTerrainCourse(
        String name,
        int kind,
        double rise,
        double tread,
        double angle,
        double heading,
        String pace,
        double distance,
        int seed) {
    double height(double x, double z) {
        double d = x * Math.sin(angle) - z * Math.cos(angle),
                l = x * Math.cos(angle) + z * Math.sin(angle);
        double steps = Math.clamp(Math.floor((d - 10) / tread), 0, 4);
        return switch (kind) {
            case 0 -> steps * rise;
            case 1 -> (4 - steps) * rise;
            case 2 -> Math.max(0, Math.min(steps, Math.floor((distance - 6 - d) / tread))) * rise;
            case 3 ->
                    d < 8
                            ? 0
                            : Math.floorMod(
                                            (int) Math.floor((l + 100) / tread) * 31
                                                    + (int) Math.floor((d + 100) / tread) * 17
                                                    + seed,
                                            3)
                                    * rise;
            case 4 -> d < 8 ? 0 : Math.clamp(Math.floor((l + 7) / tread), 0, 4) * rise;
            case 5 -> d > 14 && d < 20 && Math.abs(l) < 7 ? 6 : 0;
            case 6 -> d > 14 && d < 25 && Math.abs(l) < 7 ? -6 : 0;
            case 7 -> Math.clamp(Math.floor((d - 10) / tread), 0, 6) * rise;
            case 8 -> (6 - Math.clamp(Math.floor((d - 10) / tread), 0, 6)) * rise;
            case 10 ->
                    d < 8
                            ? 0
                            : Math.max(
                                    0,
                                    Math.floor(
                                            2 + 1.3 * Math.sin(d / 4.3) + .9 * Math.sin(l / 3.7)));
            case 11 ->
                    d < 10 || d > distance - 8
                            ? 0
                            : ((int) Math.floor((d - 10) / tread) % 2) * rise;
            case 12 ->
                    d < 8
                            ? 0
                            : Math.max(
                                    0,
                                    Math.floor(.8 + .7 * Math.sin(d / 7) + .5 * Math.sin(l / 6)));
            case 13 -> d > 17 && d < 30 && Math.abs(l) < 9 ? -3 : 0;
            case 14 -> d > 20 && d < 24 && Math.abs(l) < 15 ? 7 : 0;
            case 15 -> d > 17 && d < 22 && Math.abs(l) > 3 ? -7 : 0;
            default -> 0;
        };
    }

    Vector3d build(ServerLevel level, int slot) {
        var start = MinecraftMachinesLeaperLearning.arena(level, slot, Blocks.SMOOTH_STONE, 64);
        int ox = (int) Math.floor(start.x), oz = (int) Math.floor(start.z);
        for (int x = -64; x <= 64; x++)
            for (int z = -64; z <= 64; z++) {
                double h = height(x, z);
                if (h < 0) {
                    level.setBlockAndUpdate(
                            new BlockPos(ox + x, 6, oz + z), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(
                            new BlockPos(ox + x, (int) (6 + h), oz + z),
                            Blocks.STONE.defaultBlockState());
                    continue;
                }
                for (int y = 0; y < (int) h; y++)
                    level.setBlockAndUpdate(
                            new BlockPos(ox + x, 7 + y, oz + z),
                            Blocks.SMOOTH_STONE.defaultBlockState());
                if (h % 1 > .1)
                    level.setBlockAndUpdate(
                            new BlockPos(ox + x, 7 + (int) h, oz + z),
                            Blocks.SMOOTH_STONE_SLAB
                                    .defaultBlockState()
                                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            }
        if (kind == 12) {
            for (int along : new int[] {18, 30, 42})
                for (int across : new int[] {-12, 0, 12}) {
                    double l = across + (along == 30 ? 5 : 0), d = along;
                    int x = (int) Math.round(d * Math.sin(angle) + l * Math.cos(angle));
                    int z = (int) Math.round(-d * Math.cos(angle) + l * Math.sin(angle));
                    int floor = 7 + (int) height(x, z);
                    for (int y = 0; y < 7; y++)
                        level.setBlockAndUpdate(
                                new BlockPos(ox + x, floor + y, oz + z),
                                Blocks.OAK_LOG.defaultBlockState());
                    for (int dx = -2; dx <= 2; dx++)
                        for (int dz = -2; dz <= 2; dz++)
                            for (int dy = 5; dy <= 7; dy++)
                                if (dx != 0 || dz != 0)
                                    level.setBlockAndUpdate(
                                            new BlockPos(ox + x + dx, floor + dy, oz + z + dz),
                                            Blocks.OAK_LEAVES
                                                    .defaultBlockState()
                                                    .setValue(
                                                            net.minecraft.world.level.block
                                                                    .LeavesBlock.PERSISTENT,
                                                            true));
                }
        }
        if (kind == 13)
            for (int x = -64; x <= 64; x++)
                for (int z = -64; z <= 64; z++)
                    if (height(x, z) < 0)
                        for (int y = 4; y <= 6; y++)
                            level.setBlockAndUpdate(
                                    new BlockPos(ox + x, y, oz + z),
                                    Blocks.WATER.defaultBlockState());
        start.y += height(0, 0);
        return start;
    }

    Vector3d goal(Vector3d start) {
        double dx = distance * Math.sin(angle), dz = -distance * Math.cos(angle);
        return new Vector3d(start.x + dx, 7 + height(dx, dz), start.z + dz);
    }
}
