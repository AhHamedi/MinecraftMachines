package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;

import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperRouteTests {
    @GameTest(template = "empty", timeoutTicks = 18000, batch = "leaperRoutes")
    public static void continuousRoutesAndRetargeting(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("routes")) {
            helper.succeed();
            return;
        }
        var model =
                MinecraftMachinesLeaperModel.load(MinecraftMachinesLeaperLearning.packagedPace());
        double[][][] routes = {
            {{0, -12}, {12, -12}, {12, 0}, {0, 0}},
            {{-9, -7}, {8, -13}, {-7, -20}, {0, 0}},
            {{.7, -1.2}, {-1.5, 1}, {1.8, .4}, {0, 0}},
            {{0, -20}, {14, -2}, {-9, 6}, {0, 0}}
        };
        if (model.sprint()) routes[3][0][1] = -90;
        var rigs = new ArrayList<MinecraftMachinesLeaper>();
        var controllers = new LeaperPointController[4];
        var commands = new LeaperPointController.Command[4];
        var origins = new Vector3d[4];
        int[] waypoint = new int[4], arrivedAt = new int[4], finishedAt = new int[4];
        boolean[] retargeted = {false};
        double[] minUp = {1, 1, 1, 1}, anchor = new double[4], stopDrift = {0};
        Vector3d[] stopPosition = {null};
        double[] stopSpeed = {0}, retargetSpeed = {0};
        List<Object> arrivals = new ArrayList<>(), history = new ArrayList<>();
        int[] tick = {0};
        for (int i = 0; i < 4; i++)
            rigs.add(
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            MinecraftMachinesLeaperLearning.arena(
                                    helper.getLevel(),
                                    i,
                                    Blocks.SMOOTH_STONE,
                                    model.sprint() ? 128 : 46)));
        Runnable[] next = {null};
        next[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        boolean complete = true;
                        for (int i = 0; i < 4; i++) {
                            var r = rigs.get(i);
                            var p = r.position();
                            var up = r.up();
                            minUp[i] = Math.min(minUp[i], up.y);
                            anchor[i] = Math.max(anchor[i], r.anchorError());
                            helper.assertTrue(
                                    r.valid() && up.y > model.minimumUp() && r.anchorError() < .04,
                                    "Route posture/joint failure");
                            if (t <= 100) {
                                r.command(
                                        model.action(0, r.heading(), up.x, up.z, 0, 0, 0),
                                        .05,
                                        model.slew(),
                                        model.power());
                                origins[i] = new Vector3d(p);
                                complete = false;
                                continue;
                            }
                            if (controllers[i] == null)
                                controllers[i] =
                                        new LeaperPointController(
                                                model.navigation(),
                                                origins[i].x + routes[i][0][0],
                                                origins[i].z + routes[i][0][1]);
                            if (i == 3 && t >= 250 && t <= 350) {
                                if (t == 250) {
                                    stopPosition[0] = new Vector3d(p);
                                    var velocity = r.velocity();
                                    stopSpeed[0] = Math.hypot(velocity.x, velocity.z);
                                }
                                stopDrift[0] =
                                        Math.max(
                                                stopDrift[0],
                                                Math.hypot(
                                                        p.x - stopPosition[0].x,
                                                        p.z - stopPosition[0].z));
                                r.command(
                                        model.action(
                                                (t - 100) * .05, r.heading(), up.x, up.z, 0, 0, 0),
                                        .05,
                                        model.slew(),
                                        model.power());
                                controllers[i] = null;
                                commands[i] = null;
                                complete = false;
                                continue;
                            }
                            if (i == 3 && !retargeted[0] && t == 500) {
                                var velocity = r.velocity();
                                retargetSpeed[0] = Math.hypot(velocity.x, velocity.z);
                                waypoint[i] = 1;
                                controllers[i].setGoal(
                                        origins[i].x + routes[i][1][0],
                                        origins[i].z + routes[i][1][1]);
                                retargeted[0] = true;
                            }
                            if (commands[i] == null || (t - 100) % 2 == 1)
                                commands[i] = controllers[i].update(p.x, p.z, r.heading(), .1);
                            var cmd = commands[i];
                            r.command(
                                    model.action(
                                            (t - 100) * .05,
                                            r.heading(),
                                            up.x,
                                            up.z,
                                            cmd.forward(),
                                            cmd.strafe(),
                                            cmd.turn()),
                                    .05,
                                    model.slew(),
                                    model.power());
                            if (finishedAt[i] > 0)
                                complete &= cmd.arrived() && cmd.distance() <= .48;
                            if (finishedAt[i] == 0) {
                                complete = false;
                                if (cmd.arrived() && arrivedAt[i] == 0) arrivedAt[i] = t;
                                double error =
                                        Math.hypot(
                                                p.x - controllers[i].goalX(),
                                                p.z - controllers[i].goalZ());
                                if (error > .48) arrivedAt[i] = 0;
                                if (arrivedAt[i] > 0 && t - arrivedAt[i] >= 100) {
                                    arrivals.add(
                                            Map.of(
                                                    "route",
                                                    i,
                                                    "waypoint",
                                                    waypoint[i],
                                                    "error",
                                                    error,
                                                    "seconds",
                                                    (t - 100) * .05));
                                    arrivedAt[i] = 0;
                                    if (++waypoint[i] < routes[i].length)
                                        controllers[i].setGoal(
                                                origins[i].x + routes[i][waypoint[i]][0],
                                                origins[i].z + routes[i][waypoint[i]][1]);
                                    else finishedAt[i] = t;
                                }
                            }
                            if (t % 20 == 0)
                                history.add(
                                        Map.of(
                                                "route",
                                                i,
                                                "seconds",
                                                (t - 100) * .05,
                                                "position",
                                                List.of(p.x - origins[i].x, p.z - origins[i].z),
                                                "waypoint",
                                                waypoint[i]));
                        }
                        if (t % 1000 == 0)
                            MinecraftMachines.LOGGER.info(
                                    "LEAPER_ROUTES tick={} waypoints={}", t, waypoint);
                        if (!complete && t < 17000) {
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        MinecraftMachinesLeaperLearning.write(
                                "routes",
                                Map.of(
                                        "validated",
                                        complete
                                                && stopDrift[0] < (model.sprint() ? 4 : .8)
                                                && (!model.sprint()
                                                        || stopSpeed[0] > 3
                                                                && retargetSpeed[0] > 3),
                                        "arrivals",
                                        arrivals,
                                        "speed_at_stop_command",
                                        stopSpeed[0],
                                        "speed_at_retarget",
                                        retargetSpeed[0],
                                        "retargeted_in_motion",
                                        retargeted[0],
                                        "minimum_up",
                                        minUp,
                                        "anchor_error",
                                        anchor,
                                        "stop_drift",
                                        stopDrift[0],
                                        "history",
                                        history));
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.assertTrue(complete, "Leaper routes timed out");
                        if (model.sprint())
                            helper.assertTrue(
                                    stopSpeed[0] > 3 && retargetSpeed[0] > 3,
                                    "Sprint stop/retarget did not occur at speed");
                        helper.assertTrue(
                                stopDrift[0] < (model.sprint() ? 4 : .8),
                                "Excessive emergency stop drift");
                        helper.succeed();
                    } catch (Exception ex) {
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.fail("Leaper routes: " + ex);
                    }
                };
        helper.runAfterDelay(1, () -> next[0].run());
    }
}
