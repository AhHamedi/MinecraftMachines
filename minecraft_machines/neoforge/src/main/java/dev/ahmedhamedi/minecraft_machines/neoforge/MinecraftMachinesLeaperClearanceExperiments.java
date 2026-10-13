package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperClearanceExperiments {
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "leaperClearanceProbe")
    public static void probe(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-clearance-probe")) {
            helper.succeed();
            return;
        }
        new Run(helper).begin();
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

        void begin() {
            batch();
            loop();
        }

        void batch() {
            trials.forEach(t -> t.rig.close());
            trials.clear();
            tick = 0;
            for (int v = 0; v < 8; v++) {
                var course =
                        new MinecraftMachinesLeaperTerrainCourse(
                                new String[] {
                                            "three-block-head-on",
                                            "two-block-head-on",
                                            "dense-rubble"
                                        }
                                        [scenario],
                                scenario == 2 ? 3 : 0,
                                scenario == 0 ? 3 : scenario == 1 ? 2 : 1,
                                scenario == 0 ? 3 : scenario == 1 ? 2 : 1,
                                0,
                                0,
                                "horrific",
                                48,
                                49107 + scenario);
                var t =
                        new MinecraftMachinesLeaperPredatorRouteTests.Trial(
                                h, scenario * 8 + v, policy, course, null);
                t.nav.control.earlyClearance = v % 4 >= 2;
                t.nav.control.coordinatedSwing = v % 2 == 1;
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
                        "CLEARANCE_PROBE scenario={} tick={} results={}",
                        scenario,
                        tick,
                        trials.stream()
                                .map(
                                        t ->
                                                String.format(
                                                        Locale.ROOT,
                                                        "v%d %.1f %s",
                                                        (t.nav.control.earlyClearance ? 2 : 0)
                                                                + (t.nav.control.coordinatedSwing
                                                                        ? 1
                                                                        : 0),
                                                        t.error,
                                                        t.done ? t.success ? "PASS" : "FAIL" : ""))
                                .toList());
            if (trials.stream().anyMatch(t -> !t.done)) {
                loop();
                return;
            }
            for (var t : trials) {
                var r = t.result();
                r.put(
                        "variant",
                        (t.nav.control.earlyClearance ? 2 : 0)
                                + (t.nav.control.coordinatedSwing ? 1 : 0));
                r.put("clearance_swings", t.nav.control.clearanceSwings);
                r.put("shoulder_clamps", t.nav.control.shoulderClamps);
                records.add(r);
            }
            MinecraftMachinesLeaperLearning.write(
                    "predator-clearance-probe",
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
            if (++scenario < 3) {
                batch();
                loop();
            } else {
                trials.forEach(t -> t.rig.close());
                h.succeed();
            }
        }
    }
}
