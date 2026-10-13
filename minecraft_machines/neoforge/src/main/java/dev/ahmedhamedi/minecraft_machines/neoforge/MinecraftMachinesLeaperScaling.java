package dev.ahmedhamedi.minecraft_machines.neoforge;

import org.joml.Vector3d;

/** Physical variants are rebuilt without imposing terrain or movement prerequisites. */
final class MinecraftMachinesLeaperScaling {
    static void validate(double scale) {
        if (scale != 1 && scale != 1.5 && scale != 2)
            throw new IllegalArgumentException("Supported physical sizes: 1, 1.5 or 2 (absolute size, not a multiplier).");
    }

    static Vector3d resizeCenter(MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperPredatorNavigator nav, double scale) {
        validate(scale);
        var p = rig.corePivot();
        // Preserve altitude in flight. Grounded rebuilds align the new neutral stance
        // with the current support plane without requiring a flat or clear site.
        if (nav != null && !nav.leap.inAir() && nav.leap.supports() > 0
                && Double.isFinite(nav.leap.supportFloor()))
            p.y = nav.leap.supportFloor() + MinecraftMachinesLeaper.HEIGHT * scale;
        return p;
    }

    private MinecraftMachinesLeaperScaling() {}
}
