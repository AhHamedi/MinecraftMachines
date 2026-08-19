package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import dev.ahmedhamedi.minecraft_machines.content.training.api.ResetStrategy;

import java.util.Objects;

public record EpisodeResetContext(
        int slotIndex,
        EpisodeDefinition episodeDefinition,
        ResetStrategy resetStrategy
) {
    public EpisodeResetContext {
        if (slotIndex < 0) {
            throw new IllegalArgumentException("slotIndex must be non-negative");
        }
        episodeDefinition = Objects.requireNonNull(episodeDefinition, "episodeDefinition");
        resetStrategy = Objects.requireNonNull(resetStrategy, "resetStrategy");
    }
}
