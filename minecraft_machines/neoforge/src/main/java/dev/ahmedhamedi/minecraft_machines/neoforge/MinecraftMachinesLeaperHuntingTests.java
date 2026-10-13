package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Sustained moving prey: success must happen before the player stops. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperHuntingTests {
    static final String MODE = "predator-hunting-regression";
    static final String[] CASES = {
        "walking-prey", "sprinting-prey", "weaving-prey", "right-angle-escape", "wall-detour"
    };
    static final int START = 81, END = START + 320;

    @GameTest(template = "empty", timeoutTicks = 2500, batch = "leaperHuntingRegression")
    public static void hunting(GameTestHelper helper) throws Exception {
        if (!Set.of(MODE, MODE + "-baseline").contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed();
            return;
        }
        var run = new Run(helper);
        try {
            run.begin();
        } catch (Exception e) {
            run.close();
            throw e;
        }
    }

    static final class Run {
        final GameTestHelper helper;
        final MinecraftMachinesLeaperPursuitRegressionTests.Run fixtures;
        final List<Map<String, Object>> results = new ArrayList<>();
        final List<Map<String, Object>> history = new ArrayList<>();
        final List<Double> timings = new ArrayList<>();
        MinecraftMachinesLeaperPursuitRegressionTests.Trial fixture;
        int id, tick, previousSubsteps, captures, heldWhileMoving;
        boolean physicsChanged;
        double totalGap, finalGap, anchor, limit, minUp = 1;
        int gapSamples;

        Run(GameTestHelper helper) {
            this.helper = helper;
            fixtures = new MinecraftMachinesLeaperPursuitRegressionTests.Run(helper, MODE);
        }

        void begin() throws Exception {
            fixtures.policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
            var system = SubLevelPhysicsSystem.get(helper.getLevel());
            previousSubsteps = system.getConfig().substepsPerTick;
            system.getConfig().substepsPerTick = 4;
            system.onConfigUpdated();
            physicsChanged = true;
            initialize();
            loop();
        }

        void initialize() {
            fixture = new MinecraftMachinesLeaperPursuitRegressionTests.Trial(fixtures, 3);
            fixture.initialize();
            MinecraftMachinesLeaperPursuitRegressionTests.require(
                    !fixture.done, "Fixture: " + fixture.failures);
            fixture.nav.pace = "horrific";
            if (id == 4) {
                int ox = (int) Math.floor(fixture.origin.x),
                        oz = (int) Math.floor(fixture.origin.z);
                for (int x = ox - 8; x <= ox + 8; x++)
                    for (int z = oz - 41; z <= oz - 40; z++)
                        for (int y = 7; y < 21; y++)
                            helper.getLevel()
                                    .setBlockAndUpdate(
                                            new BlockPos(x, y, z),
                                            Blocks.STONE.defaultBlockState());
            }
            tick = captures = heldWhileMoving = gapSamples = 0;
            totalGap = finalGap = anchor = limit = 0;
            minUp = 1;
            history.clear();
            timings.clear();
        }

        void step() {
            tick++;
            var nav = fixture.nav;
            var rig = fixture.rig;
            double seconds = Math.max(0, tick - START) * .05;
            double x = fixture.origin.x + (id == 2 ? 8 * Math.sin(seconds * .65) : 0);
            double z = fixture.origin.z - 12 - (id == 1 ? 5.6 : 4.3) * seconds;
            if (id == 3) {
                x = fixture.origin.x + 5.6 * Math.max(0, seconds - 8);
                z = fixture.origin.z - 12 - 5.6 * Math.min(seconds, 8);
            }
            if (id == 4) x += 18 * Math.sin(Math.PI * Math.clamp((seconds - 1) / 10, 0, 1));
            long began = System.nanoTime();
            if (tick >= START && (tick - START) % 4 == 0) nav.pursue(x, 7, z);
            nav.step();
            timings.add((System.nanoTime() - began) / 1e6);
            var p = rig.corePivot();
            finalGap = Math.hypot(x - p.x, z - p.z);
            anchor = Math.max(anchor, rig.anchorError());
            limit = Math.max(limit, rig.limitViolation());
            minUp = Math.min(minUp, rig.upright());
            if (tick >= START + 160) {
                totalGap += finalGap;
                gapSamples++;
                if (finalGap <= 4
                        && Math.abs(p.y - 7 - fixtures.policy.value[4]) < 1.5
                        && nav.leap.supports() >= 1) captures++;
                if (nav.holdPosition) heldWhileMoving++;
            }
            if (tick % 4 == 0) {
                var row = new LinkedHashMap<String, Object>();
                row.put("tick", tick);
                row.put("player", new double[] {x, 7, z});
                row.put("core", MinecraftMachinesLeaperPursuitRegressionTests.xyz(p));
                row.put("gap", finalGap);
                row.put("state", nav.leap.state.toString());
                row.put("planning", nav.planning != null);
                row.put("hold", nav.holdPosition);
                row.put("super_drive", nav.control.superDrive);
                row.put(
                        "goal",
                        nav.goal == null
                                ? null
                                : MinecraftMachinesLeaperPursuitRegressionTests.xyz(nav.goal));
                history.add(row);
            }
            MinecraftMachinesLeaperPursuitRegressionTests.require(
                    rig.valid()
                            && p.isFinite()
                            && rig.velocity().isFinite()
                            && anchor < .12
                            && limit < .15
                            && minUp > .45,
                    "Native physics guard");
        }

        void finish() throws Exception {
            var row = new LinkedHashMap<String, Object>();
            row.put("case", CASES[id]);
            row.put(
                    "success",
                    captures >= 10
                            && totalGap / gapSamples <= 8
                            && finalGap <= 8
                            && MinecraftMachinesLeaperPursuitRegressionTests.percentile(
                                            timings, .95)
                                    <= 10);
            row.put("mean_second_half_gap", totalGap / gapSamples);
            row.put("final_gap", finalGap);
            row.put("grounded_capture_ticks", captures);
            row.put("held_while_prey_moving_ticks", heldWhileMoving);
            row.put(
                    "p95_navigation_millis",
                    MinecraftMachinesLeaperPursuitRegressionTests.percentile(timings, .95));
            row.put(
                    "max_navigation_millis",
                    MinecraftMachinesLeaperPursuitRegressionTests.percentile(timings, 1));
            row.put("anchor", anchor);
            row.put("limit", limit);
            row.put("minimum_up", minUp);
            row.put("plans", fixture.nav.plans);
            row.put("launches", fixture.nav.leap.launches);
            row.put("history", List.copyOf(history));
            results.add(row);
            fixture.close();
            MinecraftMachinesLeaperLearning.write(
                    MinecraftMachinesLeaperLearning.mode(),
                    Map.of(
                            "fixture_version",
                            "hunting-v2",
                            "complete",
                            id == CASES.length - 1,
                            "qualified",
                            false,
                            "control_hz",
                            20,
                            "player_update_ticks",
                            4,
                            "moving_seconds",
                            16,
                            "genome",
                            fixtures.policy.genome,
                            "trials",
                            results));
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                            if (tick == END) {
                                finish();
                                if (++id == CASES.length) {
                                    close();
                                    if (MinecraftMachinesLeaperLearning.mode().endsWith("-baseline")
                                            || results.stream()
                                                    .allMatch(
                                                            r ->
                                                                    Boolean.TRUE.equals(
                                                                            r.get("success"))))
                                        helper.succeed();
                                    else
                                        helper.fail(
                                                "Sustained hunt failed: "
                                                        + results.stream()
                                                                .filter(
                                                                        r ->
                                                                                !Boolean.TRUE
                                                                                        .equals(
                                                                                                r
                                                                                                        .get(
                                                                                                                "success")))
                                                                .map(r -> r.get("case"))
                                                                .toList());
                                    return;
                                }
                                initialize();
                            }
                            loop();
                        } catch (Exception e) {
                            close();
                            helper.fail(e.toString());
                        }
                    });
        }

        void close() {
            if (fixture != null) fixture.close();
            if (physicsChanged) {
                var system = SubLevelPhysicsSystem.get(helper.getLevel());
                system.getConfig().substepsPerTick = previousSubsteps;
                system.onConfigUpdated();
                physicsChanged = false;
            }
        }
    }
}
