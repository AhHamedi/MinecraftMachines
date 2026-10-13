package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.*;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

/** Matched motor-driven heading and real navigation trials. Labels never change behavior. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperTurningTests {
    static final String PREFIX = "predator-turning-";
    static final int SETTLE = 80, LIMIT = 880, WATCH = 80;
    static final Gson JSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    @GameTest(template = "empty", timeoutTicks = 1800, batch = "leaperTurning")
    public static void turning(GameTestHelper helper) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!mode.startsWith(PREFIX)) {
            helper.succeed();
            return;
        }
        new Run(helper, mode).begin();
    }

    static void require(boolean condition, String why) {
        if (!condition) throw new IllegalStateException(why);
    }

    static double[] xyz(Vector3d v) {
        return new double[] {v.x, v.y, v.z};
    }

    static class Trial {
        final Run run;
        final int id;
        final boolean pursuit, sprint;
        final double heading;
        final List<JsonElement> history = new ArrayList<>();
        final List<String> failures = new ArrayList<>(), nonfinite = new ArrayList<>();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        Vector3d origin, goal, initial;
        int tick, alignedTick = -1, stableTick = -1, arrivedTick = -1, stableSamples;
        double previousHeading, maxAnchor, maxLimit, minUp = 1, maxYawRate, maxHoldDrift;
        Vector3d holdStart;
        boolean done, success, accepted, closed;

        Trial(Run run, int id) {
            this.run = run;
            this.id = id;
            pursuit = id >= 4;
            sprint = (id % 4) >= 2;
            heading = id % 2 == 0 ? Math.PI / 2 : -Math.PI;
        }

        void begin() {
            try {
                int radius = pursuit ? 112 : 40;
                var level = run.helper.getLevel();
                origin =
                        MinecraftMachinesLeaperLearning.arena(
                                level, id, Blocks.SMOOTH_STONE, radius);
                int ox = (int) Math.floor(origin.x), oz = (int) Math.floor(origin.z);
                for (int x = ox - radius; x <= ox + radius; x++)
                    for (int z = oz - radius; z <= oz + radius; z++) {
                        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        for (int y = 7; y < top; y++)
                            level.setBlockAndUpdate(
                                    new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                rig = MinecraftMachinesLeaper.spawn(level, origin);
                rig.reset(0);
                nav = new MinecraftMachinesLeaperPredatorNavigator(rig, level, run.policy);
                nav.pace = sprint ? "super_horrific" : "horrific";
                nav.control.setPace(nav.pace);
                require(
                        nav.control.agile && !nav.leap.enabled,
                        "Require agile walking and leap disabled");
                require(
                        nav.control.balance == 1
                                && rig.motorForceScale == 1
                                && rig.motorDampingScale == 1,
                        "Unexpected actuator/balance scale");
                var bodies = MinecraftMachinesLeaperSuperSpeedTests.bodies(rig);
                require(bodies.size() == 13, "Expected13 bodies");
                double mass = 0;
                for (int i = 0; i < bodies.size(); i++) {
                    double m = bodies.get(i).getMassTracker().getMass();
                    double expected = i == 0 ? 27 : new double[] {1, 8, 6}[(i - 1) % 3];
                    require(
                            Double.isFinite(m) && Math.abs(m - expected) < 1e-6,
                            "Body mass mismatch");
                    mass += m;
                }
                metadata.put("mass", mass);
                metadata.put(
                        "settings", MinecraftMachinesLeaperHighStepSettings.effective(nav.control));
                metadata.put("gravity", xyz(rig.gravity()));
                metadata.put("motor_slew_limit", sprint ? 20 : 8);
                metadata.put("motor_power_limit", sprint ? 16 : 8);
                double distance = sprint ? 64 : 32;
                goal =
                        new Vector3d(
                                origin.x + distance * Math.sin(heading),
                                7,
                                origin.z - distance * Math.cos(heading));
                metadata.put("goal", xyz(goal));
                previousHeading = rig.heading();
            } catch (Exception e) {
                fail("setup: " + e);
            }
        }

        void fail(String reason) {
            failures.add(reason);
            done = true;
            success = false;
        }

        void step() {
            if (done) return;
            tick++;
            try {
                double current = rig.heading();
                double rate = LeaperGait.wrap(current - previousHeading) / .05;
                previousHeading = current;
                double error = LeaperGait.wrap(heading - current);
                double turn = 0;
                if (tick <= SETTLE) nav.control.step(0, 0, 0, 1);
                else {
                    if (tick == SETTLE + 1) {
                        initial = rig.position();
                        if (pursuit) {
                            accepted = nav.go(goal.x, goal.y, goal.z);
                            require(accepted, "go rejected: " + nav.reason);
                        }
                    }
                    if (pursuit) nav.step();
                    else if (stableTick < 0) {
                        nav.facingRate += .2 * (rate - nav.facingRate);
                        turn = nav.turnCommand(error, sprint);
                        nav.control.step(0, 0, turn, 1);
                    } else nav.control.step(0, 0, 0, 1);
                }
                var position = rig.position();
                var velocity = rig.velocity();
                var row = new LinkedHashMap<String, Object>();
                row.put("tick", tick);
                row.put("position", xyz(position));
                row.put("velocity", xyz(velocity));
                row.put("heading", current);
                row.put("heading_error", error);
                row.put("yaw_rate", rate);
                row.put("turn", turn);
                row.put("up", rig.upright());
                row.put("anchor", rig.anchorError());
                row.put("limit", rig.limitViolation());
                row.put("angles", rig.angles());
                row.put("targets", rig.boundedMotorTargets());
                row.put("power", nav.control.commandedPower);
                row.put("slew", nav.control.commandedSlew);
                row.put("period", nav.control.commandedPeriod);
                row.put("stride", nav.control.commandedStride);
                row.put("arrived", nav.arrived);
                row.put("holding", nav.control.holding);
                row.put("reason", nav.reason);
                row.put("distance", Math.hypot(position.x - goal.x, position.z - goal.z));
                history.add(
                        MinecraftMachinesLeaperSuperSpeedTests.finite(
                                JSON.toJsonTree(row), "tick" + tick, nonfinite));
                maxAnchor = Math.max(maxAnchor, rig.anchorError());
                maxLimit = Math.max(maxLimit, rig.limitViolation());
                minUp = Math.min(minUp, rig.upright());
                require(
                        nonfinite.isEmpty()
                                && rig.valid()
                                && maxAnchor <= .12
                                && maxLimit <= .15
                                && minUp >= .45,
                        "Finite/structural/upright gate");
                require(rig.leapImpulses.isEmpty(), "Unexpected takeoff impulse");
                require(
                        Math.abs(position.x - origin.x) < 104
                                && Math.abs(position.z - origin.z) < 104,
                        "Left fixture margin");
                if (tick > SETTLE) {
                    maxYawRate = Math.max(maxYawRate, Math.abs(rate));
                    if (alignedTick < 0 && Math.abs(error) <= Math.toRadians(10))
                        alignedTick = tick;
                    if (!pursuit && stableTick < 0) {
                        stableSamples =
                                Math.abs(error) <= Math.toRadians(10) && Math.abs(rate) < .25
                                        ? stableSamples + 1
                                        : 0;
                        if (stableSamples >= 10) {
                            stableTick = tick;
                            holdStart = new Vector3d(position);
                        }
                    }
                    if (pursuit && arrivedTick < 0 && nav.arrived) {
                        arrivedTick = tick;
                        holdStart = new Vector3d(position);
                    }
                    int entry = pursuit ? arrivedTick : stableTick;
                    if (entry >= 0) {
                        maxHoldDrift =
                                Math.max(
                                        maxHoldDrift,
                                        Math.hypot(
                                                position.x - holdStart.x,
                                                position.z - holdStart.z));
                        if (tick >= entry + WATCH) {
                            boolean settled =
                                    Math.hypot(velocity.x, velocity.z) < 1
                                            && Math.abs(velocity.y) < .75
                                            && rig.upright() > .65;
                            if (pursuit)
                                require(
                                        nav.arrived
                                                && nav.goal != null
                                                && nav.goal.distance(goal) < 1e-6
                                                && Math.hypot(
                                                                position.x - goal.x,
                                                                position.z - goal.z)
                                                        <= 2.5
                                                && settled,
                                        "Actual goal arrival/settling");
                            else
                                require(
                                        Math.abs(error) <= Math.toRadians(15)
                                                && maxHoldDrift <= 2.5
                                                && settled,
                                        "Turn stop/heading retention");
                            success = done = true;
                        }
                    }
                }
                if (!done && tick >= LIMIT)
                    fail("Timed out before turn/arrival and hold completed");
            } catch (Exception e) {
                fail("native: " + e);
            }
            if (done) close();
        }

        Map<String, Object> result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("id", id);
            r.put("pace", sprint ? "super_horrific" : "horrific");
            r.put("pursuit", pursuit);
            r.put("heading_degrees", Math.toDegrees(heading));
            r.put("complete", done);
            r.put("success", success);
            r.put("ticks", tick);
            r.put("failures", failures);
            r.put("nonfinite", nonfinite);
            r.put("aligned_seconds", alignedTick < 0 ? null : (alignedTick - SETTLE) * .05);
            r.put("stable_turn_seconds", stableTick < 0 ? null : (stableTick - SETTLE) * .05);
            r.put("arrival_seconds", arrivedTick < 0 ? null : (arrivedTick - SETTLE) * .05);
            r.put("maximum_yaw_rate", maxYawRate);
            r.put("max_anchor", maxAnchor);
            r.put("max_limit", maxLimit);
            r.put("minimum_up", minUp);
            r.put("hold_drift", maxHoldDrift);
            r.put("metadata", metadata);
            r.put("history", history);
            r.put("impulses", rig == null ? List.of() : rig.leapImpulses);
            return r;
        }

        void close() {
            if (!closed && rig != null) {
                closed = true;
                try {
                    rig.close();
                } catch (Exception e) {
                    fail("cleanup: " + e);
                }
            }
        }
    }

    static class Run {
        final GameTestHelper helper;
        final String mode;
        final List<Trial> trials = new ArrayList<>();
        MinecraftMachinesLeaperPredatorPolicy policy;
        int originalSubsteps;

        Run(GameTestHelper helper, String mode) {
            this.helper = helper;
            this.mode = mode;
        }

        void begin() throws Exception {
            require(
                    MinecraftMachinesLeaperLearning.profile().equals("horrific")
                            && Integer.getInteger("minecraft_machines.leaperSubsteps", 4) == 4
                            && Boolean.parseBoolean(
                                    System.getProperty("minecraft_machines.leaperGrip", "true")),
                    "Require H/native4/grip");
            require(
                    Files.isRegularFile(
                            Path.of("leaper_learning", "horrific-predator-candidate.json")),
                    "Explicit candidate required");
            policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
            var physics = SubLevelPhysicsSystem.get(helper.getLevel());
            originalSubsteps = physics.getConfig().substepsPerTick;
            physics.getConfig().substepsPerTick = 4;
            physics.onConfigUpdated();
            for (int i = 0; i < 8; i++) {
                var t = new Trial(this, i);
                trials.add(t);
                t.begin();
            }
            loop();
        }

        void persist() throws Exception {
            var r = new LinkedHashMap<String, Object>();
            r.put("mode", mode);
            r.put("qualified", false);
            r.put("genome", policy.genome);
            r.put("parameters", policy.named());
            r.put("expected_trials", 8);
            r.put("completed", trials.stream().filter(t -> t.done).count());
            r.put("passed", trials.stream().filter(t -> t.success).count());
            r.put("control_hz", 20);
            r.put("physics", SubLevelPhysicsSystem.get(helper.getLevel()).getConfig());
            r.put("trials", trials.stream().map(Trial::result).toList());
            MinecraftMachinesLeaperLearning.write(mode, r);
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            for (var t : trials) t.step();
                            if (trials.stream().allMatch(t -> t.done)) {
                                persist();
                                for (var t : trials) t.close();
                                var physics = SubLevelPhysicsSystem.get(helper.getLevel());
                                physics.getConfig().substepsPerTick = originalSubsteps;
                                physics.onConfigUpdated();
                                helper.succeed();
                            } else loop();
                        } catch (Exception e) {
                            for (var t : trials) {
                                if (!t.done) t.fail("harness: " + e);
                                t.close();
                            }
                            try {
                                persist();
                            } catch (Exception write) {
                                e.addSuppressed(write);
                            }
                            helper.fail(e.toString());
                        }
                    });
        }
    }
}
