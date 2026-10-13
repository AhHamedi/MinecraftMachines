package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

/** Layered rocket exhaust, emitted only during actual flight; never places fire blocks. */
final class MinecraftMachinesLeaperJumpFire {
    static void emit(ServerLevel level, MinecraftMachinesLeaper rig, int ticks, double flightSeconds) {
        if ((ticks & 1) != 0) return;
        double fraction = ticks * .05 / Math.max(.1, flightSeconds);
        double power = Math.clamp((.9 - fraction) / .3, 0, 1);
        if (power <= 0) return;
        double scale = rig.scale;
        var nozzle = rig.corePivot().add(0, -.65 * scale, 0);
        for (int jet = 0; jet < 4; jet++) {
            double angle = jet * Math.PI / 2 + ticks * .3;
            level.sendParticles(ParticleTypes.FLAME, nozzle.x, nozzle.y, nozzle.z, 0,
                    Math.cos(angle) * .05 * scale, -.55 * scale * power,
                    Math.sin(angle) * .05 * scale, 1);
        }
        // Dense bright throat, widening turbulent flame, then smoke and embers.
        // Layer positions travel with the physical chassis; particles remain in world space.
        for (int layer = 0; layer < 7; layer++) {
            double t = layer / 6.0;
            double y = nozzle.y - t * 5.5 * scale * power;
            double width = (.12 + .6 * t) * scale * power;
            level.sendParticles(ParticleTypes.FLAME, nozzle.x, y, nozzle.z,
                    7, width, .25 * scale, width, .015);
            if (layer < 2) level.sendParticles(ParticleTypes.END_ROD, nozzle.x, y, nozzle.z,
                    2, width * .4, .1 * scale, width * .4, .005);
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, nozzle.x, nozzle.y - 5 * scale * power,
                nozzle.z, 5, .7 * scale, .4 * scale, .7 * scale, .02);
        if (ticks % 6 == 0) level.sendParticles(ParticleTypes.LAVA, nozzle.x,
                nozzle.y - 3 * scale * power, nozzle.z, 2, .4 * scale, .5 * scale, .4 * scale, 0);
    }
    private MinecraftMachinesLeaperJumpFire() {}
}
