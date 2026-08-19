package dev.ahmedhamedi.minecraft_machines.content.worm.training;

import net.minecraft.world.phys.Vec3;

public record ActiveWormEpisode(
        WormInstance worm,
        WormCemCandidate candidate,
        Vec3 startPosition
) {
    public ActiveWormEpisode withWorm(final WormInstance worm) {
        return new ActiveWormEpisode(worm, this.candidate, this.startPosition);
    }
}
