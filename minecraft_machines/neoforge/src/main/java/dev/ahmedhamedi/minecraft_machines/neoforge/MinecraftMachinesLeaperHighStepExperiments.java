package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperHighStepExperiments {
    @GameTest(template = "empty", timeoutTicks = 20000, batch = "leaperHighStepProbe")
    public static void probe(GameTestHelper helper) throws Exception {
        if (MinecraftMachinesLeaperLearning.mode().equals("predator-highstep-diagnose")) {
            diagnose(helper);
            helper.succeed();
            return;
        }
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-highstep-probe")) {
            helper.succeed();
            return;
        }
        new Run(helper).begin();
    }

    static void diagnose(GameTestHelper h) throws Exception {
        var course =
                new MinecraftMachinesLeaperTerrainCourse(
                        "diagnostic", 0, 3, 2, 0, 0, "horrific", 48, 49117);
        var start = course.build(h.getLevel(), 0);
        var map = new MinecraftMachinesLeaperPredatorMap(h.getLevel(), 32, 3.31697111456031, 6.889);
        map.terrainAwareSweep = true;
        var rows = new ArrayList<Object>();
        for (double z = .5; z >= -25; z -= .5) {
            double floor = map.height(.5, z);
            var captures = new ArrayList<Object>();
            var wrapper =
                    new dev.ahmedhamedi.minecraft_machines.content.training.morphology
                            .LeaperPredatorPlanner.Terrain() {
                        public boolean adaptiveStanceSampling() {
                            return true;
                        }

                        public double height(double x, double z) {
                            return map.height(x, z);
                        }

                        public List<Double> surfaces(double x, double z, double min, double max) {
                            return map.surfaces(x, z, min, max);
                        }

                        public boolean bodyClear(double x, double floor, double z) {
                            return true;
                        }

                        public boolean bodyClear(
                                double x, double floor, double z, double gx, double gz) {
                            captures.add(
                                    Map.of(
                                            "floor",
                                            floor,
                                            "gx",
                                            gx,
                                            "gz",
                                            gz,
                                            "body_clear",
                                            map.bodyClear(x, floor, z, gx, gz)));
                            return true;
                        }
                    };
            var planner =
                    new dev.ahmedhamedi.minecraft_machines.content.training.morphology
                            .LeaperPredatorPlanner(wrapper, .5, floor, z);
            double support = planner.stanceFloor(.5, floor, z);
            rows.add(
                    Map.of(
                            "z",
                            z,
                            "floor",
                            floor,
                            "support_ignoring_body",
                            Double.isFinite(support) ? support : -999,
                            "body_queries",
                            captures));
        }
        MinecraftMachinesLeaperLearning.write("predator-highstep-diagnose", Map.of("rows", rows));
    }

    static class Run {
        final GameTestHelper h;
        final MinecraftMachinesLeaperPredatorPolicy policy;
        final List<MinecraftMachinesLeaperPredatorRouteTests.Trial> trials = new ArrayList<>();
        final List<Object> records = new ArrayList<>();
        int scenario, tick;

        Run(GameTestHelper h) throws Exception {
            this.h = h;
            policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
        }

        MinecraftMachinesLeaperPredatorPolicy variant(int v) {

            double[] physical = policy.value.clone();
            physical[12] = new double[] {.08, .04, .06, .08, .06, .04}[v];
            return MinecraftMachinesLeaperPredatorPolicy.physical(physical);
        }

        void begin() {
            var physics =
                    dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.get(h.getLevel());
            physics.getConfig().substepsPerTick = 4;
            physics.onConfigUpdated();
            batch();
            loop();
        }

        void batch() {
            trials.forEach(t -> t.rig.close());
            trials.clear();
            tick = 0;
            for (int v = 0; v < 6; v++) {
                int kind = new int[] {0, 0, 3, 1, 9, 11, 0, 0}[scenario];
                var course =
                        new MinecraftMachinesLeaperTerrainCourse(
                                "development-" + scenario,
                                kind,
                                new double[] {3, 2, 1.5, 3, 0, 3, 3, 3}[scenario],
                                new double[] {2, 2, 2, 3, 4, 4, 3, 2}[scenario],
                                new double[] {0, .35, -.25, .2, 0, -.35, .2, -.2}[scenario],
                                new double[] {0, -.2, .3, 0, 0, .4, -.3, .2}[scenario],
                                scenario >= 5 ? "careful" : "horrific",
                                48,
                                49117 + scenario);
                var t =
                        new MinecraftMachinesLeaperPredatorRouteTests.Trial(
                                h, scenario * 6 + v, variant(v), course, null);
                t.nav.control.extraLift = new double[] {1.4, 1.4, 1.4, 1.1, 1.1, 1.1}[v];
                t.nav.control.highStep = new double[] {0, 0, 0, 0, 0, 0}[v];
                t.nav.control.adaptiveLift = false;
                t.nav.control.paceLift = v >= 0;
                t.nav.control.postClearanceLift = v >= 0;
                t.nav.control.liftLead = .4;
                t.nav.control.worldSwing = false;
                t.nav.control.adaptiveFootholds = false;
                t.rig.motorForceScale = 1;
                if (v >= 0) {
                    t.rig.predatorReach(1.4, 1.2);
                    t.nav.control.shoulderReach = 1.4;
                    t.nav.control.kneeReach = 1.2;
                }
                t.nav.control.worldSwing = false;
                t.rig.motorDampingScale = 1;
                t.nav.control.terrainAwareSweep = v >= 0;
                if (t.done && v >= 0 && t.nav.go(t.goal.x, t.goal.y, t.goal.z)) {
                    t.done = false;
                    t.reason = "";
                }
                trials.add(t);
            }
        }

        void loop() {
            h.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                        } catch (Exception e) {
                            trials.forEach(t -> t.rig.close());
                            h.fail(e.toString());
                        }
                    });
        }

        void step() throws Exception {
            tick++;
            trials.forEach(t -> t.step());
            if (tick % 400 == 0)
                MinecraftMachines.LOGGER.info(
                        "HIGHSTEP_PROBE scenario={} tick={} results={}",
                        scenario,
                        tick,
                        trials.stream()
                                .map(
                                        t ->
                                                String.format(
                                                        Locale.ROOT,
                                                        "v%d %.1f %s",
                                                        trials.indexOf(t),
                                                        t.error,
                                                        t.done ? t.success ? "PASS" : "FAIL" : ""))
                                .toList());
            if (trials.stream().anyMatch(t -> !t.done)) {
                loop();
                return;
            }
            for (var t : trials) {
                var r = t.result();
                r.put("variant", trials.indexOf(t));
                r.put("pace_lift", t.nav.control.paceLift);
                r.put("post_clearance_lift", t.nav.control.postClearanceLift);
                r.put("shoulder_maximum", t.nav.control.shoulderReach);
                r.put("knee_maximum", t.nav.control.kneeReach);
                r.put("ascent_rate_scale", t.nav.control.ascentRateScale);
                r.put("body_lookahead_rise", t.nav.control.bodyLookaheadRise);
                r.put("motor_force_scale", t.rig.motorForceScale);
                r.put("motor_damping_scale", t.rig.motorDampingScale);
                r.put("lift_lead", t.nav.control.liftLead);
                r.put("adaptive_footholds", t.nav.control.adaptiveFootholds);
                r.put("world_swing", t.nav.control.worldSwing);
                r.put("adaptive_lift", t.nav.control.adaptiveLift);
                r.put("terrain_aware_sweep", t.nav.control.terrainAwareSweep);
                r.put("genome", t.nav.control.policy.genome);
                r.put("extra_lift", t.nav.control.extraLift);
                r.put("high_step", t.nav.control.highStep);
                r.put("clearance_swings", t.nav.control.clearanceSwings);
                r.put("shoulder_clamps", t.nav.control.shoulderClamps);
                records.add(r);
            }
            MinecraftMachinesLeaperLearning.write(
                    "predator-highstep-probe",
                    Map.of(
                            "policy",
                            MinecraftMachinesLeaperPredatorPolicy.VERSION,
                            "genome",
                            policy.genome,
                            "trials",
                            records,
                            "balance",
                            1,
                            "physics_substeps",
                            4));
            if (++scenario < 8) {
                batch();
                loop();
            } else {
                trials.forEach(t -> t.rig.close());
                h.succeed();
            }
        }
    }
}
