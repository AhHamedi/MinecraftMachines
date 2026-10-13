package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** Replay of the actual shoreline where standing plans repeatedly failed after crouching. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperShoreLoopTests {
    static final String MODE = "predator-shore-loop";

    @GameTest(template = "empty", timeoutTicks = 1600, batch = "leaperShoreLoop")
    public static void shoreline(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals(MODE)) {
            helper.succeed();
            return;
        }
        var level = helper.getLevel();
        MinecraftMachinesLeaperLearning.arena(level, 0, Blocks.SMOOTH_STONE, 112);
        var template = new StructureTemplate();
        try (var input =
                MinecraftMachinesLeaperShoreLoopTests.class.getResourceAsStream(
                        "/data/minecraft_machines/leaper/fixtures/shore-loop.nbt")) {
            template.load(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK),
                    NbtIo.readCompressed(
                            Objects.requireNonNull(input), NbtAccounter.unlimitedHeap()));
        }
        template.placeInWorld(
                level, BlockPos.ZERO, BlockPos.ZERO, new StructurePlaceSettings(), level.random, 2);
        var rig = MinecraftMachinesLeaper.spawn(level, new Vector3d(50.6922, 12.3147, 25.5344));
        rig.reset(-2.346);
        var nav =
                new MinecraftMachinesLeaperPredatorNavigator(
                        rig, level, MinecraftMachinesLeaperPredatorExperiments.candidate());
        nav.apex.enabled = true;
        var history = new ArrayList<Map<String, Object>>();
        class Run {
            int tick, settled;
            double anchor, minimumUp = 1, distance;

            void step() {
                try {
                    ++tick;
                    if (tick == 81) {
                        try (var reader =
                                new java.io.InputStreamReader(
                                        Objects.requireNonNull(
                                                MinecraftMachinesLeaperShoreLoopTests.class
                                                        .getResourceAsStream(
                                                                "/data/minecraft_machines/leaper/fixtures/shore-pose.json")))) {
                            var snapshot =
                                    com.google.gson.JsonParser.parseReader(reader)
                                            .getAsJsonObject();
                            var poses = snapshot.getAsJsonArray("bodies");
                            var bodies = MinecraftMachinesLeaperPounceCommandTests.bodies(rig);
                            rig.releaseFeet();
                            for (int i = 0; i < bodies.size(); i++) {
                                var pose = poses.get(i).getAsJsonObject();
                                var pos = pose.getAsJsonArray("position");
                                var q = pose.getAsJsonArray("orientation");
                                dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(
                                                bodies.get(i))
                                        .teleport(
                                                new Vector3d(
                                                        pos.get(0).getAsDouble() + 350,
                                                        pos.get(1).getAsDouble() - 54,
                                                        pos.get(2).getAsDouble() + 24),
                                                new org.joml.Quaterniond(
                                                        q.get(0).getAsDouble(),
                                                        q.get(1).getAsDouble(),
                                                        q.get(2).getAsDouble(),
                                                        q.get(3).getAsDouble()));
                                dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.get(level)
                                        .getPipeline()
                                        .resetVelocity(bodies.get(i));
                                bodies.get(i).updateLastPose();
                            }
                            var targetField =
                                    MinecraftMachinesLeaper.class.getDeclaredField("targets");
                            targetField.setAccessible(true);
                            double[] targets = (double[]) targetField.get(rig);
                            for (int i = 0; i < 12; i++)
                                targets[i] =
                                        snapshot.getAsJsonArray("targets").get(i).getAsDouble();
                            rig.command(targets.clone(), .05, 5, nav.control.policy.value[16]);
                            nav.control.resumeAfterLeap();
                        }
                        nav.go(31.1855732275, 10, 44.0223446177);
                    }
                    nav.step();
                    var p = rig.corePivot();
                    var v = rig.assemblyVelocity();
                    distance = Math.hypot(p.x - 31.1855732275, p.z - 44.0223446177);
                    anchor = Math.max(anchor, rig.anchorError());
                    minimumUp = Math.min(minimumUp, rig.upright());
                    boolean physical =
                            rig.valid()
                                    && p.isFinite()
                                    && v.isFinite()
                                    && anchor < .12
                                    && minimumUp > .45
                                    && rig.limitViolation() < .15;
                    settled =
                            distance < 3 && v.length() < 1 && nav.leap.supports() > 0
                                    ? settled + 1
                                    : 0;
                    if (tick % 2 == 0)
                        history.add(
                                Map.of(
                                        "tick",
                                        tick,
                                        "position",
                                        MinecraftMachinesLeaperPursuitRegressionTests.xyz(p),
                                        "velocity",
                                        MinecraftMachinesLeaperPursuitRegressionTests.xyz(v),
                                        "state",
                                        nav.leap.state.toString(),
                                        "supports",
                                        nav.leap.supports(),
                                        "aborts",
                                        nav.leap.aborts,
                                        "reason",
                                        nav.reason));
                    if (settled >= 20 || tick >= 1200 || !physical) {
                        boolean success = physical && settled >= 20 && nav.leap.aborts <= 3;
                        var row = new LinkedHashMap<String, Object>();
                        row.put("case", "captured-shoreline-crouch-loop");
                        row.put("success", success);
                        row.put("aborts", nav.leap.aborts);
                        row.put("launches", nav.leap.launches);
                        row.put("distance", distance);
                        row.put("ticks", tick);
                        row.put("anchor", anchor);
                        row.put("minimum_up", minimumUp);
                        row.put("events", nav.leap.events);
                        row.put("history", history);
                        MinecraftMachinesLeaperLearning.write(
                                MODE, Map.of("complete", true, "trials", List.of(row)));
                        rig.close();
                        helper.assertTrue(success, "Shoreline recovery failed; inspect report");
                        helper.succeed();
                        return;
                    }
                    helper.runAfterDelay(1, this::step);
                } catch (Exception e) {
                    rig.close();
                    helper.fail(e.toString());
                }
            }
        }
        new Run().step();
    }
}
