package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

/** Matched native ground locomotion; no leap, position servo, body impulse or time scaling. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperSuperSpeedTests {
    static final String MODE = "predator-super-speed";
    static final int SETTLE = 80, MEASURE_FROM = 180, CRUISE_END = 320, SLOW_END = 360, END = 460;
    static final Gson TREE = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    @GameTest(template = "empty", timeoutTicks = 2400, batch = "leaperSuperSpeed")
    public static void speed(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals(MODE)) {
            helper.succeed();
            return;
        }
        new Run(helper).begin();
    }

    static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    static double[] xyz(Vector3d v) {
        return new double[] {v.x, v.y, v.z};
    }

    @SuppressWarnings("unchecked")
    static List<ServerSubLevel> bodies(MinecraftMachinesLeaper rig) throws Exception {
        var f = MinecraftMachinesLeaper.class.getDeclaredField("bodies");
        f.setAccessible(true);
        return (List<ServerSubLevel>) f.get(rig);
    }

    static JsonElement finite(JsonElement e, String path, List<String> bad) {
        if (e == null || e.isJsonNull()) return JsonNull.INSTANCE;
        if (e.isJsonPrimitive()
                && e.getAsJsonPrimitive().isNumber()
                && !Double.isFinite(e.getAsDouble())) {
            bad.add(path);
            return new JsonPrimitive(e.getAsString());
        }
        if (e.isJsonArray()) {
            var out = new JsonArray();
            int i = 0;
            for (var v : e.getAsJsonArray()) out.add(finite(v, path + "[" + i++ + "]", bad));
            return out;
        }
        if (e.isJsonObject()) {
            var out = new JsonObject();
            for (var v : e.getAsJsonObject().entrySet())
                out.add(v.getKey(), finite(v.getValue(), path + "." + v.getKey(), bad));
            return out;
        }
        return e.deepCopy();
    }

    static class Trial {
        final Run run;
        final int slot, round;
        final String pace;
        final double heading, dx, dz;
        final List<JsonElement> history = new ArrayList<>();
        final List<String> failures = new ArrayList<>(), nonfinite = new ArrayList<>();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorControl control;
        MinecraftMachinesLeaperTerrainMap map;
        Vector3d origin, cruiseStart, cruiseEnd, previous, slowStart, stopStart, position, velocity;
        int tick,
                sampleCount,
                supportSamples,
                noSupportTicks,
                maxNoSupportTicks,
                firstStoppedTick = -1;
        double anchor,
                limit,
                minUp = 1,
                maxSpeed,
                backward,
                path,
                supportVelocity,
                stopDrift,
                slowDistance;
        double minHeight = Double.POSITIVE_INFINITY, maxHeight = Double.NEGATIVE_INFINITY;
        boolean done, success, closed;

        Trial(Run run, int slot, int round) {
            this.run = run;
            this.slot = slot;
            this.round = round;
            pace = (slot + round) % 2 == 0 ? "horrific" : "super_horrific";
            heading = slot < 2 ? 0 : .35;
            dx = Math.sin(heading);
            dz = -Math.cos(heading);
        }

        void initialize() {
            try {
                origin = run.origins[slot];
                rig = MinecraftMachinesLeaper.spawn(run.helper.getLevel(), origin);
                rig.reset(heading);
                map = new MinecraftMachinesLeaperTerrainMap(run.helper.getLevel());
                control = new MinecraftMachinesLeaperPredatorControl(rig, map, run.policy);
                control.setPace(pace);
                require(
                        rig.motorForceScale == 1
                                && rig.motorDampingScale == 1
                                && control.balance == 1,
                        "Unexpected motor/balance scale");
                var nativeBodies = bodies(rig);
                require(nativeBodies.size() == 13, "Expected13 bodies");
                double total = 0;
                for (int i = 0; i < nativeBodies.size(); i++) {
                    double m = nativeBodies.get(i).getMassTracker().getMass();
                    double expected = i == 0 ? 27 : new double[] {1, 8, 6}[(i - 1) % 3];
                    require(
                            Double.isFinite(m) && Math.abs(m - expected) < 1e-6,
                            "Nonstandard mass " + i);
                    total += m;
                }
                metadata.put("mass", total);
                metadata.put("bodies", MinecraftMachinesLeaperHighStepBenchmark.masses(rig));
                metadata.put("origin", xyz(origin));
                metadata.put("heading", heading);
                metadata.put("gravity", xyz(rig.gravity()));
                metadata.put("air_drag", rig.airDrag());
                metadata.put(
                        "highstep_settings",
                        MinecraftMachinesLeaperHighStepSettings.effective(control));
                metadata.put("agile", control.agile);
                metadata.put("slew_limit", pace.equals("super_horrific") ? 20 : 8);
                metadata.put("power_limit", pace.equals("super_horrific") ? 16 : 8);
                metadata.put("force_cap_limit", 150000 * (pace.equals("super_horrific") ? 16 : 8));
                metadata.put("initial_walking_parameters", control.walkingParameters());
            } catch (Exception e) {
                fail("setup: " + e);
                close();
            }
        }

        void fail(String reason) {
            failures.add(reason);
            done = true;
            success = false;
        }

        double project(Vector3d a, Vector3d b) {
            return (a.x - b.x) * dx + (a.z - b.z) * dz;
        }

        void step() {
            if (done) return;
            tick++;
            try {
                double request =
                        tick <= SETTLE
                                ? 0
                                : tick <= CRUISE_END
                                        ? Math.clamp((tick - SETTLE) / 40.0, 0, 1)
                                        : Math.clamp((SLOW_END - tick) / 40.0, 0, 1);
                double error = LeaperGait.wrap(heading - rig.heading());
                double turn =
                        request > 0
                                ? Math.clamp(2 * error + .4 * rig.angularVelocity().y, -.5, .5)
                                : 0;
                control.setPace(pace);
                control.step(request * Math.cos(error), request * Math.sin(error), turn, request);
                position = rig.position();
                velocity = rig.velocity();
                require(
                        rig.leapImpulses.isEmpty(),
                        "Unexpected translational takeoff impulse in ground speed test");
                var row = new LinkedHashMap<String, Object>();
                row.put("tick", tick);
                row.put("game_tick", run.helper.getLevel().getGameTime());
                row.put("request", request);
                row.put("turn", turn);
                row.put("position", xyz(position));
                row.put("velocity", xyz(velocity));
                row.put("heading_error", error);
                row.put("up", rig.upright());
                row.put("anchor", rig.anchorError());
                row.put("limit", rig.limitViolation());
                row.put("angles", rig.angles());
                row.put("raw_angles", control.rawRequestedAngles.clone());
                row.put("motor_targets", rig.boundedMotorTargets());
                row.put("period", control.commandedPeriod);
                row.put("stride", control.commandedStride);
                row.put("slew", control.commandedSlew);
                row.put("power", control.commandedPower);
                row.put("force_cap", 150000 * control.commandedPower);
                row.put("super_drive", control.superDrive);
                row.put("clear_ground_blend", control.clearGroundBlend);
                row.put("cycles", control.cycles);
                var toes = new ArrayList<Object>();
                int supports = 0;
                for (int leg = 0; leg < 4; leg++) {
                    var toe = rig.toe(leg);
                    double floor = map.height(toe.x, toe.z, toe.y + .35);
                    boolean near = Double.isFinite(floor) && Math.abs(toe.y - floor) < .42;
                    if (near) supports++;
                    var foot = new LinkedHashMap<String, Object>();
                    foot.put("toe", xyz(toe));
                    foot.put("floor", Double.isFinite(floor) ? floor : null);
                    foot.put("supported_proximity", near);
                    toes.add(foot);
                }
                row.put("feet", toes);
                row.put("supported_proximity_feet", supports);
                history.add(finite(TREE.toJsonTree(row), "tick" + tick, nonfinite));
                anchor = Math.max(anchor, rig.anchorError());
                limit = Math.max(limit, rig.limitViolation());
                minUp = Math.min(minUp, rig.upright());
                require(
                        nonfinite.isEmpty()
                                && rig.valid()
                                && anchor <= .12
                                && limit <= .15
                                && rig.upright() >= .45,
                        "Native finite/structural/upright gate");
                require(
                        Double.isFinite(control.commandedSlew)
                                && control.commandedSlew >= .1
                                && control.commandedSlew <= (pace.equals("super_horrific") ? 20 : 8)
                                && Double.isFinite(control.commandedPower)
                                && control.commandedPower >= 1
                                && control.commandedPower
                                        <= (pace.equals("super_horrific") ? 16 : 8),
                        "Selected finite actuator profile gate");
                require(
                        Math.abs(position.x - origin.x) < 248
                                && Math.abs(position.z - origin.z) < 248,
                        "Left clean flat fixture margin");
                maxSpeed = Math.max(maxSpeed, Math.hypot(velocity.x, velocity.z));
                if (tick == MEASURE_FROM) {
                    cruiseStart = new Vector3d(position);
                    previous = new Vector3d(position);
                }
                if (tick > MEASURE_FROM && tick <= CRUISE_END) {
                    sampleCount++;
                    backward += Math.max(0, -project(position, previous));
                    path += Math.hypot(position.x - previous.x, position.z - previous.z);
                    previous = new Vector3d(position);
                    if (supports > 0) {
                        supportSamples++;
                        supportVelocity += velocity.x * dx + velocity.z * dz;
                    }
                    noSupportTicks = supports == 0 ? noSupportTicks + 1 : 0;
                    maxNoSupportTicks = Math.max(maxNoSupportTicks, noSupportTicks);
                    minHeight = Math.min(minHeight, position.y);
                    maxHeight = Math.max(maxHeight, position.y);
                }
                if (tick == CRUISE_END) {
                    cruiseEnd = new Vector3d(position);
                    slowStart = new Vector3d(position);
                }
                if (tick > CRUISE_END)
                    slowDistance = Math.max(slowDistance, project(position, slowStart));
                if (tick == SLOW_END) stopStart = new Vector3d(position);
                if (tick >= SLOW_END) {
                    stopDrift =
                            Math.max(
                                    stopDrift,
                                    Math.hypot(position.x - stopStart.x, position.z - stopStart.z));
                    if (firstStoppedTick < 0 && Math.hypot(velocity.x, velocity.z) <= 1)
                        firstStoppedTick = tick;
                }
                if (tick >= END) {
                    success = done = true;
                }
            } catch (Exception e) {
                fail("native trial: " + e);
            }
            if (done) close();
        }

        Double speed() {
            return cruiseStart == null || cruiseEnd == null
                    ? null
                    : project(cruiseEnd, cruiseStart) / ((CRUISE_END - MEASURE_FROM) * .05);
        }

        void close() {
            if (closed) return;
            closed = true;
            if (rig != null)
                try {
                    rig.close();
                } catch (Exception e) {
                    fail("cleanup: " + e);
                }
        }

        JsonObject result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("slot", slot);
            r.put("round", round);
            r.put("pace", pace);
            r.put("success", success);
            r.put("complete", done);
            r.put("ticks", tick);
            r.put("failures", failures);
            r.put("metadata", metadata);
            r.put("history", history);
            r.put("cruise_forward_speed", speed());
            r.put("cruise_seconds", (CRUISE_END - MEASURE_FROM) * .05);
            r.put("cruise_samples", sampleCount);
            r.put("backward_travel", backward);
            r.put("cruise_path_length", path);
            r.put("support_proximity_samples", supportSamples);
            r.put(
                    "support_proximity_fraction",
                    sampleCount == 0 ? null : supportSamples / (double) sampleCount);
            r.put(
                    "velocity_during_support_proximity",
                    supportSamples == 0 ? null : supportVelocity / supportSamples);
            r.put("max_no_support_proximity_seconds", maxNoSupportTicks * .05);
            r.put("cruise_height_range", sampleCount == 0 ? null : maxHeight - minHeight);
            r.put("maximum_horizontal_speed", maxSpeed);
            r.put("anchor", anchor);
            r.put("limit", limit);
            r.put("minimum_up", minUp);
            r.put("slowdown_forward_distance", slowDistance);
            r.put("stop_drift_after_zero_request", stopDrift);
            r.put(
                    "seconds_to_speed_at_most1_after_zero_request",
                    firstStoppedTick < 0 ? null : (firstStoppedTick - SLOW_END) * .05);
            r.put("native_impulses", rig == null ? List.of() : rig.leapImpulses);
            var result = finite(TREE.toJsonTree(r), "$", nonfinite).getAsJsonObject();
            result.add("nonfinite_fields", TREE.toJsonTree(nonfinite));
            if (!nonfinite.isEmpty()) result.addProperty("success", false);
            return result;
        }
    }

    static class Run {
        final GameTestHelper helper;
        final List<Trial> trials = new ArrayList<>();
        final Vector3d[] origins = new Vector3d[4];
        MinecraftMachinesLeaperPredatorPolicy policy;
        int round, previousSubsteps;
        boolean configured;

        Run(GameTestHelper helper) {
            this.helper = helper;
        }

        void begin() throws Exception {
            require(
                    !Boolean.getBoolean("minecraft_machines.leaperLeap"),
                    "Ground speed comparison requires leap=false");
            require(
                    MinecraftMachinesLeaperLearning.profile().equals("horrific")
                            && Integer.getInteger("minecraft_machines.leaperSubsteps", 4) == 4
                            && Boolean.parseBoolean(
                                    System.getProperty("minecraft_machines.leaperGrip", "true"))
                            && Double.parseDouble(
                                            System.getProperty(
                                                    "minecraft_machines.leaperBalance", "1"))
                                    == 1,
                    "Require profileH/native4/grip/balance1");
            require(
                    Files.isRegularFile(
                            Path.of(
                                    "leaper_learning",
                                    MinecraftMachinesLeaperLearning.reportName("predator-candidate")
                                            + ".json")),
                    "Explicit unchanged base candidate required");
            policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
            var physics = SubLevelPhysicsSystem.get(helper.getLevel());
            previousSubsteps = physics.getConfig().substepsPerTick;
            physics.getConfig().substepsPerTick = 4;
            physics.onConfigUpdated();
            configured = true;
            try {
                for (int slot = 0; slot < 4; slot++) {
                    origins[slot] =
                            MinecraftMachinesLeaperLearning.arena(
                                    helper.getLevel(), slot, Blocks.SMOOTH_STONE, 256);
                    int ox = (int) Math.floor(origins[slot].x),
                            oz = (int) Math.floor(origins[slot].z);
                    for (int x = ox - 256; x <= ox + 256; x++)
                        for (int z = oz - 256; z <= oz + 256; z++) {
                            int top =
                                    helper.getLevel()
                                            .getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                            for (int y = 7; y < top; y++)
                                helper.getLevel()
                                        .setBlockAndUpdate(
                                                new BlockPos(x, y, z),
                                                Blocks.AIR.defaultBlockState());
                        }
                }
                nextRound();
                loop();
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        void nextRound() {
            for (int slot = 0; slot < 4; slot++) {
                var trial = new Trial(this, slot, round);
                trials.add(trial);
                trial.initialize();
            }
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            var active = trials.subList(round * 4, round * 4 + 4);
                            for (var trial : active) trial.step();
                            if (active.stream().allMatch(t -> t.done)) {
                                persist();
                                if (++round == 2) {
                                    close();
                                    helper.succeed();
                                } else {
                                    nextRound();
                                    loop();
                                }
                            } else loop();
                        } catch (Exception e) {
                            for (var t : trials) if (!t.done) t.fail("harness: " + e);
                            try {
                                persist();
                            } catch (Exception ignored) {
                            }
                            close();
                            helper.fail(e.toString());
                        }
                    });
        }

        void persist() throws Exception {
            var pairs = new ArrayList<Object>();
            double h = 0, s = 0;
            boolean comparable = trials.size() == 8;
            for (int slot = 0; slot < 4; slot++) {
                int pair = slot;
                var baseline =
                        trials.stream()
                                .filter(t -> t.slot == pair && t.pace.equals("horrific"))
                                .findFirst()
                                .orElse(null);
                var faster =
                        trials.stream()
                                .filter(t -> t.slot == pair && t.pace.equals("super_horrific"))
                                .findFirst()
                                .orElse(null);
                boolean valid =
                        baseline != null
                                && faster != null
                                && baseline.success
                                && faster.success
                                && baseline.speed() != null
                                && faster.speed() != null
                                && baseline.speed() > 0
                                && Double.isFinite(baseline.speed())
                                && Double.isFinite(faster.speed());
                var row = new LinkedHashMap<String, Object>();
                row.put("slot", slot);
                row.put("comparable", valid);
                row.put("horrific_speed", baseline == null ? null : baseline.speed());
                row.put("super_horrific_speed", faster == null ? null : faster.speed());
                row.put("ratio", valid ? faster.speed() / baseline.speed() : null);
                pairs.add(row);
                comparable &= valid;
                if (valid) {
                    h += baseline.speed();
                    s += faster.speed();
                }
            }
            var r = new LinkedHashMap<String, Object>();
            r.put("mode", MODE);
            r.put("qualified", false);
            r.put("complete", trials.size() == 8 && trials.stream().allMatch(t -> t.done));
            r.put("expected_trials", 8);
            r.put("genome", policy.genome);
            r.put("parameters", policy.named());
            r.put("leap_enabled", false);
            r.put("control_hz", 20);
            r.put("physics_config", SubLevelPhysicsSystem.get(helper.getLevel()).getConfig());
            r.put(
                    "comparison",
                    "Four same-slot, same-heading pairs with fresh rigs; pace order reversed across"
                            + " slots; no run resets");
            r.put(
                    "measurement",
                    "All cruise displacement over7 simulated seconds, including backward and"
                            + " unsupported samples; support is a separate proximity estimate");
            r.put("matched_pairs", pairs);
            r.put("comparable", comparable);
            r.put("mean_horrific_speed", comparable ? h / 4 : null);
            r.put("mean_super_horrific_speed", comparable ? s / 4 : null);
            r.put("measured_speed_ratio", comparable ? s / h : null);
            r.put("requested_ratio", 3);
            r.put("trials", trials.stream().map(Trial::result).toList());
            MinecraftMachinesLeaperLearning.write(MODE, r);
        }

        void close() {
            for (var trial : trials) trial.close();
            if (configured) {
                var physics = SubLevelPhysicsSystem.get(helper.getLevel());
                physics.getConfig().substepsPerTick = previousSubsteps;
                physics.onConfigUpdated();
                configured = false;
            }
        }
    }
}
