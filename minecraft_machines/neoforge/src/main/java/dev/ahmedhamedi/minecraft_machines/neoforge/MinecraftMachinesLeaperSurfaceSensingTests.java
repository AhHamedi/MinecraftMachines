package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperSurfaceSensingTests {
    @GameTest(template = "empty", timeoutTicks = 200, batch = "leaperSurfaceSensing")
    public static void exposedCollisionSurfaces(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-surface-sensing")) {
            helper.succeed();
            return;
        }
        var fixture = new Fixture(helper);
        try {
            fixture.checkCases();
        } finally {
            fixture.restore();
        }
        boolean passed = fixture.rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success")));
        MinecraftMachinesLeaperLearning.write(
                "predator-surface-sensing",
                Map.of("validated", passed, "restored", true, "cases", fixture.rows));
        helper.assertTrue(passed, "Exposed collision surfaces: " + fixture.rows);
        helper.succeed();
    }

    private static class Fixture {
        final ServerLevel level;
        final int x, z;
        final MinecraftMachinesLeaperTerrainMap legacy, exposed;
        final Map<BlockPos, BlockState> saved = new LinkedHashMap<>();
        final List<Map<String, Object>> rows = new ArrayList<>();

        Fixture(GameTestHelper helper) {
            level = helper.getLevel();
            var origin = helper.absolutePos(BlockPos.ZERO);
            x = origin.getX() + 4;
            z = origin.getZ() + 4;
            legacy = new MinecraftMachinesLeaperTerrainMap(level);
            exposed = new MinecraftMachinesLeaperTerrainMap(level);
            helper.assertTrue(!legacy.exposedSurfaces, "Legacy surface sensing is the default");
            exposed.exposedSurfaces = true;
        }

        void put(int column, int y, BlockState state) {
            var pos = new BlockPos(x + column * 2, y, z);
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            saved.putIfAbsent(pos, level.getBlockState(pos));
            // Keep fixture shapes exact and avoid propagating neighbor-shape changes.
            level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        void column(int column) {
            // Cover the complete 24-block scan window, including the low-ceiling cases.
            for (int y = -18; y <= 20; y++) put(column, y, Blocks.AIR.defaultBlockState());
            put(column, 6, Blocks.STONE.defaultBlockState());
        }

        void checkCases() {
            column(0);
            for (int y = 7; y <= 16; y++) put(0, y, Blocks.STONE.defaultBlockState());
            check("solid-column-interior", 0, .5, .5, 12.3, 12, Double.NaN);
            check("solid-column-exact-ceiling", 0, .5, .5, 12, 12, Double.NaN);
            check("solid-column-top-above-ceiling", 0, .5, .5, 16.9, 16, Double.NaN);
            check("solid-column-exposed-top", 0, .5, .5, 18, 17, 17);

            column(1);
            put(
                    1,
                    7,
                    Blocks.SMOOTH_STONE_SLAB
                            .defaultBlockState()
                            .setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            check("bottom-slab", 1, .5, .5, 9, 7.5, 7.5);
            check("bottom-slab-interior", 1, .5, .5, 7.4, 7, Double.NaN);
            check("bottom-slab-exact-ceiling", 1, .5, .5, 7.5, 7.5, 7.5);

            column(2);
            put(
                    2,
                    7,
                    Blocks.SMOOTH_STONE_SLAB
                            .defaultBlockState()
                            .setValue(SlabBlock.TYPE, SlabType.TOP));
            check("top-slab", 2, .5, .5, 9, 8, 8);
            check("floor-under-top-slab", 2, .5, .5, 7.75, 7, 7);

            var stairs =
                    Blocks.STONE_STAIRS
                            .defaultBlockState()
                            .setValue(StairBlock.FACING, Direction.EAST);
            column(3);
            put(3, 7, stairs);
            check("east-stair-low-tread", 3, .25, .5, 9, 7.5, 7.5);
            check("east-stair-high-tread", 3, .75, .5, 9, 8, 8);
            check("east-stair-low-under-ceiling", 3, .25, .5, 7.75, 7.5, 7.5);
            // Legacy interior heights depend on how the union is decomposed into AABBs.
            // Record that diagnostic, but require the exposed query to reject either form.
            check("east-stair-high-interior", 3, .75, .5, 7.75, null, Double.NaN);
            column(4);
            put(4, 7, stairs.setValue(StairBlock.FACING, Direction.SOUTH));
            check("south-stair-low-tread", 4, .5, .25, 9, 7.5, 7.5);
            check("south-stair-high-tread", 4, .5, .75, 9, 8, 8);
            check("south-stair-high-interior", 4, .5, .75, 7.75, null, Double.NaN);

            column(5);
            put(5, 11, Blocks.STONE.defaultBlockState());
            put(5, 12, Blocks.STONE.defaultBlockState());
            check("lower-floor-under-solid-roof", 5, .5, .5, 10, 7, 7);
            check("skip-roof-interior-to-lower-floor", 5, .5, .5, 12.3, 12, 7);
            check("exposed-roof-top", 5, .5, .5, 14, 13, 13);
            column(6);
            put(6, 7, Blocks.STONE.defaultBlockState());
            put(6, 8, stairs.setValue(StairBlock.HALF, Half.TOP));
            check("partial-roof-free-local-x", 6, .25, .5, 8.3, 8, 8);
            check("partial-roof-blocked-local-x", 6, .75, .5, 8.3, 8, Double.NaN);

            column(7);
            put(7, 7, Blocks.WATER.defaultBlockState());
            check("water-excluded", 7, .5, .5, 9, Double.NaN, Double.NaN);
            column(8);
            put(
                    8,
                    7,
                    Blocks.SMOOTH_STONE_SLAB
                            .defaultBlockState()
                            .setValue(SlabBlock.WATERLOGGED, true));
            check("waterlogged-slab-excluded", 8, .5, .5, 9, Double.NaN, Double.NaN);
            column(9);
            put(9, 7, Blocks.OAK_LEAVES.defaultBlockState());
            check("leaves-remain-ignored", 9, .5, .5, 9, 7, 7);
            column(10);
            put(10, 7, Blocks.OAK_FENCE.defaultBlockState());
            check("fence-extending-above-cell", 10, .5, .5, 9, 8.5, 8.5);
            check("fence-interior", 10, .5, .5, 8, 7, Double.NaN);
            check("fence-free-local-xz", 10, .1, .1, 9, 7, 7);
        }

        void check(
                String name,
                int column,
                double lx,
                double lz,
                double ceiling,
                Number expectedLegacy,
                double expectedExposed) {
            double px = x + column * 2 + lx, pz = z + lz;
            double old = legacy.height(px, pz, ceiling), next = exposed.height(px, pz, ceiling);
            boolean passed =
                    (expectedLegacy == null || same(old, expectedLegacy.doubleValue()))
                            && same(next, expectedExposed)
                            && (!Double.isFinite(next) || next <= ceiling + 1e-6);
            rows.add(
                    Map.of(
                            "name",
                            name,
                            "x",
                            px,
                            "z",
                            pz,
                            "ceiling",
                            ceiling,
                            "legacy",
                            diagnostic(old),
                            "exposed",
                            diagnostic(next),
                            "expected_legacy",
                            expectedLegacy == null
                                    ? "diagnostic only"
                                    : diagnostic(expectedLegacy.doubleValue()),
                            "expected_exposed",
                            diagnostic(expectedExposed),
                            "success",
                            passed));
        }

        static boolean same(double actual, double expected) {
            return Double.isNaN(expected)
                    ? Double.isNaN(actual)
                    : Math.abs(actual - expected) < 1e-9;
        }

        static Object diagnostic(double value) {
            return Double.isFinite(value) ? value : Double.toString(value);
        }

        void restore() {
            saved.forEach(
                    (pos, state) ->
                            level.setBlock(
                                    pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
            for (var entry : saved.entrySet())
                if (!level.getBlockState(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException(
                            "Surface fixture restoration failed at " + entry.getKey());
        }
    }
}
