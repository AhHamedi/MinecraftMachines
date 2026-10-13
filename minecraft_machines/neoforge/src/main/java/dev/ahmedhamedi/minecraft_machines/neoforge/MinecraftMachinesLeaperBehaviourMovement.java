package dev.ahmedhamedi.minecraft_machines.neoforge;

import org.joml.Vector3d;

/** A sensory retarget can replace a route after validation without first stopping the legs. */
final class MinecraftMachinesLeaperBehaviourMovement {
    static boolean go(MinecraftMachinesLeaperPredatorNavigator nav, double x, double y, double z) {
        if (!Double.isFinite(nav.planner(y).stanceFloor(x, y, z))) return false;
        if (nav.leap.state == MinecraftMachinesLeaperLeap.State.WALK && !nav.route.isEmpty()
                && !nav.routeBlocked && !nav.holdPosition && nav.leap.supportedDestination(x, y, z)) {
            nav.hunting = false;
            nav.prey.clear();
            nav.followSearch = new MinecraftMachinesLeaperNavigationSearch(nav, new Vector3d(x, y, z), true);
            nav.plans++;
            return true;
        }
        return nav.go(x, y, z);
    }
}
