package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPredatorInteractionTests {
    @GameTest(template = "empty", timeoutTicks = 8000, batch = "leaperPredatorInteraction")
    public static void interactions(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-interaction")) {
            helper.succeed();
            return;
        }
        new Run(helper).begin();
    }

    static class Trial {
        final int kind;
        final MinecraftMachinesLeaperTerrainCourse course;
        final MinecraftMachinesLeaper rig;
        final MinecraftMachinesLeaperPredatorNavigator nav;
        final Vector3d start, originalGoal;
        final List<Object> history = new ArrayList<>();
        int tick, settled, arrivalTick, fallen, maxFallen;
        boolean done, success, rejected, retargeted;
        String reason = "";
        Vector3d stopPosition, retargetGoal;
        boolean pendingRetarget, invalidPendingPreserved;
        double stopDrift, minUp = 1, anchor, limits, maxSpeed, maxAngularSpeed, previousCycles;
        boolean finiteState = true, valid = true, physicalFailure;
        final List<String> nonfiniteFields = new ArrayList<>();
        final MinecraftMachinesLeaperPendingFixture pendingFixture;

        Trial(GameTestHelper h, int i, MinecraftMachinesLeaperPredatorPolicy policy) {
            kind = i;
            int terrain = new int[] {10, 12, 13, 0, 3, 9}[i];
            course =
                    new MinecraftMachinesLeaperTerrainCourse(
                            new String[] {
                                        "moving-prey-hills",
                                        "moving-prey-forest",
                                        "retarget-pond",
                                        "continuous-pace-change",
                                        "stop-resume-rough",
                                        "dynamic-obstacle"
                                    }
                                    [i],
                            terrain,
                            1,
                            3,
                            0,
                            0,
                            "horrific",
                            i == 1 ? 56 : 48,
                            9001 + i);
            start = course.build(h.getLevel(), i);
            rig = MinecraftMachinesLeaper.spawn(h.getLevel(), start);
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, h.getLevel(), policy);
            pendingFixture =
                    kind == 2 ? new MinecraftMachinesLeaperPendingFixture(nav, start) : null;
            originalGoal = course.goal(start);
            originalGoal.y = nav.map.height(originalGoal.x, originalGoal.z, originalGoal.y + 5);
            if (!nav.go(originalGoal.x, originalGoal.y, originalGoal.z)) {
                done = true;
                reason = nav.reason;
            }
        }

        void fail(String message) {
            done = true;
            success = false;
            reason = reason.isEmpty() ? message : reason + "; " + message;
        }

        void restoreFixture() {
            if (pendingFixture != null && pendingFixture.cleanupNeeded) {
                try {
                    pendingFixture.restore(tick, true);
                } catch (RuntimeException e) {
                    fail("Fixture cleanup failed: " + e);
                }
            }
        }

        void close() {
            restoreFixture();
            rig.close();
        }

        static boolean finite(double... values) {
            for (double value : values) if (!Double.isFinite(value)) return false;
            return true;
        }

        boolean checkVector(String name, Vector3d value) {
            if (value == null || finite(value.x, value.y, value.z)) return true;
            nonfiniteFields.add(name);
            return false;
        }

        record Physical(
                Vector3d position,
                Vector3d velocity,
                Vector3d angularVelocity,
                Vector3d up,
                Vector3d core,
                org.joml.Quaterniond orientation,
                double anchor,
                double limits,
                double[] angles,
                List<Vector3d> feet) {}

        Physical samplePhysical(boolean countTick) {
            if (!rig.valid()) {
                valid = false;
                physicalFailure = true;
                fail("Physical failure: invalid rig");
                return null;
            }
            try {
                var feet = new ArrayList<Vector3d>();
                for (int i = 0; i < 4; i++) feet.add(rig.toe(i));
                var state =
                        new Physical(
                                rig.position(),
                                rig.velocity(),
                                rig.angularVelocity(),
                                rig.up(),
                                rig.corePivot(),
                                rig.coreOrientation(),
                                rig.anchorError(),
                                rig.limitViolation(),
                                rig.angles(),
                                feet);
                boolean ok =
                        checkVector("position", state.position)
                                & checkVector("velocity", state.velocity)
                                & checkVector("angular_velocity", state.angularVelocity)
                                & checkVector("up", state.up)
                                & checkVector("core", state.core)
                                & checkVector("goal", nav.goal)
                                & checkVector("pending_goal", nav.pendingGoal);
                for (int i = 0; i < 4; i++) ok &= checkVector("toe_" + i, feet.get(i));
                if (!finite(
                        state.orientation.x,
                        state.orientation.y,
                        state.orientation.z,
                        state.orientation.w)) {
                    ok = false;
                    nonfiniteFields.add("orientation");
                }
                if (!finite(state.anchor, state.limits) || !finite(state.angles)) {
                    ok = false;
                    nonfiniteFields.add("joint_state");
                }
                if (!finite(
                        nav.control.cycles,
                        nav.control.ground,
                        nav.control.gradientX,
                        nav.control.gradientZ)) {
                    ok = false;
                    nonfiniteFields.add("controller_state");
                }
                if (!ok) {
                    finiteState = false;
                    physicalFailure = true;
                    fail("Physical failure: nonfinite state " + nonfiniteFields);
                    return null;
                }
                double speed = Math.hypot(state.velocity.x, state.velocity.z);
                double angularSpeed = state.angularVelocity.length();
                if (!finite(speed, angularSpeed)) {
                    finiteState = false;
                    physicalFailure = true;
                    nonfiniteFields.add("derived_speed");
                    fail("Physical failure: nonfinite derived speed");
                    return null;
                }
                maxSpeed = Math.max(maxSpeed, speed);
                maxAngularSpeed = Math.max(maxAngularSpeed, angularSpeed);
                minUp = Math.min(minUp, state.up.y);
                anchor = Math.max(anchor, state.anchor);
                limits = Math.max(limits, state.limits);
                if (countTick) {
                    fallen = state.up.y < .5 ? fallen + 1 : 0;
                    maxFallen = Math.max(maxFallen, fallen);
                }
                // Preserve the existing immediate-up guard and enforce the critic's structural cap.
                if (state.up.y < .4
                        || maxFallen > 30
                        || anchor > .12
                        || limits > .15
                        || state.position.y < 0) {
                    physicalFailure = true;
                    fail("Physical failure");
                }
                return state;
            } catch (RuntimeException e) {
                valid = false;
                physicalFailure = true;
                fail("Physical state read failed: " + e);
                return null;
            }
        }

        void step() {
            if (done) return;
            try {
                stepChecked();
            } catch (RuntimeException e) {
                fail("Interaction exception: " + e);
            } finally {
                if (done) restoreFixture();
            }
        }

        void stepChecked() {
            ++tick;
            // Restoration runs before any early-returning state guard on tick 101.
            if (pendingFixture != null && tick == 101) pendingFixture.restore(tick, false);
            var beforeState = samplePhysical(false);
            if (beforeState == null || done) return;
            if (kind < 2 && tick % 10 == 1) {
                double d = Math.min(course.distance(), 18 + Math.max(0, tick - 80) * .05 * 2.8),
                        x = start.x,
                        z = start.z - d,
                        y = nav.map.height(x, z, start.y + 12);
                if (Double.isFinite(y)) nav.pursue(x, y, z);
            }
            if (kind == 2 && tick == 100) {
                pendingFixture.begin(tick);
                retargetGoal = new Vector3d(pendingFixture.target);
                retargeted = pendingRetarget = pendingFixture.pending;
                invalidPendingPreserved = pendingFixture.invalidPreserved;
            }
            if (kind == 3) {
                if (tick == 160) nav.pace = "careful";
                if (tick == 300) nav.pace = "fast";
                if (tick == 420) nav.pace = "horrific";
            }
            if (kind == 4 && tick == 170) {
                stopPosition = rig.position();
                nav.stop();
            }
            if (kind == 4 && tick > 170 && tick < 230)
                stopDrift =
                        Math.max(
                                stopDrift,
                                Math.hypot(
                                        rig.position().x - stopPosition.x,
                                        rig.position().z - stopPosition.z));
            if (kind == 4
                    && tick == 230
                    && !nav.go(originalGoal.x, originalGoal.y, originalGoal.z)) {
                done = true;
                reason = "Resume rejected";
            }
            if (kind == 5 && tick == 120) {
                int x = (int) Math.floor(start.x), z = (int) Math.floor(start.z - 26);
                for (int dx = -6; dx <= 6; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        for (int y = 7; y <= 13; y++)
                            nav.level.setBlockAndUpdate(
                                    new BlockPos(x + dx, y, z + dz),
                                    Blocks.STONE.defaultBlockState());
                var before = new Vector3d(nav.goal);
                rejected = !nav.go(x + .5, 14, z + .5) && before.equals(nav.goal);
            }
            if (done) return;
            nav.step();
            var state = samplePhysical(true);
            if (state == null) return;
            var p = state.position;
            double speed = Math.hypot(state.velocity.x, state.velocity.z);
            if (nav.control.cycles < previousCycles) fail("Gait phase reset");
            previousCycles = nav.control.cycles;
            if (!done && pendingFixture != null) pendingFixture.observe();
            boolean finished =
                    !done
                            && tick > (kind < 2 ? 450 : kind == 3 ? 440 : kind == 4 ? 250 : 200)
                            && nav.arrived
                            && nav.goal != null
                            && Math.hypot(p.x - nav.goal.x, p.z - nav.goal.z) <= 1
                            && speed <= .5;
            if (finished) {
                if (settled == 0) arrivalTick = tick;
                if (++settled >= 40) {
                    success = kind != 5 || rejected && nav.replans > 0;
                    success &= kind != 4 || stopDrift < 1.5;
                    success &=
                            kind != 2
                                    || nav.pendingGoal == null
                                            && retargetGoal != null
                                            && retargetGoal.equals(nav.goal)
                                            && Math.hypot(
                                                            p.x - retargetGoal.x,
                                                            p.z - retargetGoal.z)
                                                    < 1
                                            && pendingRetarget
                                            && invalidPendingPreserved
                                            && pendingFixture.complete();
                    if (kind == 2 && success)
                        pendingFixture.arrival(
                                arrivalTick,
                                tick,
                                Math.hypot(p.x - retargetGoal.x, p.z - retargetGoal.z));
                    done = true;
                    if (!success) reason = "Interaction invariant";
                }
            } else {
                settled = 0;
                arrivalTick = 0;
            }
            if (tick >= 2480 && !done) {
                done = true;
                reason = "timeout";
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("tick", tick);
            row.put("server_tick", nav.level.getGameTime());
            row.put("position", MinecraftMachinesLeaperPendingFixture.point(p));
            row.put("velocity", MinecraftMachinesLeaperPendingFixture.point(state.velocity));
            row.put(
                    "angular_velocity",
                    MinecraftMachinesLeaperPendingFixture.point(state.angularVelocity));
            row.put("up", MinecraftMachinesLeaperPendingFixture.point(state.up));
            row.put("core", MinecraftMachinesLeaperPendingFixture.point(state.core));
            row.put(
                    "orientation",
                    List.of(
                            state.orientation.x,
                            state.orientation.y,
                            state.orientation.z,
                            state.orientation.w));
            row.put("speed", speed);
            row.put("pace", nav.pace);
            row.put("goal", MinecraftMachinesLeaperPendingFixture.point(nav.goal));
            row.put(
                    "distance",
                    nav.goal == null ? 0 : Math.hypot(p.x - nav.goal.x, p.z - nav.goal.z));
            row.put("pending_goal", MinecraftMachinesLeaperPendingFixture.point(nav.pendingGoal));
            row.put("anchor", state.anchor);
            row.put("limit_violation", state.limits);
            row.put("angles", state.angles);
            row.put("arrived", nav.arrived);
            row.put("cycles", nav.control.cycles);
            row.put("ground", nav.control.ground);
            row.put(
                    "feet",
                    state.feet.stream().map(MinecraftMachinesLeaperPendingFixture::point).toList());
            history.add(row);
        }

        Map<String, Object> result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("case", course.name());
            r.put("success", success);
            r.put("reason", reason);
            r.put("seconds", Math.max(0, tick - 80) * .05);
            r.put("stop_drift", stopDrift);
            r.put("minimum_up", minUp);
            r.put("anchor_error", anchor);
            r.put("maximum_speed", maxSpeed);
            r.put("maximum_angular_speed", maxAngularSpeed);
            r.put("limit_violation", limits);
            r.put("max_fallen_ticks", maxFallen);
            r.put("valid", valid);
            r.put("finite_state", finiteState);
            r.put("physical_failure", physicalFailure);
            r.put("nonfinite_fields", nonfiniteFields);
            r.put("arrival_window_start_tick", success ? arrivalTick : 0);
            r.put("completion_tick", tick);
            if (pendingFixture != null) r.putAll(pendingFixture.report());
            r.put("replans", nav.replans);
            r.put("recoveries", nav.recoveries);
            r.put("rejected_target_preserved_route", rejected);
            r.put("pending_retarget", pendingFixture != null && pendingFixture.pending);
            r.put(
                    "invalid_pending_request_preserved",
                    pendingFixture != null && pendingFixture.invalidPreserved);
            r.put(
                    "retarget_goal_reached",
                    kind == 2
                            && success
                            && pendingFixture.complete()
                            && retargetGoal != null
                            && retargetGoal.equals(nav.goal));
            r.put("max_plan_ms", nav.maximumPlanMillis);
            r.put("mean_plan_ms", nav.totalPlanMillis / Math.max(1, nav.plans));
            r.put("history", history);
            return r;
        }
    }

    static class Run {
        final GameTestHelper helper;
        final List<Trial> trials = new ArrayList<>();
        final MinecraftMachinesLeaperPredatorPolicy policy;
        int tick;

        Run(GameTestHelper h) throws Exception {
            helper = h;
            policy = MinecraftMachinesLeaperPredatorExperiments.candidate();
        }

        void begin() {
            for (int i = 0; i < 6; i++) trials.add(new Trial(helper, i, policy));
            loop();
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                        } catch (Exception e) {
                            trials.forEach(Trial::close);
                            helper.fail("Predator interactions: " + e);
                        }
                    });
        }

        void step() throws Exception {
            ++tick;
            trials.forEach(Trial::step);
            if (tick % 300 == 0)
                MinecraftMachines.LOGGER.info(
                        "PREDATOR_INTERACTION tick={} states={}",
                        tick,
                        trials.stream()
                                .map(
                                        t ->
                                                t.course.name()
                                                        + ":"
                                                        + String.format(
                                                                Locale.ROOT, "%.1f", t.nav.distance)
                                                        + (t.done
                                                                ? t.success
                                                                        ? " PASS"
                                                                        : " FAIL " + t.reason
                                                                : ""))
                                .toList());
            if (trials.stream().anyMatch(t -> !t.done)) {
                loop();
                return;
            }
            trials.forEach(Trial::restoreFixture);
            var rows = trials.stream().map(Trial::result).toList();
            MinecraftMachinesLeaperLearning.write(
                    "predator-interaction",
                    Map.of(
                            "interaction_fixture_version",
                            MinecraftMachinesLeaperPendingFixture.VERSION,
                            "fixture_spec_sha256",
                            MinecraftMachinesLeaperPendingFixture.SPEC_SHA256,
                            "control_hz",
                            20,
                            "highstep_settings",
                            MinecraftMachinesLeaperHighStepSettings.effective(
                                    trials.getFirst().nav.control),
                            "policy",
                            MinecraftMachinesLeaperPredatorPolicy.VERSION,
                            "genome",
                            policy.genome,
                            "validated",
                            trials.stream().allMatch(t -> t.success),
                            "trials",
                            rows));
            trials.forEach(Trial::close);
            helper.assertTrue(
                    trials.stream().allMatch(t -> t.success), "Predator interaction failures");
            helper.succeed();
        }
    }
}
