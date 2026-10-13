package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.world.phys.AABB;

import org.joml.Vector3d;

/** Bounded gameplay suspension and anti-rollback drive, restricted to nearby solid terrain. */
final class MinecraftMachinesLeaperClimbAssist {
    final MinecraftMachinesLeaperPredatorNavigator nav;
    final boolean enabled =
            Boolean.parseBoolean(
                    System.getProperty("minecraft_machines.leaperClimbAssist", "true"));
    int lastSupport = -1000, committedUntil = -1000;
    int activeTicks, unsupportedTicks;
    double directionX, directionZ, targetHeight;

    MinecraftMachinesLeaperClimbAssist(MinecraftMachinesLeaperPredatorNavigator nav) {
        this.nav = nav;
    }

    boolean step(double forward, double strafe) {
        if (!enabled) return false;
        var rig = nav.rig;
        double demand = Math.hypot(forward, strafe);
        if (nav.goal == null
                || nav.arrived
                || nav.holdPosition
                || nav.planning != null
                || nav.routeBlocked
                || nav.tick < nav.backUntil
                || nav.leap.state != MinecraftMachinesLeaperLeap.State.WALK
                || demand < .12
                || rig.upright() < .55
                || nav.distance <= 2.5) {
            committedUntil = -1000;
            lastSupport = -1000;
            return false;
        }
        double heading = rig.heading();
        double dx = (Math.sin(heading) * forward + Math.cos(heading) * strafe) / demand;
        double dz = (-Math.cos(heading) * forward + Math.sin(heading) * strafe) / demand;
        if (directionX * dx + directionZ * dz < .5) committedUntil = -1000;
        directionX = dx;
        directionZ = dz;
        var core = rig.corePivot();
        var velocity = rig.assemblyVelocity();
        double standing = nav.control.standingHeight();
        double floor = nav.map.height(core.x, core.z, core.y + .3);
        double behind = nav.map.height(core.x - 2 * dx, core.z - 2 * dz, core.y + 3);
        if (!Double.isFinite(floor)) return false;
        // Look over the actual torso footprint before it reaches a riser. Remote cliffs
        // cannot become support: the clearance envelope decays with distance.
        double desired = floor + standing;
        double rise = 0;
        for (double ahead : new double[] {0, .75, 1.5, 2.25, 3}) {
            for (double side : new double[] {-1.2, 0, 1.2}) {
                double h =
                        nav.map.height(
                                core.x + ahead * dx - side * dz,
                                core.z + ahead * dz + side * dx,
                                core.y + 5);
                if (!Double.isFinite(h) || h - floor > 6.1) continue;
                rise = Math.max(rise, h - floor);
                desired = Math.max(desired, h + 1.95 - Math.max(0, ahead - 1.5) * 1.4);
            }
        }
        boolean uphill = rise > .4 || Double.isFinite(behind) && floor - behind > .4;
        if (uphill) committedUntil = nav.tick + 16;
        if (nav.tick > committedUntil) return false;
        int supports = nav.leap.supports();
        double nearest = Double.POSITIVE_INFINITY;
        for (int leg = 0; leg < 4; leg++) {
            var toe = rig.toe(leg);
            double h = nav.map.height(toe.x, toe.z, toe.y + .4);
            if (Double.isFinite(h)) nearest = Math.min(nearest, Math.abs(toe.y - h));
        }
        if (supports > 0) lastSupport = nav.tick;
        double bodyGap = core.y - floor;
        // Broad-body proximity also covers a chassis hung on an edge. The short grace
        // keeps a collision rebound from disabling support, but does not bridge a chasm.
        boolean nearGround = bodyGap > .5 && bodyGap < standing + 1.1;
        boolean grace = nav.tick - lastSupport <= 8 && nearest < 1.8 && bodyGap < standing + 2;
        if (supports == 0 && !nearGround && !grace || velocity.y < -5) return false;
        if (supports == 0) unsupportedTicks++;
        targetHeight = desired;
        double heightError = desired - core.y;
        double along = velocity.x * dx + velocity.z * dz;
        double sideSpeed = -velocity.x * dz + velocity.z * dx;
        double cruise =
                nav.pace.equals("careful") ? 1.25 : nav.pace.equals("super_horrific") ? 3.5 : 2.5;
        // First make clearance, then drive across the lip; pushing hard into its face
        // before the torso rises recreates the backward collision we are correcting.
        double clear = Math.clamp(1 - Math.max(0, heightError - .25) / 1.3, .18, 1);
        double speed = cruise * Math.min(1, demand * 2) * clear;
        double drive = Math.clamp((speed - along) * .4, -.6, .6);
        boolean braking = along < -1;
        if (braking) drive = Math.min(3, -.7 * along);
        double lateral = Math.clamp(-sideSpeed * .25, -.35, .35);
        var delta = new Vector3d(dx * drive - dz * lateral, 0, dz * drive + dx * lateral);
        double length = Math.hypot(delta.x, delta.z);
        double horizontalCap = braking ? 3 : .6;
        if (length > horizontalCap) delta.mul(horizontalCap / length);
        double desiredVy = Math.clamp(heightError * 5, -1.5, 3.8);
        double vertical =
                Math.clamp((-rig.gravity().y + 8 * (desiredVy - velocity.y)) * .05, -.7, 1.25);
        boolean headroom =
                nav.level.noCollision(
                        new AABB(
                                core.x - 1.3 * rig.scale,
                                core.y + 1.2 * rig.scale,
                                core.z - 1.3 * rig.scale,
                                core.x + 1.3 * rig.scale,
                                core.y + 2.1 * rig.scale,
                                core.z + 1.3 * rig.scale));
        if (!headroom && vertical > 0) vertical = 0;
        delta.y = vertical;
        rig.climbAssistImpulse(delta, braking);
        activeTicks++;
        return true;
    }
}
