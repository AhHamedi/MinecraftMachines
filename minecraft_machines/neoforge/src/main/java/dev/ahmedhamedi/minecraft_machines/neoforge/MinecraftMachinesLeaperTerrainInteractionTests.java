package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** Player-like retarget, rejected target, stop and pace transitions on actual stepped terrain. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperTerrainInteractionTests {
    @GameTest(template = "empty", timeoutTicks = 8500, batch = "leaperTerrainInteraction")
    public static void terrainInteractions(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("terrain-interaction")) {
            helper.succeed();
            return;
        }
        var rigs = new ArrayList<MinecraftMachinesLeaper>();
        var navs = new ArrayList<MinecraftMachinesLeaperTerrainNavigator>();
        var goals = new ArrayList<Vector3d>();
        for (int i = 0; i < 4; i++) {
            var course =
                    new MinecraftMachinesLeaperTerrainCourse(
                            "interaction-" + i,
                            2,
                            1,
                            5,
                            .2 * i,
                            .2 * i,
                            i % 2 == 0 ? "careful" : "horrific",
                            54,
                            401 + i);
            var start = course.build(helper.getLevel(), i);
            var rig = MinecraftMachinesLeaper.spawn(helper.getLevel(), start);
            rig.reset(course.heading());
            rigs.add(rig);
            var nav =
                    new MinecraftMachinesLeaperTerrainNavigator(
                            rig,
                            new MinecraftMachinesLeaperTerrainMap(helper.getLevel()),
                            MinecraftMachinesLeaperTerrainExperiments.candidate());
            nav.pace = course.pace();
            var goal = course.goal(start);
            goals.add(goal);
            helper.assertTrue(nav.go(goal.x, goal.y, goal.z), "Initial terrain route rejected");
            navs.add(nav);
        }
        int[] tick = {0}, stage = new int[4], stopTick = new int[4], settled = new int[4];
        double[] minUp = {1, 1, 1, 1},
                anchors = new double[4],
                limits = new double[4],
                drift = new double[4];
        var stopped = new Vector3d[4];
        boolean[] done = new boolean[4], passed = new boolean[4];
        String[] reasons = {"", "", "", ""};
        Runnable[] loop = {null};
        loop[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        for (int i = 0; i < 4; i++) {
                            if (done[i]) continue;
                            var r = rigs.get(i);
                            var n = navs.get(i);
                            var p = r.position();
                            var g = goals.get(i);
                            minUp[i] = Math.min(minUp[i], r.upright());
                            anchors[i] = Math.max(anchors[i], r.anchorError());
                            var q = r.angles();
                            for (int j = 0; j < 12; j++)
                                limits[i] =
                                        Math.max(
                                                limits[i],
                                                Math.max(
                                                        MinecraftMachinesLeaper.MIN[j % 3] - q[j],
                                                        q[j] - MinecraftMachinesLeaper.MAX[j % 3]));
                            if (!r.valid()
                                    || minUp[i] < .85
                                    || anchors[i] > .04
                                    || limits[i] > .04) {
                                done[i] = true;
                                reasons[i] = "Posture/joint bounds";
                                continue;
                            }
                            if (stage[i] == 0 && t > 320 && n.terrain.terrainTicks > 100) {
                                if (i < 2) {
                                    // A rejected elevated goal must leave the existing valid route
                                    // intact.
                                    var old = new Vector3d(n.goal);
                                    helper.assertTrue(
                                            !n.go(g.x, g.y + 12, g.z) && n.goal.equals(old),
                                            "Rejected target replaced valid goal");
                                    double x = g.x + 3,
                                            z = g.z + 2,
                                            y = n.map.height(x, z, p.y + 8);
                                    helper.assertTrue(n.go(x, y, z), "Moving retarget rejected");
                                    goals.set(i, new Vector3d(x, y, z));
                                    stage[i] = 2;
                                } else {
                                    n.settle();
                                    n.pace = i == 2 ? "horrific" : "careful";
                                    stopped[i] = new Vector3d(p);
                                    stopTick[i] = t;
                                    stage[i] = 1;
                                }
                            }
                            if (stage[i] == 1) {
                                drift[i] =
                                        Math.max(
                                                drift[i],
                                                Math.hypot(p.x - stopped[i].x, p.z - stopped[i].z));
                                if (t - stopTick[i] >= 140) {
                                    helper.assertTrue(n.go(g.x, g.y, g.z), "Resume rejected");
                                    stage[i] = 2;
                                }
                            }
                            n.step();
                            if (stage[i] == 2 && n.arrived) {
                                if (++settled[i] >= 100) {
                                    done[i] = true;
                                    passed[i] = drift[i] < 2;
                                }
                            } else settled[i] = 0;
                            if (t >= 8000) {
                                done[i] = true;
                                reasons[i] = "Timeout";
                            }
                        }
                        boolean allDone = true;
                        for (boolean d : done) allDone &= d;
                        if (!allDone) {
                            helper.runAfterDelay(1, () -> loop[0].run());
                            return;
                        }
                        var rows = new ArrayList<Object>();
                        boolean all = true;
                        for (int i = 0; i < 4; i++) {
                            var n = navs.get(i);
                            var p = rigs.get(i).position();
                            var g = goals.get(i);
                            all &= passed[i];
                            var row = new LinkedHashMap<String, Object>();
                            row.put("case", i < 2 ? "moving-retarget" : "stop-pace-resume");
                            row.put("pace", n.pace);
                            row.put("passed", passed[i]);
                            row.put("reason", reasons[i]);
                            row.put("minimum_up", minUp[i]);
                            row.put("anchor_error", anchors[i]);
                            row.put("limit_violation", limits[i]);
                            row.put("stop_drift", drift[i]);
                            row.put("error", Math.hypot(p.x - g.x, p.z - g.z));
                            rows.add(row);
                        }
                        MinecraftMachinesLeaperLearning.write(
                                "terrain-interaction", Map.of("validated", all, "trials", rows));
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.assertTrue(all, "Terrain interaction failed; see report");
                        helper.succeed();
                    } catch (Exception e) {
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.fail("Terrain interaction: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> loop[0].run());
    }
}
