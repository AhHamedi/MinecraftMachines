package dev.ahmedhamedi.minecraft_machines.neoforge;

import org.joml.Vector3d;

/** A temporary physical hold keeps the autonomous brain and its territory alive. */
final class MinecraftMachinesLeaperBehaviourRecovery {
    static final String REASON = "Recovering footing";

    static void begin(MinecraftMachinesLeaperPredatorNavigator nav, MinecraftMachinesLeaperShockwave pulse) {
        var goal = nav.pendingGoal != null ? new Vector3d(nav.pendingGoal)
                : nav.goal == null ? null : new Vector3d(nav.goal);
        nav.stop();
        nav.goal = goal;
        nav.reason = REASON;
        nav.backUntil = nav.tick + 12;
    }

    static boolean hold(MinecraftMachinesLeaperPredatorNavigator nav, MinecraftMachinesLeaperShockwave pulse) {
        var rig = nav.rig;
        if (!REASON.equals(nav.reason)) {
            if (nav.leap.inAir() || nav.pulseActive
                    || rig.upright() >= .35 && rig.jointStrain() <= .16
                    || rig.upright() >= .35 && rig.jointStrain() > .16
                    && pulse.canClearTrap(nav.level, rig, nav)) return false;
            begin(nav, pulse);
        }
        // Require a continuous stable interval, not one favourable contact sample.
        if (rig.upright() < .75 || rig.jointStrain() > .11 || Math.abs(rig.assemblyVelocity().y) > 1.5)
            nav.backUntil = nav.tick + 12;
        if (nav.tick >= nav.backUntil) {
            var goal = nav.goal == null ? null : new Vector3d(nav.goal);
            nav.reason = "";
            nav.backUntil = 0;
            if (goal != null) nav.requestGoal(goal.x, goal.y, goal.z);
            return false;
        }
        nav.tick++;
        nav.leap.observeFooting();
        nav.control.step(0, 0, 0, .35);
        nav.apex.brake();
        return true;
    }
}
