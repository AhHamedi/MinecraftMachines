package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedDirectionalGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedPointController;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@GameTestHolder("minecraft_machines_quadruped_navigation")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesQuadrupedRouteGameTests {
    private static final double[][][] ROUTES = {
        {{20, 0}, {20, 20}, {0, 20}, {0, 0}},
        {{0, -20}, {16, 8}, {-16, 8}, {0, 0}, {20, 0}, {-20, 0}},
        {{2, 0}, {2, 2}, {0, 2}, {0, 0}},
        {{-18, 4}, {0, 0}}
    };
    private static final String[] NAMES = {
        "square", "zigzag", "tight_square", "retarget_stop_resume"
    };

    @GameTest(template = "empty", timeoutTicks = 8000, batch = "navigationRoutes")
    public static void consecutiveGoalsRetargetAndResume(GameTestHelper helper) throws Exception {
        String mode = System.getProperty("minecraft_machines.quadrupedNavigation", "packaged");
        if (!mode.equals("routes") && !mode.equals("packaged") && !mode.equals("evaluate")) {
            helper.succeed();
            return;
        }
        var model =
                mode.equals("packaged")
                        ? MinecraftMachinesQuadrupedNavigationModel.packaged()
                        : MinecraftMachinesQuadrupedNavigationModel.candidate();
        List<MinecraftMachinesQuadruped> rigs = new ArrayList<>();
        var controllers = new QuadrupedPointController[4];
        var starts = new Vector3d[4];
        var events = new JsonArray[4];
        var traces = new JsonArray[4];
        var minimumUp = new double[] {1, 1, 1, 1};
        var index = new int[4];
        var arrivedTick = new int[4];
        var done = new boolean[4];
        var pausePosition = new Vector3d[1];
        var stoppingDrift = new double[1];
        for (int i = 0; i < 4; i++) {
            rigs.add(
                    MinecraftMachinesQuadruped.spawn(
                            helper.getLevel(),
                            MinecraftMachinesQuadrupedNavigationGameTests.arena(
                                    helper.getLevel(), 16 + i, Blocks.SMOOTH_STONE)));
            events[i] = new JsonArray();
            traces[i] = new JsonArray();
        }
        int[] tick = {0};
        Runnable[] step = new Runnable[1];
        step[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        for (int i = 0; i < 4; i++) {
                            if (done[i]) continue;
                            var rig = rigs.get(i);
                            if (t == 20) {
                                rig.captureResetPose();
                                rig.reset();
                            }
                            if (t <= 80) {
                                if (t > 20 && t % 2 == 0) {
                                    var up = rig.up();
                                    double[] targets =
                                            QuadrupedDirectionalGait.action(
                                                    model.gait(),
                                                    0,
                                                    rig.heading(),
                                                    up.x,
                                                    up.z,
                                                    0,
                                                    0);
                                    if (t < 60)
                                        for (int j = 0; j < 8; j++) targets[j] *= (t - 20) / 40.0;
                                    rig.command(targets);
                                }
                                if (t == 80) {
                                    starts[i] = rig.position();
                                    double[] goal = i == 3 ? new double[] {0, -30} : ROUTES[i][0];
                                    controllers[i] =
                                            new QuadrupedPointController(
                                                    model.navigation(),
                                                    starts[i].x + goal[0],
                                                    starts[i].z + goal[1]);
                                }
                                continue;
                            }
                            var pos = rig.position();
                            minimumUp[i] = Math.min(minimumUp[i], rig.upright());
                            helper.assertTrue(
                                    rig.valid()
                                            && rig.upright() > .9
                                            && Math.abs(pos.y - starts[i].y) < 1,
                                    "Route must remain physically stable: " + NAMES[i]);
                            if (i == 3) {
                                if (t == 240)
                                    controllers[i].setGoal(starts[i].x + 20, starts[i].z + 15);
                                if (t == 400)
                                    controllers[i].setGoal(starts[i].x - 18, starts[i].z + 4);
                                if (t == 560) pausePosition[0] = pos;
                                if (t == 660) {
                                    stoppingDrift[0] =
                                            Math.hypot(
                                                    pos.x - pausePosition[0].x,
                                                    pos.z - pausePosition[0].z);
                                    controllers[i] =
                                            new QuadrupedPointController(
                                                    model.navigation(),
                                                    starts[i].x - 18,
                                                    starts[i].z + 4);
                                }
                            }
                            if (t % 2 != 0) continue;
                            boolean paused = i == 3 && t >= 560 && t < 660;
                            var command = controllers[i].update(pos.x, pos.z, rig.heading(), .1);
                            var up = rig.up();
                            rig.command(
                                    QuadrupedDirectionalGait.action(
                                            model.gait(),
                                            (t - 80) * .05,
                                            rig.heading(),
                                            up.x,
                                            up.z,
                                            paused ? 0 : command.forward(),
                                            paused ? 0 : command.turn()));
                            if (t % 10 == 0)
                                traces[i].add(
                                        new Gson()
                                                .toJsonTree(
                                                        new double[] {
                                                            (t - 80) * .05,
                                                            pos.x - starts[i].x,
                                                            pos.z - starts[i].z,
                                                            command.distance()
                                                        }));
                            if (i == 3 && t <= 660) continue;
                            if (command.arrived() && arrivedTick[i] == 0) {
                                arrivedTick[i] = t;
                                var event = new JsonObject();
                                event.addProperty("waypoint", index[i]);
                                event.add(
                                        "goal_offset", new Gson().toJsonTree(ROUTES[i][index[i]]));
                                event.addProperty("arrival_seconds", (t - 80) * .05);
                                event.addProperty("error_blocks", command.distance());
                                events[i].add(event);
                                MinecraftMachines.LOGGER.info(
                                        "QUADRUPED_ROUTE_ARRIVAL route={} {}", NAMES[i], event);
                            }
                            if (arrivedTick[i] > 0) {
                                helper.assertTrue(
                                        command.distance() <= .75,
                                        "Route must hold each reached point");
                                if (t - arrivedTick[i] >= 40) {
                                    if (++index[i] == ROUTES[i].length) {
                                        done[i] = true;
                                        continue;
                                    }
                                    double[] next = ROUTES[i][index[i]];
                                    controllers[i].setGoal(
                                            starts[i].x + next[0], starts[i].z + next[1]);
                                    arrivedTick[i] = 0;
                                }
                            }
                        }
                        if (t < 7500
                                && !java.util.stream.IntStream.range(0, 4).allMatch(i -> done[i])) {
                            helper.runAfterDelay(1, () -> step[0].run());
                            return;
                        }
                        var report = new JsonObject();
                        var results = new JsonArray();
                        int accepted = 0;
                        for (int i = 0; i < 4; i++) {
                            var pos = rigs.get(i).position();
                            var controller = controllers[i];
                            double error =
                                    Math.hypot(
                                            controller.goalX() - pos.x, controller.goalZ() - pos.z);
                            boolean passed =
                                    done[i] && error <= .75 && (i != 3 || stoppingDrift[0] < .75);
                            if (passed) accepted++;
                            var result = new JsonObject();
                            result.addProperty("route", NAMES[i]);
                            result.addProperty("success", passed);
                            result.addProperty("completed_points", index[i]);
                            result.addProperty("final_error_blocks", error);
                            result.addProperty("minimum_up_dot", minimumUp[i]);
                            if (i == 3)
                                result.addProperty("stopping_drift_blocks", stoppingDrift[0]);
                            result.add("arrivals", events[i]);
                            MinecraftMachines.LOGGER.info("QUADRUPED_ROUTE_RESULT {}", result);
                            result.add("trace", traces[i]);
                            results.add(result);
                        }
                        report.addProperty("accepted", accepted);
                        report.addProperty("total", 4);
                        report.add("routes", results);
                        Files.createDirectories(Path.of("quadruped_navigation"));
                        Files.writeString(
                                Path.of(
                                        "quadruped_navigation/"
                                                + (mode.equals("packaged")
                                                        ? "packaged-routes"
                                                        : "routes")
                                                + ".json"),
                                new GsonBuilder().setPrettyPrinting().create().toJson(report));
                        rigs.forEach(MinecraftMachinesQuadruped::close);
                        helper.assertTrue(
                                accepted == 4,
                                "All four route, retarget, and stop/resume scenarios must pass");
                        helper.succeed();
                    } catch (Exception e) {
                        rigs.forEach(MinecraftMachinesQuadruped::close);
                        helper.fail("Route test failed: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> step[0].run());
    }
}
