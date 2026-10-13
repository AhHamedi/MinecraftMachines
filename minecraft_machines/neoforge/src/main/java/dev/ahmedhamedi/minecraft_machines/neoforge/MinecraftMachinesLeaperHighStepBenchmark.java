package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Critic-frozen evaluation. Scoring is external; failures are retained without retries. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperHighStepBenchmark {
    @GameTest(template = "empty", timeoutTicks = 22000, batch = "leaperHighStepBenchmark")
    public static void benchmark(GameTestHelper h) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!Set.of("predator-highstep-baseline", "predator-highstep-candidate").contains(mode)) {
            h.succeed();
            return;
        }
        new Run(h, mode).begin();
    }

    private static final String BASELINE_SHA256 =
            "8f9359bbbd5d3162ccd59b58a8dcadf224cdc3d9a0eab61a56c0372ebb586f1e";

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static double number(JsonObject data, String key) {
        var value = data.getAsJsonPrimitive(key);
        if (!value.isNumber() || !Double.isFinite(value.getAsDouble()))
            throw new IllegalArgumentException("Expected finite number: " + key);
        return value.getAsDouble();
    }

    static int integer(JsonObject data, String key) {
        number(data, key);
        return data.get(key).getAsBigDecimal().intValueExact();
    }

    static boolean flag(JsonObject data, String key) {
        if (!data.has(key)) return false;
        if (!data.getAsJsonPrimitive(key).isBoolean())
            throw new IllegalArgumentException("Expected boolean: " + key);
        return data.get(key).getAsBoolean();
    }

    record Fixture(
            int family, int configuration, int slot, MinecraftMachinesLeaperTerrainCourse course) {
        static Fixture read(JsonObject data, boolean continuous) {
            int family = continuous ? -1 : integer(data, "family");
            int configuration = continuous ? -1 : integer(data, "configuration");
            int slot = integer(data, "slot"), kind = integer(data, "kind");
            String pace = data.get("pace").getAsString();
            double rise = number(data, "rise"),
                    tread = number(data, "tread"),
                    distance = number(data, "distance");
            if (slot < 0
                    || kind < 0
                    || kind > 15
                    || rise < 0
                    || tread <= 0
                    || distance != 48
                    || !Set.of("horrific", "careful").contains(pace)
                    || !continuous
                            && (family < 0
                                    || family > 6
                                    || configuration < 0
                                    || configuration > 2
                                    || !pace.equals(configuration == 2 ? "careful" : "horrific")))
                throw new IllegalArgumentException("Invalid frozen fixture: " + data);
            String name =
                    data.has("name")
                            ? data.get("name").getAsString()
                            : continuous
                                    ? "critic-continuous"
                                    : "critic-F" + family + "-C" + configuration;
            return new Fixture(
                    family,
                    configuration,
                    slot,
                    new MinecraftMachinesLeaperTerrainCourse(
                            name,
                            kind,
                            rise,
                            tread,
                            number(data, "angle"),
                            number(data, "heading"),
                            pace,
                            distance,
                            integer(data, "seed")));
        }
    }

    static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    // Invalid diagnostics retain their identity as strings; scoring metrics remain numeric.
    static Object diagnostic(double value) {
        return Double.isFinite(value) ? value : Double.toString(value);
    }

    static List<Object> diagnostic(double... values) {
        var result = new ArrayList<Object>();
        for (double value : values) result.add(diagnostic(value));
        return result;
    }

    static List<Object> diagnostic(Vector3d value) {
        return diagnostic(value.x, value.y, value.z);
    }

    @SuppressWarnings("unchecked")
    static List<Object> masses(MinecraftMachinesLeaper rig) throws Exception {
        var field = MinecraftMachinesLeaper.class.getDeclaredField("bodies");
        field.setAccessible(true);
        var result = new ArrayList<Object>();
        for (var b : (List<ServerSubLevel>) field.get(rig)) {
            var m = b.getMassTracker();
            var center = m.getCenterOfMass();
            var inertia = m.getInertiaTensor().get(new double[9]);
            result.add(
                    Map.of(
                            "mass",
                            diagnostic(m.getMass()),
                            "inverse_mass",
                            diagnostic(m.getInverseMass()),
                            "center",
                            center == null
                                    ? List.of("unavailable")
                                    : diagnostic(center.x(), center.y(), center.z()),
                            "inertia",
                            diagnostic(inertia),
                            "finite",
                            center != null
                                    && finite(
                                            m.getMass(),
                                            m.getInverseMass(),
                                            center.x(),
                                            center.y(),
                                            center.z())
                                    && finite(inertia)));
        }
        return result;
    }

    static final class Episode {
        final MinecraftMachinesLeaperTerrainCourse course;
        final MinecraftMachinesLeaper rig;
        final MinecraftMachinesLeaperPredatorNavigator nav;
        final Vector3d origin, lower, upper;
        final List<Object> massData;
        Vector3d goal, previous;
        final List<Object> history = new ArrayList<>();
        int tick, settleTicks, settled, arrival, fallen, maxFallenTicks, kickbacks, progressTick;
        int replanStart, recoveryStart;
        double error,
                anchor,
                limits,
                minUp = 1,
                backward,
                worstReverse,
                longestStall,
                progressError,
                path;
        boolean done, success, wasKicking, physicalFailure, finiteState = true, valid = true;
        final Map<String, Object> failureDiagnostics = new LinkedHashMap<>();
        String reason = "";

        Episode(
                GameTestHelper h,
                int slot,
                MinecraftMachinesLeaperPredatorPolicy policy,
                MinecraftMachinesLeaperTerrainCourse course,
                double lift,
                double slowDamping,
                double flatCadence,
                double clearGroundGain,
                double clearancePause,
                double stanceCompression,
                boolean adaptive,
                boolean terrain,
                boolean freshSupport,
                boolean exposedSurfaces,
                boolean adaptiveFootholds,
                boolean worldSwingHeight,
                boolean world,
                boolean postClearanceLift,
                boolean paceLift,
                double shoulderMinimum,
                double shoulder,
                double knee)
                throws Exception {
            this.course = course;
            origin = course.build(h.getLevel(), slot);
            if (course.kind() == 3) {
                var end = course.goal(origin);
                int ex = (int) Math.floor(end.x), ez = (int) Math.floor(end.z);
                for (int x = ex - 8; x <= ex + 8; x++)
                    for (int z = ez - 8; z <= ez + 8; z++)
                        for (int y = 7; y <= 10; y++)
                            h.getLevel()
                                    .setBlockAndUpdate(
                                            new net.minecraft.core.BlockPos(x, y, z),
                                            net.minecraft.world.level.block.Blocks.AIR
                                                    .defaultBlockState());
            }
            rig = MinecraftMachinesLeaper.spawn(h.getLevel(), origin);
            rig.reset(course.heading());
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, h.getLevel(), policy);
            nav.control.extraLift = lift;
            nav.control.slowDamping = slowDamping;
            nav.control.flatCadence = flatCadence;
            nav.control.clearGroundGain = clearGroundGain;
            nav.control.clearancePause = clearancePause;
            nav.control.stanceCompression = stanceCompression;
            nav.control.adaptiveLift = adaptive;
            nav.control.terrainAwareSweep = terrain;
            nav.control.freshSupport = freshSupport;
            nav.control.map.exposedSurfaces = exposedSurfaces;
            nav.control.adaptiveFootholds = adaptiveFootholds;
            nav.control.worldSwingHeight = worldSwingHeight;
            nav.control.worldSwing = world;
            nav.control.postClearanceLift = postClearanceLift;
            nav.control.paceLift = paceLift;
            nav.control.shoulderMinimum = shoulderMinimum;
            nav.control.shoulderReach = shoulder;
            nav.control.kneeReach = knee;
            rig.predatorReach(shoulderMinimum, shoulder, knee);
            nav.pace = course.pace();
            lower = new Vector3d(origin.x, 7 + course.height(0, 0), origin.z);
            upper = course.goal(origin);
            upper.y = nav.map.height(upper.x, upper.z, upper.y + 5);
            if (!Double.isFinite(upper.y))
                throw new IllegalStateException("Fixture goal has no surface");
            massData = masses(rig);
            start(upper, 80);
        }

        void start(Vector3d target, int settleTicks) {
            this.settleTicks = settleTicks;
            goal = new Vector3d(target);
            tick = settled = arrival = fallen = maxFallenTicks = kickbacks = 0;
            anchor = limits = backward = worstReverse = longestStall = path = 0;
            minUp = 1;
            error = course.distance();
            progressError = error;
            progressTick = settleTicks;
            replanStart = nav.replans;
            recoveryStart = nav.recoveries;
            done = success = wasKicking = false;
            physicalFailure = false;
            finiteState = valid = true;
            failureDiagnostics.clear();
            reason = "";
            history.clear();
            try {
                var state = observe();
                if (!validate(state)) {
                    record(state);
                    return;
                }
                previous = state.position;
                progressError = error = Math.hypot(previous.x - goal.x, previous.z - goal.z);
                if (!nav.go(goal.x, goal.y, goal.z)) {
                    done = true;
                    reason = "planning: " + nav.reason;
                }
            } catch (Exception e) {
                exception("initialization/planning", e);
            }
        }

        record Observation(
                Vector3d position,
                Vector3d velocity,
                Vector3d up,
                Quaterniond orientation,
                double[] angles,
                double[] rawRequestedAngles,
                double[] motorTargets,
                double anchor,
                double limits,
                boolean valid,
                Vector3d[] toes) {}

        Observation observe() {
            var toes = new Vector3d[4];
            for (int i = 0; i < 4; i++) toes[i] = new Vector3d(rig.toe(i));
            return new Observation(
                    new Vector3d(rig.position()),
                    new Vector3d(rig.velocity()),
                    new Vector3d(rig.up()),
                    rig.coreOrientation(),
                    rig.angles(),
                    nav.control.rawRequestedAngles.clone(),
                    rig.boundedMotorTargets(),
                    rig.anchorError(),
                    rig.limitViolation(),
                    rig.valid(),
                    toes);
        }

        boolean validate(Observation state) {
            var invalid = new LinkedHashMap<String, Object>();
            if (!finite(state.position.x, state.position.y, state.position.z))
                invalid.put("position", diagnostic(state.position));
            if (!finite(state.velocity.x, state.velocity.y, state.velocity.z))
                invalid.put("velocity", diagnostic(state.velocity));
            if (!finite(state.up.x, state.up.y, state.up.z))
                invalid.put("up", diagnostic(state.up));
            if (!finite(
                    state.orientation.x,
                    state.orientation.y,
                    state.orientation.z,
                    state.orientation.w))
                invalid.put(
                        "core_orientation",
                        diagnostic(
                                state.orientation.x,
                                state.orientation.y,
                                state.orientation.z,
                                state.orientation.w));
            if (!finite(state.angles)) invalid.put("joint_angles", diagnostic(state.angles));
            if (!finite(state.rawRequestedAngles))
                invalid.put("raw_requested_angles", diagnostic(state.rawRequestedAngles));
            if (!finite(state.motorTargets))
                invalid.put("bounded_target_angles", diagnostic(state.motorTargets));
            if (!finite(state.anchor)) invalid.put("anchor", diagnostic(state.anchor));
            if (!finite(state.limits)) invalid.put("limits", diagnostic(state.limits));
            for (int i = 0; i < 4; i++) {
                var toe = state.toes[i];
                if (!finite(toe.x, toe.y, toe.z)) invalid.put("toe_" + i, diagnostic(toe));
            }
            if (!finite(goal.x, goal.y, goal.z)) invalid.put("goal", diagnostic(goal));
            finiteState &= invalid.isEmpty();
            valid &= state.valid;
            if (!finiteState || !valid) {
                failureDiagnostics.putAll(invalid);
                fail(!finiteState ? "nonfinite state: " + invalid.keySet() : "invalid rig");
                return false;
            }
            return true;
        }

        void fail(String detail) {
            done = physicalFailure = true;
            success = false;
            reason = "physical failure: " + detail;
        }

        void exception(String stage, Exception error) {
            fail(stage + " exception: " + error);
            failureDiagnostics.put("exception", error.toString());
            failureDiagnostics.put("stage", stage);
            try {
                var state = observe();
                String exceptionReason = reason;
                validate(state);
                reason = exceptionReason;
                record(state);
            } catch (Exception observationError) {
                valid = finiteState = false;
                failureDiagnostics.put("observation_exception", observationError.toString());
                history.add(
                        Map.of(
                                "tick",
                                tick,
                                "observation_available",
                                false,
                                "physical_failure",
                                true,
                                "diagnostics",
                                new LinkedHashMap<>(failureDiagnostics)));
            }
        }

        void step() {
            if (done) return;
            tick++;
            try {
                nav.step();
            } catch (Exception e) {
                exception("nav.step", e);
                return;
            }
            try {
                advance(observe());
            } catch (Exception e) {
                exception("observation/metrics", e);
            }
        }

        void advance(Observation state) {
            if (!validate(state)) {
                record(state);
                return;
            }
            var p = state.position;
            var v = state.velocity;
            // Validate all physical inputs before updating extrema or accumulated metrics.
            error = Math.hypot(p.x - goal.x, p.z - goal.z);
            double speed = Math.hypot(v.x, v.z);
            anchor = Math.max(anchor, state.anchor);
            limits = Math.max(limits, state.limits);
            minUp = Math.min(minUp, state.up.y);
            fallen = state.up.y < .5 ? fallen + 1 : 0;
            maxFallenTicks = Math.max(maxFallenTicks, fallen);
            if (fallen > 30
                    || anchor > .12
                    || limits > .15
                    || p.y < 0
                    || Math.hypot(p.x - origin.x, p.z - origin.z) > 76) {
                fail(
                        fallen > 30
                                ? "fallen > 30 ticks"
                                : anchor > .12
                                        ? "anchor > .12"
                                        : limits > .15
                                                ? "joint limit violation > .15"
                                                : p.y < 0
                                                        ? "body below world floor"
                                                        : "body outside 76-block fixture radius");
            }
            if (settleTicks > 0 && tick == settleTicks) {
                progressError = error;
                progressTick = tick;
            }
            if (tick > settleTicks) {
                path += Math.hypot(p.x - previous.x, p.z - previous.z);
                if (error > 3) {
                    longestStall = Math.max(longestStall, (tick - progressTick) * .05);
                    if (progressError - error >= 1) {
                        progressError = error;
                        progressTick = tick;
                    }
                } else progressTick = tick;
                if (nav.tick >= nav.backUntil && error > 3) {
                    double gx = (goal.x - p.x) / error, gz = (goal.z - p.z) / error;
                    backward += Math.max(0, -((p.x - previous.x) * gx + (p.z - previous.z) * gz));
                    double reverse = -(v.x * gx + v.z * gz);
                    worstReverse = Math.max(worstReverse, reverse);
                    boolean kicking = reverse > 4;
                    if (kicking && !wasKicking) kickbacks++;
                    wasKicking = kicking;
                } else wasKicking = false;
                if (!done && nav.arrived && error <= 1 && speed <= .5) {
                    if (settled == 0) arrival = tick;
                    if (++settled >= 40) {
                        success = true;
                        done = true;
                    }
                } else {
                    settled = 0;
                    arrival = 0;
                }
            }
            previous = p;
            if (tick >= settleTicks + 2000 && !done) {
                done = true;
                reason = "timeout";
            }
            if (!finite(error, anchor, limits, minUp, backward, worstReverse, longestStall, path)) {
                finiteState = false;
                failureDiagnostics.put(
                        "metrics",
                        diagnostic(
                                error,
                                anchor,
                                limits,
                                minUp,
                                backward,
                                worstReverse,
                                longestStall,
                                path));
                fail("nonfinite derived metric");
                // Failure flags prevent sanitized overflowed metrics from earning a pass.
                error = finite(error) ? error : 0;
                anchor = finite(anchor) ? anchor : 0;
                limits = finite(limits) ? limits : 0;
                minUp = finite(minUp) ? minUp : 0;
                backward = finite(backward) ? backward : 0;
                worstReverse = finite(worstReverse) ? worstReverse : 0;
                longestStall = finite(longestStall) ? longestStall : 0;
                path = finite(path) ? path : 0;
            }
            record(state);
        }

        void record(Observation state) {
            // Record every 20 Hz control tick, including failed episodes.
            var feet = new ArrayList<Object>();
            for (int i = 0; i < 4; i++) {
                var toe = state.toes[i];
                feet.add(
                        Map.of(
                                "position",
                                diagnostic(toe),
                                // Actual native yaw/shoulder/knee angles, in radians.
                                "joint_angles",
                                diagnostic(
                                        state.angles[3 * i],
                                        state.angles[3 * i + 1],
                                        state.angles[3 * i + 2]),
                                "raw_requested_angles",
                                diagnostic(
                                        state.rawRequestedAngles[3 * i],
                                        state.rawRequestedAngles[3 * i + 1],
                                        state.rawRequestedAngles[3 * i + 2]),
                                "bounded_target_angles",
                                diagnostic(
                                        state.motorTargets[3 * i],
                                        state.motorTargets[3 * i + 1],
                                        state.motorTargets[3 * i + 2]),
                                "swing",
                                nav.control.swingBefore[i],
                                "requested_height",
                                diagnostic(nav.control.commandedToeHeight[i]),
                                "bounded_command_height",
                                diagnostic(nav.control.boundedToeHeight[i]),
                                "departure",
                                diagnostic(nav.control.departure[i]),
                                "landing",
                                diagnostic(nav.control.landing[i]),
                                "obstacle",
                                diagnostic(nav.control.obstacle[i])));
            }
            history.add(
                    Map.of(
                            "tick",
                            tick,
                            "position",
                            diagnostic(state.position),
                            // Actual core quaternion in world space, ordered x/y/z/w.
                            "core_orientation",
                            diagnostic(
                                    state.orientation.x,
                                    state.orientation.y,
                                    state.orientation.z,
                                    state.orientation.w),
                            "speed",
                            diagnostic(Math.hypot(state.velocity.x, state.velocity.z)),
                            "error",
                            error,
                            "arrived",
                            nav.arrived,
                            "feet",
                            feet,
                            "ground",
                            diagnostic(nav.control.ground),
                            "anchor",
                            diagnostic(state.anchor),
                            "recovery",
                            nav.tick < nav.backUntil));
        }

        Map<String, Object> result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("course", course);
            r.put("success", success);
            r.put("attempted", true);
            r.put("physical_failure", physicalFailure);
            r.put("finite_state", finiteState);
            r.put("valid", valid);
            r.put("max_fallen_ticks", maxFallenTicks);
            r.put("failure_diagnostics", new LinkedHashMap<>(failureDiagnostics));
            r.put("reason", reason);
            r.put("error", error);
            r.put("ticks", tick);
            r.put("initial_settle_ticks", settleTicks);
            r.put("arrival_seconds", success ? (arrival - settleTicks) * .05 : 0);
            r.put("arrival_window_start_tick", success ? arrival : 0);
            r.put("backward_travel", backward);
            r.put("kickbacks_over_4bps", kickbacks);
            r.put("worst_reverse_speed", worstReverse);
            r.put("longest_stall_seconds", longestStall);
            r.put("anchor_error", anchor);
            r.put("limit_violation", limits);
            r.put("minimum_up", minUp);
            r.put("path_length", path);
            r.put("clearance_hold_ticks_cumulative", nav.control.clearanceClock.heldTicks());
            r.put("replans", nav.replans - replanStart);
            r.put("recoveries", nav.recoveries - recoveryStart);
            r.put("masses", massData);
            r.put("goal", List.of(goal.x, goal.y, goal.z));
            r.put("history", new ArrayList<>(history));
            return r;
        }
    }

    static final class Run {
        final GameTestHelper h;
        final String mode;
        final MinecraftMachinesLeaperPredatorPolicy policy;
        final double lift,
                shoulderMinimum,
                shoulder,
                knee,
                slowDamping,
                flatCadence,
                clearGroundGain,
                clearancePause,
                stanceCompression;
        final boolean adaptive,
                terrain,
                freshSupport,
                exposedSurfaces,
                adaptiveFootholds,
                worldSwingHeight,
                world,
                postClearanceLift,
                paceLift;
        final JsonObject manifest;
        final String manifestHash, baselineHash;
        final List<Fixture> fixtures = new ArrayList<>();
        final Fixture continuousFixture;
        final List<String> continuousLegs = new ArrayList<>();
        final List<Episode> active = new ArrayList<>();
        final List<Object> records = new ArrayList<>(), continuous = new ArrayList<>();
        int batch, tick, leg;

        Run(GameTestHelper h, String mode) throws Exception {
            this.h = h;
            this.mode = mode;
            byte[] manifestBytes =
                    Files.readAllBytes(
                            Path.of("leaper_learning", "horrific-predator-highstep-manifest.json"));
            manifestHash = sha256(manifestBytes);
            manifest =
                    JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var keys = new HashSet<String>();
            var slots = new HashSet<Integer>();
            for (var row : manifest.getAsJsonArray("routes")) {
                var fixture = Fixture.read(row.getAsJsonObject(), false);
                if (!keys.add(fixture.family + ":" + fixture.configuration)
                        || !slots.add(fixture.slot))
                    throw new IllegalArgumentException(
                            "Duplicate fixture identity or physical slot");
                fixtures.add(fixture);
            }
            if (fixtures.size() != 21)
                throw new IllegalArgumentException("Frozen benchmark requires 21 routes");
            var continuousData = manifest.getAsJsonObject("continuous");
            continuousFixture = Fixture.read(continuousData, true);
            if (!slots.add(continuousFixture.slot))
                throw new IllegalArgumentException("Continuous slot overlaps a route");
            for (var value : continuousData.getAsJsonArray("legs"))
                continuousLegs.add(value.getAsString());
            if (!continuousLegs.equals(List.of("ascent", "descent", "ascent")))
                throw new IllegalArgumentException(
                        "Frozen continuous order must be ascent/descent/ascent");
            byte[] baselineBytes =
                    Files.readAllBytes(
                            Path.of(
                                    "leaper_learning",
                                    "horrific-predator-highstep-baseline-model.json"));
            baselineHash = sha256(baselineBytes);
            if (!BASELINE_SHA256.equals(baselineHash))
                throw new IllegalArgumentException(
                        "Baseline must be the exact qualified v10"
                                + " artifacts/leaper-clearance/model.json");
            if (mode.endsWith("baseline")) {
                var data =
                        JsonParser.parseString(new String(baselineBytes, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                var a = data.getAsJsonArray("genome");
                double[] g = new double[a.size()];
                for (int i = 0; i < g.length; i++) g[i] = a.get(i).getAsDouble();
                policy = new MinecraftMachinesLeaperPredatorPolicy(g);
                lift = 0;
                slowDamping = 0;
                flatCadence = 0;
                clearGroundGain = 0;
                clearancePause = 0;
                stanceCompression = 0;
                shoulderMinimum = -1.05;
                shoulder = 1.05;
                knee = 1.2;
                adaptive =
                        terrain =
                                freshSupport =
                                        exposedSurfaces =
                                                adaptiveFootholds =
                                                        worldSwingHeight =
                                                                world =
                                                                        postClearanceLift =
                                                                                paceLift = false;
            } else {
                if (!Files.isRegularFile(
                        Path.of(
                                "leaper_learning",
                                MinecraftMachinesLeaperLearning.reportName("predator-candidate")
                                        + ".json")))
                    throw new IllegalArgumentException(
                            "Explicit candidate genome file is required");
                policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
                var path =
                        Path.of(
                                "leaper_learning",
                                MinecraftMachinesLeaperLearning.reportName(
                                                "predator-highstep-settings")
                                        + ".json");
                var data = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                lift = data.get("extra_lift").getAsDouble();
                stanceCompression =
                        data.has("stance_compression")
                                ? data.get("stance_compression").getAsDouble()
                                : 0;
                clearancePause =
                        data.has("clearance_pause") ? data.get("clearance_pause").getAsDouble() : 0;
                clearGroundGain =
                        data.has("clear_ground_gain")
                                ? data.get("clear_ground_gain").getAsDouble()
                                : 0;
                flatCadence = data.has("flat_cadence") ? data.get("flat_cadence").getAsDouble() : 0;
                slowDamping = data.has("slow_damping") ? data.get("slow_damping").getAsDouble() : 0;
                shoulderMinimum =
                        data.has("shoulder_minimum")
                                ? data.get("shoulder_minimum").getAsDouble()
                                : -1.05;
                shoulder =
                        data.has("shoulder_maximum")
                                ? data.get("shoulder_maximum").getAsDouble()
                                : 1.05;
                knee = data.has("knee_maximum") ? data.get("knee_maximum").getAsDouble() : 1.2;
                world = data.has("world_swing") && data.get("world_swing").getAsBoolean();
                adaptive = data.has("adaptive_lift") && data.get("adaptive_lift").getAsBoolean();
                terrain =
                        data.has("terrain_aware_sweep")
                                && data.get("terrain_aware_sweep").getAsBoolean();
                freshSupport = flag(data, "fresh_support");
                exposedSurfaces = flag(data, "exposed_surfaces");
                adaptiveFootholds = flag(data, "adaptive_footholds");
                worldSwingHeight = flag(data, "world_swing_height");
                postClearanceLift = flag(data, "post_clearance_lift");
                paceLift = flag(data, "pace_lift");
                if (!Double.isFinite(lift)
                        || lift < 0
                        || lift > 3
                        || !Double.isFinite(slowDamping)
                        || slowDamping < 0
                        || slowDamping > .15
                        || !Double.isFinite(flatCadence)
                        || flatCadence < 0
                        || flatCadence > .4
                        || !Double.isFinite(clearGroundGain)
                        || clearGroundGain < 0
                        || clearGroundGain > 1
                        || !Double.isFinite(clearancePause)
                        || clearancePause < 0
                        || clearancePause > .6
                        || !Double.isFinite(stanceCompression)
                        || stanceCompression < 0
                        || stanceCompression > .6)
                    throw new IllegalArgumentException("Invalid lift");
            }
            if (!finite(shoulderMinimum, shoulder, knee)
                    || shoulderMinimum < -1.6
                    || shoulderMinimum > -1.05
                    || shoulder < 1.05
                    || shoulder > 1.5
                    || knee < 1.2
                    || knee > 1.7
                    || !finite(policy.genome))
                throw new IllegalArgumentException("Invalid joint reach or genome");
        }

        void begin() throws Exception {
            if (!MinecraftMachinesLeaperLearning.profile().equals("horrific")
                    || Integer.parseInt(
                                    System.getProperty("minecraft_machines.leaperSubsteps", "4"))
                            != 4
                    || Double.parseDouble(
                                    System.getProperty("minecraft_machines.leaperBalance", "1"))
                            != 1
                    || !Boolean.parseBoolean(
                            System.getProperty("minecraft_machines.leaperGrip", "true")))
                throw new IllegalStateException(
                        "Frozen benchmark requires horrific launch profile, requested substeps4,"
                                + " balance1, grip");
            var physics = SubLevelPhysicsSystem.get(h.getLevel());
            physics.getConfig().substepsPerTick = 4;
            physics.onConfigUpdated();
            batch();
            loop();
        }

        void batch() throws Exception {
            active.forEach(t -> t.rig.close());
            active.clear();
            tick = 0;
            if (batch < 4) {
                for (int i = batch * 6; i < Math.min(fixtures.size(), batch * 6 + 6); i++)
                    active.add(
                            new Episode(
                                    h,
                                    fixtures.get(i).slot,
                                    policy,
                                    fixtures.get(i).course,
                                    lift,
                                    slowDamping,
                                    flatCadence,
                                    clearGroundGain,
                                    clearancePause,
                                    stanceCompression,
                                    adaptive,
                                    terrain,
                                    freshSupport,
                                    exposedSurfaces,
                                    adaptiveFootholds,
                                    worldSwingHeight,
                                    world,
                                    postClearanceLift,
                                    paceLift,
                                    shoulderMinimum,
                                    shoulder,
                                    knee));
            } else {
                active.add(
                        new Episode(
                                h,
                                continuousFixture.slot,
                                policy,
                                continuousFixture.course,
                                lift,
                                slowDamping,
                                flatCadence,
                                clearGroundGain,
                                clearancePause,
                                stanceCompression,
                                adaptive,
                                terrain,
                                freshSupport,
                                exposedSurfaces,
                                adaptiveFootholds,
                                worldSwingHeight,
                                world,
                                postClearanceLift,
                                paceLift,
                                shoulderMinimum,
                                shoulder,
                                knee));
            }
        }

        void loop() {
            h.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                        } catch (Exception e) {
                            active.forEach(t -> t.rig.close());
                            h.fail(e.toString());
                        }
                    });
        }

        void step() throws Exception {
            tick++;
            active.forEach(Episode::step);
            if (tick % 400 == 0)
                MinecraftMachines.LOGGER.info(
                        "HIGHSTEP_BENCHMARK mode={} batch={} leg={} tick={} states={}",
                        mode,
                        batch,
                        leg,
                        tick,
                        active.stream()
                                .map(
                                        t ->
                                                String.format(
                                                        Locale.ROOT,
                                                        "%s %.1f %s",
                                                        t.course.name(),
                                                        t.error,
                                                        t.done
                                                                ? t.success
                                                                        ? "PASS"
                                                                        : "FAIL " + t.reason
                                                                : ""))
                                .toList());
            if (active.stream().anyMatch(t -> !t.done)) {
                loop();
                return;
            }
            if (batch < 4) {
                for (int j = 0; j < active.size(); j++) {
                    int i = batch * 6 + j;
                    var r = active.get(j).result();
                    r.put("family", fixtures.get(i).family);
                    r.put("configuration", fixtures.get(i).configuration);
                    r.put("slot", fixtures.get(i).slot);
                    records.add(r);
                }
                write();
                batch++;
                batch();
                loop();
            } else {
                var e = active.getFirst();
                var r = e.result();
                r.put("leg", leg);
                r.put("leg_direction", continuousLegs.get(leg));
                r.put("slot", continuousFixture.slot);
                continuous.add(r);
                write();
                if (++leg < continuousLegs.size() && e.success) {
                    e.start(continuousLegs.get(leg).equals("descent") ? e.lower : e.upper, 0);
                    tick = 0;
                    loop();
                } else {
                    while (leg < continuousLegs.size()) {
                        var skipped = new LinkedHashMap<String, Object>();
                        skipped.put("leg_direction", continuousLegs.get(leg));
                        skipped.put("leg", leg++);
                        skipped.put("slot", continuousFixture.slot);
                        skipped.put("course", continuousFixture.course);
                        skipped.put("attempted", false);
                        skipped.put("success", false);
                        skipped.put("reason", "prior continuous leg failed");
                        for (String name :
                                List.of(
                                        "arrival_seconds",
                                        "backward_travel",
                                        "kickbacks_over_4bps",
                                        "worst_reverse_speed",
                                        "longest_stall_seconds",
                                        "anchor_error",
                                        "limit_violation")) skipped.put(name, 0);
                        continuous.add(skipped);
                    }
                    write();
                    e.rig.close();
                    h.succeed();
                }
            }
        }

        void write() throws Exception {
            var physics = SubLevelPhysicsSystem.get(h.getLevel()).getConfig();
            var r = new LinkedHashMap<String, Object>();
            r.put("mode", mode);
            r.put(
                    "highstep_settings",
                    MinecraftMachinesLeaperHighStepSettings.effective(
                            active.getFirst().nav.control));
            r.put("genome", policy.genome);
            r.put("extra_lift", lift);
            r.put("slow_damping", slowDamping);
            r.put("flat_cadence", flatCadence);
            r.put("clear_ground_gain", clearGroundGain);
            r.put("clearance_pause", clearancePause);
            r.put("stance_compression", stanceCompression);
            r.put("shoulder_minimum", shoulderMinimum);
            r.put("shoulder_maximum", shoulder);
            r.put("knee_maximum", knee);
            r.put("launch_profile", MinecraftMachinesLeaperLearning.profile());
            r.put("fixture_manifest_sha256", manifestHash);
            r.put("fixture_manifest", manifest);
            r.put("baseline_model_sha256", baselineHash);
            r.put("metric_version", "critic-v2-stall-from-settled-20hz");
            r.put("rayon_threads", System.getenv().getOrDefault("RAYON_NUM_THREADS", "default"));
            r.put("world_swing", world);
            r.put("adaptive_lift", adaptive);
            r.put("terrain_aware_sweep", terrain);
            r.put("fresh_support", freshSupport);
            r.put("exposed_surfaces", exposedSurfaces);
            r.put("adaptive_footholds", adaptiveFootholds);
            r.put("world_swing_height", worldSwingHeight);
            r.put("post_clearance_lift", postClearanceLift);
            r.put("pace_lift", paceLift);
            r.put("trials", records);
            r.put("continuous", continuous);
            r.put("physics_substeps", physics.substepsPerTick);
            r.put("physics_config", physics);
            r.put("control_hz", 20);
            r.put("balance", 1);
            MinecraftMachinesLeaperLearning.write(mode, r);
        }
    }
}
