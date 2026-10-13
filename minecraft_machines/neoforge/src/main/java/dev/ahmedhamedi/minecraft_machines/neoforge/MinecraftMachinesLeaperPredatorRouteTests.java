package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** End-to-end physical navigation; failures remain failures in the emitted report. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPredatorRouteTests {
    @GameTest(template = "empty", timeoutTicks = 30000, batch = "leaperPredatorRoutes")
    public static void routes(GameTestHelper helper) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!Set.of(
                        "predator-route",
                        "predator-qualify",
                        "predator-certify",
                        "predator-challenge",
                        "predator-baseline-certify",
                        "predator-baseline-challenge")
                .contains(mode)) {
            helper.succeed();
            return;
        }
        new Run(helper, mode).begin();
    }

    static MinecraftMachinesLeaperTerrainCourse course(int i) {
        if (MinecraftMachinesLeaperLearning.mode().endsWith("challenge")) {
            int k = i % 6, group = i / 6;
            return new MinecraftMachinesLeaperTerrainCourse(
                    new String[] {
                                "triple-riser-up",
                                "triple-riser-down",
                                "rubble-three-high",
                                "three-block-ridges",
                                "cross-slope",
                                "tight-broken-ground"
                            }
                            [k],
                    new int[] {0, 1, 3, 11, 4, 3}[k],
                    new double[] {3, 3, 1.5, 3, 1, 1}[k],
                    new double[] {3, 3, 2, 4, 3, 1}[k],
                    new double[] {0, .8, -.6}[group],
                    new double[] {0, -.4, .9}[group],
                    group == 2 ? "careful" : "horrific",
                    48,
                    29071 + i);
        }
        int k = i % 12;
        int kind = new int[] {0, 1, 3, 0, 1, 10, 11, 12, 13, 14, 15, 9}[k];
        String name =
                new String[] {
                            "full-stairs-up",
                            "full-stairs-down",
                            "broken-blocks",
                            "double-block-up",
                            "double-block-down",
                            "rolling-hills",
                            "two-block-ridges",
                            "forest",
                            "pond-detour",
                            "cliff-detour",
                            "ravine-bridge",
                            "flat"
                        }
                        [k];
        double rise = (k == 3 || k == 4 || k == 6) ? 2 : 1;
        int group = i / 12;
        double angle = new double[] {0, .65, -.85, 1.8, .3, -.3}[group],
                heading = new double[] {0, -.4, 1.2, -2.2, .1, .5}[group];
        String pace = group == 4 ? "careful" : group == 5 ? "fast" : "horrific";
        return new MinecraftMachinesLeaperTerrainCourse(
                name,
                kind,
                rise,
                k == 0 || k == 1 ? 1 : k == 3 || k == 4 ? 2 : 3,
                angle,
                heading,
                pace,
                k == 7 ? 56 : 48,
                1500 + i);
    }

    static class Trial {
        final MinecraftMachinesLeaperTerrainCourse course;
        final MinecraftMachinesLeaper rig;
        final MinecraftMachinesLeaperPredatorNavigator nav;
        final Vector3d start, goal;
        final List<Object> history = new ArrayList<>();
        int tick, settled, arrival, fallen;
        int horizon = 2080;
        double minUp = 1, anchor, limits, path, error, backwardTravel, worstReverseSpeed;
        int kickbacks;
        boolean wasKicking;
        boolean done, success;
        String reason = "";
        Vector3d previous;

        Trial(GameTestHelper helper, int slot, MinecraftMachinesLeaperPredatorPolicy policy) {
            this(helper, slot, policy, course(slot), null);
        }

        Trial(
                GameTestHelper helper,
                int slot,
                MinecraftMachinesLeaperPredatorPolicy policy,
                MinecraftMachinesLeaperTerrainCourse c,
                Vector3d built) {
            course = c;
            start = built == null ? course.build(helper.getLevel(), slot) : new Vector3d(built);
            rig = MinecraftMachinesLeaper.spawn(helper.getLevel(), start);
            rig.reset(course.heading());
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, helper.getLevel(), policy);
            goal = course.goal(start);
            goal.y = nav.map.height(goal.x, goal.z, goal.y + 5);
            previous = rig.position();
            error = course.distance();
            nav.pace = course.pace();
            if (!nav.go(goal.x, goal.y, goal.z)) {
                done = true;
                reason = "planning: " + nav.reason;
            }
        }

        void step() {
            if (done) return;
            ++tick;
            nav.step();
            var p = rig.position();
            var v = rig.velocity();
            error = Math.hypot(p.x - goal.x, p.z - goal.z);
            minUp = Math.min(minUp, rig.upright());
            anchor = Math.max(anchor, rig.anchorError());
            limits = Math.max(limits, rig.limitViolation());
            if (tick > 80) path += Math.hypot(p.x - previous.x, p.z - previous.z);
            if (tick > 80 && nav.tick >= nav.backUntil && error > 3) {
                double gx = (goal.x - p.x) / Math.max(error, 1e-6),
                        gz = (goal.z - p.z) / Math.max(error, 1e-6);
                backwardTravel += Math.max(0, -((p.x - previous.x) * gx + (p.z - previous.z) * gz));
                double reverse = -(v.x * gx + v.z * gz);
                worstReverseSpeed = Math.max(worstReverseSpeed, reverse);
                boolean kicking = reverse > 4;
                if (kicking && !wasKicking) kickbacks++;
                wasKicking = kicking;
            } else wasKicking = false;
            previous = p;
            if (rig.upright() < .5) fallen++;
            else fallen = 0;
            if (!rig.valid()
                    || !Double.isFinite(p.x + p.y + p.z)
                    || fallen > 30
                    || anchor > .12
                    || limits > .15
                    || p.y < 0
                    || Math.hypot(p.x - start.x, p.z - start.z) > 76) {
                done = true;
                reason = "physical failure";
            }
            if (!done && nav.arrived) {
                if (arrival == 0) arrival = tick;
                if (++settled >= 40) {
                    success = true;
                    done = true;
                }
            } else {
                settled = 0;
                arrival = 0;
            }
            if (tick >= horizon && !done) {
                done = true;
                reason = "timeout";
            }
            if (tick % 10 == 0) {
                var row = new LinkedHashMap<String, Object>();
                row.put("tick", tick);
                row.put("position", List.of(p.x, p.y, p.z));
                row.put("speed", Math.hypot(v.x, v.z));
                row.put("up", rig.upright());
                row.put("error", error);
                row.put("ground", nav.control.ground);
                row.put("goal_stance", nav.goalStanceFloor);
                row.put("holding", nav.holdPosition);
                row.put("recovery", nav.tick < nav.backUntil);
                if (tick % 50 == 0) {
                    var feet = new ArrayList<Object>();
                    for (int i = 0; i < 4; i++) {
                        var toe = rig.toe(i);
                        feet.add(List.of(toe.x, toe.y, toe.z));
                    }
                    row.put("feet", feet);
                    row.put("angles", rig.angles());
                }
                history.add(row);
            }
        }

        Map<String, Object> result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("course", course);
            r.put("success", success);
            r.put("reason", reason);
            r.put("error", error);
            r.put("ticks", tick);
            r.put("arrival_seconds", arrival > 0 ? (arrival - 80) * .05 : 0);
            r.put("progress_speed", arrival > 0 ? course.distance() / ((arrival - 80) * .05) : 0);
            r.put("minimum_up", minUp);
            r.put("anchor_error", anchor);
            r.put("limit_violation", limits);
            r.put("path_length", path);
            r.put("backward_travel", backwardTravel);
            r.put("worst_reverse_speed", worstReverseSpeed);
            r.put("kickbacks_over_4bps", kickbacks);
            r.put("early_clearance", nav.control.earlyClearance);
            r.put("coordinated_swing", nav.control.coordinatedSwing);
            r.put("clearance_swings", nav.control.clearanceSwings);
            r.put("shoulder_clamps", nav.control.shoulderClamps);
            r.put("shoulder_maximum", nav.control.shoulderReach);
            r.put("knee_maximum", nav.control.kneeReach);
            r.put(
                    "highstep_settings",
                    MinecraftMachinesLeaperHighStepSettings.effective(nav.control));
            r.put("replans", nav.replans);
            r.put("recoveries", nav.recoveries);
            r.put("planning_ms", nav.totalPlanMillis);
            r.put("rejected_footholds", nav.control.rejectedFootholds);
            r.put("route", nav.route);
            r.put("history", history);
            return r;
        }
    }

    static class Run {
        final GameTestHelper helper;
        final String mode;
        final MinecraftMachinesLeaperPredatorPolicy policy;
        final List<Trial> trials = new ArrayList<>();
        final List<Object> records = new ArrayList<>();
        int batch, tick;
        long began = System.nanoTime();

        Run(GameTestHelper h, String mode) throws Exception {
            helper = h;
            this.mode = mode;
            policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
        }

        void begin() {
            var physics =
                    dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.get(
                            helper.getLevel());
            physics.getConfig().substepsPerTick =
                    Integer.getInteger("minecraft_machines.leaperSubsteps", 2);
            physics.onConfigUpdated();
            batch();
            loop();
        }

        void batch() {
            trials.forEach(t -> t.rig.close());
            trials.clear();
            tick = 0;
            for (int i = 0; i < 6; i++) {
                var trial = new Trial(helper, batch * 6 + i, policy);
                if (mode.contains("baseline")) {
                    trial.nav.control.earlyClearance = trial.nav.control.coordinatedSwing = false;
                }
                trials.add(trial);
            }
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                        } catch (Exception e) {
                            trials.forEach(t -> t.rig.close());
                            helper.fail("Predator routes: " + e);
                        }
                    });
        }

        void step() throws Exception {
            ++tick;
            trials.forEach(Trial::step);
            if (tick % 300 == 0)
                MinecraftMachines.LOGGER.info(
                        "PREDATOR_ROUTE batch={} tick={} states={}",
                        batch,
                        tick,
                        trials.stream()
                                .map(
                                        t ->
                                                t.course.name()
                                                        + ":"
                                                        + String.format(
                                                                Locale.ROOT, "%.1f", t.error)
                                                        + (t.done
                                                                ? t.success
                                                                        ? " PASS"
                                                                        : " FAIL " + t.reason
                                                                : ""))
                                .toList());
            if (trials.stream().anyMatch(t -> !t.done)) {
                loop();
                return;
            }
            trials.forEach(t -> records.add(t.result()));
            MinecraftMachinesLeaperLearning.write(
                    mode,
                    Map.of(
                            "policy",
                            MinecraftMachinesLeaperPredatorPolicy.VERSION,
                            "genome",
                            policy.genome,
                            "balance",
                            trials.getFirst().nav.control.balance,
                            "physics_substeps",
                            Integer.getInteger("minecraft_machines.leaperSubsteps", 2),
                            "trials",
                            records,
                            "wall_seconds",
                            (System.nanoTime() - began) / 1e9));
            if (++batch
                    < (mode.endsWith("certify")
                            ? 12
                            : mode.equals("predator-qualify")
                                    ? 4
                                    : mode.endsWith("challenge") ? 3 : 2)) {
                batch();
                loop();
                return;
            }
            trials.forEach(t -> t.rig.close());
            long successes =
                    records.stream()
                            .filter(r -> Boolean.TRUE.equals(((Map<?, ?>) r).get("success")))
                            .count();
            if (!mode.endsWith("challenge")
                    && !mode.equals("predator-route")
                    && successes != records.size()) {
                helper.fail("Physical navigation: " + successes + "/" + records.size());
                return;
            }
            helper.succeed();
        }
    }
}
