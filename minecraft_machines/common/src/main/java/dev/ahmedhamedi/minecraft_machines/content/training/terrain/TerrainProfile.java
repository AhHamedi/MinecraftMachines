package dev.ahmedhamedi.minecraft_machines.content.training.terrain;

public record TerrainProfile(
        String generatorId,
        int widthBlocks,
        int lengthBlocks,
        boolean enabled
) {
    public static final String NONE_ID = "minecraft_machines:no_training_terrain";

    public TerrainProfile {
        if (generatorId == null || generatorId.isBlank()) {
            throw new IllegalArgumentException("generatorId must not be blank");
        }
        if (widthBlocks < 1) {
            throw new IllegalArgumentException("widthBlocks must be positive");
        }
        if (lengthBlocks < 1) {
            throw new IllegalArgumentException("lengthBlocks must be positive");
        }
    }

    public static TerrainProfile none() {
        return new TerrainProfile(NONE_ID, 1, 1, false);
    }
}
