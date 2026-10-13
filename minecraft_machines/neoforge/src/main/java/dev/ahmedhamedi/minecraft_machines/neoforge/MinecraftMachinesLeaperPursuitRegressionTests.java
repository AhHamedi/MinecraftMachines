package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.*;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

/**
 * Small, serial native development regression. Never modifies production state or supplies backoff
 * commands.
 */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPursuitRegressionTests {
    static final String PREFIX = "predator-pursuit-regression-";
    static final Gson JSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    static final int LIMIT = 1200, WATCH = 120;

    // Same fixtures/order for baseline and candidate. Labels do not select a policy.
    record Case(
            String name,
            double dx,
            double dz,
            String pace,
            int radius,
            int bankStart,
            int bankRise,
            boolean near,
            boolean timing,
            boolean backoff) {}

    static final List<Case> CASES =
            List.of(
                    new Case("near-12-horrific", 0, -12, "horrific", 32, 0, 0, true, false, false),
                    new Case(
                            "near-12-diagonal-horrific",
                            6,
                            -10.392304845413264,
                            "horrific",
                            32,
                            0,
                            0,
                            true,
                            false,
                            false),
                    new Case("near-12-careful", 0, -12, "careful", 32, 0, 0, true, false, false),
                    new Case("planning-flat-96", 0, -96, "horrific", 128, 0, 0, false, true, false),
                    new Case(
                            "planning-up12-96",
                            0,
                            -96,
                            "horrific",
                            128,
                            24,
                            12,
                            false,
                            true,
                            false),
                    new Case("close-wall-up8", 0, -40, "horrific", 80, 6, 8, false, false, true),
                    new Case("open-runup-up8", 0, -40, "horrific", 80, 16, 8, false, false, false),
                    new Case(
                            "near-12-super-horrific",
                            0,
                            -12,
                            "super_horrific",
                            32,
                            0,
                            0,
                            true,
                            false,
                            false),
                    new Case(
                            "sprint-64-super-horrific",
                            0,
                            -64,
                            "super_horrific",
                            128,
                            0,
                            0,
                            false,
                            false,
                            false));

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "leaperPursuitRegression")
    public static void pursuit(GameTestHelper helper) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!mode.equals(PREFIX + "baseline") && !mode.equals(PREFIX + "candidate")) {
            helper.succeed();
            return;
        }
        new Run(helper, mode).begin();
    }

    static void require(boolean ok, String why) {
        if (!ok) throw new IllegalStateException(why);
    }

    static double[] xyz(Vector3d v) {
        return new double[] {v.x, v.y, v.z};
    }

    static double horizontal(Vector3d a, Vector3d b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    static double percentile(List<Double> values, double quantile) {
        if (values.isEmpty()) return 0;
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(
                Math.min(sorted.size() - 1, (int) Math.ceil(quantile * sorted.size()) - 1));
    }

    // Keep nonfinite failures serializable; never silently discard the failing sample.
    static JsonElement clean(JsonElement value, String path, List<String> invalid) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isNumber()
                && !Double.isFinite(value.getAsDouble())) {
            invalid.add(path);
            return new JsonPrimitive(value.getAsString());
        }
        if (value.isJsonArray()) {
            var out = new JsonArray();
            int i = 0;
            for (var item : value.getAsJsonArray())
                out.add(clean(item, path + "[" + i++ + "]", invalid));
            return out;
        }
        if (value.isJsonObject()) {
            var out = new JsonObject();
            for (var e : value.getAsJsonObject().entrySet())
                out.add(e.getKey(), clean(e.getValue(), path + "." + e.getKey(), invalid));
            return out;
        }
        return value.deepCopy();
    }

    // Optional diagnostic fields preserve source compatibility with the pre-fix baseline.
    static Object optionalField(Object owner, String name) {
        try {
            var f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (NoSuchFieldException missing) {
            return null;
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    static boolean hasField(Object owner, String name) {
        try {
            owner.getClass().getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException missing) {
            return false;
        }
    }

    static final class Trial {
        final Run run;
        final int id;
        final Case spec;
        final List<JsonElement> history = new ArrayList<>();
        final List<Object> inputs = new ArrayList<>();
        final List<String> failures = new ArrayList<>(), nonfinite = new ArrayList<>();
        final List<Double> stepMillis = new ArrayList<>(), activeStepMillis = new ArrayList<>();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        Vector3d origin, target, initial, finalPosition, entryPosition, previous;
        int tick, entryTick = -1, holdEntries, holdExits, stableTail, longestStable;
        int airborneRun, longestAirborne, arrivedWhileAirborne, firstLaunch = -1;
        boolean attempted, done, closed, accepted, physicalFailure, previousHold;
        boolean functionalSuccess, latencySuccess, backoffExercised, goalPreserved = true;
        double goMillis, maxAnchor, maxLimit, minUp = 1, maxRise, maxUpVelocity;
        double backwardTravel, maxBackwardSpeed, minPrelaunchD, maxWatchError;
        Double lastBoundedHeight[] = new Double[4];
        double maxBoundedHeightStep;

        Trial(Run run, int id) {
            this.run = run;
            this.id = id;
            spec = CASES.get(id);
        }

        void initialize() {
            attempted = true;
            try {
                var level = run.helper.getLevel();
                origin =
                        MinecraftMachinesLeaperLearning.arena(
                                level, id, Blocks.SMOOTH_STONE, spec.radius);
                int ox = (int) Math.floor(origin.x), oz = (int) Math.floor(origin.z);
                // Disposable fresh-world fixture; clear old fixture blocks, never run in a player
                // world.
                for (int x = ox - spec.radius; x <= ox + spec.radius; x++)
                    for (int z = oz - spec.radius; z <= oz + spec.radius; z++)
                        for (int y = 7; y <= 34; y++) {
                            boolean bank =
                                    spec.bankRise > 0
                                            && oz - z >= spec.bankStart
                                            && y < 7 + spec.bankRise;
                            var pos = new BlockPos(x, y, z);
                            var desired =
                                    bank
                                            ? Blocks.STONE.defaultBlockState()
                                            : Blocks.AIR.defaultBlockState();
                            if (!level.getBlockState(pos).equals(desired))
                                level.setBlockAndUpdate(pos, desired);
                        }
                target = new Vector3d(origin.x + spec.dx, 7 + spec.bankRise, origin.z + spec.dz);
                var terrain = new MinecraftMachinesLeaperTerrainMap(level);
                terrain.exposedSurfaces = true;
                double actual = terrain.height(target.x, target.z, target.y + .55, 72);
                require(
                        Double.isFinite(actual) && Math.abs(actual - target.y) < 1e-6,
                        "Unsupported/wrong-floor fixture goal");
                rig = MinecraftMachinesLeaper.spawn(level, origin);
                rig.reset(0);
                nav = new MinecraftMachinesLeaperPredatorNavigator(rig, level, run.policy);
                nav.pace = spec.pace;
                require(
                        nav.leap.enabled && nav.control.agile,
                        "Leap/agile must be enabled in both arms");
                var field = MinecraftMachinesLeaper.class.getDeclaredField("bodies");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var bodies = (List<ServerSubLevel>) field.get(rig);
                require(bodies.size() == 13, "Expected13 bodies");
                var masses = new ArrayList<Double>();
                double total = 0;
                for (int b = 0; b < bodies.size(); b++) {
                    double mass = bodies.get(b).getMassTracker().getMass();
                    masses.add(mass);
                    total += mass;
                    double expected = b == 0 ? 27 : new double[] {1, 8, 6}[(b - 1) % 3];
                    require(
                            Double.isFinite(mass) && Math.abs(mass - expected) < 1e-6,
                            "Nonstandard body mass");
                }
                require(Math.abs(total - 87) < 1e-6, "Nonstandard total mass");
                metadata.put("body_masses", masses);
                metadata.put(
                        "effective_settings",
                        MinecraftMachinesLeaperHighStepSettings.effective(nav.control));
                metadata.put("gravity", xyz(rig.gravity()));
                metadata.put("air_drag", rig.airDrag());
                metadata.put("planning_field_available", hasField(nav, "planning"));
                metadata.put("retreat_field_available", hasField(nav.leap, "retreats"));
                metadata.put("spawn", xyz(origin));
                metadata.put("target", xyz(target));
                metadata.put("actual_target_floor", actual);
                metadata.put("bank_front_z", spec.bankRise > 0 ? oz - spec.bankStart + 1 : null);
                initial = rig.corePivot();
                previous = new Vector3d(initial);
            } catch (Exception error) {
                fail("fixture: " + error, false);
            }
        }

        void fail(String reason, boolean physical) {
            failures.add(reason);
            physicalFailure |= physical;
            done = true;
            functionalSuccess = false;
        }

        void step() {
            if (done) return;
            tick++;
            try {
                if (tick == 81) {
                    long began = System.nanoTime();
                    try {
                        accepted = nav.go(target.x, target.y, target.z);
                    } finally {
                        goMillis = (System.nanoTime() - began) / 1e6;
                        inputs.add(
                                Map.of(
                                        "event",
                                        "only-go-command",
                                        "tick",
                                        tick,
                                        "target",
                                        xyz(target),
                                        "returned",
                                        accepted,
                                        "wall_millis",
                                        goMillis));
                    }
                    // A queued planner may return false. Retain the return value and test eventual
                    // behavior.
                }
                long began = System.nanoTime();
                try {
                    nav.step();
                } finally {
                    double ms = (System.nanoTime() - began) / 1e6;
                    stepMillis.add(ms);
                    if (tick > 80) activeStepMillis.add(ms);
                }
                sample();
                if (done) return;
                if (entryTick >= 0 && tick - entryTick >= WATCH) finish();
                else if (tick >= LIMIT) {
                    failures.add(
                            "timeout: missing arrival entry or complete six-second observation");
                    finish();
                }
            } catch (Exception error) {
                fail("native/control: " + error, true);
            }
        }

        void sample() {
            var p = rig.corePivot();
            var v = rig.velocity();
            finalPosition = new Vector3d(p);
            double error = horizontal(p, target), speed = Math.hypot(v.x, v.z), up = rig.upright();
            int supports =
                    nav.leap.supports(); // Proximity estimate only, never a contact-force claim.
            double anchor = rig.anchorError(), limit = rig.limitViolation();
            maxAnchor = Math.max(maxAnchor, anchor);
            maxLimit = Math.max(maxLimit, limit);
            minUp = Math.min(minUp, up);
            double floorError = Math.abs(p.y - target.y - run.policy.value[4]);
            if (tick >= 81
                    && !((nav.goal != null && nav.goal.distanceSquared(target) < 1e-8)
                            || (nav.pendingGoal != null
                                    && nav.pendingGoal.distanceSquared(target) < 1e-8)))
                goalPreserved = false;
            if (firstLaunch < 0) {
                double d = initial.z - p.z;
                minPrelaunchD = Math.min(minPrelaunchD, d);
                if (nav.leap.launches > 0) firstLaunch = tick;
            }
            // A long Apex pounce can cross the destination while still in its
            // committed landing motion. Observe arrival after it returns to supported
            // walking; keep the stricter geometric-entry guard for near-target holds.
            if (tick > 80 && entryTick < 0 && error <= 2.5 && floorError < 1.25
                    && (spec.near || nav.leap.state == MinecraftMachinesLeaperLeap.State.WALK && supports > 0)) {
                entryTick = tick;
                entryPosition = new Vector3d(p);
                inputs.add(
                        Map.of(
                                "event",
                                spec.near ? "first-geometric-arrival-entry" : "first-supported-arrival-entry",
                                "tick",
                                tick,
                                "position",
                                xyz(p)));
            }
            if (nav.holdPosition && !previousHold) holdEntries++;
            if (!nav.holdPosition && previousHold) holdExits++;
            previousHold = nav.holdPosition;
            if (entryTick >= 0) {
                maxRise = Math.max(maxRise, p.y - entryPosition.y);
                maxUpVelocity = Math.max(maxUpVelocity, v.y);
                maxWatchError = Math.max(maxWatchError, error);
                double dx = spec.dx / Math.hypot(spec.dx, spec.dz),
                        dz = spec.dz / Math.hypot(spec.dx, spec.dz);
                backwardTravel += Math.max(0, -((p.x - previous.x) * dx + (p.z - previous.z) * dz));
                maxBackwardSpeed = Math.max(maxBackwardSpeed, -v.x * dx - v.z * dz);
                airborneRun = supports == 0 ? airborneRun + 1 : 0;
                longestAirborne = Math.max(longestAirborne, airborneRun);
                if (nav.arrived && supports == 0) arrivedWhileAirborne++;
                // Observed settled arrival is separate from Navigator's instantaneous flag.
                boolean stable =
                        error <= 2.5
                                && floorError < 1.25
                                && speed < 1
                                && Math.abs(v.y) < .75
                                && up > .65;
                stableTail = stable ? stableTail + 1 : 0;
                longestStable = Math.max(longestStable, stableTail);
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("tick", tick);
            row.put("nav_tick", nav.tick);
            row.put("core", xyz(p));
            row.put("velocity", xyz(v));
            row.put("error", error);
            row.put("floor_error", floorError);
            row.put("up", up);
            row.put("arrived", nav.arrived);
            row.put("hold_position", nav.holdPosition);
            row.put("control_holding", nav.control.holding);
            row.put("state", nav.leap.state.toString());
            row.put("launches", nav.leap.launches);
            row.put("supports_proximity_estimate", supports);
            row.put("cycles", nav.control.cycles);
            row.put("goal", nav.goal == null ? null : xyz(nav.goal));
            row.put("pending_goal", nav.pendingGoal == null ? null : xyz(nav.pendingGoal));
            row.put(
                    "planning",
                    hasField(nav, "planning") ? optionalField(nav, "planning") != null : null);
            row.put("retreats", optionalField(nav.leap, "retreats"));
            row.put("command", nav.command);
            row.put("heading", rig.heading());
            row.put("waypoint", nav.route.isEmpty() ? null : nav.route.get(nav.index));
            row.put("route_index", nav.index);
            row.put("super_drive", nav.control.superDrive);
            row.put("sprint_forward", nav.sprintForward);
            row.put("sprint_strafe", nav.sprintStrafe);
            row.put("back_until", nav.backUntil);
            row.put("recoveries", nav.recoveries);
            row.put("anchor", anchor);
            row.put("limit", limit);
            row.put("step_wall_millis", stepMillis.getLast());
            var toes = new ArrayList<Object>();
            for (int leg = 0; leg < 4; leg++) {
                var bound = rig.boundedCommandToe(leg);
                if (entryTick >= 0 && lastBoundedHeight[leg] != null)
                    maxBoundedHeightStep =
                            Math.max(
                                    maxBoundedHeightStep,
                                    Math.abs(bound.y - lastBoundedHeight[leg]));
                lastBoundedHeight[leg] = bound.y;
                toes.add(
                        Map.of(
                                "leg",
                                leg,
                                "actual",
                                xyz(rig.toe(leg)),
                                "bounded",
                                xyz(bound),
                                "requested_height",
                                nav.control.commandedToeHeight[leg],
                                "swing",
                                nav.control.swingBefore[leg]));
            }
            row.put("toes", toes);
            row.put("raw_angles", nav.control.rawRequestedAngles.clone());
            row.put("bounded_angles", rig.boundedMotorTargets());
            row.put("measured_angles", rig.angles());
            history.add(clean(JSON.toJsonTree(row), "tick" + tick, nonfinite));
            previous = new Vector3d(p);
            if (!nonfinite.isEmpty()
                    || !rig.valid()
                    || !p.isFinite()
                    || !v.isFinite()
                    || maxAnchor > .12
                    || maxLimit > .15
                    || up < .45) fail("Native finite/anchor/limit/upright guard", true);
        }

        void finish() {
            done = true;
            boolean observed = entryTick >= 0 && tick - entryTick >= WATCH;
            if (!observed) failures.add("Arrival observation incomplete");
            if (!goalPreserved) failures.add("Requested goal was lost or replaced");
            if (stableTail < 40)
                failures.add("Final two seconds not continuously settled at intended floor/point");
            if (spec.near) {
                if (nav.leap.launches != 0 || !rig.leapImpulses.isEmpty())
                    failures.add("Unexpected near-target leap impulse");
                if (maxRise > .6 || maxUpVelocity > 2)
                    failures.add("Near-arrival upward hop exceeds .6block/2bps regression bounds");
                if (maxWatchError > 2.5) failures.add("Near-arrival escaped 2.5block hold radius");
            }
            if (spec.bankRise > 0 && nav.leap.launches == 0)
                failures.add("Elevation leap unexercised");
            backoffExercised = firstLaunch > 0 && minPrelaunchD <= -1;
            // Classic's original launch envelope required a setback here. Apex can
            // clear this wall directly. Still verify any requested retreat actually
            // moved the body; the dedicated recovery replay requires a physical retreat.
            if (spec.backoff && (!nav.apex.enabled || nav.leap.retreats > 0) && !backoffExercised)
                failures.add("Automatic setback unexercised (requires >=1block before launch)");
            functionalSuccess = failures.isEmpty() && !physicalFailure;
            latencySuccess = goMillis <= 50 && percentile(stepMillis, 1) <= 50;
        }

        Map<String, Object> result() {
            var out = new LinkedHashMap<String, Object>();
            out.put("case", spec);
            out.put("attempted", attempted);
            out.put("complete", done);
            out.put("functional_success", functionalSuccess);
            out.put("latency_success", latencySuccess);
            out.put("success", functionalSuccess && (!spec.timing || latencySuccess));
            out.put("failures", failures);
            out.put("physical_failure", physicalFailure);
            out.put("nonfinite_fields", nonfinite);
            out.put("ticks", tick);
            out.put("go_returned", accepted);
            out.put("go_wall_millis", goMillis);
            out.put("step_wall_millis_all", stepMillis);
            out.put("step_wall_millis_after80", activeStepMillis);
            out.put("step_p50_millis", percentile(stepMillis, .5));
            out.put("step_p95_millis", percentile(stepMillis, .95));
            out.put("step_max_millis", percentile(stepMillis, 1));
            out.put("step_over50_count", stepMillis.stream().filter(x -> x > 50).count());
            out.put("goal_preserved", goalPreserved);
            out.put(
                    "final_distance",
                    finalPosition == null || target == null
                            ? null
                            : horizontal(finalPosition, target));
            out.put("entry_tick", entryTick);
            out.put("final_stable_ticks", stableTail);
            out.put("longest_stable_ticks", longestStable);
            out.put("final_position", finalPosition == null ? null : xyz(finalPosition));
            out.put("requested_goal", target == null ? null : xyz(target));
            out.put("max_hold_rise", maxRise);
            out.put("max_hold_up_speed", maxUpVelocity);
            out.put("max_hold_error", maxWatchError);
            out.put("hold_backward_travel", backwardTravel);
            out.put("max_hold_backward_speed", maxBackwardSpeed);
            out.put("max_bounded_toe_height_step", maxBoundedHeightStep);
            out.put("hold_entries", holdEntries);
            out.put("hold_exits", holdExits);
            out.put("longest_zero_support_estimate_ticks", longestAirborne);
            out.put("arrived_zero_support_estimate_ticks", arrivedWhileAirborne);
            out.put("first_launch_tick", firstLaunch);
            out.put("prelaunch_backoff_distance", -minPrelaunchD);
            out.put("backoff_exercised", backoffExercised);
            out.put("terrain_assist_impulses", rig == null ? 0 : rig.terrainAssistImpulses);
            out.put("terrain_assist_delta_total", rig == null ? 0 : rig.terrainAssistDeltaTotal);
            out.put(
                    "terrain_assist_max_horizontal",
                    rig == null ? 0 : rig.terrainAssistMaximumHorizontal);
            out.put(
                    "terrain_assist_max_vertical",
                    rig == null ? 0 : rig.terrainAssistMaximumVertical);
            out.put("anchor", maxAnchor);
            out.put("limit", maxLimit);
            out.put("minimum_up", minUp);
            out.put("metadata", metadata);
            out.put("inputs", inputs);
            out.put("history", history);
            out.put("leap_events", nav == null ? List.of() : nav.leap.events);
            out.put("native_impulses", rig == null ? List.of() : rig.leapImpulses);
            return out;
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                if (rig != null) rig.close();
            } catch (Exception error) {
                fail("cleanup: " + error, true);
            }
        }
    }

    static final class Run {
        final GameTestHelper helper;
        final String mode;
        final List<Trial> trials = new ArrayList<>();
        MinecraftMachinesLeaperPredatorPolicy policy;
        int current, previousSubsteps;
        boolean physicsChanged;

        Run(GameTestHelper helper, String mode) {
            this.helper = helper;
            this.mode = mode;
        }

        void begin() throws Exception {
            require(
                    MinecraftMachinesLeaperLearning.profile().equals("horrific")
                            && Boolean.getBoolean("minecraft_machines.leaperLeap")
                            && Boolean.getBoolean("minecraft_machines.leaperAgile")
                            && Integer.getInteger("minecraft_machines.leaperSubsteps", 4) == 4
                            && Boolean.parseBoolean(
                                    System.getProperty("minecraft_machines.leaperGrip", "true"))
                            && Double.parseDouble(
                                            System.getProperty(
                                                    "minecraft_machines.leaperBalance", "1"))
                                    == 1,
                    "Requires H profile/leap/agile/native4/grip/balance1");
            require(
                    Files.isRegularFile(
                            Path.of(
                                    "leaper_learning",
                                    MinecraftMachinesLeaperLearning.reportName("predator-candidate")
                                            + ".json")),
                    "Explicit input genome required");
            require(
                    !System.getProperty(
                                    "minecraft_machines.leaperImplementationSha256", "unrecorded")
                            .equals("unrecorded"),
                    "Source snapshot SHA required");
            policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
            var system = SubLevelPhysicsSystem.get(helper.getLevel());
            previousSubsteps = system.getConfig().substepsPerTick;
            system.getConfig().substepsPerTick = 4;
            system.onConfigUpdated();
            physicsChanged = true;
            for (int i = 0; i < CASES.size(); i++) trials.add(new Trial(this, i));
            trials.getFirst().initialize();
            persist();
            loop();
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            var t = trials.get(current);
                            t.step();
                            if (t.done) {
                                t.close();
                                persist();
                                current++;
                                if (current == trials.size()) {
                                    close();
                                    helper.succeed();
                                    return;
                                }
                                trials.get(current).initialize();
                            }
                            loop();
                        } catch (Exception error) {
                            for (var t : trials) if (!t.done) t.fail("harness: " + error, false);
                            close();
                            try {
                                persist();
                            } catch (Exception ignored) {
                            }
                            helper.fail(error.toString());
                        }
                    });
        }

        void persist() throws Exception {
            var report = new LinkedHashMap<String, Object>();
            report.put("mode", mode);
            report.put("fixture_version", "pursuit-regression-v1");
            report.put("qualified", false);
            report.put(
                    "purpose",
                    "Seven fixed serial native development regressions; no new score or full-rig"
                            + " collision proof");
            report.put(
                    "support_measurement",
                    "Single native-toe / collision-surface proximity estimate, not contact force");
            report.put("control_hz", 20);
            report.put("max_case_ticks", LIMIT);
            report.put("arrival_observation_ticks", WATCH);
            report.put("latency_budget_millis", 50);
            report.put(
                    "latency_scope",
                    "Complete synchronous go and step calls, all samples including startup/JIT;"
                            + " excludes diagnostic sampling, fixture setup and report IO");
            report.put("genome", policy.genome);
            report.put("parameters", policy.named());
            report.put("physics_config", SubLevelPhysicsSystem.get(helper.getLevel()).getConfig());
            report.put("expected_cases", CASES.size());
            report.put("complete", trials.stream().allMatch(t -> t.done));
            report.put("trials", trials.stream().map(Trial::result).toList());
            var invalid = new ArrayList<String>();
            var safe = clean(JSON.toJsonTree(report), "$", invalid).getAsJsonObject();
            safe.add("serialization_nonfinite_fields", JSON.toJsonTree(invalid));
            if (!invalid.isEmpty())
                for (var t : safe.getAsJsonArray("trials")) {
                    t.getAsJsonObject().addProperty("success", false);
                    t.getAsJsonObject().addProperty("functional_success", false);
                }
            MinecraftMachinesLeaperLearning.write(mode, safe);
        }

        void close() {
            for (var t : trials) t.close();
            if (physicsChanged) {
                var system = SubLevelPhysicsSystem.get(helper.getLevel());
                system.getConfig().substepsPerTick = previousSubsteps;
                system.onConfigUpdated();
                physicsChanged = false;
            }
        }
    }
}
