package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;

import org.joml.Vector3d;

import java.util.*;

/** One destination trial, shared by navigation search and independent qualification. */
final class MinecraftMachinesLeaperEpisode {
    static final int WARMUP = 100;
    final MinecraftMachinesLeaper rig;
    final double[] gait, nav;
    final double dx, dz, initialHeading;
    final boolean push, record;
    final int horizon;
    final double maxTravel;
    final double cadence, slew, power, minimumUp;
    final boolean sprint;
    LeaperPointController controller;
    LeaperPointController.Command command;
    Vector3d start, previous;
    int tick, arrivalTick, doneTick;
    boolean failed, success;
    String reason = "";
    double minUp = 1, anchor, path, maxHoldError, finalError, peakSpeed;
    final List<Object> history = new ArrayList<>();

    MinecraftMachinesLeaperEpisode(
            MinecraftMachinesLeaper rig,
            double[] gait,
            double[] nav,
            double dx,
            double dz,
            double heading,
            boolean push,
            int horizon,
            boolean record) {
        this(rig, gait, nav, dx, dz, heading, push, horizon, record, 42);
    }

    MinecraftMachinesLeaperEpisode(
            MinecraftMachinesLeaper rig,
            double[] gait,
            double[] nav,
            double dx,
            double dz,
            double heading,
            boolean push,
            int horizon,
            boolean record,
            double maxTravel) {
        this(
                rig,
                new MinecraftMachinesLeaperModel(gait, nav),
                dx,
                dz,
                heading,
                push,
                horizon,
                record,
                maxTravel);
    }

    MinecraftMachinesLeaperEpisode(
            MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperModel model,
            double dx,
            double dz,
            double heading,
            boolean push,
            int horizon,
            boolean record,
            double maxTravel) {
        this.rig = rig;
        this.gait = model.gait().clone();
        this.nav = model.navigation().clone();
        cadence = model.cadence();
        slew = model.slew();
        power = model.power();
        sprint = model.sprint();
        minimumUp = model.minimumUp();
        this.dx = dx;
        this.dz = dz;
        initialHeading = heading;
        this.push = push;
        this.horizon = horizon;
        this.record = record;
        this.maxTravel = maxTravel;
        rig.reset(heading);
    }

    boolean done() {
        return failed || success || tick >= WARMUP + horizon;
    }

    void step() {
        if (done()) return;
        ++tick;
        var p = rig.position();
        var up = rig.up();
        if (!rig.valid()
                || !Double.isFinite(p.x + p.y + p.z)
                || up.y < minimumUp
                || rig.jointStrain() > .04) {
            failed = true;
            reason = "posture or joint failure";
            return;
        }
        minUp = Math.min(minUp, up.y);
        anchor = Math.max(anchor, rig.jointStrain());
        if (tick <= WARMUP) {
            rig.command(
                    LeaperGait.action(gait, 0, rig.heading(), up.x, up.z, 0, 0, 0, sprint),
                    .05,
                    slew,
                    power);
            start = new Vector3d(p);
            previous = new Vector3d(p);
            return;
        }
        if (controller == null)
            controller = new LeaperPointController(nav, start.x + dx, start.z + dz);
        if (command == null || (tick - WARMUP) % 2 == 1)
            command = controller.update(p.x, p.z, rig.heading(), .1);
        rig.command(
                LeaperGait.action(
                        gait,
                        (tick - WARMUP) * .05 * cadence,
                        rig.heading(),
                        up.x,
                        up.z,
                        command.forward(),
                        command.strafe(),
                        command.turn(),
                        sprint),
                .05,
                slew,
                power);
        finalError = Math.hypot(p.x - controller.goalX(), p.z - controller.goalZ());
        path += Math.hypot(p.x - previous.x, p.z - previous.z);
        previous = new Vector3d(p);
        peakSpeed = Math.max(peakSpeed, Math.hypot(rig.velocity().x, rig.velocity().z));
        if (Math.hypot(p.x - start.x, p.z - start.z) > maxTravel || Math.abs(p.y - start.y) > 1.3) {
            failed = true;
            reason = "left safe arena";
            return;
        }
        if (push && tick == WARMUP + 220) rig.disturb(.45, -.25, .15);
        if (command.arrived() && arrivalTick == 0) {
            arrivalTick = tick;
            maxHoldError = finalError;
        }
        if (arrivalTick > 0) {
            maxHoldError = Math.max(maxHoldError, finalError);
            if (finalError > .48) arrivalTick = 0;
            else if (tick - arrivalTick >= 100) {
                success = true;
                doneTick = tick;
            }
        }
        if (record && (tick - WARMUP) % 20 == 0)
            history.add(
                    Map.of(
                            "tick",
                            tick - WARMUP,
                            "position",
                            List.of(p.x, p.y, p.z),
                            "heading",
                            rig.heading(),
                            "distance",
                            finalError,
                            "up",
                            up.y,
                            "command",
                            List.of(command.forward(), command.strafe(), command.turn())));
    }

    double score() {
        double distance = Math.max(.5, Math.hypot(dx, dz));
        return failed
                ? -100
                : 50 * (1 - Math.min(2, finalError / distance))
                        + (success ? 50 : 0)
                        - .025 * (success ? arrivalTick - WARMUP : horizon) * .05
                        - .15 * path / distance
                        - 6 * finalError
                        - 100 * (1 - minUp);
    }

    Map<String, Object> result() {
        var r = new LinkedHashMap<String, Object>();
        if (start != null) r.put("start", List.of(start.x, start.y, start.z));
        if (controller != null) r.put("goal", List.of(controller.goalX(), controller.goalZ()));
        r.put("goal_offset", List.of(dx, dz));
        r.put("initial_heading", initialHeading);
        r.put("push", push);
        r.put("success", success);
        r.put("failed", failed);
        r.put("reason", reason);
        r.put("final_error", finalError);
        r.put("arrival_seconds", (arrivalTick > 0 ? arrivalTick - WARMUP : horizon) * .05);
        r.put("minimum_up", minUp);
        r.put("anchor_error", anchor);
        r.put("path", path);
        r.put("max_hold_error", maxHoldError);
        r.put("peak_speed", peakSpeed);
        r.put("history", history);
        return r;
    }
}
