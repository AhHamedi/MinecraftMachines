package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode;
import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile;

import java.util.Objects;

public record EpisodeDefinition(
        long episodeId,
        long seed,
        EnvironmentTaskMode taskMode,
        CurriculumStage curriculumStage,
        LocomotionCommand command,
        int maximumControlSteps,
        double initialDistanceToTarget,
        double gaitFrequencyHz,
        TerrainProfile terrainProfile,
        PointTargetDefinition pointTarget,
        StandingDisturbance standingDisturbance
) {
    public EpisodeDefinition(
            final long episodeId,
            final long seed,
            final EnvironmentTaskMode taskMode,
            final CurriculumStage curriculumStage,
            final LocomotionCommand command,
            final int maximumControlSteps,
            final double initialDistanceToTarget,
            final double gaitFrequencyHz
    ) {
        this(
                episodeId,
                seed,
                taskMode,
                curriculumStage,
                command,
                maximumControlSteps,
                initialDistanceToTarget,
                gaitFrequencyHz,
                TerrainProfile.none());
    }

    public EpisodeDefinition(
            final long episodeId,
            final long seed,
            final EnvironmentTaskMode taskMode,
            final CurriculumStage curriculumStage,
            final LocomotionCommand command,
            final int maximumControlSteps,
            final double initialDistanceToTarget,
            final double gaitFrequencyHz,
            final TerrainProfile terrainProfile
    ) {
        this(
                episodeId,
                seed,
                taskMode,
                curriculumStage,
                command,
                maximumControlSteps,
                initialDistanceToTarget,
                gaitFrequencyHz,
                terrainProfile,
                PointTargetDefinition.NONE);
    }

    public EpisodeDefinition(
            final long episodeId,
            final long seed,
            final EnvironmentTaskMode taskMode,
            final CurriculumStage curriculumStage,
            final LocomotionCommand command,
            final int maximumControlSteps,
            final double initialDistanceToTarget,
            final double gaitFrequencyHz,
            final TerrainProfile terrainProfile,
            final PointTargetDefinition pointTarget
    ) {
        this(
                episodeId,
                seed,
                taskMode,
                curriculumStage,
                command,
                maximumControlSteps,
                initialDistanceToTarget,
                gaitFrequencyHz,
                terrainProfile,
                pointTarget,
                StandingDisturbance.NONE);
    }

    public EpisodeDefinition {
        taskMode = Objects.requireNonNull(taskMode, "taskMode");
        curriculumStage = Objects.requireNonNull(curriculumStage, "curriculumStage");
        command = Objects.requireNonNull(command, "command");
        terrainProfile = Objects.requireNonNull(terrainProfile, "terrainProfile");
        pointTarget = Objects.requireNonNull(pointTarget, "pointTarget");
        standingDisturbance = Objects.requireNonNull(standingDisturbance, "standingDisturbance");
        if (maximumControlSteps < 1) {
            throw new IllegalArgumentException("maximumControlSteps must be positive");
        }
        if (!Double.isFinite(initialDistanceToTarget) || initialDistanceToTarget < 0.0) {
            throw new IllegalArgumentException("initialDistanceToTarget must be finite and non-negative");
        }
        if (!Double.isFinite(gaitFrequencyHz) || gaitFrequencyHz <= 0.0) {
            throw new IllegalArgumentException("gaitFrequencyHz must be finite and positive");
        }
        if (taskMode == EnvironmentTaskMode.POINT_GOAL && !pointTarget.enabled()) {
            throw new IllegalArgumentException("point-goal episodes require a point target");
        }
    }
}
