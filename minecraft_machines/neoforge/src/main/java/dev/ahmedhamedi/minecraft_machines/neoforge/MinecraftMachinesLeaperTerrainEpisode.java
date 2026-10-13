package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperTerrainPlanner;

import org.joml.Vector3d;

import java.util.*;

/** Native physics episode shared by terrain optimization, qualification and route tests. */
final class MinecraftMachinesLeaperTerrainEpisode {
    final MinecraftMachinesLeaper rig;
    final MinecraftMachinesLeaperTerrainControl terrain;
    final MinecraftMachinesLeaperTerrainMap map;
    final MinecraftMachinesLeaperTerrainCourse course;
    final Vector3d start, goal;
    final LeaperPointController nav;
    final MinecraftMachinesLeaperTerrainNavigator navigator;
    final List<LeaperTerrainPlanner.Point> route;
    final int horizon;
    final boolean record;
    LeaperPointController.Command command;
    int tick, settled, arrival, index;
    double error,
            minUp = 1,
            anchor,
            limit,
            minClearance = 99,
            path,
            maxSpeed,
            upTravel,
            downTravel,
            lastY;
    boolean done, success, failed;
    String reason = "";
    Vector3d previous;
    final Vector3d[] lastToes = new Vector3d[4];
    final boolean[] lifted = new boolean[4], lastNear = new boolean[4];
    final int[] plants = new int[4];
    double nearGroundFootMotion;
    final List<Object> history = new ArrayList<>();

    MinecraftMachinesLeaperTerrainEpisode(
            MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperTerrainMap map,
            MinecraftMachinesLeaperTerrainCourse course,
            Vector3d start,
            MinecraftMachinesLeaperTerrainParameters p,
            int horizon,
            boolean record,
            boolean planned) {
        this.rig = rig;
        this.map = map;
        this.course = course;
        this.start = start;
        this.goal = course.goal(start);
        this.goal.y = map.height(goal.x, goal.z, start.y + 12);
        this.horizon = horizon;
        this.record = record;
        rig.reset(course.heading());
        if (planned) {
            navigator = new MinecraftMachinesLeaperTerrainNavigator(rig, map, p);
            navigator.pace = course.pace();
            if (!navigator.go(goal.x, goal.y, goal.z)) {
                done = failed = true;
                reason = navigator.reason;
            }
            route = navigator.route;
            nav = navigator.controller;
            terrain = navigator.terrain;
        } else {
            navigator = null;
            route = List.of();
            terrain = new MinecraftMachinesLeaperTerrainControl(rig, map, p);
            nav =
                    new LeaperPointController(
                            MinecraftMachinesLeaperModel.load("careful").navigation(),
                            goal.x,
                            goal.z);
        }
        error = course.distance();
        previous = rig.position();
        lastY = previous.y;
    }

    void step() {
        if (done) return;
        ++tick;
        var p = rig.position();
        var v = rig.velocity();
        terrain.travelX = goal.x - p.x;
        terrain.travelZ = goal.z - p.z;
        minUp = Math.min(minUp, rig.upright());
        anchor = Math.max(anchor, rig.jointStrain());
        double[] q = rig.angles();
        for (int j = 0; j < 12; j++)
            limit =
                    Math.max(
                            limit,
                            Math.max(
                                    MinecraftMachinesLeaper.MIN[j % 3] - q[j],
                                    q[j] - MinecraftMachinesLeaper.MAX[j % 3]));
        if (!rig.valid()
                || !Double.isFinite(p.x + p.y + p.z)
                || minUp < .85
                || anchor > .04
                || limit > .04) {
            done = failed = true;
            reason = "posture or joint bounds";
            return;
        }
        if (navigator == null && tick > 100 && (command == null || tick % 2 == 1)) {
            if (!route.isEmpty()) {
                while (index < route.size() - 1
                        && Math.hypot(route.get(index).x() - p.x, route.get(index).z() - p.z) < 3.5)
                    index++;
                var target = route.get(index);
                if (nav.goalX() != target.x() || nav.goalZ() != target.z())
                    nav.setGoal(target.x(), target.z());
            }
            command = nav.update(p.x, p.z, rig.heading(), .1);
        }
        if (navigator != null) {
            navigator.step();
            command = navigator.command;
            if (navigator.goal == null && !navigator.reason.isEmpty()) {
                done = failed = true;
                reason = navigator.reason;
                return;
            }
        } else
            terrain.step(
                    command == null ? 0 : command.forward(),
                    command == null ? 0 : command.strafe(),
                    command == null ? 0 : command.turn(),
                    course.pace());
        error = Math.hypot(p.x - goal.x, p.z - goal.z);
        double ground = map.height(p.x, p.z, p.y + 4);
        minClearance = Math.min(minClearance, rig.corePivot().y - ground);
        for (int i = 0; i < 4; i++) {
            var toe = rig.toe(i);
            double footGround = map.height(toe.x, toe.z, toe.y + 3);
            double clearance = toe.y - footGround;
            boolean near = clearance > -.1 && clearance < .28;
            if (tick > 100) {
                if (clearance > .50) lifted[i] = true;
                if (lifted[i] && near) {
                    plants[i]++;
                    lifted[i] = false;
                }
                if (near && lastNear[i] && lastToes[i] != null)
                    nearGroundFootMotion +=
                            Math.hypot(toe.x - lastToes[i].x, toe.z - lastToes[i].z);
            }
            lastNear[i] = near;
            lastToes[i] = new Vector3d(toe);
        }
        if (tick > 100) {
            path += Math.hypot(p.x - previous.x, p.z - previous.z);
            maxSpeed = Math.max(maxSpeed, Math.hypot(v.x, v.z));
            upTravel += Math.max(0, p.y - lastY);
            downTravel += Math.max(0, lastY - p.y);
        }
        previous = new Vector3d(p);
        lastY = p.y;
        boolean correctSurface =
                Math.abs(ground - goal.y) < .51 && p.y - goal.y > 1.15 && p.y - goal.y < 3.6;
        if (command != null
                && command.arrived()
                && (navigator == null || navigator.arrived)
                && error <= .48
                && correctSurface) {
            if (arrival == 0) arrival = tick;
            if (++settled >= 100) done = success = true;
        } else {
            settled = 0;
            arrival = 0;
        }
        if (Math.hypot(p.x - start.x, p.z - start.z) > 62 || p.y < 0) {
            done = failed = true;
            reason = "left course";
        }
        if (tick >= horizon + 100) {
            done = true;
            reason = "timeout";
        }
        if (record && tick % 20 == 0) {
            var row = new LinkedHashMap<String, Object>();
            row.put("tick", tick);
            row.put("position", List.of(p.x, p.y, p.z));
            row.put("speed", Math.hypot(v.x, v.z));
            row.put("error", error);
            row.put("up", rig.upright());
            row.put("gait", terrain.gaitName);
            row.put("roughness", terrain.roughness);
            row.put("ground", ground);
            row.put("heading", rig.heading());
            row.put("active_target", List.of(nav.goalX(), nav.goalZ()));
            row.put("command", command);
            row.put("recovery", terrain.recovery);
            history.add(row);
        }
    }

    double score() {
        return (failed ? -200 : 0)
                + 100 * (1 - Math.min(2, error / course.distance()))
                + (success ? 100 : 0)
                - .2 * (arrival > 0 ? (arrival - 100) * .05 : horizon * .05)
                - 120 * (1 - minUp)
                - 2 * path / course.distance()
                - 5 * error
                - 20 * Math.max(0, 1.15 - minClearance);
    }

    Map<String, Object> result() {
        var r = new LinkedHashMap<String, Object>();
        r.put("course", course);
        r.put("success", success);
        r.put("failed", failed);
        r.put("reason", reason);
        r.put("error", error);
        r.put("minimum_up", minUp);
        r.put("anchor_error", anchor);
        r.put("limit_violation", limit);
        r.put("arrival_seconds", arrival > 0 ? (arrival - 100) * .05 : null);
        r.put("minimum_core_clearance", minClearance);
        r.put("path", path);
        r.put("peak_speed", maxSpeed);
        r.put("terrain_ticks", terrain.terrainTicks);
        r.put("sprint_ticks", terrain.sprintTicks);
        r.put("rejected_footholds", terrain.rejectedFootholds);
        r.put("goal", List.of(goal.x, goal.y, goal.z));
        r.put("final_position", List.of(rig.position().x, rig.position().y, rig.position().z));
        r.put("route", route);
        r.put("attitude_replants", terrain.attitudeReplants);
        r.put("replans", navigator == null ? 0 : navigator.replans);
        r.put("recoveries", navigator == null ? 0 : navigator.recoveries);
        r.put("foot_plants", plants);
        r.put("near_ground_foot_motion", nearGroundFootMotion);
        r.put("score", score());
        if (record) r.put("history", history);
        return r;
    }
}
