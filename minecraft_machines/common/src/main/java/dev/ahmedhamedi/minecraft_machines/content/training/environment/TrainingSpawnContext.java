package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile;

import java.util.Objects;

public record TrainingSpawnContext(
        int slotIndex,
        long episodeId,
        long seed,
        CurriculumStage curriculumStage,
        TerrainProfile terrainProfile
) {
    public TrainingSpawnContext(
            final int slotIndex,
            final long episodeId,
            final long seed,
            final CurriculumStage curriculumStage
    ) {
        this(slotIndex, episodeId, seed, curriculumStage, TerrainProfile.none());
    }

    public TrainingSpawnContext {
        if (slotIndex < 0) {
            throw new IllegalArgumentException("slotIndex must be non-negative");
        }
        curriculumStage = curriculumStage == null ? new CurriculumStage("default", 0, "") : curriculumStage;
        terrainProfile = Objects.requireNonNull(terrainProfile, "terrainProfile");
    }
}
