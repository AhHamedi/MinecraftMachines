package dev.ahmedhamedi.minecraft_machines.content.training.environment;

import dev.ahmedhamedi.minecraft_machines.content.training.api.EpisodeRuntime;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode;
import dev.ahmedhamedi.minecraft_machines.content.training.api.MachineHealth;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ResetStrategy;
import dev.ahmedhamedi.minecraft_machines.content.training.api.RewardBreakdown;
import dev.ahmedhamedi.minecraft_machines.content.training.api.TerminationReason;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LocomotionVectorEnvironment<M> {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int MAX_REPAIRED_OBSERVATIONS_BEFORE_FAILURE = 3;

    private final TrainableMorphology<M> morphology;
    private final int slotCount;
    private final int controlTicks;
    private final ResetStrategy resetStrategy;
    private final boolean autoResetTerminatedSlots;
    private final EpisodeDefinitionProvider episodeProvider;
    private final List<Slot<M>> slots;
    private double[][] pendingActions;

    public LocomotionVectorEnvironment(
            final TrainableMorphology<M> morphology,
            final int slotCount,
            final int controlTicks,
            final ResetStrategy resetStrategy,
            final EpisodeDefinitionProvider episodeProvider
    ) {
        this(morphology, slotCount, controlTicks, resetStrategy, episodeProvider, true);
    }

    public LocomotionVectorEnvironment(
            final TrainableMorphology<M> morphology,
            final int slotCount,
            final int controlTicks,
            final ResetStrategy resetStrategy,
            final EpisodeDefinitionProvider episodeProvider,
            final boolean autoResetTerminatedSlots
    ) {
        this.morphology = Objects.requireNonNull(morphology, "morphology");
        if (slotCount < 1) {
            throw new IllegalArgumentException("slotCount must be positive");
        }
        if (controlTicks < 1) {
            throw new IllegalArgumentException("controlTicks must be positive");
        }
        this.slotCount = slotCount;
        this.controlTicks = controlTicks;
        this.resetStrategy = Objects.requireNonNull(resetStrategy, "resetStrategy");
        this.autoResetTerminatedSlots = autoResetTerminatedSlots;
        this.episodeProvider = Objects.requireNonNull(episodeProvider, "episodeProvider");
        this.slots = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            this.slots.add(new Slot<>(i));
        }
    }

    public EnvironmentBatchReset resetAll() {
        final List<EpisodeDefinition> definitions = new ArrayList<>(this.slotCount);
        for (final Slot<M> slot : this.slots) {
            definitions.add(this.episodeProvider.nextEpisode(slot.index, slot.episodeIndex));
        }
        return this.resetAll(definitions);
    }

    public EnvironmentBatchReset resetAll(final List<EpisodeDefinition> definitions) {
        if (definitions.size() != this.slotCount) {
            throw new IllegalArgumentException("episode definition count must match slot count");
        }
        final boolean[] mask = new boolean[this.slotCount];
        Arrays.fill(mask, true);
        return this.resetMask(mask, definitions);
    }

    public EnvironmentBatchReset resetMask(final boolean[] mask) {
        if (mask.length != this.slotCount) {
            throw new IllegalArgumentException("mask length must match slot count");
        }
        final List<EpisodeDefinition> definitions = new ArrayList<>(this.slotCount);
        for (final Slot<M> slot : this.slots) {
            definitions.add(mask[slot.index] ? this.episodeProvider.nextEpisode(slot.index, slot.episodeIndex) : slot.episode);
        }
        return this.resetMask(mask, definitions);
    }

    public EnvironmentBatchReset resetMask(final boolean[] mask, final List<EpisodeDefinition> definitions) {
        if (mask.length != this.slotCount || definitions.size() != this.slotCount) {
            throw new IllegalArgumentException("mask and definition counts must match slot count");
        }
        final double[][] observations = new double[this.slotCount][];
        final List<Map<String, Object>> infos = new ArrayList<>(this.slotCount);
        for (final Slot<M> slot : this.slots) {
            if (mask[slot.index]) {
                resetSlot(slot, definitions.get(slot.index));
            }
            observations[slot.index] = slot.previousObservationCopy();
            final Map<String, Object> resetInfo = new LinkedHashMap<>(this.morphology.diagnosticInfo(
                    slot.machine,
                    slot.episode,
                    slot.runtime(),
                    slot.previousAction,
                    slot.previousAction));
            addObservationRepairInfo(slot, resetInfo);
            infos.add(slot.info("reset", false, false, TerminationReason.NONE, resetInfo));
        }
        return new EnvironmentBatchReset(observations, infos);
    }

    public void beginStep(final double[][] actions) {
        if (this.pendingActions != null) {
            throw new IllegalStateException("a step is already pending");
        }
        validateActionBatch(actions);
        this.pendingActions = copy(actions);
        for (int i = 0; i < this.slotCount; i++) {
            final Slot<M> slot = this.slots.get(i);
            ensureInitialized(slot);
            final double[] action = clampAction(this.pendingActions[i]);
            this.pendingActions[i] = action;
            this.morphology.beforeControlStep(slot.machine, slot.episode, slot.runtime());
            this.morphology.applyAction(slot.machine, action);
        }
    }

    public EnvironmentBatchStep finishStep() {
        if (this.pendingActions == null) {
            throw new IllegalStateException("beginStep must be called before finishStep");
        }
        final double[][] actions = this.pendingActions;
        this.pendingActions = null;

        final double[][] observations = new double[this.slotCount][];
        final double[] rewards = new double[this.slotCount];
        final boolean[] terminated = new boolean[this.slotCount];
        final boolean[] truncated = new boolean[this.slotCount];
        final double[][] resetObservations = new double[this.slotCount][];
        final List<Map<String, Object>> infos = new ArrayList<>(this.slotCount);

        for (int i = 0; i < this.slotCount; i++) {
            final Slot<M> slot = this.slots.get(i);
            final double[] action = actions[i];
            advanceRuntime(slot, action);

            final MachineHealth inspectedHealth = this.morphology.inspectHealth(slot.machine, slot.episode, slot.runtime());
            final double currentDistanceToTarget = finiteNonNegativeOrPrevious(
                    this.morphology.distanceToTarget(slot.machine, slot.episode, slot.runtime()),
                    slot.previousDistanceToTarget);
            final boolean targetReached = isTargetReached(slot.episode, currentDistanceToTarget);
            final double[] rawObservation = this.morphology.observe(slot.machine, slot.episode, slot.runtime());
            final Map<String, Object> diagnosticInfo = this.morphology.diagnosticInfo(
                    slot.machine,
                    slot.episode,
                    slot.runtime(),
                    slot.previousAction,
                    action);
            final boolean observationRepaired = observationNeedsRepair(rawObservation);
            double[] observation = validatedObservation(rawObservation);
            if (observationRepaired) {
                slot.repairedObservationCount++;
            }
            slot.previousObservationRepaired = observationRepaired;
            final boolean repairedObservationFailure = slot.repairedObservationCount >= MAX_REPAIRED_OBSERVATIONS_BEFORE_FAILURE;
            final boolean taskFailureActive = inspectedHealth.valid()
                    && !repairedObservationFailure
                    && this.morphology.taskFailureCondition(slot.machine, slot.episode, slot.runtime());
            slot.taskFailureHoldControlSteps = taskFailureActive ? slot.taskFailureHoldControlSteps + 1 : 0;
            final boolean persistentTaskFailure = taskFailureActive
                    && slot.taskFailureHoldControlSteps >= this.morphology.taskFailureHoldControlSteps(slot.episode);
            final MachineHealth health = repairedObservationFailure && inspectedHealth.valid()
                    ? MachineHealth.failure(
                    TerminationReason.MACHINE_FAILURE,
                    "repaired observations reached " + slot.repairedObservationCount
                            + " in one episode; maximum allowed is "
                            + (MAX_REPAIRED_OBSERVATIONS_BEFORE_FAILURE - 1))
                    : persistentTaskFailure && inspectedHealth.valid()
                    ? MachineHealth.failure(
                    TerminationReason.MACHINE_FAILURE,
                    this.morphology.taskFailureMessage(slot.machine, slot.episode, slot.runtime()))
                    : inspectedHealth;
            final RewardBreakdown reward = this.morphology.calculateReward(slot.machine, slot.episode, slot.runtime(), slot.previousAction, action);
            final boolean machineFailure = !health.valid();
            final boolean timeLimit = slot.controlStep >= slot.episode.maximumControlSteps();
            final boolean taskSuccessActive = !machineFailure
                    && this.morphology.taskSuccessCondition(slot.machine, slot.episode, slot.runtime());
            slot.taskSuccessHoldControlSteps = taskSuccessActive ? slot.taskSuccessHoldControlSteps + 1 : 0;
            final boolean balanceSuccess = isBalanceSuccess(slot.episode, slot.taskSuccessHoldControlSteps, this.morphology.taskSuccessHoldControlSteps(slot.episode), machineFailure);
            final boolean isTerminated = machineFailure || targetReached || balanceSuccess;
            final boolean isTruncated = !isTerminated && timeLimit;
            final TerminationReason reason = machineFailure
                    ? health.reason()
                    : targetReached || balanceSuccess ? TerminationReason.SUCCESS
                    : isTruncated ? TerminationReason.TIME_LIMIT : TerminationReason.NONE;
            slot.accumulatedReward += reward.total();
            mergeRewardTotals(slot.rewardTotals, reward.components());
            slot.previousAction = Arrays.copyOf(action, action.length);
            slot.previousObservation = Arrays.copyOf(observation, observation.length);
            slot.previousDistanceToTarget = currentDistanceToTarget;
            final double currentBalanceError = this.morphology.balanceError(slot.machine, slot.episode, slot.runtime());
            slot.previousBalanceError = Double.isFinite(currentBalanceError) ? currentBalanceError : Double.NaN;

            observations[i] = observation;
            rewards[i] = reward.total();
            terminated[i] = isTerminated;
            truncated[i] = isTruncated;

            final Map<String, Object> stepInfo = new LinkedHashMap<>();
            stepInfo.putAll(diagnosticInfo);
            stepInfo.put("observation_repaired", observationRepaired);
            stepInfo.put("repaired_observation_count", slot.repairedObservationCount);
            stepInfo.put("distance_to_target", slot.previousDistanceToTarget);
            stepInfo.put("task_success_hold_control_steps", slot.taskSuccessHoldControlSteps);
            stepInfo.put("task_failure_hold_control_steps", slot.taskFailureHoldControlSteps);
            stepInfo.put("task_success_condition", taskSuccessActive);
            stepInfo.put("task_failure_condition", taskFailureActive);
            if (Double.isFinite(slot.previousBalanceError)) {
                stepInfo.put("previous_balance_error", slot.previousBalanceError);
            }
            stepInfo.put("reward_components", Map.copyOf(reward.components()));
            stepInfo.put("reward_component_totals", Map.copyOf(slot.rewardTotals));
            if (isTerminated || isTruncated) {
                stepInfo.put("terminal_observation", Arrays.copyOf(observation, observation.length));
                stepInfo.put("termination_reason", reason.name());
                stepInfo.put("episode_return", slot.accumulatedReward);
                stepInfo.put("episode_length", slot.controlStep);
                stepInfo.put("success", reason == TerminationReason.SUCCESS);
                stepInfo.put("health_message", health.message());
                // Capture the complete terminal record before auto-reset mutates
                // the slot's episode identity, phase, distance, and counters.
                // The reset observation is returned separately below.
                final Map<String, Object> terminalInfo = slot.info(
                        "step", isTerminated, isTruncated, reason, stepInfo);
                infos.add(terminalInfo);
                if (this.autoResetTerminatedSlots) {
                    final EpisodeDefinition nextEpisode = this.episodeProvider.nextEpisode(slot.index, slot.episodeIndex);
                    resetSlot(slot, nextEpisode);
                    resetObservations[i] = slot.previousObservationCopy();
                    terminalInfo.put("auto_reset_respawned", slot.lastResetRespawned);
                    terminalInfo.put("auto_reset_episode_id", slot.episode.episodeId());
                } else {
                    resetObservations[i] = new double[0];
                }
            } else {
                resetObservations[i] = new double[0];
                infos.add(slot.info("step", false, false, reason, stepInfo));
            }
        }

        return new EnvironmentBatchStep(observations, rewards, terminated, truncated, infos, resetObservations);
    }

    public EnvironmentBatchStep step(final double[][] actions) {
        this.beginStep(actions);
        return this.finishStep();
    }

    public EnvironmentBatchReset updateEpisodes(final EpisodeDefinitionUpdater updater) {
        if (this.pendingActions != null) {
            throw new IllegalStateException("cannot update episodes while a step is pending");
        }
        Objects.requireNonNull(updater, "updater");
        final double[][] observations = new double[this.slotCount][];
        final List<Map<String, Object>> infos = new ArrayList<>(this.slotCount);
        for (final Slot<M> slot : this.slots) {
            ensureInitialized(slot);
            final EpisodeDefinition updated = Objects.requireNonNull(
                    updater.update(slot.index, slot.episode),
                    "updated episode definition");
            updateSlotEpisode(slot, updated);
            observations[slot.index] = slot.previousObservationCopy();
            final Map<String, Object> updateInfo = new LinkedHashMap<>(this.morphology.diagnosticInfo(
                    slot.machine,
                    slot.episode,
                    slot.runtime(),
                    slot.previousAction,
                    slot.previousAction));
            addObservationRepairInfo(slot, updateInfo);
            infos.add(slot.info("episode_update", false, false, TerminationReason.NONE, updateInfo));
        }
        return new EnvironmentBatchReset(observations, infos);
    }

    public int slotCount() {
        return this.slotCount;
    }

    public int controlTicks() {
        return this.controlTicks;
    }

    public TrainableMorphology<M> morphology() {
        return this.morphology;
    }

    /**
     * Samples morphology diagnostics at the current physics pose without
     * advancing control time, reward totals, health holds, or observations.
     *
     * <p>This is intended for high-frequency audit instrumentation that must
     * observe poses between ordinary control boundaries. When an action is
     * pending, diagnostics receive that held action as the current action;
     * otherwise they receive the slot's previous action.</p>
     */
    public List<Map<String, Object>> sampleDiagnosticInfos() {
        final List<Map<String, Object>> infos = new ArrayList<>(this.slotCount);
        for (final Slot<M> slot : this.slots) {
            ensureInitialized(slot);
            final double[] currentAction = this.pendingActions == null
                    ? slot.previousAction
                    : this.pendingActions[slot.index];
            final Map<String, Object> diagnosticInfo = new LinkedHashMap<>(this.morphology.diagnosticInfo(
                    slot.machine,
                    slot.episode,
                    slot.runtime(),
                    slot.previousAction,
                    currentAction));
            addObservationRepairInfo(slot, diagnosticInfo);
            infos.add(slot.info(
                    "diagnostic_sample",
                    false,
                    false,
                    TerminationReason.NONE,
                    diagnosticInfo));
        }
        return List.copyOf(infos);
    }

    public void close() {
        for (final Slot<M> slot : this.slots) {
            if (slot.machine != null) {
                this.morphology.destroy(slot.machine);
                slot.machine = null;
            }
        }
        this.pendingActions = null;
    }

    private void resetSlot(final Slot<M> slot, final EpisodeDefinition definition) {
        final EpisodeDefinition episode = Objects.requireNonNull(definition, "episode definition");
        final double[] zeroAction = new double[this.morphology.actionSpec().size()];
        if (slot.machine == null) {
            slot.machine = this.morphology.spawn(new TrainingSpawnContext(
                    slot.index,
                    episode.episodeId(),
                    episode.seed(),
                    episode.curriculumStage(),
                    episode.terrainProfile()));
        }
        final ResetResult<M> reset = this.morphology.reset(slot.machine, new EpisodeResetContext(slot.index, episode, this.resetStrategy));
        final boolean observationRepaired = observationNeedsRepair(reset.observation());
        slot.machine = reset.machine();
        slot.episode = episode;
        slot.episodeIndex++;
        slot.controlStep = 0;
        slot.elapsedServerTicks = 0;
        slot.phaseRad = 0.0;
        slot.previousAction = zeroAction;
        slot.previousObservation = validatedObservation(reset.observation());
        slot.previousDistanceToTarget = reset.previousDistanceToTarget();
        slot.accumulatedReward = 0.0;
        slot.rewardTotals.clear();
        slot.previousObservationRepaired = observationRepaired;
        slot.repairedObservationCount = observationRepaired ? 1 : 0;
        slot.lastResetRespawned = reset.respawned();
        slot.taskSuccessHoldControlSteps = 0;
        slot.taskFailureHoldControlSteps = 0;
        final double initialBalanceError = this.morphology.balanceError(slot.machine, slot.episode, slot.runtime());
        slot.previousBalanceError = Double.isFinite(initialBalanceError) ? initialBalanceError : Double.NaN;
    }

    private void updateSlotEpisode(final Slot<M> slot, final EpisodeDefinition definition) {
        slot.episode = Objects.requireNonNull(definition, "episode definition");
        this.morphology.onEpisodeUpdated(slot.machine, slot.episode);
        slot.previousDistanceToTarget = slot.episode.taskMode() == EnvironmentTaskMode.POINT_GOAL
                ? slot.episode.initialDistanceToTarget()
                : 0.0;
        slot.previousDistanceToTarget = finiteNonNegativeOrPrevious(
                this.morphology.distanceToTarget(slot.machine, slot.episode, slot.runtime()),
                slot.previousDistanceToTarget);
        final double[] rawObservation = this.morphology.observe(slot.machine, slot.episode, slot.runtime());
        final boolean observationRepaired = observationNeedsRepair(rawObservation);
        slot.previousObservation = validatedObservation(rawObservation);
        if (observationRepaired) {
            slot.repairedObservationCount++;
        }
        slot.previousObservationRepaired = observationRepaired;
        slot.taskSuccessHoldControlSteps = 0;
        slot.taskFailureHoldControlSteps = 0;
        final double currentBalanceError = this.morphology.balanceError(slot.machine, slot.episode, slot.runtime());
        slot.previousBalanceError = Double.isFinite(currentBalanceError) ? currentBalanceError : Double.NaN;
    }

    private void ensureInitialized(final Slot<M> slot) {
        if (slot.machine == null || slot.episode == null) {
            throw new IllegalStateException("environment slot " + slot.index + " is not reset");
        }
    }

    private void advanceRuntime(final Slot<M> slot, final double[] action) {
        slot.controlStep++;
        slot.elapsedServerTicks += this.controlTicks;
        final double deltaSeconds = this.controlTicks / 20.0;
        slot.deltaSeconds = deltaSeconds;
        slot.phaseRad = positiveModulo(slot.phaseRad + TWO_PI * slot.episode.gaitFrequencyHz() * deltaSeconds, TWO_PI);
    }

    private void validateActionBatch(final double[][] actions) {
        if (actions.length != this.slotCount) {
            throw new IllegalArgumentException("action batch must have one row per slot");
        }
        for (int i = 0; i < actions.length; i++) {
            if (actions[i].length != this.morphology.actionSpec().size()) {
                throw new IllegalArgumentException("action size mismatch at slot " + i);
            }
            for (final double value : actions[i]) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("actions must be finite");
                }
            }
        }
    }

    private double[] clampAction(final double[] action) {
        final double[] copy = Arrays.copyOf(action, action.length);
        for (int i = 0; i < copy.length; i++) {
            copy[i] = Math.max(-1.0, Math.min(1.0, copy[i]));
        }
        return copy;
    }

    private double[] validatedObservation(final double[] observation) {
        if (observation.length != this.morphology.observationSpec().size()) {
            throw new IllegalArgumentException("observation size mismatch");
        }
        final double[] copy = Arrays.copyOf(observation, observation.length);
        for (int i = 0; i < copy.length; i++) {
            if (!Double.isFinite(copy[i])) {
                copy[i] = 0.0;
            }
            copy[i] = Math.max(-1.0, Math.min(1.0, copy[i]));
        }
        return copy;
    }

    private boolean observationNeedsRepair(final double[] observation) {
        if (observation.length != this.morphology.observationSpec().size()) {
            return true;
        }
        for (final double value : observation) {
            if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
                return true;
            }
        }
        return false;
    }

    private static void mergeRewardTotals(final Map<String, Double> totals, final Map<String, Double> components) {
        components.forEach((name, value) -> totals.merge(name, value, Double::sum));
    }

    private static void addObservationRepairInfo(final Slot<?> slot, final Map<String, Object> info) {
        info.put("observation_repaired", slot.previousObservationRepaired);
        info.put("repaired_observation_count", slot.repairedObservationCount);
    }

    private static double positiveModulo(final double value, final double modulus) {
        final double result = value % modulus;
        return result < 0.0 ? result + modulus : result;
    }

    private static double finiteNonNegativeOrPrevious(final double value, final double previous) {
        return Double.isFinite(value) && value >= 0.0 ? value : previous;
    }

    private static boolean isTargetReached(final EpisodeDefinition episode, final double currentDistanceToTarget) {
        return episode.taskMode() == EnvironmentTaskMode.POINT_GOAL
                && episode.pointTarget().reached(currentDistanceToTarget);
    }

    private static boolean isBalanceSuccess(
            final EpisodeDefinition episode,
            final int successHoldControlSteps,
            final int requiredSuccessHoldControlSteps,
            final boolean machineFailure
    ) {
        return episode.taskMode() == EnvironmentTaskMode.BALANCE
                && !machineFailure
                && successHoldControlSteps >= Math.max(1, requiredSuccessHoldControlSteps);
    }

    private static double[][] copy(final double[][] source) {
        final double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }

    private static final class Slot<M> {
        private final int index;
        private M machine;
        private EpisodeDefinition episode;
        private long episodeIndex;
        private int controlStep;
        private int elapsedServerTicks;
        private double phaseRad;
        private double[] previousAction = new double[0];
        private double[] previousObservation = new double[0];
        private double previousDistanceToTarget;
        private double accumulatedReward;
        private double deltaSeconds = 1.0 / 20.0;
        private final Map<String, Double> rewardTotals = new LinkedHashMap<>();
        private boolean previousObservationRepaired;
        private int repairedObservationCount;
        private boolean lastResetRespawned;
        private double previousBalanceError = Double.NaN;
        private int taskSuccessHoldControlSteps;
        private int taskFailureHoldControlSteps;

        private Slot(final int index) {
            this.index = index;
        }

        private EpisodeRuntime runtime() {
            return new EpisodeRuntime(
                    this.episode.episodeId(),
                    this.episode.seed(),
                    this.controlStep,
                    this.elapsedServerTicks,
                    this.phaseRad,
                    this.deltaSeconds,
                    this.previousAction,
                    this.previousDistanceToTarget,
                    this.accumulatedReward,
                    this.rewardTotals,
                    this.repairedObservationCount,
                    this.previousBalanceError,
                    this.taskSuccessHoldControlSteps,
                    this.taskFailureHoldControlSteps);
        }

        private double[] previousObservationCopy() {
            return Arrays.copyOf(this.previousObservation, this.previousObservation.length);
        }

        private Map<String, Object> info(
                final String event,
                final boolean terminated,
                final boolean truncated,
                final TerminationReason reason,
                final Map<String, Object> extra
        ) {
            final Map<String, Object> info = new LinkedHashMap<>();
            info.put("event", event);
            info.put("slot", this.index);
            info.put("episode_id", this.episode == null ? -1L : this.episode.episodeId());
            info.put("episode_seed", this.episode == null ? 0L : this.episode.seed());
            info.put("task_mode", this.episode == null ? "" : this.episode.taskMode().name());
            info.put("curriculum_stage", this.episode == null ? "" : this.episode.curriculumStage().id());
            info.put("curriculum_stage_index", this.episode == null ? -1 : this.episode.curriculumStage().index());
            info.put("curriculum_stage_description", this.episode == null ? "" : this.episode.curriculumStage().description());
            info.put("terrain_profile", this.episode == null ? "" : this.episode.terrainProfile().generatorId());
            info.put("terrain_enabled", this.episode != null && this.episode.terrainProfile().enabled());
            info.put("episode_length", this.controlStep);
            info.put("phase_rad", this.phaseRad);
            info.put("episode_return", this.accumulatedReward);
            info.put("distance_to_target", this.previousDistanceToTarget);
            info.put("task_success_hold_control_steps", this.taskSuccessHoldControlSteps);
            info.put("task_failure_hold_control_steps", this.taskFailureHoldControlSteps);
            info.put("terminated", terminated);
            info.put("truncated", truncated);
            info.put("termination_reason", reason.name());
            info.put("reset_respawned", this.lastResetRespawned);
            info.putAll(extra);
            return info;
        }
    }
}
