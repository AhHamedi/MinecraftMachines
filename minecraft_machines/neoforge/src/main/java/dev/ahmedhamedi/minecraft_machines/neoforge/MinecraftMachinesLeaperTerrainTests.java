package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperTerrainTests {
    @GameTest(template = "empty", timeoutTicks = 200, batch = "leaperTerrainMap")
    public static void collisionSurfaceSensing(GameTestHelper helper) {
        if (!Set.of("terrain-map", "predator-map")
                .contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed();
            return;
        }
        var level = helper.getLevel();
        MinecraftMachinesLeaperLearning.arena(level, 0, Blocks.SMOOTH_STONE, 32);
        level.setBlockAndUpdate(
                new BlockPos(0, 7, 0),
                Blocks.SMOOTH_STONE_SLAB
                        .defaultBlockState()
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        level.setBlockAndUpdate(new BlockPos(2, 7, 0), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(4, 7, 0), Blocks.WATER.defaultBlockState());
        var map = new MinecraftMachinesLeaperTerrainMap(level);
        helper.assertTrue(
                Math.abs(map.height(.5, .5, 20) - 7.5) < 1e-9, "Half-slab collision height");
        helper.assertTrue(
                Math.abs(map.height(2.5, .5, 20) - 8) < 1e-9, "Full-block collision height");
        helper.assertTrue(
                Double.isNaN(map.padHeight(2.01, .5, 20)), "Pad must reject a full-block edge");
        helper.assertTrue(
                Double.isNaN(map.height(4.5, .5, 20)), "Water is not a supported foothold");
        var predator = new MinecraftMachinesLeaperPredatorMap(level, 100);
        helper.assertTrue(predator.height(.5, .5) == 7.5, "Planner preserves half slabs");
        helper.assertTrue(predator.height(2.5, .5) == 8, "Planner preserves full blocks");
        helper.assertTrue(
                predator.height(20.5, .5) == 7, "High destination does not hide low ground");
        helper.assertTrue(Double.isNaN(predator.height(4.5, .5)), "Planner rejects water");
        helper.assertTrue(
                Double.isNaN(predator.height(1_000_000, 1_000_000)),
                "Planner rejects unloaded terrain");
        helper.assertTrue(predator.bodyClear(20.5, 7, .5), "Open ground clears leg envelope");
        level.setBlockAndUpdate(new BlockPos(25, 11, 0), Blocks.OAK_LEAVES.defaultBlockState());
        var foliage = new MinecraftMachinesLeaperPredatorMap(level, 100);
        helper.assertTrue(foliage.height(25.5, .5) == 7, "Foliage cannot become a phantom ramp");
        helper.assertTrue(
                !foliage.bodyClear(20.5, 7, .5), "Low branches obstruct the outer leg sweep");
        for (int y = 7; y <= 25; y++)
            level.setBlockAndUpdate(new BlockPos(-20, y, 0), Blocks.STONE.defaultBlockState());
        helper.assertTrue(
                Double.isNaN(new MinecraftMachinesLeaperPredatorMap(level, 10).height(-19.5, .5)),
                "Clipped cliff interior is not a surface");
        level.setBlockAndUpdate(new BlockPos(-10, 17, 0), Blocks.STONE.defaultBlockState());
        var layered = new MinecraftMachinesLeaperPredatorMap(level, 30);
        helper.assertTrue(
                layered.surfaces(-9.5, .5, 6, 20).equals(List.of(7., 18.)),
                "Ground below an overhang and its roof are separate surfaces");
        helper.assertTrue(
                layered.surfaces(-19.5, .5, 6, 20).isEmpty(),
                "A band cut through a solid cliff contains no invented floor");
        helper.assertTrue(
                layered.surfaces(4.5, .5, 6, 10).isEmpty(),
                "A submerged floor is not a dry terrain layer");
        // Both halves of a stair live in one Minecraft block but have different tops.
        var stair =
                Blocks.STONE_STAIRS
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.StairBlock.FACING,
                                net.minecraft.core.Direction.EAST);
        level.setBlockAndUpdate(new BlockPos(8, 7, 0), stair);
        var detailed = new MinecraftMachinesLeaperPredatorMap(level, 30);
        for (double fx : new double[] {.25, .75}) {
            double h = map.height(8 + fx, .5, 20);
            helper.assertTrue(
                    detailed.height(8 + fx, .5) == h,
                    "Planner samples actual sub-block stair position");
            helper.assertTrue(
                    detailed.surfaces(8 + fx, .5, 7, 9).contains(h),
                    "Layer cache distinguishes two surfaces in a block");
        }
        var part =
                dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks.LEAPER_PART
                        .getDefaultState()
                        .setValue(
                                dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperPartBlock
                                        .PART,
                                43)
                        .setValue(
                                dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperPartBlock
                                        .PRECISE,
                                true);
        helper.assertTrue(
                Math.abs(
                                dev.ryanhcode.sable.physics.config.block_properties
                                                .PhysicsBlockPropertyHelper.getFriction(part)
                                        - 1.65)
                        < 1e-9,
                "Precise foot retains native friction");
        var rig = MinecraftMachinesLeaper.spawn(level, new Vector3d(60.5, 9.25, 60.5));
        try {
            helper.assertTrue(rig.preciseCellCount() == 0, "Legacy starts on preserved colliders");
            rig.predatorMotors();
            helper.assertTrue(
                    rig.preciseCellCount() > 0,
                    "Predator actually installs precise collision states in Sable plots");
            rig.disablePredator();
            helper.assertTrue(
                    rig.preciseCellCount() == 0,
                    "Flat checkpoint restores legacy collision states");
        } finally {
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5000, batch = "leaperTerrain")
    public static void terrainPrototype(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("terrain-prototype")) {
            helper.succeed();
            return;
        }
        var trials = new ArrayList<Trial>();
        for (int i = 0; i < 8; i++) {
            int kind = i % 4;
            var start =
                    MinecraftMachinesLeaperLearning.arena(
                            helper.getLevel(), i, Blocks.SMOOTH_STONE, 64);
            int ox = (int) Math.floor(start.x), oz = (int) Math.floor(start.z);
            for (int x = -64; x <= 64; x++)
                for (int z = -64; z <= 64; z++) {
                    double distance = -z;
                    double h =
                            switch (kind) {
                                case 0 -> Math.clamp(Math.floor((distance - 10) / 6), 0, 4);
                                case 1 -> 4 - Math.clamp(Math.floor((distance - 10) / 6), 0, 4);
                                case 2 -> Math.clamp(Math.floor((distance - 10) / 4), 0, 6) * .5;
                                default ->
                                        distance < 10
                                                ? 0
                                                : (((x + 100) / 3 + (z + 100) / 3) % 3) * .5;
                            };
                    for (int y = 0; y < (int) h; y++)
                        helper.getLevel()
                                .setBlockAndUpdate(
                                        new BlockPos(ox + x, 7 + y, oz + z),
                                        Blocks.SMOOTH_STONE.defaultBlockState());
                    if (h % 1 > .1)
                        helper.getLevel()
                                .setBlockAndUpdate(
                                        new BlockPos(ox + x, 7 + (int) h, oz + z),
                                        Blocks.SMOOTH_STONE_SLAB
                                                .defaultBlockState()
                                                .setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                }
            if (kind == 1) start.y += 4;
            var rig = MinecraftMachinesLeaper.spawn(helper.getLevel(), start);
            trials.add(
                    new Trial(
                            rig,
                            new MinecraftMachinesLeaperTerrainMap(helper.getLevel()),
                            i,
                            kind,
                            start,
                            i < 4 ? "careful" : "horrific"));
        }
        Runnable[] next = {null};
        int[] tick = {0};
        long began = System.nanoTime();
        next[0] =
                () -> {
                    try {
                        ++tick[0];
                        for (var trial : trials) trial.step();
                        if (tick[0] % 200 == 0)
                            MinecraftMachines.LOGGER.info(
                                    "LEAPER_TERRAIN tick={} distances={}",
                                    tick[0],
                                    trials.stream()
                                            .map(
                                                    t ->
                                                            Math.round(t.error * 10) / 10.0
                                                                    + (t.done ? " done" : ""))
                                            .toList());
                        if (tick[0] < 3600 && trials.stream().anyMatch(t -> !t.done)) {
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        var results = trials.stream().map(Trial::result).toList();
                        MinecraftMachinesLeaperLearning.write(
                                "terrain-prototype",
                                Map.of(
                                        "trials",
                                        results,
                                        "wall_seconds",
                                        (System.nanoTime() - began) / 1e9,
                                        "validated",
                                        trials.stream().allMatch(t -> t.success)));
                        trials.forEach(t -> t.rig.close());
                        helper.succeed();
                    } catch (Exception e) {
                        trials.forEach(t -> t.rig.close());
                        helper.fail("Terrain: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> next[0].run());
    }

    static class Trial {
        final MinecraftMachinesLeaper rig;
        final MinecraftMachinesLeaperTerrainControl terrain;
        final int slot, kind;
        final Vector3d start;
        final String pace;
        final LeaperPointController nav;
        LeaperPointController.Command command;
        int tick, settled, arrival;
        double error = 42, minUp = 1, anchor, limit, maxY = -999, minY = 999;
        boolean done, success;
        String reason = "";
        final List<Object> history = new ArrayList<>();

        Trial(
                MinecraftMachinesLeaper rig,
                MinecraftMachinesLeaperTerrainMap map,
                int slot,
                int kind,
                Vector3d start,
                String pace) {
            this.rig = rig;
            this.terrain = new MinecraftMachinesLeaperTerrainControl(rig, map);
            this.slot = slot;
            this.kind = kind;
            this.start = start;
            this.pace = pace;
            nav =
                    new LeaperPointController(
                            MinecraftMachinesLeaperModel.load("careful").navigation(),
                            start.x,
                            start.z - 42);
        }

        void step() {
            if (done) return;
            ++tick;
            var p = rig.position();
            minUp = Math.min(minUp, rig.upright());
            anchor = Math.max(anchor, rig.anchorError());
            for (int j = 0; j < 12; j++)
                limit =
                        Math.max(
                                limit,
                                Math.max(
                                        MinecraftMachinesLeaper.MIN[j % 3] - rig.angles()[j],
                                        rig.angles()[j] - MinecraftMachinesLeaper.MAX[j % 3]));
            if (minUp < .80 || anchor > .05 || limit > .04) {
                done = true;
                reason = "posture/joint";
                return;
            }
            if (tick > 100 && (command == null || tick % 2 == 1))
                command = nav.update(p.x, p.z, rig.heading(), .1);
            terrain.step(
                    command == null ? 0 : command.forward(),
                    command == null ? 0 : command.strafe(),
                    command == null ? 0 : command.turn(),
                    pace);
            error = Math.hypot(p.x - nav.goalX(), p.z - nav.goalZ());
            maxY = Math.max(maxY, p.y);
            minY = Math.min(minY, p.y);
            if (command != null && command.arrived() && error < .6) {
                if (arrival == 0) arrival = tick;
                if (++settled >= 100) done = success = true;
            } else settled = 0;
            if (Math.hypot(p.x - start.x, p.z - start.z) > 58 || p.y < 3) {
                done = true;
                reason = "left course";
            }
            if (tick % 20 == 0)
                history.add(
                        Map.of(
                                "tick",
                                tick,
                                "position",
                                List.of(p.x, p.y, p.z),
                                "error",
                                error,
                                "up",
                                rig.upright(),
                                "gait",
                                terrain.gaitName,
                                "roughness",
                                terrain.roughness));
        }

        Map<String, Object> result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("slot", slot);
            r.put("course", kind);
            r.put("pace", pace);
            r.put("success", success);
            r.put("reason", reason);
            r.put("error", error);
            r.put("minimum_up", minUp);
            r.put("anchor_error", anchor);
            r.put("limit_violation", limit);
            r.put("arrival_seconds", (arrival - 100) * .05);
            r.put("height_range", maxY - minY);
            r.put("minimum_core_clearance", terrain.minimumClearance);
            r.put("terrain_ticks", terrain.terrainTicks);
            r.put("sprint_ticks", terrain.sprintTicks);
            r.put("history", history);
            return r;
        }
    }
}
