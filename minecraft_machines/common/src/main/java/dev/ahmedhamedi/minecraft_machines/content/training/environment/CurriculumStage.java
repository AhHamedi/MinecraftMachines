package dev.ahmedhamedi.minecraft_machines.content.training.environment;

public record CurriculumStage(
        String id,
        int index,
        String description
) {
    public CurriculumStage {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        description = description == null ? "" : description;
    }
}
