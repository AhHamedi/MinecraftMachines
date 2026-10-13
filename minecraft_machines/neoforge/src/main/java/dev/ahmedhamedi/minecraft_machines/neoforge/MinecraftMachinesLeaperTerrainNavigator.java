package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperTerrainPlanner;

import org.joml.Vector3d;

import java.util.*;

/** Shared player/test terrain route follower. All locomotion goes through limited native motors. */
final class MinecraftMachinesLeaperTerrainNavigator {
    final MinecraftMachinesLeaper rig;
    final MinecraftMachinesLeaperTerrainMap map;
    final MinecraftMachinesLeaperTerrainControl terrain;
    final LeaperPointController controller;
    List<LeaperTerrainPlanner.Point> route = List.of();
    Vector3d goal;
    LeaperPointController.Command command;
    String pace = "horrific", reason = "";
    int tick, index, replans, settleUntil;
    boolean arrived;
    int progressTick, recoveryUntil, recoveries;
    Vector3d progressPosition;
    double distance;

    MinecraftMachinesLeaperTerrainNavigator(
            MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperTerrainMap map,
            MinecraftMachinesLeaperTerrainParameters parameters) {
        this.rig = rig;
        this.map = map;
        terrain = new MinecraftMachinesLeaperTerrainControl(rig, map, parameters);
        var pos = rig.position();
        controller =
                new LeaperPointController(
                        MinecraftMachinesLeaperModel.load("careful").navigation(), pos.x, pos.z);
    }

    boolean go(double x, double y, double z) {
        if (!Double.isFinite(x + y + z))
            throw new IllegalArgumentException("Nonfinite terrain destination");
        var p = rig.position();
        var planner =
                new LeaperTerrainPlanner(
                        (sx, sz) -> map.height(sx, sz, Math.max(p.y, y) + 8), p.x, p.z);
        var result = planner.plan(x, y, z);
        if (!result.found()) {
            reason = result.reason();
            return false;
        }
        goal = new Vector3d(x, y, z);
        route = result.points();
        index = 0;
        arrived = false;
        reason = "";
        command = null;
        progressPosition = new Vector3d(p);
        progressTick = tick;
        return true;
    }

    void stop() {
        goal = null;
        command = null;
        route = List.of();
        arrived = false;
    }

    void settle() {
        stop();
        settleUntil = tick + 100;
    }

    void step() {
        ++tick;
        var p = rig.position();
        if (goal != null) {
            terrain.travelX = goal.x - p.x;
            terrain.travelZ = goal.z - p.z;
        }
        terrain.recovery = tick < recoveryUntil;
        if (goal != null
                && tick > Math.max(100, settleUntil)
                && (command == null || tick % 2 == 1)) {
            distance = Math.hypot(p.x - goal.x, p.z - goal.z);
            if (tick - progressTick >= 160) {
                if (distance > 2 && p.distance(progressPosition) < 1.75) {
                    recoveryUntil = tick + 180;
                    recoveries++;
                    terrain.requestRecovery();
                }
                progressPosition = new Vector3d(p);
                progressTick = tick;
            }
            while (index < route.size() - 1
                    && Math.hypot(route.get(index).x() - p.x, route.get(index).z() - p.z) < 3.5)
                index++;
            if (tick % 20 == 1) {
                var planner =
                        new LeaperTerrainPlanner(
                                (sx, sz) -> map.height(sx, sz, Math.max(p.y, goal.y) + 8),
                                p.x,
                                p.z);
                var target = route.get(index);
                if (!planner.segment(p.x, p.z, target.x(), target.z())) {
                    var saved = new Vector3d(goal);
                    replans++;
                    if (!go(saved.x, saved.y, saved.z)) {
                        stop();
                        reason = "Route is blocked. Select another supported surface.";
                    }
                }
            }
            if (goal != null) {
                while (index < route.size() - 1
                        && Math.hypot(route.get(index).x() - p.x, route.get(index).z() - p.z) < 3.5)
                    index++;
                var target = route.get(index);
                if (controller.goalX() != target.x() || controller.goalZ() != target.z())
                    controller.setGoal(target.x(), target.z());
                command = controller.update(p.x, p.z, rig.heading(), .1);
                double floor = map.height(p.x, p.z, p.y + 4);
                arrived =
                        index == route.size() - 1
                                && command.arrived()
                                && distance <= .48
                                && Math.abs(floor - goal.y) < .51
                                && p.y - goal.y > 1.15
                                && p.y - goal.y < 3.6;
            }
        }
        terrain.step(
                command == null ? 0 : command.forward(),
                command == null ? 0 : command.strafe(),
                command == null ? 0 : command.turn(),
                pace);
    }
}
