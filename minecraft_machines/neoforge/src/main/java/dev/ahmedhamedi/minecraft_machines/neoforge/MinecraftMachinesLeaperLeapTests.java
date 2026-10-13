package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

/** Native development probes; retained failures are data, not a qualification claim. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperLeapTests {
    @GameTest(template = "empty", timeoutTicks = 4000, batch = "leaperLeap")
    public static void probe(GameTestHelper h) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!Set.of("predator-leap-probe", "predator-agile-walk", "predator-pounce-elevation")
                .contains(mode)) {
            h.succeed();
            return;
        }
        new Run(h, mode).begin();
    }

    static class Trial {
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        String name;
        Vector3d goal;
        final boolean elevation;
        final Map<String, Object> fixture = new LinkedHashMap<>();
        final List<String> nonfinite = new ArrayList<>();
        Vector3d finalPosition, finalVelocity;
        boolean physicalFailure;
        final List<Object> history = new ArrayList<>();
        int ticks, settled, arrivalTick;
        double anchor, limits, minUp = 1, maxVerticalSpeed, verticalTravel, lastY;
        boolean done, success;
        String reason = "";

        Trial(GameTestHelper h, int i, String mode) throws Exception {
            elevation = mode.equals("predator-pounce-elevation");
            name = "elevation-" + i;
            try {
                var level = h.getLevel();
                Vector3d start;
                if (elevation) {
                    start = elevationCourse(h, i);
                } else if (mode.equals("predator-agile-walk")) {
                    int family = i / 2;
                    var course =
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "walk-" + i,
                                    family == 0 ? 9 : 0,
                                    family,
                                    3,
                                    i % 2 == 0 ? 0 : .45,
                                    i % 2 == 0 ? 0 : -.7,
                                    "horrific",
                                    40,
                                    84300 + i);
                    start = course.build(level, i);
                    goal = course.goal(start);
                    name = course.name();
                } else {
                    name =
                            new String[] {
                                        "flat-far",
                                        "diagonal-far",
                                        "up-two",
                                        "down-two",
                                        "gap-five",
                                        "near-no-leap",
                                        "roof-no-leap"
                                    }
                                    [i];
                    start =
                            MinecraftMachinesLeaperLearning.arena(
                                    level, i, Blocks.SMOOTH_STONE, 96);
                    int ox = (int) Math.floor(start.x), oz = (int) Math.floor(start.z);
                    for (int x = -96; x <= 96; x++)
                        for (int z = -96; z <= 96; z++) {
                            if ((i == 2 && z <= -8) || (i == 3 && z > -8))
                                for (int y = 7; y <= 8; y++)
                                    level.setBlockAndUpdate(
                                            new BlockPos(ox + x, y, oz + z),
                                            Blocks.SMOOTH_STONE.defaultBlockState());
                            if (i == 4 && z <= -8 && z >= -12)
                                level.setBlockAndUpdate(
                                        new BlockPos(ox + x, 6, oz + z),
                                        Blocks.AIR.defaultBlockState());
                            if (i == 6 && z <= -3 && z >= -26 && Math.abs(x) <= 15)
                                level.setBlockAndUpdate(
                                        new BlockPos(ox + x, 15, oz + z),
                                        Blocks.SMOOTH_STONE.defaultBlockState());
                        }
                    if (i == 3) start.y += 2;
                    goal =
                            new Vector3d(
                                    start.x + (i == 1 ? 24 : 0),
                                    i == 2 ? 9 : 7,
                                    start.z - (i == 5 ? 6 : i == 1 ? 32 : 40));
                }
                rig = MinecraftMachinesLeaper.spawn(level, start);
                rig.reset(i % 2 == 0 ? 0 : -.7);
                nav =
                        new MinecraftMachinesLeaperPredatorNavigator(
                                rig, level, MinecraftMachinesLeaperPredatorExperiments.candidate());
                nav.pace = "horrific";
                if (elevation) {
                    if (!nav.leap.enabled || !nav.control.agile)
                        throw new IllegalStateException(
                                "Elevation requires actual leap/agile enabled");
                    var masses = MinecraftMachinesLeaperHighStepBenchmark.masses(rig);
                    if (masses.size() != 13)
                        throw new IllegalStateException("Expected13 standard bodies");
                    double total = 0;
                    for (int body = 0; body < masses.size(); body++) {
                        var row = (Map<?, ?>) masses.get(body);
                        double mass = ((Number) row.get("mass")).doubleValue();
                        double expected = body == 0 ? 27 : new double[] {1, 8, 6}[(body - 1) % 3];
                        if (!Boolean.TRUE.equals(row.get("finite"))
                                || !Double.isFinite(mass)
                                || Math.abs(mass - expected) > 1e-6)
                            throw new IllegalStateException(
                                    "Nonstandard native mass/inertia body " + body);
                        total += mass;
                    }
                    fixture.put("mass", total);
                    fixture.put("body_masses", masses);
                    fixture.put("gravity", vector(rig.gravity()));
                    fixture.put("air_drag", rig.airDrag());
                    fixture.put("spawn", vector(start));
                    fixture.put("genome", nav.control.policy.genome);
                    fixture.put("parameters", nav.control.policy.named());
                }
                lastY = rig.position().y;
                if (elevation) captureFinal();
                if (!nav.go(goal.x, goal.y, goal.z)) {
                    done = true;
                    reason = elevation ? "goal rejected: " + nav.reason : nav.reason;
                }
            } catch (Exception error) {
                if (!elevation) throw error;
                done = true;
                success = false;
                reason = "Fixture/setup exception: " + error;
                captureFinal();
                if (rig != null)
                    try {
                        rig.close();
                    } catch (Exception ignored) {
                    }
            }
        }

        Vector3d elevationCourse(GameTestHelper h, int index) {
            int change = new int[] {4, 8, 12, -4, -8, -16}[index / 2];
            double angle = index % 2 == 0 ? 0 : .35;
            double sin = Math.sin(angle), cos = Math.cos(angle);
            double ox = (index % 4) * 576 + .5, oz = (index / 4) * 576 + .5;
            int initialFloor = 7 + Math.max(0, -change), landingFloor = initialFloor + change;
            name =
                    "elevation-"
                            + (change > 0 ? "up-" : "down-")
                            + Math.abs(change)
                            + (index % 2 == 0 ? "-straight" : "-diagonal");
            fixture.put("slot", index);
            fixture.put("angle", angle);
            fixture.put("initial_heading", index % 2 == 0 ? 0 : -.7);
            fixture.put("requested_elevation", change);
            fixture.put("origin", new double[] {ox, oz});
            fixture.put("course_domain_d", new double[] {-32, 104});
            fixture.put("course_domain_l", new double[] {-48, 48});
            fixture.put("bank_start_d", 12);
            fixture.put("target_d", 64);
            fixture.put("runout_beyond_target", 40);
            fixture.put("gap", false);
            fixture.put("solid_bottom_y", -16);
            fixture.put("cleared_through_y", 55);
            var level = h.getLevel();
            double minX = Double.POSITIVE_INFINITY, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
            for (double d : new double[] {-32, 104})
                for (double l : new double[] {-48, 48}) {
                    double x = ox + d * sin + l * cos, z = oz - d * cos + l * sin;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            for (int x = ((int) Math.floor(minX) - 2) >> 4;
                    x <= ((int) Math.ceil(maxX) + 2) >> 4;
                    x++)
                for (int z = ((int) Math.floor(minZ) - 2) >> 4;
                        z <= ((int) Math.ceil(maxZ) + 2) >> 4;
                        z++) level.setChunkForced(x, z, true);
            int columns = 0;
            for (int x = (int) Math.floor(minX); x <= (int) Math.ceil(maxX); x++)
                for (int z = (int) Math.floor(minZ); z <= (int) Math.ceil(maxZ); z++) {
                    double d = (x + .5 - ox) * sin - (z + .5 - oz) * cos;
                    double l = (x + .5 - ox) * cos + (z + .5 - oz) * sin;
                    if (d < -32 || d >= 104 || l < -48 || l >= 48) continue;
                    int top = d >= 12 ? landingFloor : initialFloor;
                    for (int y = -16; y <= 55; y++) {
                        var pos = new BlockPos(x, y, z);
                        var state =
                                y < top
                                        ? Blocks.SMOOTH_STONE.defaultBlockState()
                                        : Blocks.AIR.defaultBlockState();
                        if (!level.getBlockState(pos).equals(state))
                            level.setBlockAndUpdate(pos, state);
                        if (!level.getBlockState(pos).equals(state))
                            throw new IllegalStateException(
                                    "Elevation fixture block mismatch " + pos);
                    }
                    columns++;
                }
            var map = new MinecraftMachinesLeaperTerrainMap(level);
            map.exposedSurfaces = true;
            double ceiling = Math.max(initialFloor, landingFloor) + 8;
            double startY = map.height(ox, oz, initialFloor + 8);
            double gx = ox + 64 * sin, gz = oz - 64 * cos;
            double goalY = map.height(gx, gz, landingFloor + 8);
            if (!Double.isFinite(startY)
                    || !Double.isFinite(goalY)
                    || Math.abs(goalY - startY - change) > 1e-6)
                throw new IllegalStateException("Native exposed elevation differs from fixture");
            goal = new Vector3d(gx, goalY, gz);
            fixture.put("verified_columns", columns);
            fixture.put("actual_start_floor", startY);
            fixture.put("actual_goal_floor", goalY);
            fixture.put("actual_elevation", goalY - startY);
            return new Vector3d(ox, startY + MinecraftMachinesLeaper.HEIGHT, oz);
        }

        static double[] vector(Vector3d v) {
            return new double[] {v.x, v.y, v.z};
        }

        void captureFinal() {
            if (rig == null) return;
            try {
                finalPosition = rig.position();
                finalVelocity = rig.velocity();
            } catch (Exception ignored) {
            }
        }

        void step() {
            if (done) return;
            ticks++;
            nav.step();
            var p = rig.position();
            var v = rig.velocity();
            finalPosition = new Vector3d(p);
            finalVelocity = new Vector3d(v);
            anchor = Math.max(anchor, rig.anchorError());
            limits = Math.max(limits, rig.limitViolation());
            minUp = Math.min(minUp, rig.upright());
            maxVerticalSpeed = Math.max(maxVerticalSpeed, Math.abs(v.y));
            if (ticks > 80) verticalTravel += Math.abs(p.y - lastY);
            lastY = p.y;
            if (elevation || ticks % 2 == 0)
                history.add(
                        Map.of(
                                "tick",
                                ticks,
                                "position",
                                new double[] {p.x, p.y, p.z},
                                "velocity",
                                new double[] {v.x, v.y, v.z},
                                "state",
                                nav.leap.state.toString(),
                                "heading",
                                rig.heading(),
                                "error",
                                Math.hypot(p.x - goal.x, p.z - goal.z),
                                "anchor",
                                rig.anchorError(),
                                "supports",
                                nav.leap.supports()));
            if (!p.isFinite()
                    || !v.isFinite()
                    || !rig.valid()
                    || anchor > .12
                    || limits > .15
                    || rig.upright() < .45) {
                done = true;
                physicalFailure = true;
                reason = "physical failure";
                return;
            }
            if (nav.arrived
                    && (!elevation
                            || (!nav.leap.inAir()
                                    && nav.pendingGoal == null
                                    && goal.equals(nav.goal)))
                    && Math.hypot(p.x - goal.x, p.z - goal.z)
                            <= MinecraftMachinesLeaperPredatorNavigator.ARRIVAL_RELEASE_RADIUS
                    && Math.hypot(v.x, v.z) < .5) settled++;
            else settled = 0;
            if (settled >= 40) {
                done = success = true;
                arrivalTick = ticks - 39;
            }
            if (ticks >= 1680 && (!elevation || !done)) {
                done = true;
                reason = "timeout";
            }
        }

        Object diagnostic(Object value, String path) {
            if (value instanceof Number n && !Double.isFinite(n.doubleValue())) {
                if (!nonfinite.contains(path)) nonfinite.add(path);
                return n.toString();
            }
            if (value instanceof Map<?, ?> map) {
                var out = new LinkedHashMap<String, Object>();
                for (var e : map.entrySet())
                    out.put(
                            e.getKey().toString(),
                            diagnostic(e.getValue(), path + "." + e.getKey()));
                return out;
            }
            if (value instanceof Iterable<?> values) {
                var out = new ArrayList<Object>();
                for (var v : values) out.add(diagnostic(v, path + "[" + out.size() + "]"));
                return out;
            }
            if (value != null && value.getClass().isArray()) {
                var out = new ArrayList<Object>();
                for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++)
                    out.add(
                            diagnostic(
                                    java.lang.reflect.Array.get(value, i), path + "[" + i + "]"));
                return out;
            }
            if (value != null && value.getClass().isRecord()) {
                var out = new LinkedHashMap<String, Object>();
                for (var component : value.getClass().getRecordComponents()) {
                    try {
                        out.put(
                                component.getName(),
                                diagnostic(
                                        component.getAccessor().invoke(value),
                                        path + "." + component.getName()));
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException(
                                "Cannot serialize native event record " + path, error);
                    }
                }
                return out;
            }
            return value;
        }

        Map<String, Object> result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("case", name);
            r.put("success", success);
            r.put("reason", reason);
            r.put("ticks", ticks);
            r.put("arrival_seconds", arrivalTick > 0 ? (arrivalTick - 80) * .05 : 0);
            r.put("anchor_error", anchor);
            r.put("limit_violation", limits);
            r.put("minimum_up", minUp);
            r.put("maximum_vertical_speed", maxVerticalSpeed);
            r.put("vertical_travel", verticalTravel);
            r.put("launches", nav == null ? 0 : nav.leap.launches);
            r.put("terrain_assist_impulses", rig == null ? 0 : rig.terrainAssistImpulses);
            r.put("terrain_assist_delta_total", rig == null ? 0 : rig.terrainAssistDeltaTotal);
            r.put(
                    "terrain_assist_max_horizontal",
                    rig == null ? 0 : rig.terrainAssistMaximumHorizontal);
            r.put(
                    "terrain_assist_max_vertical",
                    rig == null ? 0 : rig.terrainAssistMaximumVertical);
            r.put("landings", nav == null ? 0 : nav.leap.landings);
            r.put("aborts", nav == null ? 0 : nav.leap.aborts);
            r.put("goal", goal == null ? null : vector(goal));
            r.put("events", nav == null ? List.of() : nav.leap.events);
            r.put("history", history);
            if (nav != null) {
                r.put("maximum_request_ms", nav.maximumRequestMillis);
                r.put("maximum_ground_slice_ms", nav.maximumPlanMillis);
                r.put("maximum_leap_slice_ms", nav.leap.maximumSearchMillis);
                r.put("retreats", nav.leap.retreats);
            }
            r.put("walking_parameters", nav == null ? null : nav.control.walkingParameters());
            r.put(
                    "settings",
                    nav == null
                            ? null
                            : MinecraftMachinesLeaperHighStepSettings.effective(nav.control));
            if (elevation) {
                r.put("fixture", fixture);
                r.put("qualified", false);
                r.put("position", finalPosition == null ? null : vector(finalPosition));
                r.put("velocity", finalVelocity == null ? null : vector(finalVelocity));
                r.put("termination", !done ? "running" : success ? "arrived" : reason);
                r.put("physical_failure", physicalFailure);
                r.put("attempted", ticks > 0);
                r.put("complete", done);
                r.put("leap_impulses", rig == null ? List.of() : rig.leapImpulses);
                r.put("trace_hz", 20);
                var safe = new LinkedHashMap<String, Object>();
                for (var entry : r.entrySet())
                    safe.put(entry.getKey(), diagnostic(entry.getValue(), entry.getKey()));
                safe.put("nonfinite_fields", new ArrayList<>(nonfinite));
                safe.put("finite_state", nonfinite.isEmpty());
                if (!nonfinite.isEmpty()) {
                    safe.put("success", false);
                    safe.put("physical_failure", true);
                }
                return safe;
            }
            return r;
        }
    }

    static class Run {
        final GameTestHelper helper;
        final String mode;
        final List<Trial> trials = new ArrayList<>();
        int previousSubsteps;
        boolean configuredPhysics;

        Run(GameTestHelper h, String mode) {
            helper = h;
            this.mode = mode;
        }

        void begin() throws Exception {
            if (mode.equals("predator-pounce-elevation")) {
                if (!Boolean.getBoolean("minecraft_machines.leaperLeap")
                        || !Boolean.getBoolean("minecraft_machines.leaperAgile")
                        || Integer.getInteger("minecraft_machines.leaperSubsteps", 4) != 4
                        || !MinecraftMachinesLeaperLearning.profile().equals("horrific")
                        || !Boolean.parseBoolean(
                                System.getProperty("minecraft_machines.leaperGrip", "true"))
                        || Double.parseDouble(
                                        System.getProperty("minecraft_machines.leaperBalance", "1"))
                                != 1)
                    throw new IllegalStateException(
                            "Elevation mode requires leap/agile true, profileH, native4, grip and"
                                    + " balance1");
                if (!Files.isRegularFile(
                        Path.of(
                                "leaper_learning",
                                MinecraftMachinesLeaperLearning.reportName("predator-candidate")
                                        + ".json")))
                    throw new IllegalStateException(
                            "Explicit unchanged base26 candidate file required");
                var system = SubLevelPhysicsSystem.get(helper.getLevel());
                previousSubsteps = system.getConfig().substepsPerTick;
                system.getConfig().substepsPerTick = 4;
                system.onConfigUpdated();
                configuredPhysics = true;
            }
            for (int i = 0;
                    i
                            < (mode.equals("predator-pounce-elevation")
                                    ? 12
                                    : mode.equals("predator-agile-walk") ? 6 : 7);
                    i++) trials.add(new Trial(helper, i, mode));
            loop();
        }

        void restorePhysics() {
            if (!configuredPhysics) return;
            var system = SubLevelPhysicsSystem.get(helper.getLevel());
            system.getConfig().substepsPerTick = previousSubsteps;
            system.onConfigUpdated();
            configuredPhysics = false;
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            for (var t : trials) {
                                try {
                                    t.step();
                                } catch (Exception error) {
                                    t.done = true;
                                    t.success = false;
                                    t.reason = "Native exception: " + error;
                                    t.physicalFailure = true;
                                    t.captureFinal();
                                    error.printStackTrace();
                                    try {
                                        t.rig.close();
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                            if (trials.stream().allMatch(t -> t.done)) {
                                var result = new LinkedHashMap<String, Object>();
                                result.put("mode", mode);
                                result.put("qualified", false);
                                result.put(
                                        "leap_enabled",
                                        Boolean.getBoolean("minecraft_machines.leaperLeap"));
                                result.put(
                                        "agile",
                                        Boolean.getBoolean("minecraft_machines.leaperAgile"));
                                var observed =
                                        trials.stream().filter(t -> t.rig != null).findFirst();
                                result.put(
                                        "gravity",
                                        observed.isPresent()
                                                ? Trial.vector(observed.get().rig.gravity())
                                                : null);
                                if (mode.equals("predator-pounce-elevation")) {
                                    result.put("expected_trials", 12);
                                    result.put(
                                            "complete",
                                            trials.size() == 12
                                                    && trials.stream().allMatch(t -> t.done));
                                    result.put("control_hz", 20);
                                    result.put("max_trial_ticks", 1680);
                                    result.put(
                                            "physics_config",
                                            SubLevelPhysicsSystem.get(helper.getLevel())
                                                    .getConfig());
                                    result.put(
                                            "purpose",
                                            "Native pounce elevation development; no score or"
                                                    + " qualification claim");
                                }
                                result.put("trials", trials.stream().map(Trial::result).toList());
                                MinecraftMachinesLeaperLearning.write(mode, result);
                                for (var t : trials) if (t.rig != null) t.rig.close();
                                restorePhysics();
                                helper.succeed();
                            } else loop();
                        } catch (Exception e) {
                            for (var t : trials) if (t.rig != null) t.rig.close();
                            restorePhysics();
                            helper.fail(e.toString());
                        }
                    });
        }
    }
}
