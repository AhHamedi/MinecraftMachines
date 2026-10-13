package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point;

/** Walking aims at dry terrain below an observed airborne position, not empty air. */
final class MinecraftMachinesLeaperPursuitGround {
    static double floor(MinecraftMachinesLeaperPredatorNavigator nav, double x, double y, double z) {
        double bodyFloor = nav.rig.corePivot().y - nav.control.standingHeight();
        int depth = (int) Math.clamp(Math.ceil(y - bodyFloor) + 12, 24, 96);
        return nav.map.height(x, z, y + 2, depth);
    }

    static Point searchCenter(MinecraftMachinesLeaperPredatorNavigator nav, Point memory, Point body) {
        double ground = floor(nav, memory.x(), memory.y(), memory.z());
        if (!Double.isFinite(ground) && nav.goal != null
                && Math.hypot(nav.goal.x - memory.x(), nav.goal.z - memory.z()) <= 12) ground = nav.goal.y;
        return new Point(memory.x(), Double.isFinite(ground) ? ground : body.y(), memory.z());
    }
}
