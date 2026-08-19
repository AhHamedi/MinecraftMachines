package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.environment.PointTargetDefinition;

import java.util.Objects;

public record DuopodEvaluationScenario(
        String id,
        double targetBearingDegrees,
        double targetDistanceBlocks,
        double initialYawDegrees,
        String terrainStage
) {
    public DuopodEvaluationScenario {
        id = requireText("id", id);
        terrainStage = requireText("terrainStage", terrainStage);
        requireFinite("targetBearingDegrees", targetBearingDegrees);
        requireFinite("targetDistanceBlocks", targetDistanceBlocks);
        requireFinite("initialYawDegrees", initialYawDegrees);
        if (targetDistanceBlocks <= 0.0) {
            throw new IllegalArgumentException("targetDistanceBlocks must be positive");
        }
    }

    public PointTargetDefinition pointTarget() {
        final double bearingRad = Math.toRadians(this.targetBearingDegrees);
        return new PointTargetDefinition(
                Math.cos(bearingRad) * this.targetDistanceBlocks,
                Math.sin(bearingRad) * this.targetDistanceBlocks,
                PointTargetCommandConfig.DEFAULT.successRadius());
    }

    private static String requireText(final String name, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return Objects.requireNonNull(value, name);
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
