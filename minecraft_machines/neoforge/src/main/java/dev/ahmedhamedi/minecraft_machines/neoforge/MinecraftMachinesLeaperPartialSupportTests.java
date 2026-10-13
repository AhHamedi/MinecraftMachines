package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** Remove real terrain under selected feet; no injected controller states or body teleports. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPartialSupportTests {
    static final String MODE = "predator-partial-support";

    @GameTest(template = "empty", timeoutTicks = 5000, batch = "leaperPartialSupport")
    public static void partialSupport(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals(MODE)) {
            helper.succeed();
            return;
        }
        new Run(helper).begin();
    }

    static final class Run {
        final GameTestHelper helper;
        final MinecraftMachinesLeaperPursuitRegressionTests.Run fixtures;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<Map<String, Object>> history = new ArrayList<>();
        MinecraftMachinesLeaperPursuitRegressionTests.Trial fixture;
        int variant, tick, previousSubsteps, partialSamples, settled, launchSupport;
        boolean changed, physical;
        double minimumUp, anchor, finalDistance;
        Vector3d target;

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
            changed = true;
            try {
                initialize();
                loop();
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        void initialize() {
            fixture = new MinecraftMachinesLeaperPursuitRegressionTests.Trial(fixtures, 3);
            fixture.initialize();
            MinecraftMachinesLeaperPursuitRegressionTests.require(
                    !fixture.done, "Fixture: " + fixture.failures);
            fixture.nav.apex.enabled = true;
            tick = partialSamples = settled = 0;
            launchSupport = -1;
            physical = true;
            minimumUp = 1;
            anchor = 0;
            target = new Vector3d(fixture.origin.x + 18, 7, fixture.origin.z - 36);
            history.clear();
        }

        void removeSupport() {
            int ox = (int) Math.floor(fixture.origin.x), oz = (int) Math.floor(fixture.origin.z);
            // Retain 1, 2 or 3 quadrants, leaving the body over a hole. Fourth case
            // removes all footing to prove a falling rig cannot manufacture a jump.
            for (int x = -9; x <= 9; x++)
                for (int z = -9; z <= 9; z++) {
                    boolean keep =
                            variant == 0
                                    ? x >= 1 && z <= -1
                                    : variant == 1
                                            ? Math.abs(x) >= 1 && z <= -1
                                            : variant == 2
                                                    ? Math.abs(x) >= 1
                                                            && Math.abs(z) >= 1
                                                            && !(x < 0 && z > 0)
                                                    : false;
                    if (!keep)
                        for (int y = -16; y < 7; y++)
                            helper.getLevel()
                                    .setBlockAndUpdate(
                                            new BlockPos(ox + x, y, oz + z),
                                            Blocks.AIR.defaultBlockState());
                }
        }

        void step() throws Exception {
            ++tick;
            var nav = fixture.nav;
            var rig = fixture.rig;
            if (tick == 81) {
                removeSupport();
                MinecraftMachinesLeaperPursuitRegressionTests.require(
                        nav.go(target.x, target.y, target.z), "Goal rejected");
            }
            int before = nav.leap.supports(), launches = nav.leap.launches;
            nav.step();
            if (nav.leap.launches > launches && launchSupport < 0) launchSupport = before;
            if (tick > 81 && nav.leap.launches == 0 && before > 0 && before < 4) partialSamples++;
            var p = rig.corePivot();
            var v = rig.assemblyVelocity();
            finalDistance = Math.hypot(p.x - target.x, p.z - target.z);
            minimumUp = Math.min(minimumUp, rig.upright());
            anchor = Math.max(anchor, rig.anchorError());
            physical &=
                    rig.valid()
                            && p.isFinite()
                            && v.isFinite()
                            && rig.anchorError() < .12
                            && rig.limitViolation() < .15
                            && rig.upright() > .45;
            settled = finalDistance < 3 && v.length() < 1 && before > 0 ? settled + 1 : 0;
            if (tick > 80 && tick % 2 == 0)
                history.add(
                        Map.of(
                                "tick",
                                tick,
                                "supports",
                                before,
                                "position",
                                MinecraftMachinesLeaperPursuitRegressionTests.xyz(p),
                                "state",
                                nav.leap.state.toString(),
                                "ready",
                                nav.leap.footing.ready(),
                                "heading",
                                rig.heading(),
                                "up",
                                rig.upright(),
                                "reason",
                                nav.reason));
            boolean done = variant == 3 ? tick >= 110 : !physical || settled >= 20 || tick >= 1100;
            if (!done) return;
            boolean success =
                    variant == 3
                            ? nav.leap.launches == 0 && rig.corePivot().y < fixture.origin.y - 3
                            : physical
                                    && settled >= 20
                                    && partialSamples >= 2
                                    && launchSupport >= 0
                                    && launchSupport < 4;
            var row = new LinkedHashMap<String, Object>();
            row.put(
                    "case",
                    variant == 3
                            ? "no-support-freefall"
                            : "takeoff-" + (variant + 1) + "-quadrants");
            row.put("success", success);
            row.put("launch_supports", launchSupport);
            row.put("partial_samples", partialSamples);
            row.put("launches", nav.leap.launches);
            row.put("final_distance", finalDistance);
            row.put("minimum_up", minimumUp);
            row.put("anchor", anchor);
            row.put("settled_ticks", settled);
            row.put("history", List.copyOf(history));
            row.put("events", List.copyOf(nav.leap.events));
            rows.add(row);
            fixture.close();
            boolean complete = ++variant == 4;
            MinecraftMachinesLeaperLearning.write(
                    MODE,
                    Map.of(
                            "complete",
                            complete,
                            "trials",
                            rows,
                            "support_evidence",
                            "Native toe/surface proximity, not contact-force proof"));
            if (complete) {
                close();
                helper.assertTrue(
                        rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success"))),
                        "Partial-support failures; inspect " + MODE + " report");
                helper.succeed();
            } else initialize();
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                            if (variant < 4) loop();
                        } catch (Exception e) {
                            close();
                            helper.fail(e.toString());
                        }
                    });
        }

        void close() {
            if (fixture != null) fixture.close();
            if (changed) {
                var system = SubLevelPhysicsSystem.get(helper.getLevel());
                system.getConfig().substepsPerTick = previousSubsteps;
                system.onConfigUpdated();
                changed = false;
            }
        }
    }
}
