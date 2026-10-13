package dev.ahmedhamedi.minecraft_machines.neoforge;

import org.joml.Vector3d;

/** A failed walking stance may still have planted feet for a checked short recovery leap. */
final class MinecraftMachinesLeaperRecoveryJump {
    static boolean allowed(MinecraftMachinesLeaperPredatorNavigator nav, Vector3d target,
            boolean background, String walkingFailure) {
        if (nav.automaticLeapAllowed(target)) return true;
        if (background || !(walkingFailure.equals("No supported escape stance")
                || walkingFailure.equals("Destination has no reachable dry stance")
                || walkingFailure.equals("No traversable route")) || !nav.apex.enabled
                || !nav.leap.enabled || nav.pulseActive || nav.leap.inAir()
                || nav.tick < nav.leap.nextAutomaticLeapTick || !nav.leap.canPrepare()) return false;
        var p = nav.rig.corePivot();
        double distance = Math.hypot(target.x - p.x, target.z - p.z);
        return distance >= 4 && distance <= 192 && (nav.hunting || distance <= 12.1);
    }
}
