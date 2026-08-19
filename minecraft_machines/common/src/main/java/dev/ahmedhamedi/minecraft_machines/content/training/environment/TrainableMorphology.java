package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import dev.ahmedhamedi.minecraft_machines.content.training.api.ActionSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EpisodeRuntime;
import dev.ahmedhamedi.minecraft_machines.content.training.api.MachineHealth;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ObservationSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.RewardBreakdown;

import java.util.Map;

public interface TrainableMorphology<M> {
    String id();

    ObservationSpec observationSpec();

    ActionSpec actionSpec();

    M spawn(TrainingSpawnContext context);

    default void onEpisodeUpdated(M machine, EpisodeDefinition episode) {
    }

    default void beforeControlStep(M machine, EpisodeDefinition episode, EpisodeRuntime runtime) {
    }

    void applyAction(M machine, double[] normalizedAction);

    double[] observe(M machine, EpisodeDefinition episode, EpisodeRuntime runtime);

    RewardBreakdown calculateReward(
            M machine,
            EpisodeDefinition episode,
            EpisodeRuntime runtime,
            double[] previousAction,
            double[] currentAction
    );

    MachineHealth inspectHealth(M machine, EpisodeDefinition episode, EpisodeRuntime runtime);

    default double distanceToTarget(M machine, EpisodeDefinition episode, EpisodeRuntime runtime) {
        return runtime.previousDistanceToTarget();
    }

    default Map<String, Object> diagnosticInfo(
            M machine,
            EpisodeDefinition episode,
            EpisodeRuntime runtime,
            double[] previousAction,
            double[] currentAction
    ) {
        return Map.of();
    }

    default boolean taskSuccessCondition(M machine, EpisodeDefinition episode, EpisodeRuntime runtime) {
        return false;
    }

    default int taskSuccessHoldControlSteps(EpisodeDefinition episode) {
        return 1;
    }

    default boolean taskFailureCondition(M machine, EpisodeDefinition episode, EpisodeRuntime runtime) {
        return false;
    }

    default int taskFailureHoldControlSteps(EpisodeDefinition episode) {
        return 1;
    }

    default String taskFailureMessage(M machine, EpisodeDefinition episode, EpisodeRuntime runtime) {
        return "task failure";
    }

    default double balanceError(M machine, EpisodeDefinition episode, EpisodeRuntime runtime) {
        return Double.NaN;
    }

    ResetResult<M> reset(M machine, EpisodeResetContext context);

    void destroy(M machine);
}
