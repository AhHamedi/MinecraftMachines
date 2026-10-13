package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.*;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Native command development checks. No score, contact-force proof or full-rig arc certificate. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPounceCommandTests {
    static final String MODE = "predator-pounce-commands";
    static final Gson TREE = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    static final int LIMIT = 1680;
    static final String[] NAMES = {
        "near-no-leap",
        "crouch-retarget-near",
        "crouch-stop-resume",
        "flight-retarget-valid-invalid",
        "flight-stop",
        "repeated-far-cooldown",
        "blocked-crouch-arc-restore",
        "gap-eight-early-pounce"
    };

    @GameTest(template = "empty", timeoutTicks = 4000, batch = "leaperPounceCommands")
    public static void commands(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals(MODE)) {
            helper.succeed();
            return;
        }
        new Run(helper).begin();
    }

    static void check(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    static double[] xyz(Vector3d v) {
        return new double[] {v.x, v.y, v.z};
    }

    static Vector3d copy(Vector3d v) {
        return v == null ? null : new Vector3d(v);
    }

    static double distance(Vector3d a, Vector3d b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    static String hash(Object value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(TREE.toJson(value).getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    static List<ServerSubLevel> bodies(MinecraftMachinesLeaper rig) throws Exception {
        var f = MinecraftMachinesLeaper.class.getDeclaredField("bodies");
        f.setAccessible(true);
        return (List<ServerSubLevel>) f.get(rig);
    }

    static Vector3d vector(JsonObject o, String key) {
        var a = o.getAsJsonArray(key);
        check(a != null && a.size() == 3, "Missing vector " + key);
        var v =
                new Vector3d(
                        a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        check(v.isFinite(), "Nonfinite vector " + key);
        return v;
    }

    // A failed native sample must remain writable. Record every sanitized location.
    static JsonElement safe(JsonElement e, String path, List<String> invalid) {
        if (e == null || e.isJsonNull()) return JsonNull.INSTANCE;
        if (e.isJsonPrimitive()
                && e.getAsJsonPrimitive().isNumber()
                && !Double.isFinite(e.getAsDouble())) {
            invalid.add(path);
            return new JsonPrimitive(e.getAsString());
        }
        if (e.isJsonArray()) {
            var a = new JsonArray();
            int i = 0;
            for (var v : e.getAsJsonArray()) a.add(safe(v, path + "[" + i++ + "]", invalid));
            return a;
        }
        if (e.isJsonObject()) {
            var o = new JsonObject();
            for (var v : e.getAsJsonObject().entrySet())
                o.add(v.getKey(), safe(v.getValue(), path + "." + v.getKey(), invalid));
            return o;
        }
        return e.deepCopy();
    }

    static class Trial {
        final Run run;
        final int id;
        final List<Object> history = new ArrayList<>(),
                inputs = new ArrayList<>(),
                impulseChecks = new ArrayList<>();
        final List<String> failures = new ArrayList<>(), nonfinite = new ArrayList<>();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        final Map<BlockPos, BlockState> wall = new LinkedHashMap<>();
        final Map<String, Quaterniond> orientationsBeforeStep = new LinkedHashMap<>();
        final Deque<Double> pursuitErrors = new ArrayDeque<>();
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        Vector3d origin, requested, initialGoal, position, velocity, stopPosition;
        MinecraftMachinesLeaperLeap.Plan committed;
        int tick, crouch = -1, flight = -1, eventTick = -1, invalidTick = -1, stopTick = -1;
        int stopLaunches,
                launches,
                latestStable,
                stableCount,
                settle,
                arrivalTick,
                observeUntil = -1,
                wallTick = -1;
        boolean attempted,
                done,
                success,
                closed,
                event,
                invalidPreserved,
                resumed,
                stopped,
                wallPresent;
        boolean physicalFailure, earlyPounceObserved, gapEntry, gapCrossed;
        double anchor,
                limits,
                minUp = 1,
                stopDrift,
                previousD,
                pursuitBest = Double.POSITIVE_INFINITY;
        String beforeWallHash = "", insertedWallHash = "", restoredWallHash = "";

        Trial(Run run, int id) {
            this.run = run;
            this.id = id;
        }

        void initialize() {
            try {
                var level = run.helper.getLevel();
                origin = MinecraftMachinesLeaperLearning.arena(level, id, Blocks.SMOOTH_STONE, 112);
                int ox = (int) Math.floor(origin.x), oz = (int) Math.floor(origin.z);
                boolean apexPhases =
                        Boolean.getBoolean("minecraft_machines.leaperApex") && id >= 1 && id <= 6;
                // Restore a genuinely flat start even if this disposable grid had an older probe.
                for (int x = ox - 112; x <= ox + 112; x++)
                    for (int z = oz - 112; z <= oz + 112; z++) {
                        for (int y = 7; y <= 55; y++) {
                            var p = new BlockPos(x, y, z);
                            if (!level.getBlockState(p).isAir())
                                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                        }
                        // Apex runs clear flat routes. Real gaps force its preparation/flight
                        // phases without changing any cancellation, impulse or landing assertions.
                        // The second cross-gap in case 5 exercises another required pounce.
                        if (id == 7 && oz - z >= 8 && oz - z < 16
                                || apexPhases && oz - z >= 18 && oz - z < 26
                                || apexPhases
                                        && id == 5
                                        && Math.abs(x - ox) >= 18
                                        && Math.abs(x - ox) < 26)
                            for (int y = -16; y <= 6; y++)
                                level.setBlockAndUpdate(
                                        new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                rig = MinecraftMachinesLeaper.spawn(level, origin);
                rig.reset(0);
                nav = new MinecraftMachinesLeaperPredatorNavigator(rig, level, run.policy);
                nav.pace = "horrific";
                check(nav.leap.enabled && nav.control.agile, "Actual leap/agile flags disabled");
                var list = bodies(rig);
                check(list.size() == 13, "Expected13 native bodies");
                double total = 0;
                for (int i = 0; i < list.size(); i++) {
                    double mass = list.get(i).getMassTracker().getMass();
                    double expected = i == 0 ? 27 : new double[] {1, 8, 6}[(i - 1) % 3];
                    check(
                            Double.isFinite(mass) && Math.abs(mass - expected) < 1e-6,
                            "Nonstandard native mass " + i);
                    total += mass;
                }
                initialGoal = floor(origin.x, origin.z - (id == 0 ? 12 : id == 7 ? 24 : 64));
                requested = copy(initialGoal);
                position = rig.position();
                velocity = rig.velocity();
                metadata.put("slot", id);
                metadata.put(
                        "fixture_version", apexPhases ? "apex-phase-gaps-v1" : "classic-flat-v1");
                metadata.put("spawn", xyz(origin));
                metadata.put("initial_goal", xyz(initialGoal));
                metadata.put("arena_radius", 112);
                metadata.put("slot_spacing", 576);
                metadata.put("mass", total);
                metadata.put("masses", MinecraftMachinesLeaperHighStepBenchmark.masses(rig));
                metadata.put(
                        "settings", MinecraftMachinesLeaperHighStepSettings.effective(nav.control));
                metadata.put("walking_parameters", nav.control.walkingParameters());
                metadata.put("gravity", xyz(rig.gravity()));
                metadata.put("air_drag", rig.airDrag());
                metadata.put(
                        "gap",
                        id == 7
                                ? Map.of("start_d", 8, "width", 8, "cleared_bottom_y", -16)
                                : apexPhases
                                        ? Map.of(
                                                "start_d",
                                                18,
                                                "width",
                                                8,
                                                "cleared_bottom_y",
                                                -16,
                                                "lateral_cross_gaps",
                                                id == 5)
                                        : Map.of());
            } catch (Exception error) {
                fail("fixture/setup: " + error, false);
                close();
            }
        }

        Vector3d floor(double x, double z) {
            check(
                    Math.abs(x - origin.x) <= 104 && Math.abs(z - origin.z) <= 104,
                    "Requested point outside supported arena margin");
            var map = new MinecraftMachinesLeaperTerrainMap(run.helper.getLevel());
            map.exposedSurfaces = true;
            double y = map.height(x, z, 8);
            check(
                    Double.isFinite(y) && Math.abs(y - 7) < 1e-6,
                    "Target lacks actual flat collision floor");
            return new Vector3d(x, y, z);
        }

        void fail(String reason, boolean physical) {
            failures.add(reason);
            physicalFailure |= physical;
            done = true;
            success = false;
        }

        void input(String name, String phase, Map<String, Object> detail) {
            var row = new LinkedHashMap<String, Object>(detail);
            row.put("event", name);
            row.put("tick", tick);
            row.put("nav_tick", nav.tick);
            row.put("phase", phase);
            row.put("state", nav.leap.state.toString());
            inputs.add(row);
        }

        void go(Vector3d target, String name, String phase) {
            boolean accepted = nav.go(target.x, target.y, target.z);
            input(name, phase, Map.of("target", xyz(target), "accepted", accepted));
            check(accepted, name + " rejected: " + nav.reason);
            requested = copy(target);
            settle = arrivalTick = 0;
        }

        void stop(String phase) {
            stopPosition = rig.position();
            stopTick = tick;
            stopLaunches = nav.leap.launches;
            stopped = true;
            nav.stop();
            input("stop", phase, Map.of("position", xyz(stopPosition), "launches", stopLaunches));
            check(nav.goal == null && nav.pendingGoal == null, "Stop retained a goal");
        }

        void rememberCommitment() {
            check(nav.leap.active != null, "Phase has no committed plan");
            committed =
                    new MinecraftMachinesLeaperLeap.Plan(
                            copy(nav.leap.active.destination()), nav.leap.active.arc());
        }

        boolean sameCommitment() {
            return committed != null
                    && nav.leap.active != null
                    && committed.destination().equals(nav.leap.active.destination())
                    && committed.arc().equals(nav.leap.active.arc());
        }

        void beforeStep() throws Exception {
            if (tick == 81) {
                attempted = true;
                go(initialGoal, "initial-goal", "before-control");
            }
            if (!event
                    && ((Set.of(1, 2, 6).contains(id) && crouch >= 0 && tick == crouch + 1)
                            || (Set.of(3, 4).contains(id) && flight >= 0 && tick == flight + 2))) {
                event = true;
                eventTick = tick;
                rememberCommitment();
                if (id == 1) {
                    var p = rig.corePivot();
                    go(floor(p.x + 8, p.z - 6), "near-retarget-in-crouch", "before-control");
                } else if (id == 2) stop("before-control");
                else if (id == 3) {
                    var goalBefore = copy(nav.goal);
                    go(
                            floor(committed.destination().x + 8, committed.destination().z),
                            "valid-flight-retarget",
                            "before-control");
                    check(
                            sameCommitment()
                                    && Objects.equals(goalBefore, nav.goal)
                                    && requested.equals(nav.pendingGoal),
                            "Flight retarget changed committed route/landing");
                    invalidTick = tick + 2;
                } else if (id == 4) {
                    stop("before-control");
                    check(sameCommitment(), "Flight stop changed landing commitment");
                } else insertWall();
            }
            if (id == 3 && tick == invalidTick) {
                check(nav.leap.inAir(), "Invalid flight retarget phase no longer airborne");
                var oldGoal = copy(nav.goal);
                var oldPending = copy(nav.pendingGoal);
                boolean accepted = nav.go(requested.x, requested.y + 20, requested.z);
                invalidPreserved =
                        !accepted
                                && Objects.equals(oldGoal, nav.goal)
                                && Objects.equals(oldPending, nav.pendingGoal)
                                && sameCommitment();
                input(
                        "invalid-elevated-flight-retarget",
                        "before-control",
                        Map.of(
                                "target",
                                new double[] {requested.x, requested.y + 20, requested.z},
                                "accepted",
                                accepted,
                                "preserved",
                                invalidPreserved));
                check(
                        invalidPreserved,
                        "Invalid flight request changed valid goal/pending/commitment");
            }
            if (id == 2 && stopped && tick == stopTick + 80) {
                check(nav.leap.launches == 0, "Launch while stopped in crouch");
                go(initialGoal, "explicit-resume", "before-control");
                stopped = false;
                resumed = true;
            }
            if (id == 6 && wallPresent && tick == wallTick + 40) {
                restoreWall();
                input("wall-restored", "before-control", Map.of("sha256", restoredWallHash));
            }
            if (id == 7
                    && tick > 80
                    && nav.goal != null
                    && nav.leap.state == MinecraftMachinesLeaperLeap.State.WALK) {
                pursuitBest = Math.min(pursuitBest, distance(rig.position(), nav.goal));
                pursuitErrors.addLast(pursuitBest);
                while (pursuitErrors.size() > 81) pursuitErrors.removeFirst();
            }
        }

        void step() {
            if (done) return;
            tick++;
            try {
                beforeStep();
                orientationsBeforeStep.clear();
                for (var body : bodies(rig))
                    orientationsBeforeStep.put(
                            body.getUniqueId().toString(),
                            new Quaterniond(body.logicalPose().orientation()));
                var priorState = nav.leap.state;
                int priorProgress = nav.leap.lastProgress;
                nav.step();
                sample();
                if (done) {
                    close();
                    return;
                }
                if (id == 7
                        && priorState == MinecraftMachinesLeaperLeap.State.WALK
                        && nav.leap.state != priorState) {
                    double error = distance(position, requested);
                    double best =
                            pursuitErrors.stream()
                                    .mapToDouble(Double::doubleValue)
                                    .min()
                                    .orElse(Double.POSITIVE_INFINITY);
                    boolean terrainRequiresLeap = nav.apex.needsLeap(requested);
                    // This 24-block journey must leap because of its gap, below the
                    // general long-distance threshold.
                    earlyPounceObserved =
                            error > 4 && error < nav.automaticLeapDistance()
                                    && terrainRequiresLeap && nav.tick <= 110;
                    input(
                            "observed-early-preparation",
                            "after-control",
                            Map.of(
                                    "distance",
                                    error,
                                    "window_samples",
                                    pursuitErrors.size(),
                                    "new_best_progress",
                                    pursuitErrors.isEmpty() ? 0 : pursuitErrors.getFirst() - best,
                                    "controller_ticks_since_progress",
                                    nav.tick - priorProgress,
                                    "terrain_requires_leap",
                                    terrainRequiresLeap,
                                    "asserted",
                                    earlyPounceObserved));
                    check(
                            earlyPounceObserved,
                            "Gap did not trigger a prompt terrain-based pounce");
                }
                if (nav.leap.state == MinecraftMachinesLeaperLeap.State.CROUCH && crouch < 0)
                    crouch = tick;
                if (nav.leap.state == MinecraftMachinesLeaperLeap.State.FLIGHT && flight < 0)
                    flight = tick;
                check(
                        rig.leapImpulses.size() == nav.leap.launches,
                        "Impulse count differs from launch count");
                if (nav.leap.launches != launches) {
                    check(
                            nav.leap.launches == launches + 1,
                            "Multiple launches in one control interval");
                    check(!stopped && !wallPresent, "Launch during stop or obstructed interval");
                    check(
                            latestStable == 0
                                    || nav.tick - latestStable
                                            >= MinecraftMachinesLeaperLeap.RECOVERY_COOLDOWN_TICKS,
                            "Relaunch before the contact recovery cooldown");
                    check(
                            launches == 0 || stableCount >= launches,
                            "Relaunch before prior stable landing");
                    validateImpulse(launches);
                    launches++;
                }
                if (id == 0 || id == 1) check(launches == 0, "Unexpected launch for near target");
                if (id == 2 && !resumed) check(launches == 0, "Launch before explicit resume");
                if (id == 6) check(launches <= 3, "Excessive repeat pounces");
                else if (id != 5) check(launches <= 1, "Unexpected repeated launch");
                else check(launches <= 4, "Excessive repeat pounces for two destinations");
                if ((id == 3 || id == 4) && event && nav.leap.inAir())
                    check(sameCommitment(), "Committed airborne landing changed");
                if (stopped) {
                    check(
                            nav.goal == null
                                    && nav.pendingGoal == null
                                    && nav.leap.launches == stopLaunches,
                            "Stopped pursuit/relaunch resumed without request");
                    stopDrift = Math.max(stopDrift, distance(position, stopPosition));
                }
                if (nav.leap.stableLandingTick > latestStable) {
                    latestStable = nav.leap.stableLandingTick;
                    stableCount++;
                    input(
                            "observed-stable-landing",
                            "after-control",
                            Map.of("stable_landing_nav_tick", latestStable, "launches", launches));
                    check(
                            nav.leap.landings >= stableCount,
                            "Recovery without actual touchdown event");
                    if (id == 5 && stableCount == 1) {
                        event = true;
                        eventTick = tick;
                        double dx = position.x > origin.x ? -64 : 64;
                        go(
                                floor(position.x + dx, position.z),
                                "second-far-after-stable",
                                "after-control");
                    }
                    if (id == 4) observeUntil = tick + 80;
                }
                if (id == 7 && nav.leap.inAir()) {
                    double d = origin.z - position.z;
                    if (previousD < 8 && d >= 8) gapEntry = true;
                    if (gapEntry && previousD < 16 && d >= 16) gapCrossed = true;
                }
                previousD = origin.z - position.z;
                boolean arrived =
                        nav.goal != null
                                && nav.pendingGoal == null
                                && nav.goal.equals(requested)
                                && nav.arrived
                                && !nav.leap.inAir()
                                && distance(position, requested) <= 2.5
                                && Math.hypot(velocity.x, velocity.z) <= 1;
                if (tick > 80 && arrived) {
                    if (settle++ == 0) arrivalTick = tick;
                } else {
                    settle = 0;
                    arrivalTick = 0;
                }
                if (id == 4) {
                    if (observeUntil >= 0 && tick >= observeUntil) finish();
                } else if (settle >= 40) {
                    if (id == 0) {
                        if (observeUntil < 0) observeUntil = tick + 80;
                        if (tick >= observeUntil) finish();
                    } else finish();
                }
                if (!done && tick >= LIMIT)
                    fail(
                            "timeout; required event="
                                    + event
                                    + ", launches="
                                    + launches
                                    + ", stable_landings="
                                    + stableCount,
                            false);
            } catch (Exception error) {
                fail("episode: " + error, false);
            }
            if (done) close();
        }

        void sample() {
            position = rig.position();
            velocity = rig.velocity();
            var row = new LinkedHashMap<String, Object>();
            var q = rig.coreOrientation();
            row.put("tick", tick);
            row.put("nav_tick", nav.tick);
            row.put("game_tick", run.helper.getLevel().getGameTime());
            row.put("position", xyz(position));
            row.put("velocity", xyz(velocity));
            row.put("assembly_velocity", xyz(rig.assemblyVelocity()));
            row.put("orientation", new double[] {q.x, q.y, q.z, q.w});
            row.put("angles", rig.angles());
            row.put("anchor", rig.anchorError());
            row.put("limit", rig.limitViolation());
            row.put("up", rig.upright());
            row.put("valid", rig.valid());
            row.put("state", nav.leap.state.toString());
            row.put("state_ticks", nav.leap.stateTicks);
            row.put("goal", nav.goal == null ? null : xyz(nav.goal));
            row.put("pending_goal", nav.pendingGoal == null ? null : xyz(nav.pendingGoal));
            row.put("requested_goal", xyz(requested));
            row.put("arrived", nav.arrived);
            row.put("launches", nav.leap.launches);
            row.put("landings", nav.leap.landings);
            row.put("aborts", nav.leap.aborts);
            row.put("supports_proximity_estimate", nav.leap.supports());
            row.put("demonstrated_airborne", nav.leap.demonstratedAirborne);
            row.put("stable_landing_tick", nav.leap.stableLandingTick);
            row.put("cooldown_until", nav.leap.cooldown);
            if (nav.leap.active != null)
                row.put(
                        "committed",
                        Map.of(
                                "destination",
                                xyz(nav.leap.active.destination()),
                                "arc",
                                nav.leap.active.arc()));
            history.add(safe(TREE.toJsonTree(row), "tick" + tick, nonfinite));
            anchor = Math.max(anchor, rig.anchorError());
            limits = Math.max(limits, rig.limitViolation());
            minUp = Math.min(minUp, rig.upright());
            if (!nonfinite.isEmpty()
                    || !rig.valid()
                    || anchor > .12
                    || limits > .15
                    || rig.upright() < .45)
                fail("Native finite/anchor/limit/upright failure", true);
        }

        void validateImpulse(int index) throws Exception {
            var log = TREE.toJsonTree(rig.leapImpulses.get(index)).getAsJsonObject();
            var delta = vector(log, "delta_velocity");
            var before = vector(log, "com_velocity_before");
            var after = vector(log, "com_velocity_after_api");
            var expected = new Vector3d(before).add(delta);
            check(
                    delta.length() <= 60 + 1e-6
                            && delta.y >= 0
                            && delta.y <= 24 + 1e-6
                            && Math.hypot(expected.x, expected.z) <= 50 + 1e-6
                            && expected.y <= 24 + 1e-6
                            && Math.hypot(after.x, after.z) <= 50 + 1e-6
                            && after.y <= 24 + 1e-6,
                    "Pounce60/50/24 impulse/COM cap");
            check(
                    log.get("game_tick").getAsLong() == run.helper.getLevel().getGameTime(),
                    "Impulse logged outside observed control interval");
            var rows = log.getAsJsonArray("bodies");
            check(rows.size() == 13, "Impulse must contain13 bodies");
            var ids = new HashSet<String>();
            double mass = 0;
            var sum = new Vector3d();
            for (var e : rows) {
                var b = e.getAsJsonObject();
                String id = b.get("body").getAsString();
                double m = b.get("mass").getAsDouble();
                check(
                        ids.add(id)
                                && orientationsBeforeStep.containsKey(id)
                                && Double.isFinite(m)
                                && m > 0,
                        "Duplicate/invalid impulse body/mass");
                var j =
                        new Quaterniond(orientationsBeforeStep.get(id))
                                .transform(vector(b, "local_impulse"));
                check(
                        j.distance(new Vector3d(delta).mul(m))
                                <= 1e-6 * Math.max(1, m * delta.length()),
                        "Body impulse differs from common mass-proportional delta");
                sum.add(j);
                mass += m;
            }
            check(
                    ids.equals(orientationsBeforeStep.keySet())
                            && Math.abs(mass - 87) < 1e-6
                            && sum.distance(new Vector3d(delta).mul(mass))
                                    <= 1e-6 * Math.max(1, mass * delta.length()),
                    "Aggregate native mass/impulse mismatch");
            impulseChecks.add(
                    Map.of(
                            "launch",
                            index + 1,
                            "nav_tick",
                            nav.tick,
                            "game_tick",
                            log.get("game_tick").getAsLong(),
                            "bodies",
                            13,
                            "mass",
                            mass,
                            "passed",
                            true,
                            "ticks_since_previous_stable",
                            latestStable == 0 ? -1 : nav.tick - latestStable));
        }

        List<AABB> rigBounds() throws Exception {
            var result = new ArrayList<AABB>();
            for (var body : bodies(rig)) {
                var b = body.getPlot().getBoundingBox();
                double lx = Double.POSITIVE_INFINITY,
                        ly = lx,
                        lz = lx,
                        hx = -lx,
                        hy = -lx,
                        hz = -lx;
                for (double x : new double[] {b.minX(), b.maxX() + 1})
                    for (double y : new double[] {b.minY(), b.maxY() + 1})
                        for (double z : new double[] {b.minZ(), b.maxZ() + 1}) {
                            var p =
                                    body.logicalPose()
                                            .transformPosition(
                                                    new Vector3d(x, y, z), new Vector3d());
                            lx = Math.min(lx, p.x);
                            ly = Math.min(ly, p.y);
                            lz = Math.min(lz, p.z);
                            hx = Math.max(hx, p.x);
                            hy = Math.max(hy, p.y);
                            hz = Math.max(hz, p.z);
                        }
                result.add(new AABB(lx, ly, lz, hx, hy, hz));
            }
            return result;
        }

        void insertWall() throws Exception {
            var level = run.helper.getLevel();
            var p = rig.corePivot();
            int z0 = (int) Math.floor(p.z) - 10, ox = (int) Math.floor(origin.x);
            var volume = new AABB(ox - 100, 7, z0, ox + 101, 55, z0 + 2);
            check(
                    rigBounds().stream().noneMatch(volume::intersects),
                    "Wall intersects current native rig bounds");
            check(
                    !volume.intersects(
                            new AABB(
                                    committed.destination().x - 6,
                                    6,
                                    committed.destination().z - 6,
                                    committed.destination().x + 6,
                                    18,
                                    committed.destination().z + 6)),
                    "Wall intersects committed landing/support");
            boolean crosses = false;
            for (int i = 0; i <= 320; i++) {
                var a = committed.arc().at(committed.arc().seconds() * i / 320);
                if (volume.contains(a[0], a[1], a[2])) crosses = true;
            }
            check(crosses, "Wall does not cross the actual committed arc");
            for (int x = ox - 100; x <= ox + 100; x++)
                for (int z = z0; z < z0 + 2; z++)
                    for (int y = 7; y < 55; y++) {
                        var pos = new BlockPos(x, y, z);
                        var state = level.getBlockState(pos);
                        check(
                                state.isAir() && state.getFluidState().isEmpty(),
                                "Wall insertion region is not empty");
                        wall.put(pos, state);
                    }
            beforeWallHash = wallHash();
            wallPresent = true;
            wallTick = tick;
            for (var pos : wall.keySet())
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            for (var pos : wall.keySet())
                check(level.getBlockState(pos).is(Blocks.STONE), "Native wall was not inserted");
            insertedWallHash = wallHash();
            boolean refused = nav.leap.plan(nav.goal) == null;
            input(
                    "native-wall-inserted",
                    "before-control",
                    Map.of(
                            "before_sha256",
                            beforeWallHash,
                            "inserted_sha256",
                            insertedWallHash,
                            "bounds",
                            new double[] {
                                volume.minX,
                                volume.minY,
                                volume.minZ,
                                volume.maxX,
                                volume.maxY,
                                volume.maxZ
                            },
                            "planner_refused",
                            refused));
            check(refused, "Fixture did not obstruct every currently offered arc");
        }

        String wallHash() throws Exception {
            var data = new ArrayList<Object>();
            for (var pos : wall.keySet())
                data.add(
                        List.of(
                                pos.getX(),
                                pos.getY(),
                                pos.getZ(),
                                run.helper.getLevel().getBlockState(pos).toString()));
            return hash(data);
        }

        void restoreWall() throws Exception {
            if (!wallPresent) return;
            for (var e : wall.entrySet())
                run.helper.getLevel().setBlockAndUpdate(e.getKey(), e.getValue());
            restoredWallHash = wallHash();
            check(restoredWallHash.equals(beforeWallHash), "Wall restore hash mismatch");
            wallPresent = false;
        }

        void finish() {
            check(id == 0 || id == 7 || event, "Required phase event unexercised");
            int expected = id <= 1 ? 0 : id == 5 || id == 6 ? launches : 1;
            check(id != 5 || launches >= 2, "Second destination never launched");
            check(id != 6 || launches >= 1, "Restored route never launched");
            check(
                    launches == expected && stableCount == expected,
                    "Wrong launch/stable-landing counts");
            check(id != 2 || resumed, "Explicit resume unexercised");
            check(id != 3 || invalidPreserved, "Invalid flight retarget preservation unexercised");
            check(
                    id != 6
                            || (!wallPresent
                                    && !beforeWallHash.isEmpty()
                                    && beforeWallHash.equals(restoredWallHash)),
                    "Wall restoration unexercised");
            check(
                    id != 7 || (earlyPounceObserved && gapCrossed),
                    "Early pounce or airborne gap crossing unexercised");
            done = success = true;
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                restoreWall();
            } catch (Exception error) {
                fail("wall cleanup: " + error, false);
            }
            try {
                if (rig != null) rig.close();
            } catch (Exception error) {
                fail("rig cleanup: " + error, true);
            }
        }

        JsonObject result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("case", NAMES[id]);
            r.put("attempted", attempted);
            r.put("complete", done);
            r.put("success", success);
            r.put("ticks", tick);
            r.put("failures", failures);
            r.put(
                    "termination",
                    !done ? "running" : success ? "passed" : String.join("; ", failures));
            r.put("physical_failure", physicalFailure);
            r.put("event_exercised", event);
            r.put("invalid_preserved", invalidPreserved);
            r.put("resumed", resumed);
            r.put("early_pounce_observed", earlyPounceObserved);
            r.put("gap_crossed", gapCrossed);
            r.put("launches", nav == null ? 0 : nav.leap.launches);
            r.put("impulses", rig == null ? 0 : rig.leapImpulses.size());
            r.put("stable_landings", stableCount);
            r.put("anchor", anchor);
            r.put("limit", limits);
            r.put("minimum_up", minUp);
            r.put("stop_drift_observed", stopDrift);
            r.put("arrival_seconds", arrivalTick > 80 ? (arrivalTick - 80) * .05 : null);
            r.put("requested_goal", requested == null ? null : xyz(requested));
            r.put("position", position == null ? null : xyz(position));
            r.put("velocity", velocity == null ? null : xyz(velocity));
            r.put("metadata", metadata);
            r.put("inputs", inputs);
            r.put("history", history);
            r.put("impulse_checks", impulseChecks);
            r.put("leap_events", nav == null ? List.of() : nav.leap.events);
            r.put("native_impulses", rig == null ? List.of() : rig.leapImpulses);
            r.put("wall_before_sha256", beforeWallHash);
            r.put("wall_restored_sha256", restoredWallHash);
            var clean = safe(TREE.toJsonTree(r), "$", nonfinite).getAsJsonObject();
            clean.add("nonfinite_fields", TREE.toJsonTree(nonfinite));
            clean.addProperty("finite_state", nonfinite.isEmpty());
            if (!nonfinite.isEmpty()) {
                clean.addProperty("success", false);
                clean.addProperty("physical_failure", true);
            }
            return clean;
        }
    }

    static class Run {
        final GameTestHelper helper;
        final List<Trial> trials = new ArrayList<>();
        MinecraftMachinesLeaperPredatorPolicy policy;
        int previousSubsteps;
        boolean physicsChanged;

        Run(GameTestHelper helper) {
            this.helper = helper;
        }

        void begin() throws Exception {
            check(
                    Boolean.getBoolean("minecraft_machines.leaperLeap")
                            && Boolean.getBoolean("minecraft_machines.leaperAgile")
                            && MinecraftMachinesLeaperLearning.profile().equals("horrific")
                            && Integer.getInteger("minecraft_machines.leaperSubsteps", 4) == 4
                            && Boolean.parseBoolean(
                                    System.getProperty("minecraft_machines.leaperGrip", "true"))
                            && Double.parseDouble(
                                            System.getProperty(
                                                    "minecraft_machines.leaperBalance", "1"))
                                    == 1,
                    "Command probes require profileH/native4/grip/balance1/leap/agile");
            check(
                    Files.isRegularFile(
                            Path.of(
                                    "leaper_learning",
                                    MinecraftMachinesLeaperLearning.reportName("predator-candidate")
                                            + ".json")),
                    "Explicit unchanged base26 candidate required");
            policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
            var system = SubLevelPhysicsSystem.get(helper.getLevel());
            previousSubsteps = system.getConfig().substepsPerTick;
            system.getConfig().substepsPerTick = 4;
            system.onConfigUpdated();
            physicsChanged = true;
            for (int i = 0; i < NAMES.length; i++) {
                var t = new Trial(this, i);
                trials.add(t);
                t.initialize();
            }
            persist();
            loop();
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            for (var trial : trials) trial.step();
                            if (trials.stream().allMatch(t -> t.done)) {
                                persist();
                                close();
                                helper.succeed();
                            } else {
                                if (trials.stream().mapToInt(t -> t.tick).max().orElse(0) % 200
                                        == 0) persist();
                                loop();
                            }
                        } catch (Exception error) {
                            for (var t : trials) if (!t.done) t.fail("harness: " + error, false);
                            try {
                                persist();
                            } catch (Exception ignored) {
                            }
                            close();
                            helper.fail(error.toString());
                        }
                    });
        }

        void persist() throws Exception {
            var rows = trials.stream().map(Trial::result).toList();
            var r = new LinkedHashMap<String, Object>();
            r.put("mode", MODE);
            r.put("qualified", false);
            r.put("purpose", "Functional native pounce command development checks; no score");
            r.put(
                    "geometry_proof",
                    "Separate and incomplete; these checks do not certify full-rig swept geometry"
                            + " or contact force");
            r.put(
                    "support_measurement",
                    "Existing single-toe native collision-surface proximity estimate");
            r.put("expected_cases", 8);
            r.put("complete", trials.size() == 8 && trials.stream().allMatch(t -> t.done));
            r.put("passed", rows.stream().filter(o -> o.get("success").getAsBoolean()).count());
            r.put(
                    "failed",
                    rows.stream()
                            .filter(
                                    o ->
                                            o.get("complete").getAsBoolean()
                                                    && !o.get("success").getAsBoolean())
                            .count());
            r.put(
                    "unattempted",
                    rows.stream().filter(o -> !o.get("attempted").getAsBoolean()).count());
            r.put("genome", policy.genome);
            r.put("parameters", policy.named());
            r.put("physics_config", SubLevelPhysicsSystem.get(helper.getLevel()).getConfig());
            r.put("control_hz", 20);
            r.put("max_trial_ticks", LIMIT);
            r.put("leap_enabled", true);
            r.put("agile", true);
            r.put("trials", rows);
            MinecraftMachinesLeaperLearning.write(MODE, r);
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
