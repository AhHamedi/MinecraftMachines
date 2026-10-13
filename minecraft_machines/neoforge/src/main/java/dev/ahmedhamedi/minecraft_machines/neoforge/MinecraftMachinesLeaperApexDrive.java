package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperApexDrive;

import net.minecraft.world.phys.AABB;

import org.joml.Vector3d;

/** Route-aware traction and suspension for Apex. Every correction goes through native impulses. */
final class MinecraftMachinesLeaperApexDrive {
    final MinecraftMachinesLeaperPredatorNavigator nav;
    boolean enabled = true;
    int activeTicks, brakingTicks;
    double requestedSpeed, maxSpeed;
    int hazardTick = -100;
    boolean hazard;
    Vector3d hazardTarget;

    MinecraftMachinesLeaperApexDrive(MinecraftMachinesLeaperPredatorNavigator nav) {
        this.nav = nav;
    }

    void steer() {
        var rig = nav.rig;
        boolean preparing =
                nav.leap.state == MinecraftMachinesLeaperLeap.State.ALIGN
                        || nav.leap.state == MinecraftMachinesLeaperLeap.State.CROUCH;
        boolean landing = nav.leap.inAir() && nav.leap.active != null;
        if (!enabled
                || nav.tick <= 80
                || nav.tick < nav.backUntil
                || !preparing
                        && !landing
                        && (nav.planning != null || nav.route.isEmpty() || nav.control.holding)
                || nav.goal == null
                || nav.holdPosition
                || nav.leap.state == MinecraftMachinesLeaperLeap.State.RETREAT) {
            rig.assistedHeading = Double.NaN;
            return;
        }
        var p = rig.position();
        double x = nav.goal.x, z = nav.goal.z;
        if (nav.leap.active != null && nav.leap.state != MinecraftMachinesLeaperLeap.State.WALK) {
            x = nav.leap.active.destination().x;
            z = nav.leap.active.destination().z;
        } else if (!nav.route.isEmpty()) {
            var target = nav.route.get(nav.index);
            x = target.x();
            z = target.z();
        }
        if (!Double.isFinite(rig.assistedHeading)) rig.assistedHeading = rig.heading();
        // Hold the planned landing orientation in flight: turning toward a point already
        // crossed would spin the rig at touchdown. Translation stays ballistic.
        double desired =
                landing && nav.leap.active.landing() != null
                        ? nav.leap.active.landing().heading()
                        : Math.atan2(x - p.x, -(z - p.z));
        double error =
                dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait.wrap(
                        desired - rig.assistedHeading);
        rig.assistedHeading += Math.clamp(error, -.08, .08);
    }

    double cruise() {
        return nav.pace.equals("careful")
                ? 4
                : nav.pace.equals("fast") ? 9 : LeaperApexDrive.CRUISE;
    }

    boolean needsLeap(Vector3d target) {
        // Every locomotion profile needs the same gap/ledge evidence for automatic jumps.
        if (!nav.leap.enabled) return false;
        if (nav.tick - hazardTick < 4
                && hazardTarget != null
                && hazardTarget.distanceSquared(target) < 4) return hazard;
        hazardTick = nav.tick;
        hazardTarget = new Vector3d(target);
        hazard = false;
        var p = nav.rig.corePivot();
        double last = nav.map.height(p.x, p.z, p.y + .3);
        if (!Double.isFinite(last)) {
            hazard = true;
            return true;
        }
        double dx = target.x - p.x, dz = target.z - p.z, distance = Math.hypot(dx, dz);
        if (distance < 5) return false;
        dx /= distance;
        dz /= distance;
        for (double ahead = 2; ahead <= Math.min(28, distance); ahead += 2) {
            double h =
                    nav.map.height(p.x + dx * ahead, p.z + dz * ahead, Math.max(p.y + 5, last + 4));
            if (!Double.isFinite(h) || h - last > 3.1 || last - h > 3.6) {
                hazard = true;
                break;
            }
            last = h;
        }
        return hazard;
    }

    void brake() {
        if (!enabled || nav.leap.inAir() || nav.tick <= 80) return;
        requestedSpeed = 0;
        nav.assistStallTicks = 0;
        apply(0, 0, false);
        brakingTicks++;
    }

    boolean step() {
        if (!enabled) return false;
        if (nav.tick <= 80 || nav.leap.state != MinecraftMachinesLeaperLeap.State.WALK) return true;
        if (nav.goal == null
                || nav.holdPosition
                || nav.arrived
                || nav.routeBlocked
                || nav.route.isEmpty()) {
            brake();
            return true;
        }
        if (nav.tick < nav.backUntil) return false;
        var rig = nav.rig;
        var p = rig.position();
        var target = nav.route.get(nav.index);
        double dx = target.x() - p.x, dz = target.z() - p.z, length = Math.hypot(dx, dz);
        if (length < .01) {
            brake();
            return true;
        }
        dx /= length;
        dz /= length;
        double ceiling = cruise();
        double flat = Math.clamp(nav.control.clearGroundBlend, 0, 1);
        ceiling = Math.min(ceiling, 9 + (ceiling - 9) * flat);
        double alignment = Math.sin(rig.heading()) * dx - Math.cos(rig.heading()) * dz;
        ceiling *= Math.clamp(.3 + .7 * alignment, .3, 1);
        requestedSpeed = LeaperApexDrive.approachSpeed(nav.distance, ceiling);
        if (nav.movingPrey() && nav.index == nav.route.size() - 1) {
            double gap = nav.prey.distance(p.x, p.z);
            requestedSpeed =
                    Math.clamp(
                            nav.prey.speedAlong(dx, dz, nav.tick) + 2.5 * (gap - 1.8), 0, ceiling);
        }
        if (needsLeap(nav.goal)) requestedSpeed = Math.min(requestedSpeed, 4);
        // A grounded body that keeps requesting travel without making progress should
        // retain stall evidence for the recovery pulse and motor-driven backout.
        double progress = (nav.control.measuredX * nav.rig.scale) * dx + (nav.control.measuredZ * nav.rig.scale) * dz;
        nav.assistStallTicks =
                requestedSpeed > 2
                                && nav.distance > 4
                                && alignment > .6
                                && progress < .6
                                && nav.leap.supports() > 0
                        ? nav.assistStallTicks + 1
                        : 0;
        apply(dx * requestedSpeed, dz * requestedSpeed, true);
        activeTicks++;
        return true;
    }

    private void apply(double desiredX, double desiredZ, boolean suspension) {
        var rig = nav.rig;
        if (rig.upright() < .65 || rig.jointStrain() > .11) return;
        var p = rig.corePivot();
        var v = rig.assemblyVelocity();
        maxSpeed = Math.max(maxSpeed, Math.hypot(v.x, v.z));
        double floor = nav.map.height(p.x, p.z, p.y + .3);
        double standing = nav.control.standingHeight();
        if (!Double.isFinite(floor) || p.y - floor > standing + 1) {
            double feetFloor = nav.leap.supportFloor();
            if (Double.isFinite(feetFloor)) floor = feetFloor;
        }
        // Native airtime between strokes is allowed; distant ground cannot suspend the rig.
        if (!Double.isFinite(floor) || p.y - floor > standing + 6 || p.y - floor < .5) return;
        double desiredHeight = floor + standing;
        double demand = Math.hypot(desiredX, desiredZ);
        double clearance = 1;
        if (suspension && demand > .1) {
            double dx = desiredX / demand, dz = desiredZ / demand;
            for (double ahead : new double[] {.75, 1.5, 2.5, 3.5})
                for (double side : new double[] {-1.2, 0, 1.2}) {
                    double h =
                            nav.map.height(
                                    p.x + dx * ahead - dz * side,
                                    p.z + dz * ahead + dx * side,
                                    p.y + 5);
                    if (Double.isFinite(h) && h - floor <= 6.1)
                        desiredHeight =
                                Math.max(desiredHeight, h + 2.1 - Math.max(0, ahead - 1.5) * 1.3);
                }
            clearance = Math.clamp(1 - Math.max(0, desiredHeight - p.y - .4) / 1.6, .12, 1);
        }
        var push = LeaperApexDrive.accelerate(v.x, v.z, desiredX * clearance, desiredZ * clearance);
        double vertical = 0;
        if (suspension) {
            double desiredVy = Math.clamp((desiredHeight - p.y) * 7, -3, 6);
            vertical = Math.clamp((-rig.gravity().y + 18 * (desiredVy - v.y)) * .05, -2.7, 1.25);
            if (vertical > 0
                    && !nav.level.noCollision(
                            new AABB(
                                    p.x - 1.3 * rig.scale, p.y + 1.2 * rig.scale, p.z - 1.3 * rig.scale, p.x + 1.3 * rig.scale, p.y + 2.1 * rig.scale,
                                    p.z + 1.3 * rig.scale))) vertical = 0;
        }
        rig.apexImpulse(new Vector3d(push.x(), vertical, push.z()));
    }
}
