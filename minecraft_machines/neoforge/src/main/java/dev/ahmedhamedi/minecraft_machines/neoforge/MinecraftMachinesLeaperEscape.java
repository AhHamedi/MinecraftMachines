package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPredatorPlanner;
import org.joml.Vector3d;
import java.util.List;

/** Short, motor-driven retreats out of a conservative foliage envelope. Never relaxes torso or support checks. */
final class MinecraftMachinesLeaperEscape {
    static MinecraftMachinesLeaperPredatorMap terrain(MinecraftMachinesLeaperPredatorNavigator nav) {
        var map = new MinecraftMachinesLeaperPredatorMap(nav.level, nav.rig.corePivot().y + 7,
                nav.control.standingHeight(), (nav.control.policy.value[3] + .5 * nav.control.policy.value[0] + .5) * nav.rig.scale);
        map.terrainAwareSweep = nav.control.terrainAwareSweep;
        map.scale = nav.rig.scale;
        return map;
    }

    static LeaperPredatorPlanner planner(MinecraftMachinesLeaperPredatorMap map, Vector3d origin,
            MinecraftMachinesLeaperClearance.Result allowance) {
        return new LeaperPredatorPlanner(new LeaperPredatorPlanner.Terrain() {
            public double bodyScale() { return map.scale; }
            public double height(double x, double z) { return map.height(x, z); }
            public List<Double> surfaces(double x, double z, double low, double high) {
                return map.surfaces(x, z, low, high);
            }
            public boolean adaptiveStanceSampling() { return map.terrainAwareSweep; }
            public double chassisStepClearance() { return map.terrainAwareSweep ? map.bodyHeight - 1.5 * map.scale : 1; }
            public boolean bodyClear(double x, double y, double z) { return bodyClear(x, y, z, 0, 0); }
            public boolean bodyClear(double x, double y, double z, double gx, double gz) {
                var clearance = MinecraftMachinesLeaperClearance.measure(map, x, y, z, gx, gz);
                return clearance.clear() || allowance != null && Math.hypot(x - origin.x, z - origin.z) <= 8.01
                        && clearance.recedingFrom(allowance);
            }
        }, origin.x, origin.y, origin.z);
    }

    static boolean canRetreat(MinecraftMachinesLeaperPredatorMap map, Vector3d origin, Vector3d target) {
        double length = Math.hypot(target.x - origin.x, target.z - origin.z);
        if (length > 8 || length < .1 || Math.abs(target.y - origin.y) > 1.5) return false;
        var initial = MinecraftMachinesLeaperClearance.measure(map, origin.x, origin.y, origin.z, 0, 0);
        if (!initial.hardClear()) return false;
        // A retreat may shed existing peripheral overlaps, but cannot initially move into one.
        for (var block : initial.peripheral().keySet()) {
            double bx = Math.clamp(origin.x, block.getX(), block.getX() + 1);
            double bz = Math.clamp(origin.z, block.getZ(), block.getZ() + 1);
            if ((target.x - origin.x) * (origin.x - bx) + (target.z - origin.z) * (origin.z - bz) < -.01)
                return false;
        }
        return Double.isFinite(planner(map, origin, null).stanceFloor(target.x, target.y, target.z))
                && planner(map, origin, initial).segmentTo(target.x, target.y, target.z);
    }

    static boolean step(MinecraftMachinesLeaperPredatorNavigator nav) {
        if (nav.goal == null || !nav.route.isEmpty() || !nav.routeBlocked || nav.leap.inAir() || nav.pulseActive)
            return false;
        var p = nav.rig.corePivot();
        double floor = nav.leap.supportFloor();
        if (!Double.isFinite(floor) || nav.leap.supports() < (nav.escape == null ? 3 : 1) || nav.rig.upright() < .75
                || nav.rig.jointStrain() > .11) {
            if (nav.escape != null) nav.reason = "Retreat obstructed; waiting for clearing burst";
            nav.escape = null;
            nav.backUntil = 0;
            return false;
        }
        var origin = new Vector3d(p.x, floor, p.z);
        if (nav.escape == null) {
            if (!nav.reason.equals("No supported escape stance") || nav.tick % 4 != 0) return false;
            var map = terrain(nav);
            var initial = MinecraftMachinesLeaperClearance.measure(map, p.x, floor, p.z, 0, 0);
            if (!initial.hardClear() || initial.peripheral().isEmpty()) return false;
            double awayX = 0, awayZ = 0;
            for (var block : initial.peripheral().keySet()) {
                awayX += p.x - block.getX() - .5;
                awayZ += p.z - block.getZ() - .5;
            }
            double away = Math.atan2(awayZ, awayX);
            for (int attempt = 0; attempt < 2; attempt++) {
                int i = Math.floorMod(nav.approachIndex++, 24), sector = i % 8;
                int offset = sector % 2 == 0 ? -(sector / 2) : (sector + 1) / 2;
                double angle = away + offset * Math.PI / 4, radius = 4 + 2 * (i / 8);
                double x = p.x + radius * Math.cos(angle), z = p.z + radius * Math.sin(angle);
                for (double y : map.surfaces(x, z, floor - 1.5, floor + 1.5)) {
                    var target = new Vector3d(x, y, z);
                    if (!canRetreat(map, origin, target)) continue;
                    nav.escape = target;
                    nav.backUntil = nav.tick + 160;
                    nav.recoveries++;
                    nav.reason = "Backing away from nearby foliage";
                    break;
                }
                if (nav.escape != null) break;
            }
            if (nav.escape == null) return false;
        }
        if (nav.tick % 4 == 0) {
            var map = terrain(nav);
            if (Double.isFinite(planner(map, origin, null).stanceFloor(p.x, floor, p.z))) {
                var target = new Vector3d(nav.goal);
                nav.escape = null;
                nav.backUntil = 0;
                nav.requestGoal(target.x, target.y, target.z);
                nav.reason = "Replanning from clear footing";
                nav.control.step(0, 0, 0, .45);
                return true;
            }
            if (nav.tick >= nav.backUntil || !canRetreat(map, origin, nav.escape)) {
                nav.escape = null;
                nav.backUntil = 0;
                nav.reason = "Retreat obstructed; waiting for clearing burst";
                nav.control.step(0, 0, 0, .45);
                return true;
            }
        }
        double dx = nav.escape.x - p.x, dz = nav.escape.z - p.z;
        double length = Math.max(1, Math.hypot(dx, dz)), h = nav.rig.heading();
        nav.control.setPace("careful");
        nav.control.apex = nav.apex.enabled;
        nav.control.apexTargetSpeed = 0;
        nav.control.sprintAllowed = false;
        nav.control.step(.65 * (Math.sin(h) * dx - Math.cos(h) * dz) / length,
                .65 * (Math.cos(h) * dx + Math.sin(h) * dz) / length, 0, .45);
        return true;
    }
}
