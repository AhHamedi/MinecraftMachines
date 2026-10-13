package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** Paired physical navigation: the same terrain and target, with Classic and Apex controllers. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperApexTests {
    @GameTest(template = "empty", timeoutTicks = 200, batch = "leaperApexCollision")
    public static void collisionHeightmap(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-apex-collision")) {
            helper.succeed();
            return;
        }
        var level = helper.getLevel();
        var origin = MinecraftMachinesLeaperLearning.arena(level, 0, Blocks.SMOOTH_STONE, 24);
        var rows = new ArrayList<Map<String, Object>>();
        java.util.function.BooleanSupplier clear =
                () -> {
                    var map =
                            new MinecraftMachinesLeaperPredatorMap(
                                    level, 64, 3.31697111456031, 8.4);
                    map.terrainAwareSweep = true;
                    return map.bodyClear(origin.x, 7, origin.z);
                };
        rows.add(Map.of("case", "empty-above-floor", "success", clear.getAsBoolean()));
        var blocks =
                new Block[] {
                    Blocks.OAK_LOG, Blocks.OAK_LEAVES,
                    Blocks.IRON_BARS, Blocks.SMOOTH_STONE_SLAB,
                    Blocks.STONE, Blocks.WATER
                };
        String[] names = {
            "trunk", "foliage", "thin-bars", "raised-slab", "chassis-roof", "wet-foothold"
        };
        for (int i = 0; i < blocks.length; i++) {
            var pos =
                    BlockPos.containing(
                            origin.x + (i == 4 ? 0 : i == 5 ? 6 : 5),
                            i < 2 ? 7 : i < 4 ? 13 : i == 4 ? 11 : 6,
                            origin.z);
            var previous = level.getBlockState(pos);
            try {
                level.setBlockAndUpdate(pos, blocks[i].defaultBlockState());
                rows.add(Map.of("case", names[i] + "-inserted", "success", !clear.getAsBoolean()));
            } finally {
                level.setBlockAndUpdate(pos, previous);
            }
            rows.add(Map.of("case", names[i] + "-removed", "success", clear.getAsBoolean()));
        }
        MinecraftMachinesLeaperLearning.write(
                "predator-apex-collision", Map.of("complete", true, "trials", rows));
        helper.assertTrue(
                rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success"))),
                "Live heightmap collision checks: " + rows);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 7600, batch = "leaperApex")
    public static void compare(GameTestHelper helper) throws Exception {
        if (!Set.of("predator-apex-probe", "predator-apex-qualify")
                .contains(MinecraftMachinesLeaperLearning.mode())) {
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
        final List<Map<String, Object>> results = new ArrayList<>(), history = new ArrayList<>();
        final List<Double> timings = new ArrayList<>();
        MinecraftMachinesLeaperPursuitRegressionTests.Trial fixture;
        int variant, tick, entryTick, stable, cruiseStart, cruiseEnd, previousSubsteps;
        boolean changed;
        double anchor, limit, minUp, maxHeight, minHeight, maxSpeed, finalDistance, cruiseDistance;
        Vector3d start;
        double directionX, directionZ;

        Run(GameTestHelper helper) {
            this.helper = helper;
            fixtures =
                    new MinecraftMachinesLeaperPursuitRegressionTests.Run(
                            helper, MinecraftMachinesLeaperLearning.mode());
        }

        void begin() throws Exception {
            fixtures.policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
            var system = SubLevelPhysicsSystem.get(helper.getLevel());
            previousSubsteps = system.getConfig().substepsPerTick;
            system.getConfig().substepsPerTick = 4;
            system.onConfigUpdated();
            changed = true;
            initialize();
            loop();
        }

        void initialize() {
            fixture = new MinecraftMachinesLeaperPursuitRegressionTests.Trial(fixtures, 3);
            fixture.initialize();
            MinecraftMachinesLeaperPursuitRegressionTests.require(
                    !fixture.done, "Fixture failed: " + fixture.failures);
            fixture.nav.apex.enabled = variant % 2 == 1;
            double heading = new double[] {0, .35, -.7}[variant / 2];
            fixture.rig.reset(heading);
            fixture.nav.lastFacing = heading;
            directionX = Math.sin(heading);
            directionZ = -Math.cos(heading);
            start = new Vector3d(fixture.origin);
            tick = stable = 0;
            entryTick = cruiseStart = cruiseEnd = -1;
            anchor = limit = maxSpeed = cruiseDistance = 0;
            minUp = 1;
            minHeight = Double.POSITIVE_INFINITY;
            maxHeight = Double.NEGATIVE_INFINITY;
            history.clear();
            timings.clear();
        }

        void step() throws Exception {
            ++tick;
            var nav = fixture.nav;
            var rig = fixture.rig;
            long began = System.nanoTime();
            if (tick == 81) nav.pursue(start.x + 110 * directionX, 7, start.z + 110 * directionZ);
            nav.step();
            timings.add((System.nanoTime() - began) / 1e6);
            var p = rig.corePivot();
            var v = rig.assemblyVelocity();
            finalDistance =
                    Math.hypot(
                            p.x - (start.x + 110 * directionX), p.z - (start.z + 110 * directionZ));
            double progress = (p.x - start.x) * directionX + (p.z - start.z) * directionZ;
            if (cruiseStart < 0 && progress >= 20) {
                cruiseStart = tick;
                cruiseDistance = progress;
            }
            if (cruiseStart >= 0 && cruiseEnd < 0 && progress >= 80) {
                cruiseEnd = tick;
                cruiseDistance = progress - cruiseDistance;
            }
            if (progress >= 20 && progress <= 80) {
                minHeight = Math.min(minHeight, p.y);
                maxHeight = Math.max(maxHeight, p.y);
            }
            maxSpeed = Math.max(maxSpeed, Math.hypot(v.x, v.z));
            anchor = Math.max(anchor, rig.anchorError());
            limit = Math.max(limit, rig.limitViolation());
            minUp = Math.min(minUp, rig.upright());
            if (entryTick < 0 && finalDistance <= 3) entryTick = tick;
            boolean settled =
                    finalDistance <= 3
                            && Math.abs(p.y - 7 - fixtures.policy.value[4]) < 1.25
                            && Math.hypot(v.x, v.z) < 1
                            && Math.abs(v.y) < .75
                            && nav.leap.supports() > 0;
            stable = settled ? stable + 1 : 0;
            if (tick % 2 == 0) {
                var row = new LinkedHashMap<String, Object>();
                row.put("tick", tick);
                row.put("core", MinecraftMachinesLeaperPursuitRegressionTests.xyz(p));
                row.put("velocity", MinecraftMachinesLeaperPursuitRegressionTests.xyz(v));
                row.put("distance", finalDistance);
                row.put("state", nav.leap.state.toString());
                row.put("drive_speed", nav.apex.requestedSpeed);
                row.put("period", nav.control.commandedPeriod);
                row.put("anchor", rig.anchorError());
                row.put("up", rig.upright());
                history.add(row);
            }
            boolean physical =
                    rig.valid()
                            && p.isFinite()
                            && v.isFinite()
                            && anchor < .12
                            && limit < .15
                            && minUp > .45;
            if (!physical || stable >= 30 || tick >= 1200) {
                var result = new LinkedHashMap<String, Object>();
                result.put("variant", variant % 2 == 0 ? "classic" : "apex");
                result.put("case", "flat-110-" + variant / 2);
                result.put(
                        "success",
                        physical
                                && stable >= 30
                                && cruiseEnd > cruiseStart
                                && cruiseStart > 0
                                && nav.leap.launches == 0);
                result.put("ticks", tick);
                result.put("arrival_seconds", entryTick < 0 ? null : (entryTick - 80) * .05);
                result.put(
                        "cruise_speed",
                        cruiseEnd > cruiseStart && cruiseStart > 0
                                ? cruiseDistance / ((cruiseEnd - cruiseStart) * .05)
                                : null);
                result.put("cruise_height_range", cruiseEnd > 0 ? maxHeight - minHeight : null);
                result.put("final_distance", finalDistance);
                result.put("stable_ticks", stable);
                result.put("maximum_speed", maxSpeed);
                result.put("anchor", anchor);
                result.put("limit", limit);
                result.put("minimum_up", minUp);
                result.put(
                        "p95_navigation_millis",
                        MinecraftMachinesLeaperPursuitRegressionTests.percentile(timings, .95));
                result.put(
                        "max_navigation_millis",
                        MinecraftMachinesLeaperPursuitRegressionTests.percentile(timings, 1));
                result.put(
                        "total_navigation_millis",
                        timings.stream().mapToDouble(Double::doubleValue).sum());
                result.put("assistance_impulses", rig.apexImpulses);
                result.put("max_assistance_horizontal", rig.apexMaximumHorizontal);
                result.put("max_assistance_vertical", rig.apexMaximumVertical);
                result.put("launches", nav.leap.launches);
                result.put("history", List.copyOf(history));
                results.add(result);
                fixture.close();
                MinecraftMachinesLeaperLearning.write(
                        MinecraftMachinesLeaperLearning.mode(),
                        Map.of(
                                "complete",
                                variant == 5,
                                "qualified",
                                false,
                                "fixture_version",
                                "apex-paired-v2",
                                "genome",
                                fixtures.policy.genome,
                                "trials",
                                results));
                if (++variant == 6) {
                    close();
                    boolean pass =
                            results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success")));
                    if (pass) {
                        double classic =
                                results.stream()
                                        .filter(r -> r.get("variant").equals("classic"))
                                        .mapToDouble(
                                                r -> ((Number) r.get("cruise_speed")).doubleValue())
                                        .average()
                                        .orElseThrow();
                        double apex =
                                results.stream()
                                        .filter(r -> r.get("variant").equals("apex"))
                                        .mapToDouble(
                                                r -> ((Number) r.get("cruise_speed")).doubleValue())
                                        .average()
                                        .orElseThrow();
                        pass = apex >= 2 * classic;
                    }
                    if (pass
                            || MinecraftMachinesLeaperLearning.mode().equals("predator-apex-probe"))
                        helper.succeed();
                    else helper.fail("Apex must finish and exceed twice Classic cruise speed");
                } else initialize();
            }
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                            if (variant < 6) loop();
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
