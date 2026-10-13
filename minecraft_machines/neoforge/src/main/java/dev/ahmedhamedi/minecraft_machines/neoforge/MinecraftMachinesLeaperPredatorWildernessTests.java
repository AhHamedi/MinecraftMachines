package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** Replays a recorded, normally generated world. No terrain is flattened by this test. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPredatorWildernessTests {
    @GameTest(template = "empty", timeoutTicks = 40000, batch = "predatorWilderness")
    public static void recordedWilderness(GameTestHelper helper) throws Exception {
        if (!Set.of("predator-wilderness", "predator-survey", "predator-natural-tour")
                .contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed();
            return;
        }
        var level = helper.getLevel();
        for (int cx = -6; cx <= 6; cx++)
            for (int cz = -6; cz <= 6; cz++) level.setChunkForced(cx, cz, true);
        if (MinecraftMachinesLeaperLearning.mode().equals("predator-survey")) {
            var results = new ArrayList<Object>();
            int[] index = {0};
            Runnable[] next = {null};
            next[0] =
                    () -> {
                        try {
                            int i = index[0]++;
                            double x = -50 + (i % 5) * 25 + .5, z = -50 + (i / 5) * 25 + .5;
                            var map = new MinecraftMachinesLeaperPredatorMap(level, 150);
                            double y = map.height(x, z);
                            var planner =
                                    new dev.ahmedhamedi.minecraft_machines.content.training
                                            .morphology.LeaperPredatorPlanner(map, .5, .5);
                            long began = System.nanoTime();
                            var result = planner.plan(x, y, z);
                            var row = new LinkedHashMap<String, Object>();
                            row.put("x", x);
                            row.put("y", Double.isFinite(y) ? y : null);
                            row.put("z", z);
                            row.put("found", result.found());
                            row.put("reason", result.reason());
                            row.put("route", result.points());
                            row.put("ms", (System.nanoTime() - began) / 1e6);
                            results.add(row);
                            if (index[0] < 25) helper.runAfterDelay(1, () -> next[0].run());
                            else {
                                MinecraftMachinesLeaperLearning.write(
                                        "predator-survey",
                                        Map.of("world_seed", 2026090717L, "points", results));
                                helper.succeed();
                            }
                        } catch (Exception e) {
                            helper.fail("Survey: " + e);
                        }
                    };
            helper.runAfterDelay(100, next[0]);
            return;
        }
        var run = new Run(helper, MinecraftMachinesLeaperPredatorExperiments.candidate());
        helper.runAfterDelay(100, run::begin);
    }

    static class Run {
        final GameTestHelper helper;
        final MinecraftMachinesLeaperPredatorPolicy policy;
        final List<Object> records = new ArrayList<>(), history = new ArrayList<>();
        final double[][] goals;
        final boolean tour = MinecraftMachinesLeaperLearning.mode().equals("predator-natural-tour");
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        int stage, tick, hold, arrival;
        double anchor, limit, minUp = 1, path;
        Vector3d previous;
        long began = System.nanoTime();

        Run(GameTestHelper h, MinecraftMachinesLeaperPredatorPolicy p) {
            helper = h;
            policy = p;
            goals =
                    tour
                            ? new double[][] {
                                {.5, 74, -50.5},
                                {-49.5, 78, .5},
                                {-49.5, 87, 50.5},
                                {-24.5, 83, 25.5},
                                {.5, 74, -50.5},
                                {-49.5, 78, .5},
                                {-49.5, 87, 50.5},
                                {-24.5, 83, 25.5},
                                {.5, 74, -50.5},
                                {-49.5, 78, .5},
                                {-49.5, 87, 50.5},
                                {-24.5, 83, 25.5},
                                {.5, 74, -50.5}
                            }
                            : new double[][] {
                                {.5, 74, -50.5},
                                {.5, 84, .5},
                                {.5, 74, -50.5},
                                {.5, 84, .5},
                                {.5, 74, -50.5},
                                {.5, 84, .5}
                            };
        }

        void begin() {
            rig =
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            new Vector3d(.5, 84 + MinecraftMachinesLeaper.HEIGHT, .5));
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, helper.getLevel(), policy);
            previous = rig.position();
            target();
            loop();
        }

        void target() {
            tick = hold = arrival = 0;
            path = anchor = limit = 0;
            minUp = 1;
            history.clear();
            var g = goals[stage];
            nav.pace =
                    tour
                            ? (stage < 5 ? "horrific" : stage < 9 ? "careful" : "fast")
                            : (stage < 2 ? "horrific" : stage < 4 ? "careful" : "fast");
            if (!nav.go(g[0], g[1], g[2]))
                throw new IllegalStateException("Wilderness plan: " + nav.reason);
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                        } catch (Exception e) {
                            rig.close();
                            helper.fail("Wilderness: " + e);
                        }
                    });
        }

        void step() throws Exception {
            tick++;
            nav.step();
            var p = rig.position();
            if (tick > 80 || stage > 0) path += p.distance(previous);
            previous = p;
            anchor = Math.max(anchor, rig.anchorError());
            limit = Math.max(limit, rig.limitViolation());
            minUp = Math.min(minUp, rig.upright());
            if (nav.arrived) {
                if (arrival == 0) arrival = tick;
                hold++;
            } else {
                hold = 0;
                arrival = 0;
            }
            if (tick % 20 == 0)
                history.add(
                        Map.of(
                                "tick",
                                tick,
                                "position",
                                List.of(p.x, p.y, p.z),
                                "speed",
                                rig.velocity().length(),
                                "distance",
                                nav.distance,
                                "anchor",
                                rig.anchorError(),
                                "blocked",
                                nav.routeBlocked,
                                "route_index",
                                nav.index,
                                "feet",
                                java.util.stream.IntStream.range(0, 4)
                                        .mapToObj(
                                                i -> {
                                                    var f = rig.toe(i);
                                                    return List.of(f.x, f.y, f.z);
                                                })
                                        .toList()));
            if (tick % 200 == 0)
                MinecraftMachines.LOGGER.info(
                        "PREDATOR_WILDERNESS stage={} tick={} distance={} position={} recoveries={}"
                                + " anchor={}",
                        stage,
                        tick,
                        nav.distance,
                        p,
                        nav.recoveries,
                        anchor);
            boolean physical = anchor > .12 || limit > .15 || minUp < .5 || !rig.valid();
            if (hold >= 40 || tick >= 2480 || physical) {
                boolean success = hold >= 40 && !physical;
                var result = new LinkedHashMap<String, Object>();
                result.put("pace", nav.pace);
                result.put("stage", stage);
                result.put("goal", goals[stage]);
                result.put("success", success);
                result.put("reason", physical ? "physical failure" : success ? "" : "timeout");
                result.put("ticks", tick);
                result.put("arrival_seconds", arrival * .05);
                result.put("error", nav.distance);
                result.put("anchor_error", anchor);
                result.put("limit_violation", limit);
                result.put("minimum_up", minUp);
                result.put("path_length", path);
                result.put("replans", nav.replans);
                result.put("recoveries", nav.recoveries);
                result.put("maximum_plan_ms", nav.maximumPlanMillis);
                result.put("mean_plan_ms", nav.totalPlanMillis / Math.max(1, nav.plans));
                result.put("history", List.copyOf(history));
                result.put("route", nav.route);
                records.add(result);
                MinecraftMachinesLeaperLearning.write(
                        tour ? "predator-natural-tour" : "predator-wilderness",
                        Map.of(
                                "policy",
                                MinecraftMachinesLeaperPredatorPolicy.VERSION,
                                "genome",
                                policy.genome,
                                "trials",
                                records,
                                "world_seed",
                                2026090717L,
                                "fixture",
                                "recorded natural chunks plus original starting platform",
                                "wall_seconds",
                                (System.nanoTime() - began) / 1e9));
                if (!success) {
                    rig.close();
                    helper.fail("Wilderness stage " + stage + ": " + result.get("reason"));
                    return;
                }
                if (++stage == goals.length) {
                    rig.close();
                    helper.succeed();
                    return;
                }
                target();
            }
            loop();
        }
    }
}
