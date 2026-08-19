package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.training.api.ActionSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EpisodeRuntime;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode;
import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import dev.ahmedhamedi.minecraft_machines.content.training.api.MachineHealth;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ObservationSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ResetStrategy;
import dev.ahmedhamedi.minecraft_machines.content.training.api.RewardBreakdown;
import dev.ahmedhamedi.minecraft_machines.content.training.api.TerminationReason;
import dev.ahmedhamedi.minecraft_machines.content.training.api.VectorFieldSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.CurriculumStage;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchReset;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchStep;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeResetContext;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.LocomotionVectorEnvironment;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.PointTargetDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.ResetResult;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.TrainableMorphology;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.TrainingSpawnContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocomotionVectorEnvironmentTest {
    @Test
    void resetAllSpawnsSlotsAndUsesDynamicDimensions() {
        final FakeMorphology morphology = new FakeMorphology(4, 3);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 2, 2, 4);

        final EnvironmentBatchReset reset = env.resetAll();

        assertEquals(2, reset.observations().length);
        assertEquals(4, reset.observations()[0].length);
        assertEquals(2, morphology.spawned);
        assertEquals(2, morphology.resetCount);
        assertEquals(4, morphology.observationSpec().size());
        assertEquals(3, morphology.actionSpec().size());
        assertEquals(0, reset.infos().get(0).get("episode_length"));
        assertEquals(EnvironmentTaskMode.COMMAND_TRACKING.name(), reset.infos().get(0).get("task_mode"));
        assertEquals("test", reset.infos().get(0).get("curriculum_stage"));
        assertEquals(1, reset.infos().get(0).get("curriculum_stage_index"));
        assertEquals("test stage", reset.infos().get(0).get("curriculum_stage_description"));
    }

    @Test
    void morphologyDiagnosticsAppearOnResetAndStepInfo() {
        final FakeMorphology morphology = new FakeMorphology(3, 2);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 1, 4);

        final EnvironmentBatchReset reset = env.resetAll();
        final EnvironmentBatchStep step = env.step(new double[][]{{0.25, -0.5}});

        assertEquals(0, reset.infos().get(0).get("machine_id"));
        assertEquals(0, reset.infos().get(0).get("diagnostic_control_step"));
        assertEquals(Boolean.FALSE, reset.infos().get(0).get("observation_repaired"));
        assertEquals(0, reset.infos().get(0).get("repaired_observation_count"));
        assertEquals(0, step.infos().get(0).get("machine_id"));
        assertEquals(1, step.infos().get(0).get("diagnostic_control_step"));
        assertEquals(0.25, (Double) step.infos().get(0).get("diagnostic_action_0"), 1.0e-10);
        final Map<?, ?> rewardComponents = (Map<?, ?>) step.infos().get(0).get("reward_components");
        final Map<?, ?> rewardComponentTotals = (Map<?, ?>) step.infos().get(0).get("reward_component_totals");
        assertEquals(0.05, (Double) rewardComponents.get("alive"), 1.0e-10);
        assertEquals(0.0625, (Double) rewardComponents.get("action_rate"), 1.0e-10);
        assertEquals(rewardComponents, rewardComponentTotals);
    }

    @Test
    void resetReportsRepairedObservationDiagnostics() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        morphology.invalidResetObservation = true;
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 1, 5);

        final EnvironmentBatchReset reset = env.resetAll();

        assertEquals(0.0, reset.observations()[0][0], 1.0e-10);
        assertEquals(Boolean.TRUE, reset.infos().get(0).get("observation_repaired"));
        assertEquals(1, reset.infos().get(0).get("repaired_observation_count"));
    }

    @Test
    void stepClampsActionsAndReturnsTerminalObservationBeforeAutoReset() {
        final FakeMorphology morphology = new FakeMorphology(3, 2);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 1, 8);
        env.resetAll();

        final EnvironmentBatchStep step = env.step(new double[][]{{2.0, -2.0}});

        assertTrue(step.terminated()[0]);
        assertFalse(step.truncated()[0]);
        assertEquals(TerminationReason.MACHINE_FAILURE.name(), step.infos().get(0).get("termination_reason"));
        assertEquals(1.0, step.observations()[0][1], 1.0e-10);
        assertEquals(-1.0, step.observations()[0][2], 1.0e-10);
        assertNotNull(step.infos().get(0).get("terminal_observation"));
        assertEquals(3, step.resetObservations()[0].length);
        assertEquals(2, morphology.resetCount);

        final EnvironmentBatchStep afterReset = env.step(new double[][]{{0.0, 0.0}});
        assertFalse(afterReset.terminated()[0]);
        assertFalse(afterReset.truncated()[0]);
    }

    @Test
    void timeLimitUsesTruncationNotTermination() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 1, 1);
        env.resetAll();

        final EnvironmentBatchStep step = env.step(new double[][]{{0.0}});

        assertFalse(step.terminated()[0]);
        assertTrue(step.truncated()[0]);
        assertEquals(TerminationReason.TIME_LIMIT.name(), step.infos().get(0).get("termination_reason"));
        assertEquals(0L, step.infos().get(0).get("episode_id"));
        assertEquals(1, step.infos().get(0).get("episode_length"));
        assertEquals(2, morphology.resetCount);

        final EnvironmentBatchStep afterReset = env.step(new double[][]{{0.0}});
        assertEquals(1L, afterReset.infos().get(0).get("episode_id"));
    }

    @Test
    void balanceTimeLimitTruncatesWithoutHeldSuccess() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        final LocomotionVectorEnvironment<FakeMachine> env = new LocomotionVectorEnvironment<>(
                morphology,
                1,
                1,
                ResetStrategy.RESPAWN,
                (slot, episodeIndex) -> new EpisodeDefinition(
                        episodeIndex,
                        42L + episodeIndex,
                        EnvironmentTaskMode.BALANCE,
                        new CurriculumStage("balance", 0, "balance test"),
                        LocomotionCommand.ZERO,
                        1,
                        0.0,
                        1.0));
        env.resetAll();

        final EnvironmentBatchStep step = env.step(new double[][]{{0.0}});

        assertFalse(step.terminated()[0]);
        assertTrue(step.truncated()[0]);
        assertEquals(TerminationReason.TIME_LIMIT.name(), step.infos().get(0).get("termination_reason"));
        assertEquals(Boolean.FALSE, step.infos().get(0).get("success"));
        assertEquals(2, morphology.resetCount);
    }

    @Test
    void balanceSuccessRequiresConsecutiveHeldCondition() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        morphology.taskSuccess = true;
        morphology.successHoldSteps = 2;
        final LocomotionVectorEnvironment<FakeMachine> env = new LocomotionVectorEnvironment<>(
                morphology,
                1,
                1,
                ResetStrategy.RESPAWN,
                (slot, episodeIndex) -> new EpisodeDefinition(
                        episodeIndex,
                        42L + episodeIndex,
                        EnvironmentTaskMode.BALANCE,
                        new CurriculumStage("balance", 0, "balance test"),
                        LocomotionCommand.ZERO,
                        5,
                        0.0,
                        1.0));
        env.resetAll();

        final EnvironmentBatchStep first = env.step(new double[][]{{0.0}});
        final EnvironmentBatchStep second = env.step(new double[][]{{0.0}});

        assertFalse(first.terminated()[0]);
        assertFalse(first.truncated()[0]);
        assertEquals(1, first.infos().get(0).get("task_success_hold_control_steps"));
        assertTrue(second.terminated()[0]);
        assertFalse(second.truncated()[0]);
        assertEquals(TerminationReason.SUCCESS.name(), second.infos().get(0).get("termination_reason"));
        assertEquals(Boolean.TRUE, second.infos().get(0).get("success"));
        assertEquals(2, second.infos().get(0).get("task_success_hold_control_steps"));
    }

    @Test
    void balanceFailureRequiresConsecutiveHeldCondition() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        morphology.taskFailure = true;
        morphology.failureHoldSteps = 2;
        final LocomotionVectorEnvironment<FakeMachine> env = new LocomotionVectorEnvironment<>(
                morphology,
                1,
                1,
                ResetStrategy.RESPAWN,
                (slot, episodeIndex) -> new EpisodeDefinition(
                        episodeIndex,
                        42L + episodeIndex,
                        EnvironmentTaskMode.BALANCE,
                        new CurriculumStage("balance", 0, "balance test"),
                        LocomotionCommand.ZERO,
                        5,
                        0.0,
                        1.0));
        env.resetAll();

        final EnvironmentBatchStep first = env.step(new double[][]{{0.0}});
        final EnvironmentBatchStep second = env.step(new double[][]{{0.0}});

        assertFalse(first.terminated()[0]);
        assertFalse(first.truncated()[0]);
        assertEquals(1, first.infos().get(0).get("task_failure_hold_control_steps"));
        assertTrue(second.terminated()[0]);
        assertFalse(second.truncated()[0]);
        assertEquals(TerminationReason.MACHINE_FAILURE.name(), second.infos().get(0).get("termination_reason"));
        assertEquals("held test failure", second.infos().get(0).get("health_message"));
        assertEquals(2, second.infos().get(0).get("task_failure_hold_control_steps"));
    }

    @Test
    void pointGoalSuccessTerminatesWithoutMachineFailure() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        final PointTargetDefinition target = new PointTargetDefinition(4.0, 0.0, 4.5);
        final LocomotionVectorEnvironment<FakeMachine> env = new LocomotionVectorEnvironment<>(
                morphology,
                1,
                1,
                ResetStrategy.RESPAWN,
                (slot, episodeIndex) -> new EpisodeDefinition(
                        episodeIndex,
                        42L + episodeIndex,
                        EnvironmentTaskMode.POINT_GOAL,
                        new CurriculumStage("point", 0, "point test"),
                        LocomotionCommand.ZERO,
                        10,
                        target.initialDistanceBlocks(),
                        1.0,
                        dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile.none(),
                        target));
        env.resetAll();

        final EnvironmentBatchStep step = env.step(new double[][]{{0.0}});

        assertTrue(step.terminated()[0]);
        assertFalse(step.truncated()[0]);
        assertEquals(TerminationReason.SUCCESS.name(), step.infos().get(0).get("termination_reason"));
        assertEquals(Boolean.TRUE, step.infos().get(0).get("success"));
        assertEquals(target.initialDistanceBlocks(), (Double) step.infos().get(0).get("distance_to_target"), 1.0e-10);
        assertEquals(2, morphology.resetCount);
    }

    @Test
    void resetMaskClearsPreviousActionPhaseRewardAndTerminalFlags() {
        final FakeMorphology morphology = new FakeMorphology(3, 2);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 2, 5);
        env.resetAll();
        env.step(new double[][]{{0.5, 0.25}});

        final EnvironmentBatchReset reset = env.resetMask(new boolean[]{true});
        final EnvironmentBatchStep step = env.step(new double[][]{{0.0, 0.0}});

        assertEquals(0, reset.infos().get(0).get("episode_length"));
        assertEquals(0.0, step.observations()[0][1], 1.0e-10);
        assertEquals(0.0, step.observations()[0][2], 1.0e-10);
        assertFalse(step.terminated()[0]);
        assertFalse(step.truncated()[0]);
        assertEquals(2, morphology.resetCount);
    }

    @Test
    void repeatedRepairedObservationsTerminateAsMachineFailure() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        morphology.invalidObservation = true;
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 1, 10);
        env.resetAll();

        final EnvironmentBatchStep first = env.step(new double[][]{{0.0}});
        final EnvironmentBatchStep second = env.step(new double[][]{{0.0}});
        final EnvironmentBatchStep third = env.step(new double[][]{{0.0}});

        assertFalse(first.terminated()[0]);
        assertEquals(Boolean.TRUE, first.infos().get(0).get("observation_repaired"));
        assertEquals(1, first.infos().get(0).get("repaired_observation_count"));
        assertFalse(second.terminated()[0]);
        assertEquals(2, second.infos().get(0).get("repaired_observation_count"));
        assertTrue(third.terminated()[0]);
        assertFalse(third.truncated()[0]);
        assertEquals(TerminationReason.MACHINE_FAILURE.name(), third.infos().get(0).get("termination_reason"));
        assertEquals(3, third.infos().get(0).get("repaired_observation_count"));
        assertTrue(((String) third.infos().get(0).get("health_message")).contains("repaired observations"));
        assertEquals(2, third.resetObservations()[0].length);
        assertEquals(2, morphology.resetCount);
    }

    @Test
    void updateEpisodesChangesTargetWithoutRespawnOrPhaseReset() {
        final FakeMorphology morphology = new FakeMorphology(3, 2);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 2, 8);
        env.resetAll();
        final EnvironmentBatchStep beforeUpdate = env.step(new double[][]{{0.5, 0.0}});
        final PointTargetDefinition target = new PointTargetDefinition(10.0, 0.0, 1.0);

        final EnvironmentBatchReset update = env.updateEpisodes((slot, current) -> new EpisodeDefinition(
                current.episodeId(),
                current.seed(),
                EnvironmentTaskMode.POINT_GOAL,
                new CurriculumStage("updated_target", 1, "updated target"),
                LocomotionCommand.ZERO,
                current.maximumControlSteps(),
                target.initialDistanceBlocks(),
                current.gaitFrequencyHz(),
                dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile.none(),
                target));
        final EnvironmentBatchStep afterUpdate = env.step(new double[][]{{0.0, 0.0}});

        assertEquals(1, morphology.spawned);
        assertEquals(1, morphology.resetCount);
        assertEquals(1, morphology.episodeUpdateCount);
        assertEquals(EnvironmentTaskMode.POINT_GOAL, morphology.lastUpdatedTaskMode);
        assertEquals("episode_update", update.infos().get(0).get("event"));
        assertEquals("POINT_GOAL", update.infos().get(0).get("diagnostic_task_mode"));
        assertEquals(target.initialDistanceBlocks(), (Double) update.infos().get(0).get("diagnostic_initial_distance"), 1.0e-10);
        assertEquals(1, update.infos().get(0).get("diagnostic_control_step"));
        assertEquals(Boolean.FALSE, update.infos().get(0).get("observation_repaired"));
        assertEquals(0, update.infos().get(0).get("repaired_observation_count"));
        assertEquals(beforeUpdate.observations()[0][0], update.observations()[0][0], 1.0e-10);
        assertEquals(4.875, (Double) update.infos().get(0).get("distance_to_target"), 1.0e-10);
        assertFalse(afterUpdate.terminated()[0]);
    }

    @Test
    void episodeUpdateReportsRepairedObservationWithoutRespawn() {
        final FakeMorphology morphology = new FakeMorphology(3, 2);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 2, 8);
        env.resetAll();
        env.step(new double[][]{{0.5, 0.0}});
        morphology.invalidObservation = true;

        final EnvironmentBatchReset update = env.updateEpisodes((slot, current) -> current);

        assertEquals(1, morphology.spawned);
        assertEquals(1, morphology.resetCount);
        assertEquals("episode_update", update.infos().get(0).get("event"));
        assertEquals(Boolean.TRUE, update.infos().get(0).get("observation_repaired"));
        assertEquals(1, update.infos().get(0).get("repaired_observation_count"));
        assertEquals(0.0, update.observations()[0][0], 1.0e-10);
    }

    @Test
    void beginStepRejectsWrongDimensionsAndOutstandingRequests() {
        final FakeMorphology morphology = new FakeMorphology(2, 2);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 1, 3);
        env.resetAll();

        assertThrows(IllegalArgumentException.class, () -> env.beginStep(new double[][]{{0.0}}));

        env.beginStep(new double[][]{{0.0, 0.0}});
        assertThrows(IllegalStateException.class, () -> env.beginStep(new double[][]{{0.0, 0.0}}));
        assertThrows(IllegalStateException.class, () -> env.updateEpisodes((slot, current) -> current));
        env.finishStep();
    }

    @Test
    void diagnosticSamplingObservesHeldActionWithoutAdvancingControlState() {
        final FakeMorphology morphology = new FakeMorphology(2, 2);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 1, 4, 3);
        env.resetAll();
        env.beginStep(new double[][]{{0.25, -0.5}});

        final Map<String, Object> first = env.sampleDiagnosticInfos().get(0);
        final Map<String, Object> second = env.sampleDiagnosticInfos().get(0);

        assertEquals("diagnostic_sample", first.get("event"));
        assertEquals(0, first.get("episode_length"));
        assertEquals(0, first.get("diagnostic_control_step"));
        assertEquals(0.25, (Double) first.get("diagnostic_action_0"), 1.0e-10);
        assertEquals(first.get("episode_length"), second.get("episode_length"));
        assertEquals(first.get("episode_return"), second.get("episode_return"));

        final EnvironmentBatchStep completed = env.finishStep();
        assertEquals(1, completed.infos().get(0).get("episode_length"));
    }

    @Test
    void closeDestroysOwnedMachines() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 2, 1, 3);
        env.resetAll();

        env.close();

        assertEquals(2, morphology.destroyed);
    }

    @Test
    void closeCanRetryAfterDestroyFailureWithoutRedestroyingCompletedSlots() {
        final FakeMorphology morphology = new FakeMorphology(2, 1);
        final LocomotionVectorEnvironment<FakeMachine> env = newEnvironment(morphology, 3, 1, 3);
        env.resetAll();
        morphology.failDestroyOnceId = 1;

        assertThrows(IllegalStateException.class, env::close);
        assertEquals(List.of(0, 1), morphology.destroyAttempts);
        assertEquals(1, morphology.destroyed);

        env.close();
        assertEquals(List.of(0, 1, 1, 2), morphology.destroyAttempts);
        assertEquals(3, morphology.destroyed);

        env.close();
        assertEquals(List.of(0, 1, 1, 2), morphology.destroyAttempts);
        assertEquals(3, morphology.destroyed);
    }

    private static LocomotionVectorEnvironment<FakeMachine> newEnvironment(
            final FakeMorphology morphology,
            final int slots,
            final int controlTicks,
            final int maxControlSteps
    ) {
        return new LocomotionVectorEnvironment<>(
                morphology,
                slots,
                controlTicks,
                ResetStrategy.RESPAWN,
                (slot, episodeIndex) -> new EpisodeDefinition(
                        (slot * 1_000L) + episodeIndex,
                        42L + slot + episodeIndex,
                        EnvironmentTaskMode.COMMAND_TRACKING,
                        new CurriculumStage("test", 1, "test stage"),
                        new LocomotionCommand(1.0, 0.0, 0.0),
                        maxControlSteps,
                        5.0,
                        1.0));
    }

    private static final class FakeMorphology implements TrainableMorphology<FakeMachine> {
        private final ObservationSpec observationSpec;
        private final ActionSpec actionSpec;
        private int nextId;
        private int spawned;
        private int resetCount;
        private int destroyed;
        private final List<Integer> destroyAttempts = new java.util.ArrayList<>();
        private int failDestroyOnceId = -1;
        private int episodeUpdateCount;
        private EnvironmentTaskMode lastUpdatedTaskMode;
        private boolean invalidObservation;
        private boolean invalidResetObservation;
        private boolean taskSuccess;
        private int successHoldSteps = 1;
        private boolean taskFailure;
        private int failureHoldSteps = 1;

        private FakeMorphology(final int observationSize, final int actionSize) {
            this.observationSpec = new ObservationSpec("test:observation", 1, fields("obs", observationSize));
            this.actionSpec = new ActionSpec("test:action", 1, fields("act", actionSize));
        }

        @Override
        public String id() {
            return "test:fake";
        }

        @Override
        public ObservationSpec observationSpec() {
            return this.observationSpec;
        }

        @Override
        public ActionSpec actionSpec() {
            return this.actionSpec;
        }

        @Override
        public FakeMachine spawn(final TrainingSpawnContext context) {
            this.spawned++;
            return new FakeMachine(this.nextId++, this.actionSpec.size());
        }

        @Override
        public void onEpisodeUpdated(final FakeMachine machine, final EpisodeDefinition episode) {
            this.episodeUpdateCount++;
            this.lastUpdatedTaskMode = episode.taskMode();
        }

        @Override
        public void applyAction(final FakeMachine machine, final double[] normalizedAction) {
            machine.lastAction = Arrays.copyOf(normalizedAction, normalizedAction.length);
            machine.distance = Math.max(0.0, machine.distance - Math.abs(normalizedAction[0]) * 0.25);
        }

        @Override
        public double[] observe(final FakeMachine machine, final EpisodeDefinition episode, final EpisodeRuntime runtime) {
            final double[] observation = new double[this.observationSpec.size()];
            observation[0] = this.invalidObservation ? Double.NaN : Math.sin(runtime.phaseRad());
            for (int i = 0; i < Math.min(machine.lastAction.length, observation.length - 1); i++) {
                observation[i + 1] = machine.lastAction[i];
            }
            return observation;
        }

        @Override
        public RewardBreakdown calculateReward(
                final FakeMachine machine,
                final EpisodeDefinition episode,
                final EpisodeRuntime runtime,
                final double[] previousAction,
                final double[] currentAction
        ) {
            final double actionRate = square(currentAction[0] - previousAction[0]);
            return new RewardBreakdown(runtime.deltaSeconds() - actionRate * 0.01, Map.of(
                    "alive", runtime.deltaSeconds(),
                    "action_rate", actionRate));
        }

        @Override
        public MachineHealth inspectHealth(final FakeMachine machine, final EpisodeDefinition episode, final EpisodeRuntime runtime) {
            return machine.lastAction.length > 0 && machine.lastAction[0] > 0.95
                    ? MachineHealth.failure(TerminationReason.MACHINE_FAILURE, "test failure")
                    : MachineHealth.VALID;
        }

        @Override
        public boolean taskSuccessCondition(final FakeMachine machine, final EpisodeDefinition episode, final EpisodeRuntime runtime) {
            return this.taskSuccess;
        }

        @Override
        public int taskSuccessHoldControlSteps(final EpisodeDefinition episode) {
            return this.successHoldSteps;
        }

        @Override
        public boolean taskFailureCondition(final FakeMachine machine, final EpisodeDefinition episode, final EpisodeRuntime runtime) {
            return this.taskFailure;
        }

        @Override
        public int taskFailureHoldControlSteps(final EpisodeDefinition episode) {
            return this.failureHoldSteps;
        }

        @Override
        public String taskFailureMessage(final FakeMachine machine, final EpisodeDefinition episode, final EpisodeRuntime runtime) {
            return "held test failure";
        }

        @Override
        public double distanceToTarget(final FakeMachine machine, final EpisodeDefinition episode, final EpisodeRuntime runtime) {
            return machine.distance;
        }

        @Override
        public Map<String, Object> diagnosticInfo(
                final FakeMachine machine,
                final EpisodeDefinition episode,
                final EpisodeRuntime runtime,
                final double[] previousAction,
                final double[] currentAction
        ) {
            return Map.of(
                    "machine_id", machine.id,
                    "diagnostic_control_step", runtime.controlStep(),
                    "diagnostic_action_0", currentAction.length == 0 ? 0.0 : currentAction[0],
                    "diagnostic_task_mode", episode.taskMode().name(),
                    "diagnostic_initial_distance", episode.initialDistanceToTarget());
        }

        @Override
        public ResetResult<FakeMachine> reset(final FakeMachine machine, final EpisodeResetContext context) {
            this.resetCount++;
            machine.distance = context.episodeDefinition().initialDistanceToTarget();
            machine.lastAction = new double[this.actionSpec.size()];
            final double[] observation = new double[this.observationSpec.size()];
            if (this.invalidResetObservation && observation.length > 0) {
                observation[0] = Double.NaN;
            }
            return new ResetResult<>(machine, observation, machine.distance, true);
        }

        @Override
        public void destroy(final FakeMachine machine) {
            this.destroyAttempts.add(machine.id);
            if (machine.id == this.failDestroyOnceId) {
                this.failDestroyOnceId = -1;
                throw new IllegalStateException("injected destroy failure");
            }
            this.destroyed++;
        }

        private static List<VectorFieldSpec> fields(final String prefix, final int count) {
            return java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> new VectorFieldSpec(prefix + "_" + index, -1.0, 1.0, "normalized", "test"))
                    .toList();
        }

        private static double square(final double value) {
            return value * value;
        }
    }

    private static final class FakeMachine {
        private final int id;
        private double[] lastAction;
        private double distance;

        private FakeMachine(final int id, final int actionSize) {
            this.id = id;
            this.lastAction = new double[actionSize];
        }
    }
}
