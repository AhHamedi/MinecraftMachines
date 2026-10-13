package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedDirectionalGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedPointController;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fresh-body, held-out targets and headings. A stopped arrival must remain within tolerance for
 * five seconds.
 */
final class MinecraftMachinesQuadrupedNavigationEvaluator {
    private static final int BATCH = 16, WARMUP = 80, HORIZON = 1800;
    private static final long SEED = 2026090702L;

    private record Scenario(
            String name, double dx, double dz, double heading, Block floor, boolean push) {}

    private static final Block[] FLOORS = {
        Blocks.SMOOTH_STONE, Blocks.GRASS_BLOCK, Blocks.OAK_PLANKS, Blocks.COBBLESTONE
    };
    private final GameTestHelper helper;
    private final MinecraftMachinesQuadrupedNavigationModel model;
    private final String outputName;
    private final List<Scenario> scenarios = new ArrayList<>();
    private final List<MinecraftMachinesQuadruped> rigs = new ArrayList<>();
    private final QuadrupedPointController[] controllers = new QuadrupedPointController[BATCH];
    private final Vector3d[] starts = new Vector3d[BATCH], previous = new Vector3d[BATCH];
    private final double[] minimumUp = new double[BATCH],
            path = new double[BATCH],
            holdError = new double[BATCH],
            lastSpeed = new double[BATCH];
    private final int[] arrivalTick = new int[BATCH], doneTick = new int[BATCH];
    private final boolean[] failed = new boolean[BATCH];
    private final JsonArray[] traces = new JsonArray[BATCH];
    private final JsonArray results = new JsonArray();
    private int batch, tick;
    private boolean finished;

    MinecraftMachinesQuadrupedNavigationEvaluator(
            GameTestHelper helper, boolean packaged, boolean baseline) throws Exception {
        this.helper = helper;
        var candidate =
                packaged
                        ? MinecraftMachinesQuadrupedNavigationModel.packaged()
                        : MinecraftMachinesQuadrupedNavigationModel.candidate();
        model =
                baseline
                        ? new MinecraftMachinesQuadrupedNavigationModel(
                                candidate.gait(), new double[10])
                        : candidate;
        outputName =
                packaged
                        ? "packaged-qualification"
                        : baseline ? "baseline-qualification" : "qualification";
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            scenarios.add(
                    new Scenario(
                            "compass_" + (i * 45),
                            24 * Math.sin(angle),
                            -24 * Math.cos(angle),
                            0,
                            FLOORS[i % 4],
                            false));
        }
        var random = new Random(SEED);
        for (int i = 0; i < 64; i++) {
            double radius = 8 + 24 * random.nextDouble(), angle = 2 * Math.PI * random.nextDouble();
            scenarios.add(
                    new Scenario(
                            "random_" + i,
                            radius * Math.sin(angle),
                            -radius * Math.cos(angle),
                            2 * Math.PI * random.nextDouble() - Math.PI,
                            FLOORS[i % 4],
                            i % 4 == 3));
        }
        for (int i = 0; i < 8; i++) {
            double radius = new double[] {.5, 1, 2, 4}[i % 4], angle = .31 + i * Math.PI / 4;
            scenarios.add(
                    new Scenario(
                            "near_" + i,
                            radius * Math.sin(angle),
                            -radius * Math.cos(angle),
                            i * Math.PI / 3,
                            FLOORS[i % 4],
                            false));
        }
        begin();
    }

    private void begin() {
        tick = 0;
        rigs.clear();
        java.util.Arrays.fill(minimumUp, 1);
        java.util.Arrays.fill(path, 0);
        java.util.Arrays.fill(holdError, 0);
        java.util.Arrays.fill(arrivalTick, 0);
        java.util.Arrays.fill(doneTick, 0);
        java.util.Arrays.fill(failed, false);
        for (int i = 0; i < BATCH; i++) {
            var scenario = scenarios.get(batch * BATCH + i);
            BlockPos center =
                    MinecraftMachinesQuadrupedNavigationGameTests.arena(
                            helper.getLevel(), i, scenario.floor);
            rigs.add(MinecraftMachinesQuadruped.spawn(helper.getLevel(), center));
            traces[i] = new JsonArray();
        }
    }

    void run() {
        helper.runAfterDelay(
                1,
                () -> {
                    if (finished) return;
                    try {
                        step();
                        if (!finished) run();
                    } catch (Exception e) {
                        close();
                        helper.fail("Navigation evaluation failed: " + e);
                    }
                });
    }

    private void step() throws Exception {
        tick++;
        for (int i = 0; i < BATCH; i++) {
            var rig = rigs.get(i);
            var scenario = scenarios.get(batch * BATCH + i);
            if (tick == 20) {
                rig.captureResetPose();
                rig.reset();
                rig.rotateInitialHeading(-scenario.heading);
            }
            if (tick <= WARMUP) {
                if (tick > 20 && tick % 2 == 0) {
                    var up = rig.up();
                    double[] action =
                            QuadrupedDirectionalGait.action(
                                    model.gait(), 0, rig.heading(), up.x, up.z, 0, 0);
                    if (tick < 60) for (int j = 0; j < 8; j++) action[j] *= (tick - 20) / 40.0;
                    rig.command(action);
                }
                if (tick == WARMUP) {
                    starts[i] = rig.position();
                    previous[i] = new Vector3d(starts[i]);
                    controllers[i] =
                            new QuadrupedPointController(
                                    model.navigation(),
                                    starts[i].x + scenario.dx,
                                    starts[i].z + scenario.dz);
                }
                continue;
            }
            if (doneTick[i] > 0) continue;
            var pos = rig.position();
            double up = rig.upright();
            minimumUp[i] = Math.min(minimumUp[i], up);
            path[i] += Math.hypot(pos.x - previous[i].x, pos.z - previous[i].z);
            previous[i] = pos;
            if (tick == WARMUP + 100 && scenario.push) rig.disturb(.5, .2);
            if (!rig.valid()
                    || !Double.isFinite(pos.x + pos.y + pos.z + up)
                    || up < .9
                    || Math.abs(pos.y - starts[i].y) > 1
                    || Math.abs(pos.x - starts[i].x) > 44
                    || Math.abs(pos.z - starts[i].z) > 44) {
                failed[i] = true;
                doneTick[i] = tick;
                rig.command(new double[8]);
                continue;
            }
            if (tick % 2 != 0) continue;
            var command = controllers[i].update(pos.x, pos.z, rig.heading(), .1);
            lastSpeed[i] = command.speed();
            var bodyUp = rig.up();
            rig.command(
                    QuadrupedDirectionalGait.action(
                            model.gait(),
                            (tick - WARMUP) * .05,
                            rig.heading(),
                            bodyUp.x,
                            bodyUp.z,
                            command.forward(),
                            command.turn()));
            if (tick % 10 == 0) {
                var sample =
                        new Gson()
                                .toJsonTree(
                                        new double[] {
                                            (tick - WARMUP) * .05,
                                            pos.x - starts[i].x,
                                            pos.z - starts[i].z,
                                            rig.heading(),
                                            command.distance(),
                                            command.speed()
                                        });
                traces[i].add(sample);
            }
            if (command.arrived() && arrivalTick[i] == 0) {
                arrivalTick[i] = tick;
                holdError[i] = command.distance();
            }
            if (arrivalTick[i] > 0) {
                holdError[i] = Math.max(holdError[i], command.distance());
                if (command.distance() > .75) {
                    failed[i] = true;
                    doneTick[i] = tick;
                    continue;
                }
                if (tick - arrivalTick[i] >= 100) doneTick[i] = tick;
            }
        }
        boolean done = java.util.stream.IntStream.range(0, BATCH).allMatch(i -> doneTick[i] > 0);
        if (!done && tick < WARMUP + HORIZON) return;
        for (int i = 0; i < BATCH; i++) {
            var scenario = scenarios.get(batch * BATCH + i);
            var pos = rigs.get(i).position();
            var controller = controllers[i];
            double error = Math.hypot(controller.goalX() - pos.x, controller.goalZ() - pos.z);
            boolean success =
                    !failed[i]
                            && arrivalTick[i] > 0
                            && doneTick[i] > 0
                            && error <= .75
                            && lastSpeed[i] <= .15;
            var r = new JsonObject();
            r.addProperty("scenario", scenario.name);
            r.add("goal_offset", new Gson().toJsonTree(new double[] {scenario.dx, scenario.dz}));
            r.addProperty("initial_heading_degrees", Math.toDegrees(scenario.heading));
            r.addProperty("surface", scenario.floor.getDescriptionId());
            r.addProperty("disturbed", scenario.push);
            r.addProperty("success", success);
            r.addProperty("final_error_blocks", error);
            r.addProperty("minimum_up_dot", minimumUp[i]);
            r.addProperty("maximum_hold_error_blocks", holdError[i]);
            r.addProperty("final_speed_blocks_per_second", lastSpeed[i]);
            r.addProperty("path_blocks", path[i]);
            if (arrivalTick[i] > 0)
                r.addProperty("arrival_seconds", (arrivalTick[i] - WARMUP) * .05);
            else r.add("arrival_seconds", com.google.gson.JsonNull.INSTANCE);
            r.addProperty("fell_or_left_arena", failed[i]);
            MinecraftMachines.LOGGER.info("QUADRUPED_NAV_EVAL {}", r);
            r.add("trace", traces[i]);
            results.add(r);
        }
        rigs.forEach(MinecraftMachinesQuadruped::close);
        write();
        if (++batch < scenarios.size() / BATCH) {
            begin();
            return;
        }
        long accepted =
                java.util.stream.StreamSupport.stream(results.spliterator(), false)
                        .filter(r -> r.getAsJsonObject().get("success").getAsBoolean())
                        .count();
        finished = true;
        helper.assertTrue(
                accepted == scenarios.size(),
                "Navigation qualification: "
                        + accepted
                        + "/"
                        + scenarios.size()
                        + " arrivals passed; complete report saved");
        helper.succeed();
    }

    private void write() throws Exception {
        var result = new JsonObject();
        result.addProperty("seed", SEED);
        result.addProperty("policy", QuadrupedPointController.VERSION);
        result.addProperty("position_tolerance_blocks", .75);
        result.addProperty("settled_speed_limit", .15);
        result.addProperty("hold_seconds", 5);
        result.addProperty("deadline_seconds", 90);
        result.addProperty("parameters_changed", false);
        result.add("navigation_genome", new Gson().toJsonTree(model.navigation()));
        result.add("gait_genome", new Gson().toJsonTree(model.gait()));
        result.add("trials", results);
        Files.createDirectories(Path.of("quadruped_navigation"));
        Files.writeString(
                Path.of("quadruped_navigation/" + outputName + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private void close() {
        finished = true;
        rigs.forEach(MinecraftMachinesQuadruped::close);
    }
}
