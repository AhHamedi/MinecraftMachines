package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EpisodeRuntime;
import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ResetStrategy;
import dev.ahmedhamedi.minecraft_machines.content.training.api.RewardBreakdown;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CemExplorationDiagnostics;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CemFitnessProtocol;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CemScenarioSeeds;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CemSettings;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.ContinuousCemDistribution;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CurriculumScopedBestSelection;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.GenomePolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.LinearTanhPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.ScoredGenome;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.CurriculumStage;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchReset;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchStep;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.LocomotionVectorEnvironment;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.TrainingSpawnContext;
import dev.ahmedhamedi.minecraft_machines.content.training.evaluation.FirstTerminalSnapshot;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodEvaluationManifest;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodEvaluationManifests;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodEvaluationSanity;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodEvaluationScenario;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodPhaseGaitPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodPhaseGaitSeeds;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodKinematics;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodSchemas;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodTrainingScenarios;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodWalkForwardFitness;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PointTargetCommandConfig;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PointTargetCommandGenerator;
import dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public final class MinecraftMachinesDuopodCemTrainer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final String LINEAR_TANH_POLICY_TYPE = "linear_tanh";
    private static final String LEGACY_PHASE_GAIT_POLICY_TYPE = "duopod_phase_gait_v1";
    static final String CEM_FITNESS_CONTRACT = "minecraft_machines:duopod_cem_fitness_v6";
    private static final String UNKNOWN_FITNESS_CONTRACT = "legacy_unknown";
    static final int TRAINING_CHUNK_ACTIVATION_TIMEOUT_TICKS = 600;
    private static final double PHASE_GAIT_INITIAL_STD = 0.85;
    private static final double LOOK_TARGET_MAX_DISTANCE_BLOCKS = 96.0;
    private static final int LOOK_TARGET_UPDATE_INTERVAL_TICKS = 2;
    private static final int INITIAL_ACTION_RAMP_CONTROL_STEPS = 5;
    private static final double CEM_INITIAL_WEIGHT_STD = 0.18;
    private static final double CEM_INITIAL_BIAS_STD = 0.45;
    private static final double CEM_SEEDED_RESTART_STD = 0.04;
    private static final double CEM_PROTOCOL_RESTART_STD_MULTIPLIER = 1.5;
    private static final double CEM_MINIMUM_STD = 0.02;
    private static final double PHASE_GAIT_MINIMUM_STD = 0.12;
    private static final double PHASE_GAIT_PROTOCOL_RESTART_STD = 0.60;
    private static final double CEM_BALANCE_MINIMUM_STD = 0.06;
    private static final double CEM_MAXIMUM_STD = 1.25;
    private static final int CEM_STAGNATION_WINDOW = 5;
    private static final double CEM_STAGNATION_MINIMUM_IMPROVEMENT = 0.10;
    private static final double CEM_MINIMUM_ACTION_DIVERSITY = 0.035;
    private static final double CEM_COLLAPSED_NEAR_IDENTICAL_ACTION_FRACTION = 0.75;
    private static final double CEM_BALANCE_UNSOLVED_NEAR_IDENTICAL_ACTION_FRACTION = 0.40;
    private static final double CEM_EXPLORATION_EXPANSION_MULTIPLIER = 1.25;
    private static final double PHASE_GAIT_HARD_EXPANSION_MULTIPLIER = 2.0;
    private static final int PHASE_GAIT_HARD_EXPANSION_COOLDOWN_GENERATIONS = 2;
    private static final double PHASE_GAIT_HARD_EXPANSION_MAXIMUM_MEAN_STD = 0.20;
    private static final double CEM_ACTION_SATURATION_THRESHOLD = 0.98;
    private static final double CEM_MAXIMUM_SATURATION_FOR_EXPANSION = 0.65;
    private static final double CEM_NEAR_IDENTICAL_ACTION_DISTANCE = 0.02;
    private static final PointTargetCommandGenerator TARGET_COMMAND_GENERATOR =
            new PointTargetCommandGenerator(PointTargetCommandConfig.DEFAULT);
    private static final int DEFAULT_EVALUATION_CONTROL_TICKS = 4;
    static final int DEFAULT_EVALUATION_MAX_CONTROL_STEPS = 200;
    private static final double EVALUATION_DISTANCE_SANITY_TOLERANCE_BLOCKS = 1.0e-4;
    static final int DEFAULT_TARGET_CHANGE_SWITCH_STEP = 40;
    static final int DEFAULT_TARGET_CHANGE_MAX_CONTROL_STEPS = 100;
    private static final double TARGET_CHANGE_FORWARD_BLOCKS = 8.0;
    private static final double TARGET_CHANGE_RIGHT_BLOCKS = 4.0;
    private static final int REWARD_VISUALIZATION_CONTROL_STEP_INTERVAL = 5;
    private static final int REWARD_VISUALIZATION_MAX_SLOTS = 1;
    private static final double REWARD_VISUALIZATION_COMMAND_SCALE = 1.8;
    private static final double REWARD_VISUALIZATION_VELOCITY_SCALE = 1.4;
    private static final DustParticleOptions REWARD_VIZ_GOAL =
            new DustParticleOptions(new Vector3f(0.05F, 0.65F, 1.0F), 1.15F);
    private static final DustParticleOptions REWARD_VIZ_TARGET =
            new DustParticleOptions(new Vector3f(1.0F, 0.85F, 0.10F), 1.2F);
    private static final DustParticleOptions REWARD_VIZ_ACTUAL_GOOD =
            new DustParticleOptions(new Vector3f(0.10F, 0.95F, 0.20F), 1.05F);
    private static final DustParticleOptions REWARD_VIZ_ACTUAL_BAD =
            new DustParticleOptions(new Vector3f(1.0F, 0.10F, 0.08F), 1.05F);
    private static final DustParticleOptions REWARD_VIZ_REWARD =
            new DustParticleOptions(new Vector3f(0.10F, 1.0F, 0.25F), 1.0F);
    private static final DustParticleOptions REWARD_VIZ_PENALTY =
            new DustParticleOptions(new Vector3f(1.0F, 0.05F, 0.05F), 1.0F);

    private static TrainingRun activeRun;
    private static EvaluationRun activeEvaluation;
    private static TargetChangeValidationRun activeTargetChangeValidation;
    private static ReplayRun replayRun;
    private static LookTargetMode lookTargetMode;
    private static BestGenome bestEver;
    private static boolean rewardVisualizationEnabled;

    private MinecraftMachinesDuopodCemTrainer() {
    }

    public static int start(final CommandSourceStack source, final Config config) {
        return start(source, config, false);
    }

    public static int startFresh(final CommandSourceStack source, final Config config) {
        return start(source, config, true);
    }

    private static int start(final CommandSourceStack source, final Config config, final boolean forceFresh) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        final Direction forward = player.getDirection();
        final BlockPos origin = player.blockPosition().relative(forward, 5);
        final long seed = player.serverLevel().getGameTime() ^ player.getUUID().getMostSignificantBits();
        return startAt(source, player.serverLevel(), player.getUUID(), player.getGameProfile().getName(), origin, forward, config, seed, forceFresh);
    }

    public static int startAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final Config config
    ) {
        return startAt(source, x, y, z, forwardName, config, false);
    }

    public static int startAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final Config config,
            final long seed
    ) {
        return startAt(source, x, y, z, forwardName, config, false, seed);
    }

    public static int startFreshAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final Config config
    ) {
        return startAt(source, x, y, z, forwardName, config, true);
    }

    public static int startFreshAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final Config config,
            final long seed
    ) {
        return startAt(source, x, y, z, forwardName, config, true, seed);
    }

    private static int startAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final Config config,
            final boolean forceFresh
    ) {
        final Direction forward = parseHorizontalDirection(forwardName);
        if (forward == null) {
            source.sendFailure(Component.literal("forward must be one of north, south, east, or west"));
            return 0;
        }
        final ServerLevel level = source.getLevel();
        final BlockPos origin = new BlockPos(x, y, z);
        final long seed = level.getGameTime() ^ origin.asLong() ^ ((long) forward.get3DDataValue() << 32);
        return startAt(source, level, null, "console", origin, forward, config, seed, forceFresh);
    }

    private static int startAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final Config config,
            final boolean forceFresh,
            final long seed
    ) {
        final Direction forward = parseHorizontalDirection(forwardName);
        if (forward == null) {
            source.sendFailure(Component.literal("forward must be one of north, south, east, or west"));
            return 0;
        }
        final ServerLevel level = source.getLevel();
        final BlockPos origin = new BlockPos(x, y, z);
        return startAt(source, level, null, "console", origin, forward, config, seed, forceFresh);
    }

    private static int startAt(
            final CommandSourceStack source,
            final ServerLevel level,
            final UUID ownerId,
            final String ownerName,
            final BlockPos origin,
            final Direction forward,
            final Config config,
            final long seed,
            final boolean forceFresh
    ) {
        if (MinecraftMachinesTrainingBridge.hasActiveBridge()) {
            source.sendFailure(Component.literal("Stop the dimension-global PPO training bridge before starting CEM training."));
            return 0;
        }
        if (activeRun != null) {
            source.sendFailure(Component.literal("A Duopod CEM run is already active. Use /mm train duopod cem stop first."));
            return 0;
        }
        if (activeEvaluation != null) {
            source.sendFailure(Component.literal("A Duopod CEM evaluation is already active. Use /mm train duopod cem stop first."));
            return 0;
        }
        if (activeTargetChangeValidation != null) {
            source.sendFailure(Component.literal("A Duopod target-change validation is already active. Use /mm train duopod cem stop first."));
            return 0;
        }
        if (MinecraftMachinesDuopodWalkForwardBenchmark.hasActiveRun()) {
            source.sendFailure(Component.literal("A Duopod walk-forward benchmark is active. Use /mm train duopod cem stop first."));
            return 0;
        }

        clearReplay(source.getServer());
        final GenomePolicy policy = duopodPolicy(config.curriculumStage());
        final boolean freshOptimizer = usesFreshOptimizer(policy, config, forceFresh);
        final ContinuousCemDistribution distribution = initialDistribution(policy, seed, config, forceFresh);
        final TrainingRun run = new TrainingRun(
                UUID.randomUUID(),
                ownerId,
                level.dimension(),
                origin,
                forward,
                UUID.randomUUID(),
                config,
                policy,
                distribution,
                RandomGeneratorFactory.of("L64X128MixRandom").create(seed),
                seed,
                freshOptimizer);

        if (!run.startGeneration(level)) {
            source.sendFailure(Component.literal(run.lastFailure()));
            run.close(level);
            return 0;
        }

        if (forceFresh) {
            // Explicit fresh start bypasses the in-memory checkpoint without deleting or
            // rewriting its on-disk file. The new run establishes its own score scale.
            bestEver = null;
        }

        activeRun = run;
        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_BEGIN run={} player={} dimension={} origin={} forward={} seed={} optimizerStart={} config={} observationSize={} actionSize={} genomeSize={}",
                run.runId(),
                ownerName,
                run.dimension().location(),
                run.origin().toShortString(),
                forward,
                seed,
                forceFresh ? "explicit_fresh" : freshOptimizer ? "fresh" : "checkpoint",
                config.compactDescription(),
                policy.observationSize(),
                policy.actionSize(),
                policy.genomeSize());
        source.sendSuccess(() -> Component.literal("Started Duopod CEM run %s (%s, seed %d): %s.%s"
                .formatted(
                        run.runId(),
                        forceFresh ? "explicit fresh optimizer" : freshOptimizer ? "fresh optimizer" : "checkpoint optimizer",
                        seed,
                        config.compactDescription(),
                        forceFresh ? " Saved checkpoint file was left unchanged" : "")), true);
        return 1;
    }

    public static int stop(final CommandSourceStack source) {
        final int stopped = stopActive(source.getServer(), "command_stop");
        if (stopped == 0) {
            source.sendFailure(Component.literal("No active Duopod CEM run, evaluation, target-change validation, or walk-forward benchmark."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stopped active Duopod CEM task."), true);
        return 1;
    }

    public static int stopActive(final MinecraftServer server, final String reason) {
        int stopped = 0;
        final TrainingRun run = activeRun;
        if (run != null) {
            final ServerLevel level = server.getLevel(run.dimension());
            final boolean cleanupComplete = level != null ? run.close(level) : run.close();
            if (cleanupComplete) {
                activeRun = null;
            }
            stopped++;
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason={} cleanup={}",
                    run.runId(),
                    reason,
                    cleanupComplete ? "complete" : "retry_pending");
        }
        final EvaluationRun evaluation = activeEvaluation;
        if (evaluation != null) {
            final ServerLevel level = server.getLevel(evaluation.dimension());
            if (level != null) {
                evaluation.close(level);
            }
            activeEvaluation = null;
            stopped++;
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason={}", evaluation.evaluationId(), reason);
        }
        final TargetChangeValidationRun validation = activeTargetChangeValidation;
        if (validation != null) {
            final ServerLevel level = server.getLevel(validation.dimension());
            if (level != null) {
                validation.close(level);
            }
            activeTargetChangeValidation = null;
            stopped++;
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_TARGET_CHANGE_END validation={} reason={}", validation.validationId(), reason);
        }
        stopped += MinecraftMachinesDuopodWalkForwardBenchmark.stopActive(server, reason);
        return stopped;
    }

    public static int evaluateBest(final CommandSourceStack source) {
        return evaluateBest(source, DEFAULT_EVALUATION_MAX_CONTROL_STEPS);
    }

    public static int evaluateBest(final CommandSourceStack source, final int maximumControlSteps) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        final Direction forward = player.getDirection();
        final BlockPos origin = player.blockPosition().relative(forward, 5);
        return evaluateBestAt(source, player.serverLevel(), player.getUUID(), player.getGameProfile().getName(), origin, forward, maximumControlSteps);
    }

    public static int evaluateBestAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final int maximumControlSteps
    ) {
        final Direction forward = parseHorizontalDirection(forwardName);
        if (forward == null) {
            source.sendFailure(Component.literal("forward must be one of north, south, east, or west"));
            return 0;
        }
        return evaluateBestAt(source, source.getLevel(), null, "console", new BlockPos(x, y, z), forward, maximumControlSteps);
    }

    private static int evaluateBestAt(
            final CommandSourceStack source,
            final ServerLevel level,
            final UUID ownerId,
            final String ownerName,
            final BlockPos origin,
            final Direction forward,
            final int maximumControlSteps
    ) {
        if (MinecraftMachinesTrainingBridge.hasActiveBridge()) {
            source.sendFailure(Component.literal("Stop the dimension-global PPO training bridge before CEM evaluation."));
            return 0;
        }
        if (bestEver == null) {
            source.sendFailure(Component.literal("No Duopod CEM best genome exists yet."));
            return 0;
        }
        if (LEGACY_PHASE_GAIT_POLICY_TYPE.equals(bestEver.policyType())) {
            source.sendFailure(Component.literal(
                    "Legacy duopod_phase_gait_v1 checkpoints cannot be replayed faithfully by the v2 policy. "
                            + "Resume training to produce a v2 checkpoint or use the immutable historical artifact."));
            return 0;
        }
        if (!LINEAR_TANH_POLICY_TYPE.equals(bestEver.policyType())) {
            source.sendFailure(Component.literal("Held-out point-goal evaluation requires a linear_tanh target-capable policy; replay the walk_forward gait best instead."));
            return 0;
        }
        if (activeRun != null) {
            source.sendFailure(Component.literal("Stop the active Duopod CEM training run before evaluation."));
            return 0;
        }
        if (activeEvaluation != null) {
            source.sendFailure(Component.literal("A Duopod CEM evaluation is already active."));
            return 0;
        }
        if (activeTargetChangeValidation != null) {
            source.sendFailure(Component.literal("A Duopod target-change validation is already active."));
            return 0;
        }
        if (MinecraftMachinesDuopodWalkForwardBenchmark.hasActiveRun()) {
            source.sendFailure(Component.literal("A Duopod walk-forward benchmark is already active."));
            return 0;
        }

        clearReplay(source.getServer());
        final EvaluationRun evaluation = new EvaluationRun(
                UUID.randomUUID(),
                ownerId,
                level.dimension(),
                origin,
                forward,
                UUID.randomUUID(),
                bestEver.genome(),
                DuopodEvaluationManifests.heldOutPointGoals(),
                DEFAULT_EVALUATION_CONTROL_TICKS,
                bestEver.trainingConfig().spawnWarmupTicks(),
                maximumControlSteps);
        if (!evaluation.start(level)) {
            source.sendFailure(Component.literal(evaluation.lastFailure()));
            evaluation.close(level);
            return 0;
        }
        activeEvaluation = evaluation;
        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_BEGIN evaluation={} player={} scenarios={} manifestHash={}",
                evaluation.evaluationId(),
                ownerName,
                evaluation.manifest().scenarios().size(),
                evaluation.manifest().compatibilityHash());
        source.sendSuccess(() -> Component.literal("Started Duopod CEM held-out evaluation %s over %d scenarios."
                .formatted(evaluation.evaluationId(), evaluation.manifest().scenarios().size())), true);
        return 1;
    }

    public static int benchmarkWalkForward(final CommandSourceStack source) {
        return benchmarkWalkForward(source, MinecraftMachinesDuopodWalkForwardBenchmark.DEFAULT_MAX_CONTROL_STEPS);
    }

    public static int benchmarkWalkForward(final CommandSourceStack source, final int maximumControlSteps) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only; use benchmark_walk_forward_at from the server console."));
            return 0;
        }
        final Direction forward = player.getDirection();
        final BlockPos origin = player.blockPosition().relative(forward, 5);
        return benchmarkWalkForwardAt(
                source,
                player.serverLevel(),
                player.getUUID(),
                player.getGameProfile().getName(),
                origin,
                forward,
                maximumControlSteps);
    }

    public static int benchmarkWalkForwardAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final int maximumControlSteps
    ) {
        final Direction forward = parseHorizontalDirection(forwardName);
        if (forward == null) {
            source.sendFailure(Component.literal("forward must be one of north, south, east, or west"));
            return 0;
        }
        return benchmarkWalkForwardAt(
                source,
                source.getLevel(),
                null,
                "console",
                new BlockPos(x, y, z),
                forward,
                maximumControlSteps);
    }

    private static int benchmarkWalkForwardAt(
            final CommandSourceStack source,
            final ServerLevel level,
            final UUID ownerId,
            final String ownerName,
            final BlockPos origin,
            final Direction forward,
            final int maximumControlSteps
    ) {
        if (MinecraftMachinesTrainingBridge.hasActiveBridge()) {
            source.sendFailure(Component.literal("Stop the dimension-global PPO training bridge before benchmarking."));
            return 0;
        }
        final BestGenome checkpoint = bestEver;
        if (checkpoint == null) {
            source.sendFailure(Component.literal("No Duopod CEM best genome exists yet."));
            return 0;
        }
        if (!DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(checkpoint.curriculumStage())
                || !DuopodPhaseGaitPolicy.POLICY_TYPE.equals(checkpoint.policyType())) {
            source.sendFailure(Component.literal(
                    "Walk-forward benchmark requires a current walk_forward / "
                            + DuopodPhaseGaitPolicy.POLICY_TYPE
                            + " checkpoint; load or train that checkpoint first."));
            return 0;
        }
        if (maximumControlSteps < 1) {
            source.sendFailure(Component.literal("maxControlSteps must be positive."));
            return 0;
        }
        if (maximumControlSteps != MinecraftMachinesDuopodWalkForwardBenchmark.DEFAULT_MAX_CONTROL_STEPS) {
            source.sendFailure(Component.literal(
                    "Controlled benchmark requires exactly "
                            + MinecraftMachinesDuopodWalkForwardBenchmark.DEFAULT_MAX_CONTROL_STEPS
                            + " control steps."));
            return 0;
        }
        if (activeRun != null || activeEvaluation != null || activeTargetChangeValidation != null) {
            source.sendFailure(Component.literal("Stop the active Duopod CEM run/evaluation/target-change validation first."));
            return 0;
        }
        clearReplay(source.getServer());
        final Config trainingConfig = checkpoint.trainingConfig();
        if (!CEM_FITNESS_CONTRACT.equals(checkpoint.fitnessContract())
                || !MinecraftMachinesDuopodFlatArena.ARENA_ID.equals(checkpoint.trainingArenaId())
                || !MinecraftMachinesDuopodFlatArena.SLOT_LAYOUT_ID.equals(checkpoint.trainingSlotLayoutId())
                || trainingConfig.spacingBlocks() != MinecraftMachinesDuopodWalkForwardBenchmark.SLOT_SPACING_BLOCKS
                || trainingConfig.controlTicks() != MinecraftMachinesDuopodWalkForwardBenchmark.DEFAULT_CONTROL_TICKS
                || trainingConfig.spawnWarmupTicks() != MinecraftMachinesDuopodWalkForwardBenchmark.DEFAULT_SPAWN_WARMUP_TICKS
                || trainingConfig.maximumControlSteps() != MinecraftMachinesDuopodWalkForwardBenchmark.DEFAULT_MAX_CONTROL_STEPS
                || trainingConfig.episodesPerCandidate() != MinecraftMachinesDuopodFlatArena.LANES_PER_CANDIDATE_GROUP) {
            source.sendFailure(Component.literal(
                    "Checkpoint is not compatible with the controlled benchmark: retrain under the exact current fitness, arena, slot-layout, spacing, cadence, warmup, horizon, and three-speed contract."));
            return 0;
        }
        return MinecraftMachinesDuopodWalkForwardBenchmark.start(
                source,
                level,
                ownerId,
                ownerName,
                origin,
                forward,
                checkpoint.genome(),
                trainingConfig.controlTicks(),
                trainingConfig.spawnWarmupTicks(),
                maximumControlSteps,
                new MinecraftMachinesDuopodWalkForwardBenchmark.CheckpointMetadata(
                        checkpoint.runId(),
                        checkpoint.generation(),
                        checkpoint.aggregateFitness(),
                        checkpoint.failureRate(),
                        checkpoint.createdAt(),
                        checkpoint.fitnessContract(),
                        checkpoint.trainingArenaId(),
                        checkpoint.trainingSlotLayoutId(),
                        trainingConfig.spacingBlocks(),
                        trainingConfig.controlTicks(),
                        trainingConfig.spawnWarmupTicks(),
                        trainingConfig.maximumControlSteps()));
    }

    public static int validateTargetChange(final CommandSourceStack source) {
        return validateTargetChange(source, DEFAULT_TARGET_CHANGE_SWITCH_STEP, DEFAULT_TARGET_CHANGE_MAX_CONTROL_STEPS);
    }

    public static int validateTargetChange(
            final CommandSourceStack source,
            final int switchControlStep,
            final int maximumControlSteps
    ) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        final Direction forward = player.getDirection();
        final BlockPos origin = player.blockPosition().relative(forward, 5);
        return validateTargetChangeAt(source, player.serverLevel(), player.getUUID(), player.getGameProfile().getName(), origin, forward, switchControlStep, maximumControlSteps);
    }

    public static int validateTargetChangeAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final int switchControlStep,
            final int maximumControlSteps
    ) {
        final Direction forward = parseHorizontalDirection(forwardName);
        if (forward == null) {
            source.sendFailure(Component.literal("forward must be one of north, south, east, or west"));
            return 0;
        }
        return validateTargetChangeAt(source, source.getLevel(), null, "console", new BlockPos(x, y, z), forward, switchControlStep, maximumControlSteps);
    }

    private static int validateTargetChangeAt(
            final CommandSourceStack source,
            final ServerLevel level,
            final UUID ownerId,
            final String ownerName,
            final BlockPos origin,
            final Direction forward,
            final int switchControlStep,
            final int maximumControlSteps
    ) {
        if (MinecraftMachinesTrainingBridge.hasActiveBridge()) {
            source.sendFailure(Component.literal("Stop the dimension-global PPO training bridge before target-change validation."));
            return 0;
        }
        if (bestEver == null) {
            source.sendFailure(Component.literal("No Duopod CEM best genome exists yet."));
            return 0;
        }
        if (!LINEAR_TANH_POLICY_TYPE.equals(bestEver.policyType())) {
            source.sendFailure(Component.literal("Target-change validation requires a linear_tanh target-capable policy; replay the walk_forward gait best instead."));
            return 0;
        }
        if (switchControlStep < 1 || maximumControlSteps <= switchControlStep + 1) {
            source.sendFailure(Component.literal("Target-change validation requires at least one post-switch response step."));
            return 0;
        }
        if (activeRun != null || activeEvaluation != null || activeTargetChangeValidation != null
                || MinecraftMachinesDuopodWalkForwardBenchmark.hasActiveRun()) {
            source.sendFailure(Component.literal("Stop the active Duopod CEM run/evaluation/target-change validation first."));
            return 0;
        }

        clearReplay(source.getServer());
        final TargetChangeValidationRun validation = new TargetChangeValidationRun(
                UUID.randomUUID(),
                ownerId,
                level.dimension(),
                origin,
                forward,
                UUID.randomUUID(),
                bestEver.genome(),
                DEFAULT_EVALUATION_CONTROL_TICKS,
                switchControlStep,
                maximumControlSteps);
        if (!validation.start(level)) {
            source.sendFailure(Component.literal(validation.lastFailure()));
            validation.close(level);
            return 0;
        }
        activeTargetChangeValidation = validation;
        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_TARGET_CHANGE_BEGIN validation={} player={} switchStep={} maxSteps={}",
                validation.validationId(),
                ownerName,
                switchControlStep,
                maximumControlSteps);
        source.sendSuccess(() -> Component.literal("Started Duopod CEM target-change validation %s; switch step %d/%d."
                .formatted(validation.validationId(), switchControlStep, maximumControlSteps)), true);
        return 1;
    }

    public static int stopEvaluation(final CommandSourceStack source) {
        final EvaluationRun evaluation = activeEvaluation;
        if (evaluation == null) {
            source.sendFailure(Component.literal("No active Duopod CEM evaluation."));
            return 0;
        }
        final ServerLevel level = source.getServer().getLevel(evaluation.dimension());
        if (level != null) {
            evaluation.close(level);
        }
        activeEvaluation = null;
        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=command_stop", evaluation.evaluationId());
        source.sendSuccess(() -> Component.literal("Stopped Duopod CEM evaluation " + evaluation.evaluationId() + "."), true);
        return 1;
    }

    public static int status(final CommandSourceStack source) {
        final TrainingRun run = activeRun;
        final String best = bestEver == null
                ? "No best genome recorded."
                : "Best %s aggregate %.3f mean %.3f from generation %d."
                .formatted(bestEver.curriculumStage(), bestEver.aggregateFitness(), bestEver.meanScore(), bestEver.generation());
        if (run == null) {
            final String replay = replayRun == null ? "No replay active." : "Replay active.";
            final String lookTarget = lookTargetMode == null ? "Look target inactive." : "Look target active.";
            final String evaluation = activeEvaluation == null ? "No evaluation active." : "Evaluation active.";
            final String targetChange = activeTargetChangeValidation == null ? "No target-change validation active." : "Target-change validation active.";
            final String benchmark = MinecraftMachinesDuopodWalkForwardBenchmark.hasActiveRun()
                    ? "Walk-forward benchmark active."
                    : "No walk-forward benchmark active.";
            final String rewardViz = rewardVisualizationEnabled ? "Reward visualization on." : "Reward visualization off.";
            source.sendSuccess(() -> Component.literal("Duopod CEM inactive. " + best + " " + replay + " " + lookTarget + " " + evaluation + " " + targetChange + " " + benchmark + " " + rewardViz), false);
            return 1;
        }

        final String warmup = run.cleanupPending()
                ? "cleanup=retry_pending "
                : run.spawnWarmupTicksRemaining() > 0
                ? "warmup=%d ".formatted(run.spawnWarmupTicksRemaining())
                : "";
        source.sendSuccess(() -> Component.literal("Duopod CEM run %s gen=%d/%d %sstep=%d/%d slots=%d rewardViz=%s %s exploration: %s"
                .formatted(run.runId(),
                        run.generation() + 1,
                        run.config().maximumGenerations(),
                        warmup,
                        run.finishedControlSteps(),
                        run.config().maximumControlSteps(),
                        run.slotCount(),
                        rewardVisualizationEnabled ? "on" : "off",
                        best,
                        run.lastExplorationSummary())), false);
        return 1;
    }

    public static int toggleRewardVisualization(final CommandSourceStack source) {
        return setRewardVisualization(source, !rewardVisualizationEnabled);
    }

    public static int setRewardVisualization(final CommandSourceStack source, final boolean enabled) {
        rewardVisualizationEnabled = enabled;
        source.sendSuccess(() -> Component.literal("Duopod CEM reward visualization " + (enabled ? "enabled" : "disabled") + "."), true);
        return 1;
    }

    static boolean rewardVisualizationEnabledForGameTest() {
        return rewardVisualizationEnabled;
    }

    public static int replayBest(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        if (MinecraftMachinesTrainingBridge.hasActiveBridge()) {
            source.sendFailure(Component.literal("Stop the dimension-global PPO training bridge before replay."));
            return 0;
        }
        if (bestEver == null) {
            source.sendFailure(Component.literal("No Duopod CEM best genome exists yet."));
            return 0;
        }
        if (LEGACY_PHASE_GAIT_POLICY_TYPE.equals(bestEver.policyType())) {
            source.sendFailure(Component.literal(
                    "Legacy duopod_phase_gait_v1 checkpoints cannot be replayed faithfully by the v2 policy. "
                            + "Resume training to produce a v2 checkpoint or use the immutable historical artifact."));
            return 0;
        }
        if (activeRun != null || activeEvaluation != null || activeTargetChangeValidation != null
                || MinecraftMachinesDuopodWalkForwardBenchmark.hasActiveRun()) {
            source.sendFailure(Component.literal("Stop the active Duopod CEM run/evaluation/target-change validation before replay."));
            return 0;
        }

        clearReplay(player.server);
        final Direction forward = player.getDirection();
        final BlockPos origin = player.blockPosition().relative(forward, 5);
        final ReplayRun replay;
        try {
            replay = ReplayRun.spawn(
                    player.serverLevel(),
                    player.getUUID(),
                    origin,
                    forward,
                    bestEver.genome(),
                    bestEver.curriculumStage(),
                    bestEver.policyType(),
                    bestEver.trainingConfig().controlTicks(),
                    bestEver.trainingConfig().spawnWarmupTicks());
        } catch (final RuntimeException e) {
            source.sendFailure(Component.literal("Could not spawn Duopod replay: " + e.getMessage()));
            return 0;
        }

        replayRun = replay;
        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_REPLAY_BEGIN aggregate={} mean={} generation={} run={}",
                bestEver.aggregateFitness(),
                bestEver.meanScore(),
                bestEver.generation(),
                bestEver.runId());
        if (replay.usesTarget()) {
            source.sendSuccess(() -> Component.literal("Replaying Duopod CEM best genome. Use /mm train duopod cem look_target on, /mm train target <x> <y> <z>, or /mm train duopod cem replay_target <forwardBlocks> <rightBlocks> to redirect it."), true);
        } else {
            source.sendSuccess(() -> Component.literal("Replaying Duopod CEM best balance genome with a neutral targetless episode."), true);
        }
        return 1;
    }

    public static int setReplayTarget(
            final CommandSourceStack source,
            final double forwardBlocks,
            final double rightBlocks
    ) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        final ReplayRun replay = replayRun;
        if (replay == null) {
            source.sendFailure(Component.literal("No Duopod CEM replay is active."));
            return 0;
        }
        final ServerLevel level = player.server.getLevel(replay.dimension());
        if (level == null) {
            source.sendFailure(Component.literal("Replay level is not loaded."));
            return 0;
        }
        if (!replay.usesTarget()) {
            source.sendFailure(Component.literal("The active Duopod replay is a balance policy; replay targets are disabled for this checkpoint."));
            return 0;
        }

        replay.setTargetRelative(forwardBlocks, rightBlocks);
        final Vec3 target = replay.targetPosition();
        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_REPLAY_TARGET target=({},{},{}) forwardBlocks={} rightBlocks={}",
                format(target.x),
                format(target.y),
                format(target.z),
                format(forwardBlocks),
                format(rightBlocks));
        source.sendSuccess(() -> Component.literal("Set Duopod replay target to %.1f forward, %.1f right of its current body."
                .formatted(forwardBlocks, rightBlocks)), true);
        return 1;
    }

    public static int setReplayWorldTarget(
            final CommandSourceStack source,
            final double x,
            final double y,
            final double z
    ) {
        final ReplayRun replay = replayRun;
        if (replay == null) {
            source.sendFailure(Component.literal("No Duopod CEM replay is active."));
            return 0;
        }
        final ServerLevel level = source.getServer().getLevel(replay.dimension());
        if (level == null) {
            source.sendFailure(Component.literal("Replay level is not loaded."));
            return 0;
        }
        if (!replay.usesTarget()) {
            source.sendFailure(Component.literal("The active Duopod replay is a balance policy; replay targets are disabled for this checkpoint."));
            return 0;
        }

        final Vec3 target = new Vec3(x, y, z);
        replay.setTargetWorld(target);
        final DuopodKinematics.LocalOffset localOffset = replay.targetLocalOffset();
        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_REPLAY_TARGET target=({},{},{}) forwardBlocks={} rightBlocks={} mode=world",
                format(target.x),
                format(target.y),
                format(target.z),
                format(localOffset.forward()),
                format(localOffset.right()));
        source.sendSuccess(() -> Component.literal("Set Duopod replay target to world %.2f %.2f %.2f (currently %.1f forward, %.1f right)."
                .formatted(x, y, z, localOffset.forward(), localOffset.right())), true);
        return 1;
    }

    public static int toggleLookTarget(final CommandSourceStack source) {
        if (lookTargetMode != null) {
            return setLookTarget(source, false);
        }
        return setLookTarget(source, true);
    }

    public static int setLookTarget(final CommandSourceStack source, final boolean enabled) {
        if (!enabled) {
            lookTargetMode = null;
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_LOOK_TARGET_DISABLE");
            source.sendSuccess(() -> Component.literal("Duopod look target disabled."), true);
            return 1;
        }
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        final ReplayRun replay = replayRun;
        if (replay == null) {
            source.sendFailure(Component.literal("No Duopod CEM replay is active. Start /mm train duopod cem replay_best first."));
            return 0;
        }
        if (!player.serverLevel().dimension().equals(replay.dimension())) {
            source.sendFailure(Component.literal("Stand in the same dimension as the Duopod replay before enabling look targeting."));
            return 0;
        }
        if (!replay.usesTarget()) {
            source.sendFailure(Component.literal("The active Duopod replay is a balance policy; look targeting is disabled for this checkpoint."));
            return 0;
        }

        final LookTargetMode mode = new LookTargetMode(player.getUUID());
        lookTargetMode = mode;
        updateLookTargetFromPlayer(player, replay, mode);
        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_LOOK_TARGET_ENABLE player={}", player.getUUID());
        source.sendSuccess(() -> Component.literal("Duopod look target enabled. The replay target follows the block under your crosshair; run /mm train duopod cem look_target off to disable it."), true);
        return 1;
    }

    public static int saveCheckpoint(final CommandSourceStack source) {
        return saveCheckpoint(source, checkpointPath(source.getServer()));
    }

    public static int saveCheckpoint(final CommandSourceStack source, final String name) {
        final Path path;
        try {
            path = namedCheckpointPath(source.getServer(), name);
        } catch (final IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        return saveCheckpoint(source, path);
    }

    private static int saveCheckpoint(final CommandSourceStack source, final Path path) {
        final BestGenome checkpoint = bestEver;
        if (checkpoint == null) {
            source.sendFailure(Component.literal("No Duopod CEM best genome exists yet."));
            return 0;
        }
        try {
            writeCheckpointAtomically(path, checkpoint);
        } catch (final RuntimeException | IOException e) {
            source.sendFailure(Component.literal("Failed to save Duopod CEM checkpoint: " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Saved Duopod CEM checkpoint to %s: curriculum %s aggregate %.3f mean %.3f generation %d."
                .formatted(path, checkpoint.curriculumStage(), checkpoint.aggregateFitness(), checkpoint.meanScore(), checkpoint.generation())), true);
        return 1;
    }

    public static int loadCheckpoint(final CommandSourceStack source) {
        return loadCheckpoint(source, checkpointPath(source.getServer()));
    }

    public static int loadCheckpoint(final CommandSourceStack source, final String name) {
        final Path path;
        try {
            path = namedCheckpointPath(source.getServer(), name);
        } catch (final IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        return loadCheckpoint(source, path);
    }

    private static int loadCheckpoint(final CommandSourceStack source, final Path path) {
        if (hasActiveWork() || MinecraftMachinesTrainingBridge.hasActiveBridge()) {
            source.sendFailure(Component.literal(
                    "Stop active Duopod training, evaluation, benchmark, replay, and bridge work before loading a checkpoint."));
            return 0;
        }
        final LoadedCheckpoint loaded;
        try {
            loaded = loadCheckpointWithBackup(path);
        } catch (final RuntimeException | IOException e) {
            source.sendFailure(Component.literal("Failed to load Duopod CEM checkpoint: " + e.getMessage()));
            return 0;
        }
        final BestGenome checkpoint = loaded.checkpoint();
        bestEver = checkpoint;
        source.sendSuccess(() -> Component.literal("Loaded Duopod CEM checkpoint%s: curriculum %s aggregate %.3f mean %.3f generation %d."
                .formatted(
                        loaded.recoveredBackup() ? " from recovery backup " + loaded.sourcePath() : "",
                        checkpoint.curriculumStage(),
                        checkpoint.aggregateFitness(),
                        checkpoint.meanScore(),
                        checkpoint.generation())), true);
        return 1;
    }

    public static int clear(final CommandSourceStack source) {
        final int cleared = clearActive(source.getServer());
        source.sendSuccess(() -> Component.literal("Cleared Duopod CEM trainer bodies."), true);
        return cleared;
    }

    public static int clearActive(final MinecraftServer server) {
        int cleared = stopActive(server, "command_clear");
        if (clearReplay(server)) {
            cleared++;
        }
        return cleared;
    }

    public static boolean hasActiveWork() {
        return activeRun != null || activeEvaluation != null || activeTargetChangeValidation != null || replayRun != null
                || MinecraftMachinesDuopodWalkForwardBenchmark.hasActiveRun();
    }

    public static boolean hasActiveReplay() {
        return replayRun != null;
    }

    public static void tick(final ServerTickEvent.Pre event) {
        final MinecraftServer server = event.getServer();
        final TrainingRun run = activeRun;
        if (run != null && !run.tick(server)) {
            if (run.close()) {
                activeRun = null;
            }
        }

        final EvaluationRun evaluation = activeEvaluation;
        if (evaluation != null && !evaluation.tick(server)) {
            activeEvaluation = null;
        }

        final TargetChangeValidationRun validation = activeTargetChangeValidation;
        if (validation != null && !validation.tick(server)) {
            activeTargetChangeValidation = null;
        }

        MinecraftMachinesDuopodWalkForwardBenchmark.tick(server);

        final ReplayRun replay = replayRun;
        if (replay != null) {
            tickLookTarget(server, replay);
        }
        if (replay != null && !replay.tick(server)) {
            replayRun = null;
            lookTargetMode = null;
        }
    }

    private static boolean clearReplay(final MinecraftServer server) {
        final ReplayRun replay = replayRun;
        lookTargetMode = null;
        if (replay == null) {
            return false;
        }
        final ServerLevel level = server.getLevel(replay.dimension());
        if (level != null) {
            replay.clear();
        }
        replayRun = null;
        return true;
    }

    private static void tickLookTarget(final MinecraftServer server, final ReplayRun replay) {
        final LookTargetMode mode = lookTargetMode;
        if (mode == null) {
            return;
        }
        if (mode.ticksUntilNextUpdate > 0) {
            mode.ticksUntilNextUpdate--;
            return;
        }
        mode.ticksUntilNextUpdate = LOOK_TARGET_UPDATE_INTERVAL_TICKS;

        final ServerPlayer player = server.getPlayerList().getPlayer(mode.playerId);
        if (player == null) {
            lookTargetMode = null;
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_LOOK_TARGET_DISABLE reason=player_offline");
            return;
        }
        if (!player.serverLevel().dimension().equals(replay.dimension())) {
            lookTargetMode = null;
            player.sendSystemMessage(Component.literal("Duopod look target disabled because you left the replay dimension."));
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_LOOK_TARGET_DISABLE reason=dimension_changed player={}", player.getUUID());
            return;
        }
        updateLookTargetFromPlayer(player, replay, mode);
    }

    private static void updateLookTargetFromPlayer(
            final ServerPlayer player,
            final ReplayRun replay,
            final LookTargetMode mode
    ) {
        final Vec3 eye = player.getEyePosition(1.0F);
        final Vec3 end = eye.add(player.getLookAngle().scale(LOOK_TARGET_MAX_DISTANCE_BLOCKS));
        final BlockHitResult hit = player.serverLevel().clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        final BlockPos block = hit.getBlockPos().immutable();
        if (block.equals(mode.lastBlock)) {
            return;
        }
        mode.lastBlock = block;
        replay.setTargetWorld(new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5));
    }

    private static GenomePolicy duopodPolicy(final String curriculumStage) {
        return DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage))
                ? new DuopodPhaseGaitPolicy()
                : new LinearTanhPolicy(DuopodSchemas.observationSpec().size(), DuopodSchemas.actionSpec().size());
    }

    private static GenomePolicy duopodPolicy(final String curriculumStage, final String policyType) {
        final String type = normalizePolicyType(policyType, curriculumStage);
        if (isPhaseGaitPolicyType(type)) {
            return new DuopodPhaseGaitPolicy();
        }
        return new LinearTanhPolicy(DuopodSchemas.observationSpec().size(), DuopodSchemas.actionSpec().size());
    }

    private static String policyType(final GenomePolicy policy) {
        return policy instanceof DuopodPhaseGaitPolicy ? DuopodPhaseGaitPolicy.POLICY_TYPE : LINEAR_TANH_POLICY_TYPE;
    }

    private static String normalizePolicyType(final String policyType, final String curriculumStage) {
        if (policyType != null && !policyType.isBlank()) {
            return policyType;
        }
        return DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage))
                ? DuopodPhaseGaitPolicy.POLICY_TYPE
                : LINEAR_TANH_POLICY_TYPE;
    }

    private static boolean isPhaseGaitPolicyType(final String type) {
        return DuopodPhaseGaitPolicy.POLICY_TYPE.equals(type)
                || LEGACY_PHASE_GAIT_POLICY_TYPE.equals(type);
    }

    private static boolean compatiblePolicyFamily(final String first, final String second) {
        return first.equals(second) || (isPhaseGaitPolicyType(first) && isPhaseGaitPolicyType(second));
    }

    static ContinuousCemDistribution initialDistribution(
            final GenomePolicy policy,
            final long seed,
            final Config config
    ) {
        return initialDistribution(policy, seed, config, false);
    }

    static ContinuousCemDistribution initialDistribution(
            final GenomePolicy policy,
            final long seed,
            final Config config,
            final boolean forceFresh
    ) {
        final String curriculumStage = config.curriculumStage();
        final String type = policyType(policy);
        final boolean balance = DuopodTrainingScenarios.isBalanceCurriculumStage(curriculumStage);
        if (forceFresh || balance) {
            return freshDistribution(policy, seed, balance);
        }
        if (bestEver != null
                && bestEver.genome().length == policy.genomeSize()
                && bestEver.curriculumStage().equals(curriculumStage)
                && compatiblePolicyFamily(bestEver.policyType(), type)) {
            if (!CEM_FITNESS_CONTRACT.equals(bestEver.fitnessContract())) {
                return protocolRestartDistribution(policy, seed, bestEver.genome(), bestEver.distribution());
            }
            if (!bestEver.trainingConfig().fitnessProtocol().isCompatibleWith(config.fitnessProtocol())) {
                return protocolRestartDistribution(policy, seed, bestEver.genome(), bestEver.distribution());
            }
            if (bestEver.distribution() != null) {
                return bestEver.distribution().toDistribution(seed, bestEver.genome(), bestEver.aggregateFitness());
            }
            return seededRestartDistribution(policy, seed, bestEver.genome(), bestEver.aggregateFitness());
        }
        return freshDistribution(policy, seed, false);
    }

    private static boolean usesFreshOptimizer(
            final GenomePolicy policy,
            final Config config,
            final boolean forceFresh
    ) {
        if (forceFresh || DuopodTrainingScenarios.isBalanceCurriculumStage(config.curriculumStage())) {
            return true;
        }
        return bestEver == null
                || bestEver.genome().length != policy.genomeSize()
                || !bestEver.curriculumStage().equals(config.curriculumStage())
                || !compatiblePolicyFamily(bestEver.policyType(), policyType(policy));
    }

    private static ContinuousCemDistribution freshDistribution(final GenomePolicy policy, final long seed) {
        return freshDistribution(policy, seed, false);
    }

    private static ContinuousCemDistribution freshDistribution(final GenomePolicy policy, final long seed, final boolean balance) {
        final double initialStd = policy instanceof DuopodPhaseGaitPolicy ? PHASE_GAIT_INITIAL_STD : CEM_INITIAL_WEIGHT_STD;
        final double minimumStd = policy instanceof DuopodPhaseGaitPolicy
                ? PHASE_GAIT_MINIMUM_STD
                : balance ? CEM_BALANCE_MINIMUM_STD : CEM_MINIMUM_STD;
        return ContinuousCemDistribution.initial(
                policy.genomeSize(),
                initialStd,
                CEM_INITIAL_BIAS_STD,
                policy.observationSize(),
                -3.0,
                3.0,
                minimumStd,
                CEM_MAXIMUM_STD,
                seed);
    }

    static boolean shouldReplaceBestGenome(
            final ScoredGenome candidate,
            final String curriculumStage,
            final String policyType,
            final Config trainingConfig
    ) {
        final BestGenome current = bestEver;
        if (current == null || !current.policyType().equals(policyType)) {
            return true;
        }
        if (!CEM_FITNESS_CONTRACT.equals(current.fitnessContract())) {
            return true;
        }
        if (!current.trainingConfig().fitnessProtocol().isCompatibleWith(trainingConfig.fitnessProtocol())) {
            return true;
        }
        return CurriculumScopedBestSelection.shouldReplace(
                current.curriculumStage(),
                current.aggregateFitness(),
                curriculumStage,
                candidate.aggregateFitness());
    }

    private static ContinuousCemDistribution seededRestartDistribution(
            final GenomePolicy policy,
            final long seed,
            final double[] genome,
            final double aggregateFitness
    ) {
        final double[] mean = Arrays.copyOf(genome, genome.length);
        final double[] std = new double[mean.length];
        final double[] lower = new double[mean.length];
        final double[] upper = new double[mean.length];
        final double[] minStd = new double[mean.length];
        final double[] maxStd = new double[mean.length];
        final boolean phaseGait = policy instanceof DuopodPhaseGaitPolicy;
        Arrays.fill(std, phaseGait ? PHASE_GAIT_PROTOCOL_RESTART_STD : CEM_SEEDED_RESTART_STD);
        Arrays.fill(lower, -3.0);
        Arrays.fill(upper, 3.0);
        Arrays.fill(minStd, phaseGait ? PHASE_GAIT_MINIMUM_STD : CEM_MINIMUM_STD);
        Arrays.fill(maxStd, CEM_MAXIMUM_STD);
        return new ContinuousCemDistribution(mean, std, lower, upper, minStd, maxStd, seed, 0, mean, aggregateFitness);
    }

    private static ContinuousCemDistribution protocolRestartDistribution(
            final GenomePolicy policy,
            final long seed,
            final double[] genome,
            final DistributionSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.standardDeviation().length != genome.length) {
            return seededRestartDistribution(policy, seed, genome, Double.NEGATIVE_INFINITY);
        }
        final double[] standardDeviation = Arrays.copyOf(snapshot.standardDeviation(), genome.length);
        final double[] minimumStandardDeviation = Arrays.copyOf(snapshot.minimumStandardDeviation(), genome.length);
        final double[] maximumStandardDeviation = Arrays.copyOf(snapshot.maximumStandardDeviation(), genome.length);
        final boolean phaseGait = policy instanceof DuopodPhaseGaitPolicy;
        for (int i = 0; i < standardDeviation.length; i++) {
            if (phaseGait) {
                minimumStandardDeviation[i] = Math.min(
                        maximumStandardDeviation[i],
                        Math.max(minimumStandardDeviation[i], PHASE_GAIT_MINIMUM_STD));
            }
            standardDeviation[i] = clamp(
                    phaseGait
                            ? Math.max(
                            standardDeviation[i] * CEM_PROTOCOL_RESTART_STD_MULTIPLIER,
                            PHASE_GAIT_PROTOCOL_RESTART_STD)
                            : standardDeviation[i] * CEM_PROTOCOL_RESTART_STD_MULTIPLIER,
                    minimumStandardDeviation[i],
                    maximumStandardDeviation[i]);
        }
        return new ContinuousCemDistribution(
                genome,
                standardDeviation,
                snapshot.lowerBounds(),
                snapshot.upperBounds(),
                minimumStandardDeviation,
                maximumStandardDeviation,
                seed,
                0,
                genome,
                Double.NEGATIVE_INFINITY);
    }

    static ContinuousCemDistribution protocolRestartDistribution(
            final GenomePolicy policy,
            final long seed,
            final double[] genome,
            final ContinuousCemDistribution previous
    ) {
        return protocolRestartDistribution(
                policy,
                seed,
                genome,
                previous == null ? null : DistributionSnapshot.from(previous));
    }

    private static Path checkpointPath(final MinecraftServer server) {
        return checkpointDirectory(server).resolve("duopod_cem_best.json");
    }

    private static Path namedCheckpointPath(final MinecraftServer server, final String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.endsWith(".json")) {
            normalized = normalized.substring(0, normalized.length() - ".json".length());
        }
        if (normalized.isBlank() || !normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Checkpoint name must use only letters, digits, '_', '-', or '.'");
        }
        return checkpointDirectory(server).resolve("duopod_cem_" + normalized + ".json");
    }

    private static void writeCheckpointAtomically(final Path path, final BestGenome checkpoint) throws IOException {
        final Path parent = path.getParent();
        Files.createDirectories(parent);
        final Path temporary = Files.createTempFile(
                parent,
                "." + path.getFileName() + ".",
                ".tmp");
        try {
            writeForcedUtf8(temporary, GSON.toJson(checkpoint.toJson()));
            // Validate the exact bytes that will be promoted, including schema hashes,
            // policy dimensions, and optimizer distribution invariants.
            readCheckpointFile(temporary);
            preserveValidCheckpointBackup(path);
            replaceAtomically(temporary, path);
            forceDirectoryBestEffort(parent);
            readCheckpointFile(path);
        } finally {
            deleteTemporaryBestEffort(temporary);
        }
    }

    private static void preserveValidCheckpointBackup(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try {
            readCheckpointFile(path);
        } catch (final RuntimeException invalidPrimary) {
            // Never replace an older valid recovery copy with a corrupt primary.
            MinecraftMachines.LOGGER.warn(
                    "MM_DUOPOD_CEM_CHECKPOINT_BACKUP_SKIP path={} reason=invalid_primary failure={}",
                    path,
                    invalidPrimary.getMessage());
            return;
        }

        final Path backup = checkpointBackupPath(path);
        final Path temporaryBackup = Files.createTempFile(
                path.getParent(),
                "." + backup.getFileName() + ".",
                ".tmp");
        try {
            Files.copy(path, temporaryBackup, StandardCopyOption.REPLACE_EXISTING);
            forceFile(temporaryBackup);
            readCheckpointFile(temporaryBackup);
            replaceAtomically(temporaryBackup, backup);
            forceDirectoryBestEffort(path.getParent());
        } finally {
            deleteTemporaryBestEffort(temporaryBackup);
        }
    }

    private static LoadedCheckpoint loadCheckpointWithBackup(final Path path) throws IOException {
        Throwable primaryFailure = null;
        if (Files.exists(path)) {
            try {
                return new LoadedCheckpoint(readCheckpointFile(path), path, false);
            } catch (final RuntimeException | IOException e) {
                primaryFailure = e;
            }
        } else {
            primaryFailure = new IOException("primary checkpoint does not exist");
        }

        final Path backup = checkpointBackupPath(path);
        Throwable backupFailure = null;
        if (Files.exists(backup)) {
            try {
                final BestGenome recovered = readCheckpointFile(backup);
                MinecraftMachines.LOGGER.warn(
                        "MM_DUOPOD_CEM_CHECKPOINT_RECOVER primary={} backup={} primaryFailure={}",
                        path,
                        backup,
                        failureMessage(primaryFailure));
                return new LoadedCheckpoint(recovered, backup, true);
            } catch (final RuntimeException | IOException e) {
                backupFailure = e;
            }
        } else {
            backupFailure = new IOException("recovery backup does not exist");
        }

        final IOException failure = new IOException(
                "No valid Duopod CEM checkpoint at " + path
                        + " or " + backup
                        + " (primary: " + failureMessage(primaryFailure)
                        + "; backup: " + failureMessage(backupFailure) + ")");
        failure.addSuppressed(primaryFailure);
        failure.addSuppressed(backupFailure);
        throw failure;
    }

    private static BestGenome readCheckpointFile(final Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return BestGenome.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    private static Path checkpointBackupPath(final Path path) {
        return path.resolveSibling(path.getFileName() + ".bak");
    }

    private static void writeForcedUtf8(final Path path, final String contents) throws IOException {
        final ByteBuffer bytes = ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    private static void forceFile(final Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void replaceAtomically(final Path source, final Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectoryBestEffort(final Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final RuntimeException | IOException e) {
            MinecraftMachines.LOGGER.warn(
                    "MM_DUOPOD_CEM_CHECKPOINT_DIRECTORY_FORCE_UNAVAILABLE path={} failure={}",
                    directory,
                    e.getMessage());
        }
    }

    private static void deleteTemporaryBestEffort(final Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (final IOException e) {
            MinecraftMachines.LOGGER.warn(
                    "MM_DUOPOD_CEM_CHECKPOINT_TEMP_DELETE_FAILED path={} failure={}",
                    temporary,
                    e.getMessage());
        }
    }

    private static String failureMessage(final Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "unknown failure"
                : failure.getMessage();
    }

    private record LoadedCheckpoint(BestGenome checkpoint, Path sourcePath, boolean recoveredBackup) {
    }

    private static Path checkpointDirectory(final MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("minecraft_machines");
    }

    private static Path evaluationPath(final MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("minecraft_machines").resolve("duopod_cem_evaluation_latest.json");
    }

    private static Path targetChangeValidationPath(final MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("minecraft_machines").resolve("duopod_cem_target_change_latest.json");
    }

    private static JsonObject manifestJson(final DuopodEvaluationManifest manifest) {
        final JsonObject json = new JsonObject();
        json.addProperty("id", manifest.id());
        json.addProperty("version", manifest.version());
        json.addProperty("compatibility_hash", manifest.compatibilityHash());
        final JsonArray scenarios = new JsonArray(manifest.scenarios().size());
        for (final DuopodEvaluationScenario scenario : manifest.scenarios()) {
            final JsonObject scenarioJson = new JsonObject();
            scenarioJson.addProperty("id", scenario.id());
            scenarioJson.addProperty("target_bearing_degrees", scenario.targetBearingDegrees());
            scenarioJson.addProperty("target_distance_blocks", scenario.targetDistanceBlocks());
            scenarioJson.addProperty("initial_yaw_degrees", scenario.initialYawDegrees());
            scenarioJson.addProperty("terrain_stage", scenario.terrainStage());
            scenarios.add(scenarioJson);
        }
        json.add("scenarios", scenarios);
        return json;
    }

    private static JsonObject evaluationContractJson() {
        final JsonObject json = new JsonObject();
        json.addProperty("id", DuopodEvaluationManifests.EVALUATION_CONTRACT_ID);
        json.addProperty("version", DuopodEvaluationManifests.EVALUATION_CONTRACT_VERSION);
        json.addProperty("target_geometry", "world_horizontal_xz");
        json.addProperty("terminal_state", "first_terminal");
        json.addProperty("distance_units", "blocks");
        return json;
    }

    private static JsonObject specJson(
            final String schemaId,
            final int schemaVersion,
            final int size,
            final String compatibilityHash
    ) {
        final JsonObject json = new JsonObject();
        json.addProperty("schemaId", schemaId);
        json.addProperty("schemaVersion", schemaVersion);
        json.addProperty("size", size);
        json.addProperty("compatibilityHash", compatibilityHash);
        return json;
    }

    private static JsonArray doubleArrayJson(final double[] values) {
        final JsonArray array = new JsonArray(values.length);
        for (final double value : values) {
            array.add(value);
        }
        return array;
    }

    private static double[] readDoubleArray(final JsonObject json, final String name) {
        final JsonArray array = json.getAsJsonArray(name);
        final double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsDouble();
        }
        return values;
    }

    private static double[] defaultMaximumStandardDeviation(final double[] lowerBounds, final double[] upperBounds) {
        final double[] maximum = new double[lowerBounds.length];
        for (int i = 0; i < maximum.length; i++) {
            maximum[i] = Math.abs(upperBounds[i] - lowerBounds[i]);
        }
        return maximum;
    }

    private static double absoluteDelta(final double[] current, final double[] previous) {
        double total = 0.0;
        for (int i = 0; i < Math.min(current.length, previous.length); i++) {
            total += Math.abs(current[i] - previous[i]);
        }
        return total;
    }

    private static double finiteNumberOrDefault(final Map<String, Object> info, final String key, final double fallback) {
        final Object value = info.get(key);
        if (value instanceof final Number number) {
            final double result = number.doubleValue();
            return Double.isFinite(result) ? result : fallback;
        }
        return fallback;
    }

    private static boolean shouldRenderRewardVisualization(final int controlStep) {
        return rewardVisualizationEnabled && controlStep > 0 && controlStep % REWARD_VISUALIZATION_CONTROL_STEP_INTERVAL == 0;
    }

    private static void renderRewardVisualization(
            final ServerLevel level,
            final Direction fallbackForward,
            final double reward,
            final Map<String, Object> info
    ) {
        final Vec3 basePosition = infoPosition(info, "base_position");
        if (basePosition == null) {
            return;
        }

        final Vec3 forward = horizontalAxis(info, "body_forward", directionVector(fallbackForward));
        final Vec3 right = horizontalAxis(info, "body_right", directionVector(fallbackForward.getClockWise()));
        final Vec3 anchor = basePosition.add(0.0, 1.35, 0.0);
        final double desiredForward = finiteNumberOrDefault(info, "desired_forward_velocity", 0.0);
        final double desiredLateral = finiteNumberOrDefault(info, "desired_lateral_velocity", 0.0);
        final double desiredYaw = finiteNumberOrDefault(info, "desired_yaw_rate", 0.0);
        final double actualForward = finiteNumberOrDefault(info, "local_forward_velocity", 0.0);
        final double actualLateral = finiteNumberOrDefault(info, "local_lateral_velocity", 0.0);
        final double actualYaw = finiteNumberOrDefault(info, "local_yaw_rate", 0.0);

        final Vec3 targetPosition = infoPosition(info, "target_position");
        if (targetPosition != null) {
            final Vec3 targetAnchor = targetPosition.add(0.0, 1.0, 0.0);
            particleLine(level, REWARD_VIZ_TARGET, targetAnchor.add(0.0, -0.45, 0.0), targetAnchor.add(0.0, 0.45, 0.0), 5);
            particleArrow(level, REWARD_VIZ_GOAL, anchor, targetAnchor, 10);
        } else {
            final Vec3 desiredVector = forward.scale(desiredForward).add(right.scale(desiredLateral));
            if (horizontalLength(desiredVector) > 1.0e-6) {
                final Vec3 commandEnd = anchor.add(clampHorizontalLength(
                        desiredVector.scale(REWARD_VISUALIZATION_COMMAND_SCALE),
                        0.35,
                        2.4));
                particleArrow(level, REWARD_VIZ_GOAL, anchor, commandEnd, 8);
            }
        }

        final Vec3 actualVector = forward.scale(actualForward).add(right.scale(actualLateral));
        if (horizontalLength(actualVector) > 1.0e-6) {
            final double trackingError = Math.abs(actualForward - desiredForward)
                    + Math.abs(actualLateral - desiredLateral)
                    + Math.abs(actualYaw - desiredYaw) * 0.5;
            final ParticleOptions actualParticle = trackingError <= 0.35 ? REWARD_VIZ_ACTUAL_GOOD : REWARD_VIZ_ACTUAL_BAD;
            final Vec3 actualAnchor = anchor.add(0.0, -0.22, 0.0);
            final Vec3 actualEnd = actualAnchor.add(clampHorizontalLength(
                    actualVector.scale(REWARD_VISUALIZATION_VELOCITY_SCALE),
                    0.25,
                    2.0));
            particleArrow(level, actualParticle, actualAnchor, actualEnd, 7);
        }

        final Vec3 rewardBase = anchor.add(right.scale(1.45)).add(0.0, -0.35, 0.0);
        final double rewardHeight = clamp(Math.abs(reward) * 2.4, 0.12, 1.4);
        particleLine(level,
                reward >= 0.0 ? REWARD_VIZ_REWARD : REWARD_VIZ_PENALTY,
                rewardBase,
                rewardBase.add(0.0, reward >= 0.0 ? rewardHeight : -rewardHeight, 0.0),
                5);

        final double bad = -negativeRewardComponentSum(info);
        if (bad > 1.0e-5) {
            final Vec3 badBase = rewardBase.add(right.scale(0.32));
            particleLine(level,
                    REWARD_VIZ_PENALTY,
                    badBase,
                    badBase.add(0.0, -clamp(bad * 3.2, 0.12, 1.3), 0.0),
                    5);
        }
    }

    private static void sendRewardVisualizationMessage(
            final MinecraftServer server,
            final UUID ownerId,
            final ResourceKey<Level> dimension,
            final RewardVisualizationFrame frame
    ) {
        final Component message = frame.message();
        final ServerPlayer owner = owner(server, ownerId);
        if (owner != null) {
            owner.displayClientMessage(message, true);
            return;
        }
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel().dimension().equals(dimension)) {
                player.displayClientMessage(message, true);
            }
        }
    }

    private static Vec3 infoPosition(final Map<String, Object> info, final String prefix) {
        final String xKey = prefix + "_x";
        final String yKey = prefix + "_y";
        final String zKey = prefix + "_z";
        if (!hasFiniteNumber(info, xKey) || !hasFiniteNumber(info, yKey) || !hasFiniteNumber(info, zKey)) {
            return null;
        }
        return new Vec3(
                finiteNumberOrDefault(info, xKey, 0.0),
                finiteNumberOrDefault(info, yKey, 0.0),
                finiteNumberOrDefault(info, zKey, 0.0));
    }

    private static boolean hasFiniteNumber(final Map<String, Object> info, final String key) {
        final Object value = info.get(key);
        return value instanceof final Number number && Double.isFinite(number.doubleValue());
    }

    private static Vec3 horizontalAxis(final Map<String, Object> info, final String prefix, final Vec3 fallback) {
        final Vec3 axis = new Vec3(
                finiteNumberOrDefault(info, prefix + "_x", fallback.x),
                0.0,
                finiteNumberOrDefault(info, prefix + "_z", fallback.z));
        return normalizeHorizontalOr(axis, fallback);
    }

    private static Vec3 directionVector(final Direction direction) {
        return new Vec3(direction.getStepX(), 0.0, direction.getStepZ());
    }

    private static Vec3 normalizeHorizontalOr(final Vec3 vector, final Vec3 fallback) {
        final double length = horizontalLength(vector);
        if (length <= 1.0e-9 || !Double.isFinite(length)) {
            final double fallbackLength = horizontalLength(fallback);
            return fallbackLength <= 1.0e-9 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(fallback.x / fallbackLength, 0.0, fallback.z / fallbackLength);
        }
        return new Vec3(vector.x / length, 0.0, vector.z / length);
    }

    private static Vec3 clampHorizontalLength(final Vec3 vector, final double minimum, final double maximum) {
        final double length = horizontalLength(vector);
        if (length <= 1.0e-9 || !Double.isFinite(length)) {
            return vector;
        }
        final double targetLength = clamp(length, minimum, maximum);
        return new Vec3(vector.x / length * targetLength, vector.y, vector.z / length * targetLength);
    }

    private static double horizontalLength(final Vec3 vector) {
        return Math.sqrt((vector.x * vector.x) + (vector.z * vector.z));
    }

    private static void particleLine(
            final ServerLevel level,
            final ParticleOptions particle,
            final Vec3 start,
            final Vec3 end,
            final int points
    ) {
        final int safePoints = Math.max(1, points);
        for (int i = 0; i <= safePoints; i++) {
            final double t = i / (double) safePoints;
            final Vec3 point = new Vec3(
                    start.x + (end.x - start.x) * t,
                    start.y + (end.y - start.y) * t,
                    start.z + (end.z - start.z) * t);
            level.sendParticles(particle, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static void particleArrow(
            final ServerLevel level,
            final ParticleOptions particle,
            final Vec3 start,
            final Vec3 end,
            final int points
    ) {
        particleLine(level, particle, start, end, points);
        final Vec3 direction = new Vec3(end.x - start.x, 0.0, end.z - start.z);
        final double length = horizontalLength(direction);
        if (length <= 1.0e-9 || !Double.isFinite(length)) {
            return;
        }
        final Vec3 unit = new Vec3(direction.x / length, 0.0, direction.z / length);
        final Vec3 side = new Vec3(-unit.z, 0.0, unit.x);
        final Vec3 back = unit.scale(-0.35);
        particleLine(level, particle, end, end.add(back).add(side.scale(0.18)), 3);
        particleLine(level, particle, end, end.add(back).add(side.scale(-0.18)), 3);
    }

    private static double negativeRewardComponentSum(final Map<String, Object> info) {
        double total = 0.0;
        final Object components = info.get("reward_components");
        if (components instanceof final Map<?, ?> map) {
            for (final Object value : map.values()) {
                if (value instanceof final Number number) {
                    final double component = number.doubleValue();
                    if (Double.isFinite(component) && component < 0.0) {
                        total += component;
                    }
                }
            }
        }
        return total;
    }

    private static double positiveRewardComponentSum(final Map<String, Object> info) {
        double total = 0.0;
        final Object components = info.get("reward_components");
        if (components instanceof final Map<?, ?> map) {
            for (final Object value : map.values()) {
                if (value instanceof final Number number) {
                    final double component = number.doubleValue();
                    if (Double.isFinite(component) && component > 0.0) {
                        total += component;
                    }
                }
            }
        }
        return total;
    }

    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static double square(final double value) {
        return value * value;
    }

    private static double[] rampInitialAction(final double[] action, final int controlStep) {
        final double scale = initialActionScale(controlStep);
        if (scale >= 1.0) {
            return action;
        }
        final double[] scaled = Arrays.copyOf(action, action.length);
        for (int i = 0; i < scaled.length; i++) {
            scaled[i] *= scale;
        }
        return scaled;
    }

    private static double[] curriculumAction(final String curriculumStage, final double[] action, final double[] previousAction) {
        return action;
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double initialActionScale(final int controlStep) {
        if (INITIAL_ACTION_RAMP_CONTROL_STEPS <= 0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, (controlStep + 1) / (double) INITIAL_ACTION_RAMP_CONTROL_STEPS));
    }

    static double replayPhaseAdvanceRadians(final int controlTicks, final double gaitFrequencyHz) {
        if (controlTicks < 1 || !Double.isFinite(gaitFrequencyHz) || gaitFrequencyHz <= 0.0) {
            throw new IllegalArgumentException("replay cadence requires positive controlTicks and gaitFrequencyHz");
        }
        return Math.PI * 2.0 * gaitFrequencyHz * controlTicks / 20.0;
    }

    static int replayControlHoldCountdown(final int controlTicks) {
        if (controlTicks < 1) {
            throw new IllegalArgumentException("replay controlTicks must be positive");
        }
        // The action-application tick is the first held physics tick. Waiting one less
        // subsequent pre-tick therefore reproduces TrainingRun's exact N-tick cadence.
        return controlTicks - 1;
    }

    public record Config(
            int populationSize,
            int eliteCount,
            int maximumGenerations,
            int episodeTicks,
            int controlTicks,
            int episodesPerCandidate,
            int spacingBlocks,
            int maxConcurrentSlots,
            String curriculumStage,
            double pointGoalNearDistanceBlocks,
            double pointGoalFarDistanceBlocks,
            int spawnWarmupTicks
    ) {
        public static final int DEFAULT_MAX_CONCURRENT_SLOTS = 32;
        public static final int DEFAULT_SPAWN_WARMUP_TICKS = 20;
        public static final int DEFAULT_ELITE_COUNT = 8;
        public static final int MINIMUM_SAFE_SPACING_BLOCKS = 12;
        public static final Config DEFAULT = new Config(
                64,
                DEFAULT_ELITE_COUNT,
                50,
                160,
                4,
                5,
                MINIMUM_SAFE_SPACING_BLOCKS,
                DEFAULT_MAX_CONCURRENT_SLOTS,
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE,
                DuopodTrainingScenarios.DEFAULT_POINT_GOAL_NEAR_DISTANCE_BLOCKS,
                DuopodTrainingScenarios.DEFAULT_POINT_GOAL_FAR_DISTANCE_BLOCKS,
                DEFAULT_SPAWN_WARMUP_TICKS);

        public Config(
                final int populationSize,
                final int eliteCount,
                final int maximumGenerations,
                final int episodeTicks,
                final int controlTicks,
                final int episodesPerCandidate,
                final int spacingBlocks,
                final int maxConcurrentSlots,
                final String curriculumStage
        ) {
            this(
                    populationSize,
                    eliteCount,
                    maximumGenerations,
                    episodeTicks,
                    controlTicks,
                    episodesPerCandidate,
                    spacingBlocks,
                    maxConcurrentSlots,
                    curriculumStage,
                    DuopodTrainingScenarios.DEFAULT_POINT_GOAL_NEAR_DISTANCE_BLOCKS,
                    DuopodTrainingScenarios.DEFAULT_POINT_GOAL_FAR_DISTANCE_BLOCKS,
                    DEFAULT_SPAWN_WARMUP_TICKS);
        }

        public Config(
                final int populationSize,
                final int eliteCount,
                final int maximumGenerations,
                final int episodeTicks,
                final int controlTicks,
                final int episodesPerCandidate,
                final int spacingBlocks,
                final String curriculumStage
        ) {
            this(
                    populationSize,
                    eliteCount,
                    maximumGenerations,
                    episodeTicks,
                    controlTicks,
                    episodesPerCandidate,
                    spacingBlocks,
                    DEFAULT_MAX_CONCURRENT_SLOTS,
                    curriculumStage,
                    DuopodTrainingScenarios.DEFAULT_POINT_GOAL_NEAR_DISTANCE_BLOCKS,
                    DuopodTrainingScenarios.DEFAULT_POINT_GOAL_FAR_DISTANCE_BLOCKS,
                    DEFAULT_SPAWN_WARMUP_TICKS);
        }

        public Config {
            if (spacingBlocks == 0) {
                spacingBlocks = MINIMUM_SAFE_SPACING_BLOCKS;
            }
            curriculumStage = curriculumStage == null || curriculumStage.isBlank()
                    ? DuopodTrainingScenarios.defaultTrainingCurriculumStage()
                    : DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage);
            if (populationSize < 1) {
                throw new IllegalArgumentException("populationSize must be positive");
            }
            if (eliteCount < 1 || eliteCount > populationSize) {
                throw new IllegalArgumentException("eliteCount must be in 1..populationSize");
            }
            if (maximumGenerations < 1) {
                throw new IllegalArgumentException("maximumGenerations must be positive");
            }
            if (episodeTicks < 1 || controlTicks < 1) {
                throw new IllegalArgumentException("episodeTicks and controlTicks must be positive");
            }
            if (episodesPerCandidate < 1) {
                throw new IllegalArgumentException("episodesPerCandidate must be positive");
            }
            if (spacingBlocks < MINIMUM_SAFE_SPACING_BLOCKS) {
                throw new IllegalArgumentException(
                        "spacingBlocks must be 0 for the safe default or at least " + MINIMUM_SAFE_SPACING_BLOCKS);
            }
            if (maxConcurrentSlots < episodesPerCandidate) {
                throw new IllegalArgumentException("maxConcurrentSlots must be at least episodesPerCandidate");
            }
            if (!DuopodTrainingScenarios.isTrainingCurriculumStage(curriculumStage)) {
                throw new IllegalArgumentException("unsupported Duopod CEM curriculum stage " + curriculumStage);
            }
            if (DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(curriculumStage)
                    && episodesPerCandidate != MinecraftMachinesDuopodFlatArena.LANES_PER_CANDIDATE_GROUP) {
                throw new IllegalArgumentException(
                        "walk_forward requires exactly "
                                + MinecraftMachinesDuopodFlatArena.LANES_PER_CANDIDATE_GROUP
                                + " speed lanes per candidate");
            }
            if (!Double.isFinite(pointGoalNearDistanceBlocks) || !Double.isFinite(pointGoalFarDistanceBlocks)
                    || pointGoalNearDistanceBlocks <= 0.0 || pointGoalFarDistanceBlocks <= 0.0) {
                throw new IllegalArgumentException("point-goal target distances must be finite and positive");
            }
            if (pointGoalFarDistanceBlocks < pointGoalNearDistanceBlocks) {
                throw new IllegalArgumentException("point-goal far target distance must be greater than or equal to near distance");
            }
            if (spawnWarmupTicks < 0) {
                throw new IllegalArgumentException("spawnWarmupTicks must be non-negative");
            }
        }

        Config withPopulationSize(final int value) {
            // About one quarter of the population contributes to the elite covariance,
            // capped at the benchmark default. A one-elite population has zero sample
            // variance, so every nontrivial run retains at least two elites.
            final int scaledEliteCount = value == 1
                    ? 1
                    : Math.min(value, Math.min(DEFAULT_ELITE_COUNT, Math.max(2, (value + 3) / 4)));
            return new Config(value, scaledEliteCount, this.maximumGenerations, this.episodeTicks, this.controlTicks, this.episodesPerCandidate, this.spacingBlocks, this.maxConcurrentSlots, this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withMaximumGenerations(final int value) {
            return new Config(this.populationSize, this.eliteCount, value, this.episodeTicks, this.controlTicks, this.episodesPerCandidate, this.spacingBlocks, this.maxConcurrentSlots, this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withEpisodeTicks(final int value) {
            return new Config(this.populationSize, this.eliteCount, this.maximumGenerations, value, this.controlTicks, this.episodesPerCandidate, this.spacingBlocks, this.maxConcurrentSlots, this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withControlTicks(final int value) {
            return new Config(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodeTicks, value, this.episodesPerCandidate, this.spacingBlocks, this.maxConcurrentSlots, this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withEpisodesPerCandidate(final int value) {
            return new Config(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodeTicks, this.controlTicks, value, this.spacingBlocks, Math.max(this.maxConcurrentSlots, value), this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withSpacingBlocks(final int value) {
            return new Config(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodeTicks, this.controlTicks, this.episodesPerCandidate, value, this.maxConcurrentSlots, this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withMaxConcurrentSlots(final int value) {
            return new Config(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodeTicks, this.controlTicks, this.episodesPerCandidate, this.spacingBlocks, Math.max(value, this.episodesPerCandidate), this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withCurriculumStage(final String value) {
            final String stage = DuopodTrainingScenarios.normalizeCurriculumStage(value);
            final int defaultScenarios = defaultScenarioCount(stage);
            final int scenarios = defaultScenarios > 0 && this.episodesPerCandidate == DEFAULT.episodesPerCandidate()
                    ? defaultScenarios
                    : this.episodesPerCandidate;
            return new Config(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodeTicks, this.controlTicks, scenarios, this.spacingBlocks, Math.max(this.maxConcurrentSlots, scenarios), value, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withPointGoalTargetDistances(final double nearDistanceBlocks, final double farDistanceBlocks) {
            return new Config(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodeTicks, this.controlTicks, this.episodesPerCandidate, this.spacingBlocks, this.maxConcurrentSlots, this.curriculumStage, nearDistanceBlocks, farDistanceBlocks, this.spawnWarmupTicks);
        }

        Config withSpawnWarmupTicks(final int value) {
            return new Config(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodeTicks, this.controlTicks, this.episodesPerCandidate, this.spacingBlocks, this.maxConcurrentSlots, this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, value);
        }

        int maximumControlSteps() {
            return Math.max(1, (this.episodeTicks + this.controlTicks - 1) / this.controlTicks);
        }

        CemFitnessProtocol fitnessProtocol() {
            return new CemFitnessProtocol(
                    this.episodeTicks,
                    this.controlTicks,
                    this.episodesPerCandidate,
                    this.spawnWarmupTicks,
                    this.pointGoalNearDistanceBlocks,
                    this.pointGoalFarDistanceBlocks,
                    this.spacingBlocks,
                    DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(this.curriculumStage)
                            ? CEM_FITNESS_CONTRACT + "|" + MinecraftMachinesDuopodFlatArena.ARENA_ID
                                    + "|" + MinecraftMachinesDuopodFlatArena.SLOT_LAYOUT_ID
                            : CEM_FITNESS_CONTRACT + "|" + this.curriculumStage);
        }

        int slotCount() {
            return this.candidatesPerBatch() * this.episodesPerCandidate;
        }

        int totalScenarioCount() {
            return this.populationSize * this.episodesPerCandidate;
        }

        int candidatesPerBatch() {
            return Math.max(1, Math.min(this.populationSize, this.maxConcurrentSlots / this.episodesPerCandidate));
        }

        CemSettings settings() {
            return new CemSettings(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodesPerCandidate, 0.35, 0.65);
        }

        String compactDescription() {
            return "pop=%d elite=%d generations=%d episodeTicks=%d controlTicks=%d scenarios=%d maxSlots=%d activeSlots=%d spacing=%d curriculum=%s pointGoalTargets=%.1f/%.1f spawnWarmupTicks=%d"
                    .formatted(this.populationSize, this.eliteCount, this.maximumGenerations, this.episodeTicks, this.controlTicks, this.episodesPerCandidate, this.maxConcurrentSlots, this.slotCount(), this.spacingBlocks, this.curriculumStage, this.pointGoalNearDistanceBlocks, this.pointGoalFarDistanceBlocks, this.spawnWarmupTicks);
        }

        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("populationSize", this.populationSize);
            json.addProperty("eliteCount", this.eliteCount);
            json.addProperty("maximumGenerations", this.maximumGenerations);
            json.addProperty("episodeTicks", this.episodeTicks);
            json.addProperty("controlTicks", this.controlTicks);
            json.addProperty("episodesPerCandidate", this.episodesPerCandidate);
            json.addProperty("spacingBlocks", this.spacingBlocks);
            json.addProperty("maxConcurrentSlots", this.maxConcurrentSlots);
            json.addProperty("activeSlotCount", this.slotCount());
            json.addProperty("totalScenarioCount", this.totalScenarioCount());
            json.addProperty("curriculumStage", this.curriculumStage);
            json.addProperty("pointGoalNearDistanceBlocks", this.pointGoalNearDistanceBlocks);
            json.addProperty("pointGoalFarDistanceBlocks", this.pointGoalFarDistanceBlocks);
            json.addProperty("spawnWarmupTicks", this.spawnWarmupTicks);
            json.addProperty("maximumControlSteps", this.maximumControlSteps());
            json.addProperty("chunkActivationTimeoutTicks", TRAINING_CHUNK_ACTIVATION_TIMEOUT_TICKS);
            final CemSettings cemSettings = this.settings();
            final boolean phaseGait = DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(this.curriculumStage);
            final boolean balance = DuopodTrainingScenarios.isBalanceCurriculumStage(this.curriculumStage);
            final double initialParameterStd = phaseGait ? PHASE_GAIT_INITIAL_STD : CEM_INITIAL_WEIGHT_STD;
            final double minimumParameterStd = phaseGait
                    ? PHASE_GAIT_MINIMUM_STD
                    : balance ? CEM_BALANCE_MINIMUM_STD : CEM_MINIMUM_STD;
            final JsonObject cem = new JsonObject();
            cem.addProperty("populationSize", cemSettings.populationSize());
            cem.addProperty("eliteCount", cemSettings.eliteCount());
            cem.addProperty("episodesPerCandidate", cemSettings.episodesPerCandidate());
            cem.addProperty("smoothingOld", cemSettings.smoothingOld());
            cem.addProperty("smoothingElite", cemSettings.smoothingElite());
            cem.addProperty("parameterization", phaseGait ? "phase_gait_genome" : "linear_tanh_weights_and_biases");
            cem.addProperty("initialParameterStd", initialParameterStd);
            cem.addProperty("initialParameterStdScope", phaseGait
                    ? "all_phase_gait_parameters"
                    : "weight_parameters_only_bias_uses_initialBiasStd");
            cem.addProperty("minimumParameterStd", minimumParameterStd);
            cem.addProperty("minimumParameterStdScope", "all_parameters");
            cem.addProperty("initialWeightStd", initialParameterStd);
            cem.addProperty("initialBiasStd", phaseGait ? initialParameterStd : CEM_INITIAL_BIAS_STD);
            cem.addProperty("biasParametersPresent", !phaseGait);
            cem.addProperty("legacyWeightAndBiasFieldsApply", !phaseGait);
            cem.addProperty("minimumStd", minimumParameterStd);
            cem.addProperty("maximumStd", CEM_MAXIMUM_STD);
            cem.addProperty("stagnationWindow", CEM_STAGNATION_WINDOW);
            cem.addProperty("stagnationMinimumImprovement", CEM_STAGNATION_MINIMUM_IMPROVEMENT);
            cem.addProperty("minimumActionDiversity", CEM_MINIMUM_ACTION_DIVERSITY);
            cem.addProperty("explorationExpansionMultiplier", CEM_EXPLORATION_EXPANSION_MULTIPLIER);
            cem.addProperty("maximumSaturationForExpansion", CEM_MAXIMUM_SATURATION_FOR_EXPANSION);
            json.add("cemSettings", cem);
            if (DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(this.curriculumStage)) {
                json.addProperty("trainingArena", MinecraftMachinesDuopodFlatArena.ARENA_ID);
                json.addProperty("trainingSlotLayout", MinecraftMachinesDuopodFlatArena.SLOT_LAYOUT_ID);
                json.addProperty("scenarioSeedSchedule", "generation_and_speed_index_common_across_candidates_v1");
                json.addProperty("commonRandomNumbersAcrossCandidates", true);
                final JsonObject terminalFitness = new JsonObject();
                terminalFitness.addProperty("fitnessContract", CEM_FITNESS_CONTRACT);
                terminalFitness.addProperty("forwardFrame", "spawn_horizontal");
                terminalFitness.addProperty("displacementReference", "post_spawn_warmup");
                terminalFitness.addProperty("verticalExcursionReference", "post_spawn_warmup_base_height");
                terminalFitness.addProperty("arenaEscapeFrame", "raw_spawn_horizontal");
                terminalFitness.addProperty("arenaEscapeRule", "raw_forward_outside_prepared_arena_or_absolute_raw_lateral_above_lane_half_width");
                terminalFitness.addProperty("arenaLaneHalfWidthBlocks", MinecraftMachinesDuopodFlatArena.LANE_HALF_WIDTH_BLOCKS);
                terminalFitness.addProperty("requiredMinimumBodyUp", DuopodWalkForwardFitness.REQUIRED_MINIMUM_BODY_UP);
                terminalFitness.addProperty("maximumPeakVerticalExcursionBlocks", DuopodWalkForwardFitness.MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS);
                terminalFitness.addProperty("successRule", "no_machine_failure_and_no_arena_escape_and_minimum_body_up_at_least_threshold_and_peak_vertical_excursion_at_most_threshold_and_post_warmup_terminal_forward_positive");
                terminalFitness.addProperty("unstablePositiveForwardCredit", 0.0);
                terminalFitness.addProperty("terminalForwardWeight", DuopodWalkForwardFitness.TERMINAL_FORWARD_WEIGHT);
                terminalFitness.addProperty("terminalSuccessBonus", DuopodWalkForwardFitness.TERMINAL_SUCCESS_BONUS);
                terminalFitness.addProperty("terminalInstabilityPenalty", DuopodWalkForwardFitness.TERMINAL_INSTABILITY_PENALTY);
                terminalFitness.addProperty("terminalUprightShortfallWeight", DuopodWalkForwardFitness.TERMINAL_UPRIGHT_SHORTFALL_WEIGHT);
                terminalFitness.addProperty("terminalVerticalExcessWeight", DuopodWalkForwardFitness.TERMINAL_VERTICAL_EXCESS_WEIGHT);
                terminalFitness.addProperty("terminalArenaEscapePenalty", DuopodWalkForwardFitness.TERMINAL_ARENA_ESCAPE_PENALTY);
                terminalFitness.addProperty("worstSpeedWeight", ScoredGenome.WORST_SCORE_WEIGHT);
                json.add("walkForwardTerminalFitness", terminalFitness);
            } else {
                json.addProperty("trainingArena", "not_applicable");
                json.addProperty("commonRandomNumbersAcrossCandidates", false);
            }
            return json;
        }

        static Config fromJsonOrDefault(final JsonObject json) {
            if (json == null) {
                return DEFAULT;
            }
            return new Config(
                    json.has("populationSize") ? json.get("populationSize").getAsInt() : DEFAULT.populationSize(),
                    json.has("eliteCount") ? json.get("eliteCount").getAsInt() : DEFAULT.eliteCount(),
                    json.has("maximumGenerations") ? json.get("maximumGenerations").getAsInt() : DEFAULT.maximumGenerations(),
                    json.has("episodeTicks") ? json.get("episodeTicks").getAsInt() : DEFAULT.episodeTicks(),
                    json.has("controlTicks") ? json.get("controlTicks").getAsInt() : DEFAULT.controlTicks(),
                    json.has("episodesPerCandidate") ? json.get("episodesPerCandidate").getAsInt() : DEFAULT.episodesPerCandidate(),
                    json.has("spacingBlocks") ? json.get("spacingBlocks").getAsInt() : DEFAULT.spacingBlocks(),
                    json.has("maxConcurrentSlots") ? json.get("maxConcurrentSlots").getAsInt() : DEFAULT.maxConcurrentSlots(),
                    json.has("curriculumStage") ? json.get("curriculumStage").getAsString() : DEFAULT.curriculumStage(),
                    json.has("pointGoalNearDistanceBlocks") ? json.get("pointGoalNearDistanceBlocks").getAsDouble() : DEFAULT.pointGoalNearDistanceBlocks(),
                    json.has("pointGoalFarDistanceBlocks") ? json.get("pointGoalFarDistanceBlocks").getAsDouble() : DEFAULT.pointGoalFarDistanceBlocks(),
                    json.has("spawnWarmupTicks") ? json.get("spawnWarmupTicks").getAsInt() : DEFAULT.spawnWarmupTicks());
        }

        private static int defaultScenarioCount(final String curriculumStage) {
            return switch (curriculumStage) {
                case DuopodTrainingScenarios.BALANCE_STAND_STAGE, DuopodTrainingScenarios.BALANCE_CENTER_OF_MASS_STAGE -> DuopodTrainingScenarios.BALANCE_STAND_SCENARIO_COUNT;
                case DuopodTrainingScenarios.WALK_FORWARD_STAGE -> DuopodTrainingScenarios.WALK_FORWARD_SCENARIO_COUNT;
                case DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE -> DuopodTrainingScenarios.FLAT_COMMAND_SCENARIO_COUNT;
                default -> 0;
            };
        }
    }

    static final class TrainingRun {
        private final UUID runId;
        private final UUID ownerId;
        private final ResourceKey<Level> dimension;
        private final BlockPos origin;
        private final Direction forwardDirection;
        private final UUID batchId;
        private final Config config;
        private final GenomePolicy policy;
        private final RandomGenerator random;
        private final long runSeed;
        private final boolean freshOptimizer;
        private ContinuousCemDistribution distribution;
        private MinecraftMachinesTrainingChunkLease chunkLease;
        private MinecraftMachinesDuopodFlatArena flatArena;
        private LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod> environment;
        private double[][] observations = new double[0][];
        private double[][] previousActions = new double[0][];
        private List<double[]> genomes = List.of();
        private double[] slotReturns = new double[0];
        private int[] slotSuccesses = new int[0];
        private boolean[] slotFailures = new boolean[0];
        private boolean[] slotCompleted = new boolean[0];
        private double[] candidateReturnSums = new double[0];
        private double[] candidateWorstReturns = new double[0];
        private int[] candidateSuccesses = new int[0];
        private int[] candidateFailures = new int[0];
        private double[][] candidateActionSums = new double[0][];
        private int[] candidateActionCounts = new int[0];
        private int[] slotEndLengths = new int[0];
        /** Latest raw displacement from the original morphology spawn frame. */
        private double[] slotTerminalForwardDisplacements = new double[0];
        private double[] slotTerminalLateralDisplacements = new double[0];
        private double[] slotWarmupForwardDisplacements = new double[0];
        private double[] slotWarmupLateralDisplacements = new double[0];
        private double[] slotWarmupBaseYs = new double[0];
        private double[] slotPeakVerticalExcursions = new double[0];
        private double[] slotMinimumBodyUps = new double[0];
        private boolean[] slotArenaEscapes = new boolean[0];
        private double[] slotTerminalFitnessAdjustments = new double[0];
        private boolean[] slotWalkForwardFitnessApplied = new boolean[0];
        private final List<double[]> generationActionSamples = new ArrayList<>();
        private final List<Integer> generationSurvivalLengths = new ArrayList<>();
        private final List<Double> recentBestFitness = new ArrayList<>();
        private final Map<String, Double> generationRewardComponentSums = new java.util.LinkedHashMap<>();
        private int generationRewardComponentSamples;
        private int generationObservationRepairCount;
        private int generationSuccessCount;
        private int generationFallCount;
        private double generationRecoveryProgressSum;
        private int generationRecoveryProgressSamples;
        private int generationPositiveRecoveryProgressSteps;
        private double maxForwardTilt;
        private double maxBackwardTilt;
        private double maxLeftRoll;
        private double maxRightRoll;
        private int lastExplorationExpansionGeneration = -CEM_STAGNATION_WINDOW;
        private String lastExplorationSummary = "no exploration diagnostics yet";
        private int currentBatchStartCandidate;
        private int currentBatchCandidateCount;
        private int currentBatchSlotCount;
        private int generation;
        private int finishedControlSteps;
        private int spawnWarmupTicksRemaining;
        private int ticksUntilFinish;
        private int chunkActivationWaitTicks;
        private boolean batchSpawnPending;
        private boolean stepPending;
        private boolean cleanupPending;
        private String lastFailure = "unknown failure";

        TrainingRun(
                final UUID runId,
                final UUID ownerId,
                final ResourceKey<Level> dimension,
                final BlockPos origin,
                final Direction forwardDirection,
                final UUID batchId,
                final Config config,
                final GenomePolicy policy,
                final ContinuousCemDistribution distribution,
                final RandomGenerator random,
                final long runSeed
        ) {
            this(
                    runId,
                    ownerId,
                    dimension,
                    origin,
                    forwardDirection,
                    batchId,
                    config,
                    policy,
                    distribution,
                    random,
                    runSeed,
                    false);
        }

        TrainingRun(
                final UUID runId,
                final UUID ownerId,
                final ResourceKey<Level> dimension,
                final BlockPos origin,
                final Direction forwardDirection,
                final UUID batchId,
                final Config config,
                final GenomePolicy policy,
                final ContinuousCemDistribution distribution,
                final RandomGenerator random,
                final long runSeed,
                final boolean freshOptimizer
        ) {
            this.runId = runId;
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.origin = origin.immutable();
            this.forwardDirection = forwardDirection;
            this.batchId = batchId;
            this.config = config;
            this.policy = policy;
            this.distribution = distribution;
            this.random = random;
            this.runSeed = runSeed;
            this.freshOptimizer = freshOptimizer;
            // A protocol-compatible checkpoint restores the optimizer distribution exactly.
            // Keep scenario scheduling, progress reporting, and the absolute generation limit
            // on that same timeline instead of silently replaying generation-zero scenarios.
            this.generation = distribution.generation();
        }

        boolean startGeneration(final ServerLevel level) {
            if (!this.closeBatchEnvironment()) {
                return false;
            }
            this.genomes = this.sampleGenerationGenomes();
            this.candidateReturnSums = new double[this.config.populationSize()];
            this.candidateWorstReturns = new double[this.config.populationSize()];
            Arrays.fill(this.candidateWorstReturns, Double.POSITIVE_INFINITY);
            this.candidateSuccesses = new int[this.config.populationSize()];
            this.candidateFailures = new int[this.config.populationSize()];
            this.candidateActionSums = new double[this.config.populationSize()][this.policy.actionSize()];
            this.candidateActionCounts = new int[this.config.populationSize()];
            this.generationActionSamples.clear();
            this.generationSurvivalLengths.clear();
            this.generationRewardComponentSums.clear();
            this.generationRewardComponentSamples = 0;
            this.generationObservationRepairCount = 0;
            this.generationSuccessCount = 0;
            this.generationFallCount = 0;
            this.generationRecoveryProgressSum = 0.0;
            this.generationRecoveryProgressSamples = 0;
            this.generationPositiveRecoveryProgressSteps = 0;
            this.maxForwardTilt = 0.0;
            this.maxBackwardTilt = 0.0;
            this.maxLeftRoll = 0.0;
            this.maxRightRoll = 0.0;

            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_GENERATION_START run={} generation={} population={} activeSlots={} totalScenarios={} distributionGeneration={}",
                    this.runId,
                    this.generation + 1,
                    this.config.populationSize(),
                    this.config.slotCount(),
                    this.config.totalScenarioCount(),
                    this.distribution.generation());
            return this.startBatch(level, 0);
        }

        private List<double[]> sampleGenerationGenomes() {
            final List<double[]> sampled = new ArrayList<>(this.distribution.sampleAntitheticWithMeanAnchor(
                    this.config.populationSize(),
                    this.random));
            if (shouldInjectStructuredGaitSeeds(
                    this.config.curriculumStage(),
                    this.generation,
                    this.freshOptimizer)) {
                final List<double[]> probes = DuopodPhaseGaitSeeds.stableBasinProbes();
                final int injected = Math.min(probes.size(), Math.max(0, sampled.size() - 1));
                for (int i = 0; i < injected; i++) {
                    sampled.set(i + 1, probes.get(i));
                }
            }
            if (DuopodTrainingScenarios.isBalanceCurriculumStage(this.config.curriculumStage()) && !sampled.isEmpty()) {
                sampled.set(0, new double[this.policy.genomeSize()]);
            }
            return sampled;
        }

        static boolean shouldInjectStructuredGaitSeeds(
                final String curriculumStage,
                final int generation,
                final boolean freshOptimizer
        ) {
            return freshOptimizer
                    && generation == 0
                    && DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(
                    DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage));
        }

        private boolean startBatch(final ServerLevel level, final int startCandidate) {
            if (!this.closeBatchEnvironment()) {
                return false;
            }
            this.currentBatchStartCandidate = startCandidate;
            this.currentBatchCandidateCount = Math.min(this.config.candidatesPerBatch(), this.config.populationSize() - startCandidate);
            this.currentBatchSlotCount = this.currentBatchCandidateCount * this.config.episodesPerCandidate();
            this.slotReturns = new double[this.currentBatchSlotCount];
            this.slotSuccesses = new int[this.currentBatchSlotCount];
            this.slotFailures = new boolean[this.currentBatchSlotCount];
            this.slotCompleted = new boolean[this.currentBatchSlotCount];
            this.slotEndLengths = new int[this.currentBatchSlotCount];
            this.slotTerminalForwardDisplacements = new double[this.currentBatchSlotCount];
            this.slotTerminalLateralDisplacements = new double[this.currentBatchSlotCount];
            this.slotWarmupForwardDisplacements = new double[this.currentBatchSlotCount];
            this.slotWarmupLateralDisplacements = new double[this.currentBatchSlotCount];
            this.slotWarmupBaseYs = new double[this.currentBatchSlotCount];
            this.slotPeakVerticalExcursions = new double[this.currentBatchSlotCount];
            this.slotMinimumBodyUps = new double[this.currentBatchSlotCount];
            this.slotArenaEscapes = new boolean[this.currentBatchSlotCount];
            this.slotTerminalFitnessAdjustments = new double[this.currentBatchSlotCount];
            this.slotWalkForwardFitnessApplied = new boolean[this.currentBatchSlotCount];
            Arrays.fill(this.slotTerminalForwardDisplacements, Double.NaN);
            Arrays.fill(this.slotTerminalLateralDisplacements, Double.NaN);
            Arrays.fill(this.slotWarmupForwardDisplacements, Double.NaN);
            Arrays.fill(this.slotWarmupLateralDisplacements, Double.NaN);
            Arrays.fill(this.slotWarmupBaseYs, Double.NaN);
            Arrays.fill(this.slotMinimumBodyUps, Double.POSITIVE_INFINITY);
            // A slot becomes eligible only after a complete post-warmup baseline is captured.
            Arrays.fill(this.slotArenaEscapes, true);
            this.previousActions = new double[this.currentBatchSlotCount][this.policy.actionSize()];
            this.finishedControlSteps = 0;
            this.spawnWarmupTicksRemaining = this.config.spawnWarmupTicks();
            this.ticksUntilFinish = 0;
            this.chunkActivationWaitTicks = 0;
            this.stepPending = false;
            if (this.chunkLease == null) {
                try {
                    this.chunkLease = MinecraftMachinesTrainingChunkLease.acquire(
                            level,
                            this.origin,
                            this.forwardDirection,
                            this.config.slotCount(),
                            this.config.spacingBlocks(),
                            this.config.maximumControlSteps(),
                            this.config.controlTicks(),
                            this.isWalkForwardCurriculum());
                } catch (final RuntimeException e) {
                    this.lastFailure = "Could not load the Duopod training corridor: " + e.getMessage();
                    this.close(level);
                    return false;
                }
            }
            this.batchSpawnPending = true;
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_BATCH_SCHEDULED run={} generation={} firstCandidate={} candidates={} slots={}",
                    this.runId,
                    this.generation + 1,
                    this.currentBatchStartCandidate,
                    this.currentBatchCandidateCount,
                    this.currentBatchSlotCount);
            return true;
        }

        private boolean spawnScheduledBatch(final ServerLevel level) {
            try {
                if (this.flatArena == null && this.isWalkForwardCurriculum()) {
                    this.flatArena = MinecraftMachinesDuopodFlatArena.prepare(
                            level,
                            this.origin,
                            this.forwardDirection,
                            this.config.slotCount(),
                            this.config.spacingBlocks(),
                            this.config.maximumControlSteps(),
                            this.config.controlTicks());
                }
                final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                        level,
                        this.flatArena == null ? this.origin : this.flatArena.morphologyOrigin(),
                        this.forwardDirection,
                        this.batchId,
                        this.flatArena == null ? this.config.spacingBlocks() : this.flatArena.slotSpacingBlocks(),
                        this.flatArena != null);
                this.environment = new LocomotionVectorEnvironment<>(
                        morphology,
                        this.currentBatchSlotCount,
                        this.config.controlTicks(),
                        ResetStrategy.RESPAWN,
                        this::episodeForSlot,
                        false);
                final List<EpisodeDefinition> definitions = new ArrayList<>(this.currentBatchSlotCount);
                for (int slot = 0; slot < this.currentBatchSlotCount; slot++) {
                    definitions.add(this.episodeForSlot(slot, 0L));
                }
                final EnvironmentBatchReset reset = this.environment.resetAll(definitions);
                this.observations = reset.observations();
                if (this.isWalkForwardCurriculum() && this.spawnWarmupTicksRemaining == 0) {
                    for (int slot = 0; slot < this.currentBatchSlotCount; slot++) {
                        this.captureWalkForwardWarmupBaseline(slot, reset.infos().get(slot));
                    }
                }
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                this.close(level);
                return false;
            }
            this.batchSpawnPending = false;

            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_BATCH_START run={} generation={} firstCandidate={} candidates={} slots={} spawnWarmupTicks={}",
                    this.runId,
                    this.generation + 1,
                    this.currentBatchStartCandidate,
                    this.currentBatchCandidateCount,
                    this.currentBatchSlotCount,
                    this.spawnWarmupTicksRemaining);
            return true;
        }

        boolean tick(final MinecraftServer server) {
            if (this.cleanupPending) {
                // Keep the run registered and retry the transactional arena restore before
                // any new task can reuse or mutate the corridor.
                return !this.close();
            }
            final ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=missing_level", this.runId);
                return false;
            }
            if (this.batchSpawnPending) {
                if (this.chunkLease == null) {
                    this.lastFailure = "Scheduled Duopod batch lost its training chunk lease";
                    MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=missing_chunk_lease", this.runId);
                    return false;
                }
                final boolean readyForSpawn;
                try {
                    readyForSpawn = this.chunkLease.readyForSpawn();
                } catch (final RuntimeException e) {
                    this.lastFailure = "Could not activate the Duopod training corridor: " + e.getMessage();
                    MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=chunk_activation_failed failure={}", this.runId, this.lastFailure);
                    return false;
                }
                if (!readyForSpawn) {
                    this.chunkActivationWaitTicks++;
                    if (this.chunkActivationWaitTicks >= TRAINING_CHUNK_ACTIVATION_TIMEOUT_TICKS) {
                        this.lastFailure = "Timed out after %d ticks waiting for the Duopod training corridor chunks to become ticking"
                                .formatted(TRAINING_CHUNK_ACTIVATION_TIMEOUT_TICKS);
                        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=chunk_activation_timeout waitedTicks={}",
                                this.runId,
                                this.chunkActivationWaitTicks);
                        return false;
                    }
                    return true;
                }
                this.chunkActivationWaitTicks = 0;
                // Do not consume a warmup tick in the same tick that creates the bodies.
                // This preserves the configured number of full physics ticks between reset
                // and the first observation refresh.
                return this.spawnScheduledBatch(level);
            }
            if (this.environment == null) {
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=missing_environment", this.runId);
                return false;
            }

            if (this.spawnWarmupTicksRemaining > 0) {
                this.spawnWarmupTicksRemaining--;
                if (this.spawnWarmupTicksRemaining == 0 && !this.finishSpawnWarmup(level)) {
                    return false;
                }
                return true;
            }

            if (this.stepPending) {
                if (!this.sampleHeldActionWalkForwardPoses(level)) {
                    return false;
                }
                this.ticksUntilFinish--;
                if (this.ticksUntilFinish <= 0) {
                    return this.finishControlStep(server, level);
                }
                return true;
            }

            return this.beginControlStep(level);
        }

        private boolean beginControlStep(final ServerLevel level) {
            final double[][] actions = new double[this.currentBatchSlotCount][];
            for (int slot = 0; slot < actions.length; slot++) {
                actions[slot] = this.slotCompleted[slot]
                        ? new double[this.policy.actionSize()]
                        : curriculumAction(this.config.curriculumStage(), rampInitialAction(
                                this.policy.action(this.genomes.get(this.candidateIndex(slot)), this.observations[slot]),
                                this.finishedControlSteps), this.previousActions[slot]);
                if (!this.slotCompleted[slot]) {
                    this.recordActionSample(this.candidateIndex(slot), actions[slot]);
                }
            }
            try {
                this.environment.beginStep(actions);
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=begin_failed failure={}", this.runId, this.lastFailure);
                this.close(level);
                return false;
            }
            for (int slot = 0; slot < actions.length; slot++) {
                this.previousActions[slot] = Arrays.copyOf(actions[slot], actions[slot].length);
            }
            this.stepPending = true;
            this.ticksUntilFinish = this.config.controlTicks();
            return true;
        }

        private boolean finishSpawnWarmup(final ServerLevel level) {
            try {
                final EnvironmentBatchReset refreshed = this.environment.updateEpisodes((slot, episode) -> episode);
                this.observations = refreshed.observations();
                if (this.isWalkForwardCurriculum()) {
                    for (int slot = 0; slot < this.currentBatchSlotCount; slot++) {
                        this.captureWalkForwardWarmupBaseline(slot, refreshed.infos().get(slot));
                    }
                }
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=warmup_refresh_failed failure={}", this.runId, this.lastFailure);
                this.close(level);
                return false;
            }
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_BATCH_WARMUP_END run={} generation={} firstCandidate={} slots={}",
                    this.runId,
                    this.generation + 1,
                    this.currentBatchStartCandidate,
                    this.currentBatchSlotCount);
            return true;
        }

        private boolean sampleHeldActionWalkForwardPoses(final ServerLevel level) {
            if (!this.isWalkForwardCurriculum()) {
                return true;
            }
            final List<Map<String, Object>> infos;
            try {
                infos = this.environment.sampleDiagnosticInfos();
            } catch (final RuntimeException e) {
                this.lastFailure = "Could not sample held-action Duopod poses: " + e.getMessage();
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_CEM_END run={} reason=physics_pose_sample_failed failure={}",
                        this.runId,
                        this.lastFailure);
                this.close(level);
                return false;
            }
            if (infos.size() != this.currentBatchSlotCount) {
                this.lastFailure = "Held-action Duopod diagnostic count did not match the active slot count";
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_CEM_END run={} reason=physics_pose_sample_failed failure={}",
                        this.runId,
                        this.lastFailure);
                this.close(level);
                return false;
            }
            for (int slot = 0; slot < this.currentBatchSlotCount; slot++) {
                if (!this.slotCompleted[slot]) {
                    this.recordWalkForwardPose(slot, infos.get(slot));
                }
            }
            return true;
        }

        private void recordActionSample(final int candidate, final double[] action) {
            this.generationActionSamples.add(Arrays.copyOf(action, action.length));
            if (candidate >= 0 && candidate < this.candidateActionSums.length) {
                for (int i = 0; i < Math.min(action.length, this.candidateActionSums[candidate].length); i++) {
                    this.candidateActionSums[candidate][i] += action[i];
                }
                this.candidateActionCounts[candidate]++;
            }
        }

        private boolean finishControlStep(final MinecraftServer server, final ServerLevel level) {
            final EnvironmentBatchStep step;
            try {
                step = this.environment.finishStep();
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=finish_failed failure={}", this.runId, this.lastFailure);
                this.close(level);
                return false;
            }
            this.stepPending = false;
            this.finishedControlSteps++;

            final double[] rewards = step.rewards();
            final boolean[] terminated = step.terminated();
            final boolean[] truncated = step.truncated();
            final double[][] nextObservations = step.observations();
            final double[][] resetObservations = step.resetObservations();
            final boolean renderRewardVisualization = shouldRenderRewardVisualization(this.finishedControlSteps);
            final RewardVisualizationFrame rewardVisualizationFrame = renderRewardVisualization ? new RewardVisualizationFrame() : null;
            int rewardVisualizationSlots = 0;
            for (int slot = 0; slot < rewards.length; slot++) {
                if (this.slotCompleted[slot]) {
                    continue;
                }
                final Map<String, Object> info = step.infos().get(slot);
                this.recordExplorationInfo(info);
                this.recordWalkForwardPose(slot, info);
                this.slotReturns[slot] += rewards[slot];
                if (rewardVisualizationFrame != null) {
                    rewardVisualizationFrame.add(rewards[slot], info);
                    if (rewardVisualizationSlots < REWARD_VISUALIZATION_MAX_SLOTS) {
                        renderRewardVisualization(level, this.forwardDirection, rewards[slot], info);
                        rewardVisualizationSlots++;
                    }
                }
                // Walk-forward success is owned by the terminal v3 gate. Dense-environment
                // success hints must not survive a later ballistic or arena-escape rejection.
                if (!this.isWalkForwardCurriculum() && Boolean.TRUE.equals(info.get("success"))) {
                    this.slotSuccesses[slot]++;
                    this.generationSuccessCount++;
                }
                if (terminated[slot] || truncated[slot]) {
                    this.slotCompleted[slot] = true;
                    this.slotEndLengths[slot] = (int) finiteNumberOrDefault(info, "episode_length", this.finishedControlSteps);
                    if ("MACHINE_FAILURE".equals(info.get("termination_reason"))) {
                        this.slotFailures[slot] = true;
                        this.generationFallCount++;
                    }
                    if (this.isWalkForwardCurriculum()) {
                        final double denseReturn = this.slotReturns[slot];
                        final DuopodWalkForwardFitness.EpisodeFitness terminalFitness =
                                this.applyWalkForwardTerminalFitness(slot);
                        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_SLOT_END run={} generation={} candidate={} slot={} reason={} length={} denseReturn={} selectionReturn={} postWarmupForward={} postWarmupLateral={} rawTerminalForward={} rawTerminalLateral={} minimumBodyUp={} peakVerticalExcursion={} arenaEscape={} gateStable={} gateSuccess={} verticalExcess={} terminalFitnessAdjustment={} health=\"{}\"",
                                this.runId,
                                this.generation + 1,
                                this.candidateIndex(slot),
                                slot,
                                info.get("termination_reason"),
                                info.get("episode_length"),
                                format(denseReturn),
                                format(terminalFitness.selectionReturn()),
                                format(this.postWarmupForwardDisplacementForSlot(slot)),
                                format(this.postWarmupLateralDisplacementForSlot(slot)),
                                format(this.slotTerminalForwardDisplacements[slot]),
                                format(this.slotTerminalLateralDisplacements[slot]),
                                format(this.minimumBodyUpForSlot(slot)),
                                format(this.peakVerticalExcursionForSlot(slot)),
                                this.slotArenaEscapes[slot],
                                terminalFitness.stable(),
                                terminalFitness.success(),
                                format(terminalFitness.verticalExcessBlocks()),
                                format(terminalFitness.terminalShaping()),
                                info.getOrDefault("health_message", ""));
                    } else {
                        MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_SLOT_END run={} generation={} candidate={} slot={} reason={} length={} return={} health=\"{}\"",
                                this.runId,
                                this.generation + 1,
                                this.candidateIndex(slot),
                                slot,
                                info.get("termination_reason"),
                                info.get("episode_length"),
                                format(this.slotReturns[slot]),
                                info.getOrDefault("health_message", ""));
                    }
                }
                if ((terminated[slot] || truncated[slot]) && resetObservations[slot].length == this.policy.observationSize()) {
                    nextObservations[slot] = resetObservations[slot];
                }
            }
            this.observations = nextObservations;
            if (rewardVisualizationFrame != null && rewardVisualizationFrame.hasSamples()) {
                sendRewardVisualizationMessage(server, this.ownerId, this.dimension, rewardVisualizationFrame);
            }

            if (this.allSlotsCompleted() || this.finishedControlSteps >= this.config.maximumControlSteps()) {
                return this.finishBatch(server, level);
            }
            // Apply the next action before this server tick reaches physics;
            // otherwise the previous target persists for one unaccounted tick
            // between every control interval.
            return this.beginControlStep(level);
        }

        @SuppressWarnings("unchecked")
        private void recordExplorationInfo(final Map<String, Object> info) {
            if (Boolean.TRUE.equals(info.get("observation_repaired"))) {
                this.generationObservationRepairCount++;
            }
            final double projectedForward = finiteNumberOrDefault(info, "projected_gravity_forward", 0.0);
            final double projectedRight = finiteNumberOrDefault(info, "projected_gravity_right", 0.0);
            this.maxForwardTilt = Math.max(this.maxForwardTilt, projectedForward);
            this.maxBackwardTilt = Math.min(this.maxBackwardTilt, projectedForward);
            this.maxRightRoll = Math.max(this.maxRightRoll, projectedRight);
            this.maxLeftRoll = Math.min(this.maxLeftRoll, projectedRight);
            final Object components = info.get("reward_components");
            if (components instanceof final Map<?, ?> map) {
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof final String key && entry.getValue() instanceof final Number number) {
                        final double value = number.doubleValue();
                        if (Double.isFinite(value)) {
                            this.generationRewardComponentSums.merge(key, value, Double::sum);
                            if ("recovery_progress".equals(key)) {
                                this.generationRecoveryProgressSum += value;
                                this.generationRecoveryProgressSamples++;
                                if (value > 0.0) {
                                    this.generationPositiveRecoveryProgressSteps++;
                                }
                            }
                        }
                    }
                }
                this.generationRewardComponentSamples++;
            }
        }

        private boolean isWalkForwardCurriculum() {
            return DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(this.config.curriculumStage());
        }

        private void captureWalkForwardWarmupBaseline(final int slot, final Map<String, Object> info) {
            if (!this.isWalkForwardCurriculum()) {
                return;
            }
            if (!hasFiniteNumber(info, "forward_displacement_from_spawn_blocks")
                    || !hasFiniteNumber(info, "lateral_displacement_from_spawn_blocks")
                    || !hasFiniteNumber(info, "base_position_y")) {
                // Fail closed: an incomplete handoff snapshot cannot establish the v3
                // displacement, vertical-excursion, or arena-bound reference frame.
                this.slotArenaEscapes[slot] = true;
                return;
            }
            final double forward = finiteNumberOrDefault(info, "forward_displacement_from_spawn_blocks", 0.0);
            final double lateral = finiteNumberOrDefault(info, "lateral_displacement_from_spawn_blocks", 0.0);
            this.slotTerminalForwardDisplacements[slot] = forward;
            this.slotTerminalLateralDisplacements[slot] = lateral;
            this.slotWarmupForwardDisplacements[slot] = forward;
            this.slotWarmupLateralDisplacements[slot] = lateral;
            this.slotWarmupBaseYs[slot] = finiteNumberOrDefault(info, "base_position_y", 0.0);
            this.slotPeakVerticalExcursions[slot] = 0.0;
            this.slotMinimumBodyUps[slot] = Double.POSITIVE_INFINITY;
            this.slotArenaEscapes[slot] = false;
            // The handoff pose is the first controlled-episode stability sample and must
            // itself remain inside the prepared physical lane.
            this.recordWalkForwardPose(slot, info);
        }

        private void recordWalkForwardPose(final int slot, final Map<String, Object> info) {
            if (!this.isWalkForwardCurriculum()) {
                return;
            }
            if (!Double.isFinite(this.slotWarmupForwardDisplacements[slot])
                    || !Double.isFinite(this.slotWarmupLateralDisplacements[slot])
                    || !Double.isFinite(this.slotWarmupBaseYs[slot])) {
                this.slotArenaEscapes[slot] = true;
                return;
            }
            if (!hasFiniteNumber(info, "forward_displacement_from_spawn_blocks")
                    || !hasFiniteNumber(info, "lateral_displacement_from_spawn_blocks")
                    || !hasFiniteNumber(info, "base_position_y")) {
                this.slotArenaEscapes[slot] = true;
                return;
            }
            final double rawForward = finiteNumberOrDefault(
                    info,
                    "forward_displacement_from_spawn_blocks",
                    this.slotTerminalForwardDisplacements[slot]);
            final double rawLateral = finiteNumberOrDefault(
                    info,
                    "lateral_displacement_from_spawn_blocks",
                    this.slotTerminalLateralDisplacements[slot]);
            final double baseY = finiteNumberOrDefault(info, "base_position_y", this.slotWarmupBaseYs[slot]);
            this.slotTerminalForwardDisplacements[slot] = rawForward;
            this.slotTerminalLateralDisplacements[slot] = rawLateral;
            this.slotPeakVerticalExcursions[slot] = Math.max(
                    this.slotPeakVerticalExcursions[slot],
                    Math.abs(baseY - this.slotWarmupBaseYs[slot]));
            if (this.flatArena == null
                    || rawForward < this.flatArena.minimumForwardOffset()
                    || rawForward > this.flatArena.maximumForwardOffset()
                    || Math.abs(rawLateral) > MinecraftMachinesDuopodFlatArena.LANE_HALF_WIDTH_BLOCKS) {
                this.slotArenaEscapes[slot] = true;
            }
            if (!hasFiniteNumber(info, "body_up_dot_world_up")) {
                this.slotMinimumBodyUps[slot] = -1.0;
                this.slotArenaEscapes[slot] = true;
                return;
            }
            final double bodyUp = clamp(
                    finiteNumberOrDefault(info, "body_up_dot_world_up", -1.0),
                    -1.0,
                    1.0);
            this.slotMinimumBodyUps[slot] = Math.min(this.slotMinimumBodyUps[slot], bodyUp);
        }

        static double postWarmupDisplacement(final double terminalDisplacement, final double warmupDisplacement) {
            return Double.isFinite(terminalDisplacement) && Double.isFinite(warmupDisplacement)
                    ? terminalDisplacement - warmupDisplacement
                    : 0.0;
        }

        private double postWarmupForwardDisplacementForSlot(final int slot) {
            return postWarmupDisplacement(
                    this.slotTerminalForwardDisplacements[slot],
                    this.slotWarmupForwardDisplacements[slot]);
        }

        private double postWarmupLateralDisplacementForSlot(final int slot) {
            return postWarmupDisplacement(
                    this.slotTerminalLateralDisplacements[slot],
                    this.slotWarmupLateralDisplacements[slot]);
        }

        private double peakVerticalExcursionForSlot(final int slot) {
            return Double.isFinite(this.slotWarmupBaseYs[slot])
                    && Double.isFinite(this.slotPeakVerticalExcursions[slot])
                    ? this.slotPeakVerticalExcursions[slot]
                    : 0.0;
        }

        private double minimumBodyUpForSlot(final int slot) {
            return Double.isFinite(this.slotMinimumBodyUps[slot])
                    ? this.slotMinimumBodyUps[slot]
                    : -1.0;
        }

        private DuopodWalkForwardFitness.EpisodeFitness applyWalkForwardTerminalFitness(final int slot) {
            if (this.slotWalkForwardFitnessApplied[slot]) {
                throw new IllegalStateException("walk-forward terminal fitness already applied to slot " + slot);
            }
            final DuopodWalkForwardFitness.EpisodeFitness fitness = DuopodWalkForwardFitness.assessEpisode(
                    this.slotReturns[slot],
                    this.postWarmupForwardDisplacementForSlot(slot),
                    this.minimumBodyUpForSlot(slot),
                    this.peakVerticalExcursionForSlot(slot),
                    this.slotFailures[slot],
                    this.slotArenaEscapes[slot]);
            this.slotReturns[slot] = fitness.selectionReturn();
            this.slotTerminalFitnessAdjustments[slot] = fitness.terminalShaping();
            this.slotWalkForwardFitnessApplied[slot] = true;
            this.slotSuccesses[slot] = fitness.success() ? 1 : 0;
            if (fitness.success()) {
                this.generationSuccessCount++;
            }
            return fitness;
        }

        private boolean allSlotsCompleted() {
            for (final boolean completed : this.slotCompleted) {
                if (!completed) {
                    return false;
                }
            }
            return this.slotCompleted.length > 0;
        }

        private boolean finishBatch(final MinecraftServer server, final ServerLevel level) {
            for (int slot = 0; slot < this.currentBatchSlotCount; slot++) {
                if (this.isWalkForwardCurriculum() && !this.slotWalkForwardFitnessApplied[slot]) {
                    this.applyWalkForwardTerminalFitness(slot);
                }
                if (this.slotEndLengths[slot] <= 0) {
                    this.slotEndLengths[slot] = this.finishedControlSteps;
                }
                this.generationSurvivalLengths.add(this.slotEndLengths[slot]);
                final int candidate = this.candidateIndex(slot);
                this.candidateReturnSums[candidate] += this.slotReturns[slot];
                this.candidateWorstReturns[candidate] = Math.min(this.candidateWorstReturns[candidate], this.slotReturns[slot]);
                this.candidateSuccesses[candidate] += this.slotSuccesses[slot];
                this.candidateFailures[candidate] += this.slotFailures[slot] ? 1 : 0;
            }

            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_BATCH_END run={} generation={} firstCandidate={} candidates={}",
                    this.runId,
                    this.generation + 1,
                    this.currentBatchStartCandidate,
                    this.currentBatchCandidateCount);

            final int nextCandidate = this.currentBatchStartCandidate + this.currentBatchCandidateCount;
            if (nextCandidate < this.config.populationSize()) {
                return this.startBatch(level, nextCandidate);
            }
            return this.finishGeneration(server, level);
        }

        private double[][] candidateMeanActions() {
            final double[][] meanActions = new double[this.candidateActionSums.length][this.policy.actionSize()];
            for (int candidate = 0; candidate < this.candidateActionSums.length; candidate++) {
                if (this.candidateActionCounts[candidate] <= 0) {
                    continue;
                }
                for (int action = 0; action < meanActions[candidate].length; action++) {
                    meanActions[candidate][action] = this.candidateActionSums[candidate][action] / this.candidateActionCounts[candidate];
                }
            }
            return meanActions;
        }

        private double survivalLengthVariance() {
            if (this.generationSurvivalLengths.isEmpty()) {
                return 0.0;
            }
            double total = 0.0;
            for (final int length : this.generationSurvivalLengths) {
                total += length;
            }
            final double mean = total / this.generationSurvivalLengths.size();
            double variance = 0.0;
            for (final int length : this.generationSurvivalLengths) {
                variance += square(length - mean);
            }
            return variance / this.generationSurvivalLengths.size();
        }

        private Map<String, Double> rewardComponentMeans() {
            final Map<String, Double> means = new java.util.LinkedHashMap<>();
            final int samples = Math.max(1, this.generationRewardComponentSamples);
            this.generationRewardComponentSums.forEach((key, value) -> means.put(key, value / samples));
            return means;
        }

        private boolean finishGeneration(final MinecraftServer server, final ServerLevel level) {
            final List<ScoredGenome> scored = new ArrayList<>(this.config.populationSize());
            for (int candidate = 0; candidate < this.config.populationSize(); candidate++) {
                final double mean = this.candidateReturnSums[candidate] / this.config.episodesPerCandidate();
                final double successRate = this.candidateSuccesses[candidate] / (double) this.config.episodesPerCandidate();
                final double failureRate = this.candidateFailures[candidate] / (double) this.config.episodesPerCandidate();
                final ScoredGenome genome = new ScoredGenome(
                        this.genomes.get(candidate),
                        mean,
                        this.candidateWorstReturns[candidate],
                        successRate,
                        failureRate);
                scored.add(genome);
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_CANDIDATE run={} generation={} index={} mean={} worst={} successRate={} failureRate={} aggregate={}",
                        this.runId,
                        this.generation + 1,
                        candidate,
                        format(genome.meanScore()),
                        format(genome.worstScore()),
                        format(genome.successRate()),
                        format(genome.failureRate()),
                        format(genome.aggregateFitness()));
            }

            final ScoredGenome best = scored.stream()
                    .max(Comparator.comparingDouble(ScoredGenome::aggregateFitness))
                    .orElseThrow();
            final String currentPolicyType = policyType(this.policy);
            final boolean improvedBestEver = shouldReplaceBestGenome(
                    best,
                    this.config.curriculumStage(),
                    currentPolicyType,
                    this.config);

            final ContinuousCemDistribution previous = this.distribution;
            final CemSettings settings = this.config.settings();
            this.distribution = this.distribution.update(scored, settings.eliteCount(), settings.smoothingOld(), settings.smoothingElite());
            final CemExplorationDiagnostics.ActionStats actionStats = CemExplorationDiagnostics.actionStats(
                    this.generationActionSamples,
                    CEM_ACTION_SATURATION_THRESHOLD);
            final double[][] candidateMeanActions = this.candidateMeanActions();
            final double nearIdenticalActionFraction = CemExplorationDiagnostics.nearIdenticalCandidateFraction(
                    candidateMeanActions,
                    this.candidateActionCounts,
                    CEM_NEAR_IDENTICAL_ACTION_DISTANCE);
            this.recentBestFitness.add(best.aggregateFitness());
            while (this.recentBestFitness.size() > CEM_STAGNATION_WINDOW) {
                this.recentBestFitness.remove(0);
            }
            final CemExplorationDiagnostics.DistributionStats preExpansionDistributionStats =
                    CemExplorationDiagnostics.distributionStats(this.distribution, this.policy.observationSize());
            final boolean explorationExpansionAllowed =
                    this.generation - this.lastExplorationExpansionGeneration >= CEM_STAGNATION_WINDOW;
            final boolean stagnantLowDiversity = this.recentBestFitness.size() == CEM_STAGNATION_WINDOW
                    && CemExplorationDiagnostics.shouldExpandExploration(
                    this.recentBestFitness.stream().mapToDouble(Double::doubleValue).toArray(),
                    CEM_STAGNATION_MINIMUM_IMPROVEMENT,
                    actionStats.actionStandardDeviation(),
                    CEM_MINIMUM_ACTION_DIVERSITY,
                    actionStats.saturatedActionFraction(),
                    CEM_MAXIMUM_SATURATION_FOR_EXPANSION);
            // Temporal gait motion can make the raw action standard deviation look healthy
            // even when every candidate emits effectively the same trajectory. Use the
            // cross-candidate diagnostic as a second collapse signal so phase-gait CEM can
            // recover from a minimum-std population.
            final boolean stagnantCandidateCollapse = this.recentBestFitness.size() == CEM_STAGNATION_WINDOW
                    && CemExplorationDiagnostics.hasStagnated(
                    this.recentBestFitness.stream().mapToDouble(Double::doubleValue).toArray(),
                    CEM_STAGNATION_MINIMUM_IMPROVEMENT)
                    && nearIdenticalActionFraction >= CEM_COLLAPSED_NEAR_IDENTICAL_ACTION_FRACTION
                    && actionStats.saturatedActionFraction() < CEM_MAXIMUM_SATURATION_FOR_EXPANSION;
            final boolean unsolvedBalanceCollapse = DuopodTrainingScenarios.isBalanceCurriculumStage(this.config.curriculumStage())
                    && this.generationSuccessCount == 0
                    && nearIdenticalActionFraction >= CEM_BALANCE_UNSOLVED_NEAR_IDENTICAL_ACTION_FRACTION
                    && actionStats.saturatedActionFraction() <= CEM_MAXIMUM_SATURATION_FOR_EXPANSION;
            final boolean phaseGaitHardCollapse = this.policy instanceof DuopodPhaseGaitPolicy
                    && this.recentBestFitness.size() >= 2
                    && this.generation - this.lastExplorationExpansionGeneration
                    >= PHASE_GAIT_HARD_EXPANSION_COOLDOWN_GENERATIONS
                    && preExpansionDistributionStats.meanStandardDeviation()
                    <= PHASE_GAIT_HARD_EXPANSION_MAXIMUM_MEAN_STD
                    && nearIdenticalActionFraction >= CEM_COLLAPSED_NEAR_IDENTICAL_ACTION_FRACTION
                    && actionStats.saturatedActionFraction() < CEM_MAXIMUM_SATURATION_FOR_EXPANSION;
            final boolean expandedExploration = phaseGaitHardCollapse || (explorationExpansionAllowed
                    && (stagnantLowDiversity || stagnantCandidateCollapse || unsolvedBalanceCollapse));
            if (expandedExploration) {
                this.distribution = this.distribution.expandStandardDeviation(
                        phaseGaitHardCollapse
                                ? PHASE_GAIT_HARD_EXPANSION_MULTIPLIER
                                : CEM_EXPLORATION_EXPANSION_MULTIPLIER);
                this.lastExplorationExpansionGeneration = this.generation;
            }
            final CemExplorationDiagnostics.DistributionStats distributionStats =
                    CemExplorationDiagnostics.distributionStats(this.distribution, this.policy.observationSize());
            final double meanRecoveryProgress = this.generationRecoveryProgressSamples == 0
                    ? 0.0
                    : this.generationRecoveryProgressSum / this.generationRecoveryProgressSamples;
            final double positiveRecoveryProgressFraction = this.generationRecoveryProgressSamples == 0
                    ? 0.0
                    : this.generationPositiveRecoveryProgressSteps / (double) this.generationRecoveryProgressSamples;
            final double survivalVariance = this.survivalLengthVariance();
            final Map<String, Double> rewardComponentMeans = this.rewardComponentMeans();
            this.lastExplorationSummary = "std(mean/min/max)=%.3f/%.3f/%.3f action(mean/std/sat)=%.3f/%.3f/%.3f identical=%.3f recovery=%.4f positive=%.3f success=%d fall=%d repaired=%d expanded=%s hard=%s"
                    .formatted(
                            distributionStats.meanStandardDeviation(),
                            distributionStats.minimumStandardDeviation(),
                            distributionStats.maximumStandardDeviation(),
                            actionStats.meanActionMagnitude(),
                            actionStats.actionStandardDeviation(),
                            actionStats.saturatedActionFraction(),
                            nearIdenticalActionFraction,
                            meanRecoveryProgress,
                            positiveRecoveryProgressFraction,
                            this.generationSuccessCount,
                            this.generationFallCount,
                            this.generationObservationRepairCount,
                            expandedExploration,
                            phaseGaitHardCollapse);
            if (improvedBestEver) {
                bestEver = new BestGenome(
                        best.genome(),
                        best.meanScore(),
                        best.worstScore(),
                        best.successRate(),
                        best.failureRate(),
                        best.aggregateFitness(),
                        this.runId,
                        this.runSeed,
                        this.generation + 1,
                        Instant.now().toString(),
                        currentPolicyType,
                        this.config.curriculumStage(),
                        this.config,
                        this.config.toJson(),
                        CEM_FITNESS_CONTRACT,
                        DistributionSnapshot.from(this.distribution),
                        EvaluationMetadata.defaultHeldOut());
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_BEST run={} generation={} aggregate={} mean={} worst={} successRate={} failureRate={}",
                        this.runId,
                        this.generation + 1,
                        format(best.aggregateFitness()),
                        format(best.meanScore()),
                        format(best.worstScore()),
                        format(best.successRate()),
                        format(best.failureRate()));
            }
            // The best model's discovery metadata is immutable, but resume state is not.
            // Persist the optimizer after every completed generation so a later manual
            // save does not silently roll CEM back to the last generation that improved
            // the model score.
            bestEver = bestEver.withDistribution(DistributionSnapshot.from(this.distribution));
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_GENERATION_END run={} generation={} bestAggregate={} oldGeneration={} newGeneration={}",
                    this.runId,
                    this.generation + 1,
                    format(best.aggregateFitness()),
                    previous.generation(),
                    this.distribution.generation());
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EXPLORATION run={} generation={} meanStd={} minStd={} maxStd={} weightStd={} biasStd={} actionMeanMagnitude={} actionStd={} actionSaturation={} nearIdenticalActions={} survivalVariance={} maxForwardTilt={} maxBackwardTilt={} maxLeftRoll={} maxRightRoll={} meanRecoveryProgress={} positiveRecoveryProgressFraction={} successCount={} fallCount={} observationRepairCount={} expanded={} hardExpansion={} rewardMeans={}",
                    this.runId,
                    this.generation + 1,
                    format(distributionStats.meanStandardDeviation()),
                    format(distributionStats.minimumStandardDeviation()),
                    format(distributionStats.maximumStandardDeviation()),
                    format(distributionStats.weightStandardDeviation()),
                    format(distributionStats.biasStandardDeviation()),
                    format(actionStats.meanActionMagnitude()),
                    format(actionStats.actionStandardDeviation()),
                    format(actionStats.saturatedActionFraction()),
                    format(nearIdenticalActionFraction),
                    format(survivalVariance),
                    format(this.maxForwardTilt),
                    format(this.maxBackwardTilt),
                    format(this.maxLeftRoll),
                    format(this.maxRightRoll),
                    format(meanRecoveryProgress),
                    format(positiveRecoveryProgressFraction),
                    this.generationSuccessCount,
                    this.generationFallCount,
                    this.generationObservationRepairCount,
                    expandedExploration,
                    phaseGaitHardCollapse,
                    rewardComponentMeans);

            final ServerPlayer player = owner(server, this.ownerId);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Duopod CEM gen %d/%d best aggregate %.3f mean %.3f"
                        .formatted(this.generation + 1,
                                this.config.maximumGenerations(),
                                best.aggregateFitness(),
                                best.meanScore())));
            }

            if (!this.closeBatchEnvironment()) {
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_CEM_END run={} reason=batch_cleanup_retry_pending failure={}",
                        this.runId,
                        this.lastFailure);
                if (player != null) {
                    player.sendSystemMessage(Component.literal(
                            "Duopod CEM stopped after generation cleanup failed; restoration will retry: "
                                    + this.lastFailure));
                }
                return true;
            }
            this.generation++;
            if (this.generation >= this.config.maximumGenerations()) {
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=complete bestAggregate={}",
                        this.runId,
                        bestEver == null ? "none" : format(bestEver.aggregateFitness()));
                if (player != null) {
                    player.sendSystemMessage(Component.literal("Duopod CEM complete. Best aggregate %.3f mean %.3f."
                            .formatted(bestEver == null ? 0.0 : bestEver.aggregateFitness(),
                                    bestEver == null ? 0.0 : bestEver.meanScore())));
                }
                return !this.close();
            }
            if (!this.startGeneration(level)) {
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_END run={} reason=spawn_failed failure={}", this.runId, this.lastFailure);
                if (player != null) {
                    player.sendSystemMessage(Component.literal("Duopod CEM stopped: " + this.lastFailure));
                }
                return false;
            }
            return true;
        }

        boolean close(final ServerLevel level) {
            return this.close();
        }

        boolean close() {
            if (!this.closeBatchEnvironment()) {
                // Environment ownership is deliberately retained. Restoring the arena or
                // releasing its chunk lease while bodies may still exist would make the
                // remaining cleanup both destructive and unrecoverable.
                return false;
            }
            if (this.flatArena != null) {
                try {
                    this.flatArena.close();
                    this.flatArena = null;
                } catch (final RuntimeException e) {
                    this.cleanupPending = true;
                    this.lastFailure = "Could not restore the controlled Duopod arena; cleanup will retry: " + e.getMessage();
                    MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_CLEANUP run={} component=flat_arena result=retry_pending failure={}",
                            this.runId,
                            e.getMessage());
                    // Keep the chunk lease alive so every restore retry targets loaded,
                    // ticking chunks. Never discard the retryable arena snapshot.
                    return false;
                }
            }
            if (this.chunkLease != null) {
                this.chunkLease.close();
                this.chunkLease = null;
            }
            this.cleanupPending = false;
            this.chunkActivationWaitTicks = 0;
            return true;
        }

        private boolean closeBatchEnvironment() {
            this.stepPending = false;
            this.ticksUntilFinish = 0;
            this.spawnWarmupTicksRemaining = 0;
            this.batchSpawnPending = false;
            if (this.environment == null) {
                return true;
            }
            try {
                this.environment.close();
                this.environment = null;
                return true;
            } catch (final RuntimeException e) {
                this.cleanupPending = true;
                this.lastFailure = "Could not destroy the Duopod batch; cleanup will retry: " + e.getMessage();
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_CEM_CLEANUP run={} component=environment result=retry_pending failure={}",
                        this.runId,
                        e.getMessage());
                return false;
            }
        }

        private EpisodeDefinition episodeForSlot(final int slot, final long episodeIndex) {
            final int scenario = DuopodTrainingScenarios.rollingTrainingScenario(
                    this.config.curriculumStage(),
                    this.generation,
                    slot % this.config.episodesPerCandidate(),
                    this.config.episodesPerCandidate());
            final int candidate = this.candidateIndex(slot);
            final int globalScenarioSlot = candidate * this.config.episodesPerCandidate() + scenario;
            final long episodeSeed = trainingEpisodeSeed(
                    this.config.curriculumStage(),
                    this.runSeed,
                    this.generation,
                    this.config.episodesPerCandidate(),
                    scenario,
                    globalScenarioSlot,
                    episodeIndex);
            return DuopodTrainingScenarios.episodeForStage(
                    this.config.curriculumStage(),
                    (((long) this.generation) << 40) ^ (((long) globalScenarioSlot) << 16) ^ episodeIndex,
                    episodeSeed,
                    this.generation,
                    this.config.maximumControlSteps(),
                    scenario,
                    this.config.pointGoalNearDistanceBlocks(),
                    this.config.pointGoalFarDistanceBlocks());
        }

        static long trainingEpisodeSeed(
                final String curriculumStage,
                final long runSeed,
                final int generation,
                final int scenarioCount,
                final int scenario,
                final int globalScenarioSlot,
                final long episodeIndex
        ) {
            final String normalizedStage = DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage);
            final boolean commonRandomNumbers = DuopodTrainingScenarios.isBalanceCurriculumStage(normalizedStage)
                    || DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(normalizedStage);
            return commonRandomNumbers
                    ? CemScenarioSeeds.forGeneration(runSeed, generation, scenarioCount).seeds()[scenario] + episodeIndex
                    : runSeed + 1_000_003L * generation + 65_537L * globalScenarioSlot + episodeIndex;
        }

        private int candidateIndex(final int slot) {
            return this.currentBatchStartCandidate + (slot / this.config.episodesPerCandidate());
        }

        UUID runId() {
            return this.runId;
        }

        ResourceKey<Level> dimension() {
            return this.dimension;
        }

        BlockPos origin() {
            return this.origin;
        }

        Config config() {
            return this.config;
        }

        int generation() {
            return this.generation;
        }

        int finishedControlSteps() {
            return this.finishedControlSteps;
        }

        int spawnWarmupTicksRemaining() {
            return this.spawnWarmupTicksRemaining;
        }

        boolean cleanupPending() {
            return this.cleanupPending;
        }

        int slotCount() {
            return this.config.slotCount();
        }

        String lastFailure() {
            return this.lastFailure;
        }

        String lastExplorationSummary() {
            return this.lastExplorationSummary;
        }
    }

    static final class EvaluationRun {
        private final UUID evaluationId;
        private final UUID ownerId;
        private final ResourceKey<Level> dimension;
        private final BlockPos origin;
        private final Direction forwardDirection;
        private final UUID batchId;
        private final double[] genome;
        private final DuopodEvaluationManifest manifest;
        private final int controlTicks;
        private final int spawnWarmupTicks;
        private final int maximumControlSteps;
        private final GenomePolicy policy;
        private MinecraftMachinesDuopodTrainingMorphology morphology;
        private LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod> environment;
        private double[][] observations;
        private double[][] previousActions;
        private double[] scenarioReturns;
        private double[] actionTotalVariation;
        private double[] distanceTravelled;
        private double[] initialDistanceToTarget;
        private double[] previousBaseX;
        private double[] previousBaseZ;
        private boolean[] hasPreviousBasePosition;
        private double[] forwardCommandAbsError;
        private double[] yawCommandAbsError;
        private double[] servoLoadSum;
        private double[] peakServoLoad;
        private boolean[] metricTelemetryValid;
        private int[] metricSamples;
        private int[] timeToTargetSteps;
        private List<FirstTerminalSnapshot<JsonObject>> episodeSnapshots;
        private int finishedControlSteps;
        private int spawnWarmupTicksRemaining;
        private int ticksUntilFinish;
        private boolean stepPending;
        private String lastFailure = "";

        EvaluationRun(
                final UUID evaluationId,
                final UUID ownerId,
                final ResourceKey<Level> dimension,
                final BlockPos origin,
                final Direction forwardDirection,
                final UUID batchId,
                final double[] genome,
                final DuopodEvaluationManifest manifest,
                final int controlTicks,
                final int spawnWarmupTicks,
                final int maximumControlSteps
        ) {
            this.evaluationId = evaluationId;
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.origin = origin.immutable();
            this.forwardDirection = forwardDirection;
            this.batchId = batchId;
            this.genome = Arrays.copyOf(genome, genome.length);
            this.manifest = manifest;
            this.controlTicks = controlTicks;
            if (spawnWarmupTicks < 0) {
                throw new IllegalArgumentException("evaluation spawn warmup ticks must be non-negative");
            }
            this.spawnWarmupTicks = spawnWarmupTicks;
            this.maximumControlSteps = maximumControlSteps;
            this.policy = duopodPolicy(DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE);
            if (this.genome.length != this.policy.genomeSize()) {
                throw new IllegalArgumentException("evaluation genome length does not match current Duopod policy");
            }
        }

        boolean start(final ServerLevel level) {
            this.close(level);
            this.finishedControlSteps = 0;
            this.spawnWarmupTicksRemaining = this.spawnWarmupTicks;
            this.ticksUntilFinish = 0;
            this.stepPending = false;
            final int slotCount = this.manifest.scenarios().size();
            this.scenarioReturns = new double[slotCount];
            this.actionTotalVariation = new double[slotCount];
            this.distanceTravelled = new double[slotCount];
            this.initialDistanceToTarget = new double[slotCount];
            Arrays.fill(this.initialDistanceToTarget, Double.NaN);
            this.previousBaseX = new double[slotCount];
            this.previousBaseZ = new double[slotCount];
            this.hasPreviousBasePosition = new boolean[slotCount];
            this.forwardCommandAbsError = new double[slotCount];
            this.yawCommandAbsError = new double[slotCount];
            this.servoLoadSum = new double[slotCount];
            this.peakServoLoad = new double[slotCount];
            this.metricTelemetryValid = new boolean[slotCount];
            Arrays.fill(this.metricTelemetryValid, true);
            this.metricSamples = new int[slotCount];
            this.timeToTargetSteps = new int[slotCount];
            Arrays.fill(this.timeToTargetSteps, -1);
            this.previousActions = new double[slotCount][this.policy.actionSize()];
            this.episodeSnapshots = new ArrayList<>(slotCount);
            for (int slot = 0; slot < slotCount; slot++) {
                this.episodeSnapshots.add(new FirstTerminalSnapshot<>());
            }
            this.morphology = new MinecraftMachinesDuopodTrainingMorphology(
                    level,
                    this.origin,
                    this.forwardDirection,
                    this.batchId,
                    0);
            this.environment = new LocomotionVectorEnvironment<>(
                    this.morphology,
                    slotCount,
                    this.controlTicks,
                    ResetStrategy.RESPAWN,
                    this::episodeForSlot,
                    false);
            try {
                final EnvironmentBatchReset reset = this.environment.resetAll();
                this.observations = reset.observations();
                if (this.spawnWarmupTicksRemaining == 0) {
                    this.captureEvaluationBaselines(reset);
                }
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                this.close(level);
                return false;
            }
            return true;
        }

        boolean tick(final MinecraftServer server) {
            final ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=missing_level", this.evaluationId);
                return false;
            }
            if (this.environment == null) {
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=missing_environment", this.evaluationId);
                return false;
            }

            if (this.spawnWarmupTicksRemaining > 0) {
                this.spawnWarmupTicksRemaining--;
                if (this.spawnWarmupTicksRemaining == 0 && !this.finishSpawnWarmup(level)) {
                    return false;
                }
                return true;
            }

            if (this.stepPending) {
                this.ticksUntilFinish--;
                if (this.ticksUntilFinish <= 0) {
                    return this.finishControlStep(server, level);
                }
                return true;
            }

            return this.beginControlStep(level);
        }

        private boolean finishSpawnWarmup(final ServerLevel level) {
            try {
                final EnvironmentBatchReset refreshed = this.environment.updateEpisodes((slot, episode) -> episode);
                this.observations = refreshed.observations();
                this.captureEvaluationBaselines(refreshed);
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=warmup_refresh_failed failure={}",
                        this.evaluationId,
                        this.lastFailure);
                this.close(level);
                return false;
            }
            return true;
        }

        private void captureEvaluationBaselines(final EnvironmentBatchReset reset) {
            for (int slot = 0; slot < reset.infos().size(); slot++) {
                final JsonObject info = GSON.toJsonTree(reset.infos().get(slot)).getAsJsonObject();
                this.episodeSnapshots.get(slot).record(info, false, 0);
                this.captureInitialPosition(slot, info);
                this.captureInitialDistance(slot, info);
            }
        }

        private boolean beginControlStep(final ServerLevel level) {
            final double[][] actions = new double[this.manifest.scenarios().size()][];
            for (int slot = 0; slot < actions.length; slot++) {
                actions[slot] = this.episodeSnapshots.get(slot).completed()
                        ? new double[this.policy.actionSize()]
                        : rampInitialAction(this.policy.action(this.genome, this.observations[slot]), this.finishedControlSteps);
                if (!this.episodeSnapshots.get(slot).completed()) {
                    this.actionTotalVariation[slot] += absoluteDelta(actions[slot], this.previousActions[slot]);
                    this.previousActions[slot] = Arrays.copyOf(actions[slot], actions[slot].length);
                }
            }
            try {
                this.environment.beginStep(actions);
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=begin_failed failure={}", this.evaluationId, this.lastFailure);
                this.close(level);
                return false;
            }
            this.stepPending = true;
            this.ticksUntilFinish = this.controlTicks;
            return true;
        }

        private boolean finishControlStep(final MinecraftServer server, final ServerLevel level) {
            final EnvironmentBatchStep step;
            try {
                step = this.environment.finishStep();
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=finish_failed failure={}", this.evaluationId, this.lastFailure);
                this.close(level);
                return false;
            }
            this.stepPending = false;
            this.finishedControlSteps++;

            final double[] rewards = step.rewards();
            final boolean[] terminated = step.terminated();
            final boolean[] truncated = step.truncated();
            final double[][] nextObservations = step.observations();
            final double[][] resetObservations = step.resetObservations();
            for (int slot = 0; slot < rewards.length; slot++) {
                final JsonObject info = GSON.toJsonTree(step.infos().get(slot)).getAsJsonObject();
                final FirstTerminalSnapshot<JsonObject> episodeSnapshot = this.episodeSnapshots.get(slot);
                if (episodeSnapshot.record(info, terminated[slot] || truncated[slot], this.finishedControlSteps)) {
                    this.scenarioReturns[slot] += rewards[slot];
                    this.recordMetrics(slot, info);
                    if (episodeSnapshot.completed()) {
                        if (terminated[slot]
                                && info.has("success")
                                && info.get("success").getAsBoolean()
                                && this.timeToTargetSteps[slot] < 0) {
                            this.timeToTargetSteps[slot] = this.finishedControlSteps;
                        }
                    }
                }
                if ((terminated[slot] || truncated[slot]) && resetObservations[slot].length == this.policy.observationSize()) {
                    nextObservations[slot] = resetObservations[slot];
                }
            }
            this.observations = nextObservations;

            if (this.allCompleted() || this.finishedControlSteps >= this.maximumControlSteps) {
                this.writeResults(server, level);
                return false;
            }
            // Apply the next targets during this same Pre tick so evaluation
            // executes exactly controlTicks physics intervals per transition.
            return this.beginControlStep(level);
        }

        private void writeResults(final MinecraftServer server, final ServerLevel level) {
            try {
                this.writeValidatedResults(server);
            } catch (final IOException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=write_failed failure={}", this.evaluationId, this.lastFailure);
            } catch (final IllegalStateException e) {
                this.lastFailure = "invalid evaluation metrics: " + e.getMessage();
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=invalid_metrics failure={}", this.evaluationId, this.lastFailure);
                final ServerPlayer player = owner(server, this.ownerId);
                if (player != null) {
                    player.sendSystemMessage(Component.literal("Duopod CEM evaluation rejected: " + this.lastFailure));
                }
            } finally {
                this.close(level);
            }
        }

        private void writeValidatedResults(final MinecraftServer server) throws IOException {
            final JsonArray rows = new JsonArray(this.manifest.scenarios().size());
            int successes = 0;
            int failures = 0;
            double totalReturn = 0.0;
            double worstReturn = Double.POSITIVE_INFINITY;
            double totalFinalDistance = 0.0;
            double totalDistanceTravelled = 0.0;
            double totalPathDirectness = 0.0;
            double totalServoLoad = 0.0;
            double peakServoLoad = 0.0;
            double totalForwardCommandError = 0.0;
            double totalYawCommandError = 0.0;
            double totalTimeToTarget = 0.0;
            for (int slot = 0; slot < this.manifest.scenarios().size(); slot++) {
                final JsonObject row = this.row(slot);
                rows.add(row);
                final boolean success = row.get("success").getAsBoolean();
                final String reason = row.get("termination_reason").getAsString();
                successes += success ? 1 : 0;
                failures += "MACHINE_FAILURE".equals(reason) ? 1 : 0;
                totalReturn += this.scenarioReturns[slot];
                worstReturn = Math.min(worstReturn, this.scenarioReturns[slot]);
                totalFinalDistance += row.get("final_distance_to_target").getAsDouble();
                totalDistanceTravelled += row.get("distance_travelled_blocks").getAsDouble();
                totalPathDirectness += row.get("path_directness").getAsDouble();
                totalServoLoad += row.get("mean_servo_load").getAsDouble();
                peakServoLoad = Math.max(peakServoLoad, row.get("peak_servo_load").getAsDouble());
                totalForwardCommandError += row.get("mean_forward_command_error").getAsDouble();
                totalYawCommandError += row.get("mean_yaw_command_error").getAsDouble();
                if (success && row.has("time_to_target_steps") && !row.get("time_to_target_steps").isJsonNull()) {
                    totalTimeToTarget += row.get("time_to_target_steps").getAsDouble();
                }
            }
            final int scenarioCount = this.manifest.scenarios().size();
            final double successRate = DuopodEvaluationSanity.requireUnitInterval(
                    "success_rate",
                    successes / (double) scenarioCount);
            final double failureRate = DuopodEvaluationSanity.requireUnitInterval(
                    "failure_rate",
                    failures / (double) scenarioCount);
            final JsonObject summary = new JsonObject();
            summary.addProperty("evaluation_valid", true);
            summary.addProperty("episodes", scenarioCount);
            summary.addProperty("mean_return", totalReturn / scenarioCount);
            summary.addProperty("worst_return", worstReturn);
            summary.addProperty("success_rate", successRate);
            summary.addProperty("failure_rate", failureRate);
            summary.addProperty("mean_final_distance", totalFinalDistance / scenarioCount);
            summary.addProperty("mean_distance_travelled_blocks", totalDistanceTravelled / scenarioCount);
            summary.addProperty("mean_path_directness", totalPathDirectness / scenarioCount);
            summary.addProperty("mean_servo_load", totalServoLoad / scenarioCount);
            summary.addProperty("peak_servo_load", peakServoLoad);
            summary.addProperty("mean_forward_command_error", totalForwardCommandError / scenarioCount);
            summary.addProperty("mean_yaw_command_error", totalYawCommandError / scenarioCount);
            if (successes > 0) {
                summary.addProperty("mean_time_to_target_steps", totalTimeToTarget / successes);
            } else {
                summary.add("mean_time_to_target_steps", JsonNull.INSTANCE);
            }
            summary.addProperty("control_steps", this.finishedControlSteps);

            final JsonObject payload = new JsonObject();
            payload.add("evaluation_contract", evaluationContractJson());
            payload.add("scenario_manifest", manifestJson(this.manifest));
            payload.add("summary", summary);
            payload.add("episodes", rows);
            payload.add("policy", this.policyMetadata());

            final Path path = evaluationPath(server);
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(payload, writer);
            }
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_EVALUATION_END evaluation={} reason=complete path={} successRate={} meanReturn={}",
                    this.evaluationId,
                    path,
                    format(summary.get("success_rate").getAsDouble()),
                    format(summary.get("mean_return").getAsDouble()));
            final ServerPlayer player = owner(server, this.ownerId);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Duopod CEM evaluation complete: success %.2f mean %.3f saved %s"
                        .formatted(summary.get("success_rate").getAsDouble(),
                                summary.get("mean_return").getAsDouble(),
                                path)));
            }
        }

        private JsonObject row(final int slot) {
            final DuopodEvaluationScenario scenario = this.manifest.scenarios().get(slot);
            final FirstTerminalSnapshot<JsonObject> episodeSnapshot = this.episodeSnapshots.get(slot);
            final JsonObject info = episodeSnapshot.value() == null ? new JsonObject() : episodeSnapshot.value();
            if (!this.metricTelemetryValid[slot]) {
                throw new IllegalStateException("evaluation telemetry was incomplete or non-finite for slot " + slot);
            }
            final double finalDistance = requiredNonNegativeFiniteNumber(info, "distance_to_target", "terminal slot " + slot);
            final double progress = this.targetProgress(slot, finalDistance);
            final double pathDirectness = this.pathDirectness(slot, progress);
            final JsonObject row = new JsonObject();
            row.addProperty("scenario_id", scenario.id());
            row.addProperty("slot", slot);
            row.addProperty("target_bearing_degrees", scenario.targetBearingDegrees());
            row.addProperty("target_distance_blocks", scenario.targetDistanceBlocks());
            row.addProperty("initial_distance_to_target", this.initialDistanceToTarget[slot]);
            row.addProperty("progress_to_target_blocks", progress);
            row.addProperty("steps", episodeSnapshot.terminalStep() >= 0
                    ? episodeSnapshot.terminalStep()
                    : Math.min(this.finishedControlSteps, this.maximumControlSteps));
            row.addProperty("return", this.scenarioReturns[slot]);
            row.addProperty("success", episodeSnapshot.completed()
                    && info.has("success")
                    && info.get("success").getAsBoolean());
            row.addProperty("termination_reason", episodeSnapshot.completed() && info.has("termination_reason")
                    ? info.get("termination_reason").getAsString()
                    : "EVALUATION_STEP_LIMIT");
            row.addProperty("final_distance_to_target", finalDistance);
            row.addProperty("action_total_variation", this.actionTotalVariation[slot]);
            row.addProperty("distance_travelled_blocks", this.distanceTravelled[slot]);
            row.addProperty("path_directness", pathDirectness);
            if (this.timeToTargetSteps[slot] >= 0) {
                row.addProperty("time_to_target_steps", this.timeToTargetSteps[slot]);
            } else {
                row.add("time_to_target_steps", JsonNull.INSTANCE);
            }
            row.addProperty("mean_servo_load", this.meanMetric(this.servoLoadSum[slot], slot));
            row.addProperty("peak_servo_load", this.peakServoLoad[slot]);
            row.addProperty("mean_forward_command_error", this.meanMetric(this.forwardCommandAbsError[slot], slot));
            row.addProperty("mean_yaw_command_error", this.meanMetric(this.yawCommandAbsError[slot], slot));
            return row;
        }

        private void captureInitialPosition(final int slot, final JsonObject info) {
            this.previousBaseX[slot] = requiredFiniteNumber(info, "base_position_x", "reset slot " + slot);
            this.previousBaseZ[slot] = requiredFiniteNumber(info, "base_position_z", "reset slot " + slot);
            this.hasPreviousBasePosition[slot] = true;
        }

        private void captureInitialDistance(final int slot, final JsonObject info) {
            final double requestedDistance = this.manifest.scenarios().get(slot).targetDistanceBlocks();
            final double measuredDistance = requiredNonNegativeFiniteNumber(
                    info,
                    "distance_to_target",
                    "reset slot " + slot);
            this.initialDistanceToTarget[slot] = DuopodEvaluationSanity.requireMatchingInitialDistance(
                    requestedDistance,
                    measuredDistance,
                    EVALUATION_DISTANCE_SANITY_TOLERANCE_BLOCKS);
        }

        private void recordMetrics(final int slot, final JsonObject info) {
            final String[] requiredMetrics = {
                    "base_position_x",
                    "base_position_z",
                    "desired_forward_velocity",
                    "local_forward_velocity",
                    "desired_yaw_rate",
                    "local_yaw_rate",
                    "mean_servo_load",
                    "peak_servo_load"
            };
            for (final String key : requiredMetrics) {
                if (!hasFiniteNumber(info, key)) {
                    this.metricTelemetryValid[slot] = false;
                    return;
                }
            }
            final double baseX = info.get("base_position_x").getAsDouble();
            final double baseZ = info.get("base_position_z").getAsDouble();
            if (this.hasPreviousBasePosition[slot]) {
                final double dx = baseX - this.previousBaseX[slot];
                final double dz = baseZ - this.previousBaseZ[slot];
                this.distanceTravelled[slot] += Math.sqrt((dx * dx) + (dz * dz));
            }
            this.previousBaseX[slot] = baseX;
            this.previousBaseZ[slot] = baseZ;
            this.hasPreviousBasePosition[slot] = true;

            final double desiredForward = info.get("desired_forward_velocity").getAsDouble();
            final double actualForward = info.get("local_forward_velocity").getAsDouble();
            final double desiredYaw = info.get("desired_yaw_rate").getAsDouble();
            final double actualYaw = info.get("local_yaw_rate").getAsDouble();
            final double meanServoLoad = Math.abs(info.get("mean_servo_load").getAsDouble());
            final double peakServoLoad = Math.abs(info.get("peak_servo_load").getAsDouble());
            this.forwardCommandAbsError[slot] += Math.abs(actualForward - desiredForward);
            this.yawCommandAbsError[slot] += Math.abs(actualYaw - desiredYaw);
            this.servoLoadSum[slot] += meanServoLoad;
            this.peakServoLoad[slot] = Math.max(this.peakServoLoad[slot], peakServoLoad);
            this.metricSamples[slot]++;
        }

        private double targetProgress(final int slot, final double finalDistanceToTarget) {
            return DuopodEvaluationSanity.requireReachableProgress(
                    this.initialDistanceToTarget[slot],
                    finalDistanceToTarget,
                    this.distanceTravelled[slot],
                    EVALUATION_DISTANCE_SANITY_TOLERANCE_BLOCKS);
        }

        private double pathDirectness(final int slot, final double progress) {
            if (this.distanceTravelled[slot] <= 1.0e-9) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, progress / this.distanceTravelled[slot]));
        }

        private double meanMetric(final double total, final int slot) {
            return this.metricSamples[slot] <= 0 ? 0.0 : total / this.metricSamples[slot];
        }

        private static boolean hasNumber(final JsonObject info, final String key) {
            return info.has(key) && info.get(key).isJsonPrimitive() && info.get(key).getAsJsonPrimitive().isNumber();
        }

        private static boolean hasFiniteNumber(final JsonObject info, final String key) {
            return hasNumber(info, key) && Double.isFinite(info.get(key).getAsDouble());
        }

        private static double requiredFiniteNumber(final JsonObject info, final String key, final String context) {
            if (!hasFiniteNumber(info, key)) {
                throw new IllegalStateException(context + " did not report finite " + key);
            }
            return info.get(key).getAsDouble();
        }

        private static double requiredNonNegativeFiniteNumber(
                final JsonObject info,
                final String key,
                final String context
        ) {
            final double value = requiredFiniteNumber(info, key, context);
            if (value < 0.0) {
                throw new IllegalStateException(context + " reported negative " + key + ": " + value);
            }
            return value;
        }

        private JsonObject policyMetadata() {
            final JsonObject policyJson = new JsonObject();
            policyJson.addProperty("policy_type", "linear_tanh");
            policyJson.addProperty("morphology_id", MinecraftMachines.MOD_ID + ":" + DuopodInstance.MORPHOLOGY_TYPE);
            policyJson.addProperty("observation_schema_hash", DuopodSchemas.observationSpec().compatibilityHash());
            policyJson.addProperty("action_schema_hash", DuopodSchemas.actionSpec().compatibilityHash());
            policyJson.addProperty("genome_size", this.policy.genomeSize());
            return policyJson;
        }

        private EpisodeDefinition episodeForSlot(final int slot, final long episodeIndex) {
            final DuopodEvaluationScenario scenario = this.manifest.scenarios().get(slot % this.manifest.scenarios().size());
            final var target = scenario.pointTarget();
            return new EpisodeDefinition(
                    (slot * 1_000_000L) + episodeIndex,
                    9_000_001L + 65_537L * slot + episodeIndex,
                    EnvironmentTaskMode.POINT_GOAL,
                    new CurriculumStage(scenario.terrainStage(), slot, scenario.id()),
                    TARGET_COMMAND_GENERATOR.commandForLocalOffset(target.localForwardBlocks(), target.localRightBlocks()),
                    this.maximumControlSteps,
                    target.initialDistanceBlocks(),
                    DuopodTrainingScenarios.DEFAULT_GAIT_FREQUENCY_HZ,
                    TerrainProfile.none(),
                    target);
        }

        private boolean allCompleted() {
            for (final FirstTerminalSnapshot<JsonObject> snapshot : this.episodeSnapshots) {
                if (!snapshot.completed()) {
                    return false;
                }
            }
            return true;
        }

        void close(final ServerLevel level) {
            if (this.environment != null) {
                this.environment.close();
                this.environment = null;
            }
            this.stepPending = false;
            this.ticksUntilFinish = 0;
        }

        UUID evaluationId() {
            return this.evaluationId;
        }

        ResourceKey<Level> dimension() {
            return this.dimension;
        }

        DuopodEvaluationManifest manifest() {
            return this.manifest;
        }

        String lastFailure() {
            return this.lastFailure;
        }
    }

    static final class TargetChangeValidationRun {
        private final UUID validationId;
        private final UUID ownerId;
        private final ResourceKey<Level> dimension;
        private final BlockPos origin;
        private final Direction forwardDirection;
        private final UUID batchId;
        private final double[] genome;
        private final int controlTicks;
        private final int switchControlStep;
        private final int maximumControlSteps;
        private final GenomePolicy policy;
        private MinecraftMachinesDuopodTrainingMorphology morphology;
        private MinecraftMachinesLiveDuopod machine;
        private Vec3 targetPosition;
        private double targetForwardBlocks;
        private double targetRightBlocks;
        private double[] previousAction = new double[DuopodSchemas.actionSpec().size()];
        private int controlStep;
        private int elapsedTicks;
        private int ticksUntilNextControl;
        private double phaseRad;
        private boolean switched;
        private UUID initialMachineId;
        private UUID switchMachineId;
        private UUID finalMachineId;
        private TargetChangeSnapshot beforeSwitch;
        private TargetChangeSnapshot afterSwitch;
        private TargetChangeSnapshot postSwitchResponse;
        private String lastFailure = "";

        TargetChangeValidationRun(
                final UUID validationId,
                final UUID ownerId,
                final ResourceKey<Level> dimension,
                final BlockPos origin,
                final Direction forwardDirection,
                final UUID batchId,
                final double[] genome,
                final int controlTicks,
                final int switchControlStep,
                final int maximumControlSteps
        ) {
            if (maximumControlSteps <= switchControlStep + 1) {
                throw new IllegalArgumentException("target-change validation needs at least one post-switch response step");
            }
            this.validationId = validationId;
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.origin = origin.immutable();
            this.forwardDirection = forwardDirection;
            this.batchId = batchId;
            this.genome = Arrays.copyOf(genome, genome.length);
            this.controlTicks = controlTicks;
            this.switchControlStep = switchControlStep;
            this.maximumControlSteps = maximumControlSteps;
            this.policy = duopodPolicy(DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE);
            if (this.genome.length != this.policy.genomeSize()) {
                throw new IllegalArgumentException("target-change validation genome length does not match current Duopod policy");
            }
        }

        boolean start(final ServerLevel level) {
            this.close(level);
            this.morphology = new MinecraftMachinesDuopodTrainingMorphology(
                    level,
                    this.origin,
                    this.forwardDirection,
                    this.batchId,
                    0);
            try {
                this.machine = this.morphology.spawn(new TrainingSpawnContext(
                        0,
                        0L,
                        level.getGameTime(),
                        new CurriculumStage("duopod_target_change_validation", 0, "target-change validation"),
                        TerrainProfile.none()));
                this.initialMachineId = this.machine.control().duopod().machineId();
                this.targetForwardBlocks = TARGET_CHANGE_FORWARD_BLOCKS;
                this.targetRightBlocks = -TARGET_CHANGE_RIGHT_BLOCKS;
                this.targetPosition = this.targetRelativeToBody(this.targetForwardBlocks, this.targetRightBlocks);
                this.previousAction = new double[this.policy.actionSize()];
                this.controlStep = 0;
                this.elapsedTicks = 0;
                this.ticksUntilNextControl = 0;
                this.phaseRad = 0.0;
                this.switched = false;
                this.switchMachineId = null;
                this.finalMachineId = null;
                this.beforeSwitch = null;
                this.afterSwitch = null;
                this.postSwitchResponse = null;
                return true;
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                this.close(level);
                return false;
            }
        }

        boolean tick(final MinecraftServer server) {
            final ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_TARGET_CHANGE_END validation={} reason=missing_level", this.validationId);
                return false;
            }
            if (this.machine == null || !this.machine.control().isValid()) {
                this.lastFailure = "validation Duopod became invalid";
                this.writeResults(server, level, "machine_invalid");
                return false;
            }

            this.elapsedTicks++;

            if (this.ticksUntilNextControl > 0) {
                this.ticksUntilNextControl--;
                return true;
            }

            if (this.controlStep >= this.maximumControlSteps) {
                this.writeResults(server, level, "complete");
                return false;
            }

            if (this.switched && this.afterSwitch != null && this.postSwitchResponse == null && this.controlStep > this.switchControlStep) {
                this.postSwitchResponse = this.snapshot("post_switch_response", this.previousAction);
            }

            if (!this.switched && this.controlStep >= this.switchControlStep) {
                this.beforeSwitch = this.snapshot("before_switch", this.previousAction);
                this.targetForwardBlocks = TARGET_CHANGE_FORWARD_BLOCKS;
                this.targetRightBlocks = TARGET_CHANGE_RIGHT_BLOCKS;
                this.targetPosition = this.targetRelativeToBody(this.targetForwardBlocks, this.targetRightBlocks);
                this.switched = true;
                this.switchMachineId = this.machine.control().duopod().machineId();
            }

            final EpisodeDefinition episode = this.currentEpisode();
            final EpisodeRuntime runtime = this.runtime();
            final double[] observation = this.morphology.observe(this.machine, episode, runtime);
            final double[] action = rampInitialAction(this.policy.action(this.genome, observation), this.controlStep);
            this.morphology.applyAction(this.machine, action);
            if (this.switched && this.afterSwitch == null) {
                this.afterSwitch = this.snapshot("after_switch", action);
                this.logSwitch();
            }
            this.previousAction = Arrays.copyOf(action, action.length);
            this.controlStep++;
            this.phaseRad = positiveModulo(
                    this.phaseRad + Math.PI * 2.0 * DuopodTrainingScenarios.DEFAULT_GAIT_FREQUENCY_HZ * this.controlTicks / 20.0,
                    Math.PI * 2.0);
            this.ticksUntilNextControl = this.controlTicks;
            return true;
        }

        private void logSwitch() {
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_TARGET_CHANGE_SWITCH validation={} oldBearing={} newBearing={} desiredYawBefore={} desiredYawAfter={} observedYawBefore={} leftActionBefore={} rightActionBefore={} leftActionAfter={} rightActionAfter={}",
                    this.validationId,
                    format(-targetBearingDegrees()),
                    format(targetBearingDegrees()),
                    format(this.beforeSwitch == null ? 0.0 : this.beforeSwitch.desiredYawRate()),
                    format(this.afterSwitch == null ? 0.0 : this.afterSwitch.desiredYawRate()),
                    format(this.beforeSwitch == null ? 0.0 : this.beforeSwitch.observedYawRate()),
                    format(this.beforeSwitch == null ? 0.0 : this.beforeSwitch.leftAction()),
                    format(this.beforeSwitch == null ? 0.0 : this.beforeSwitch.rightAction()),
                    format(this.afterSwitch == null ? 0.0 : this.afterSwitch.leftAction()),
                    format(this.afterSwitch == null ? 0.0 : this.afterSwitch.rightAction()));
        }

        private void writeResults(final MinecraftServer server, final ServerLevel level, final String reason) {
            this.finalMachineId = this.machine == null ? null : this.machine.control().duopod().machineId();
            final TargetChangeSnapshot response = this.postSwitchResponse == null ? this.afterSwitch : this.postSwitchResponse;
            final JsonObject payload = new JsonObject();
            payload.addProperty("format", "minecraft_machines_duopod_cem_target_change_v1");
            payload.addProperty("validation_id", this.validationId.toString());
            payload.addProperty("reason", reason);
            payload.addProperty("control_steps", this.controlStep);
            payload.add("policy", this.policyMetadata());
            payload.add("config", this.configJson());
            payload.add("machine", this.machineJson());
            payload.add("before_switch", this.beforeSwitch == null ? JsonNull.INSTANCE : this.beforeSwitch.toJson());
            payload.add("after_switch", this.afterSwitch == null ? JsonNull.INSTANCE : this.afterSwitch.toJson());
            payload.add("post_switch_response", response == null ? JsonNull.INSTANCE : response.toJson());
            payload.add("summary", this.summaryJson(response));

            final Path path = targetChangeValidationPath(server);
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(payload, writer);
                }
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_TARGET_CHANGE_END validation={} reason={} path={} sameMachine={} yawDelta={}",
                        this.validationId,
                        reason,
                        path,
                        this.sameMachine(),
                        format(this.observedYawDelta(response)));
                final ServerPlayer player = owner(server, this.ownerId);
                if (player != null) {
                    player.sendSystemMessage(Component.literal("Duopod target-change validation complete: sameMachine=%s yawDelta=%.3f saved %s"
                            .formatted(this.sameMachine(), this.observedYawDelta(response), path)));
                }
            } catch (final IOException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info("MM_DUOPOD_CEM_TARGET_CHANGE_END validation={} reason=write_failed failure={}", this.validationId, this.lastFailure);
            } finally {
                this.close(level);
            }
        }

        private JsonObject configJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("control_ticks", this.controlTicks);
            json.addProperty("switch_control_step", this.switchControlStep);
            json.addProperty("maximum_control_steps", this.maximumControlSteps);
            json.add("old_target", targetJson(TARGET_CHANGE_FORWARD_BLOCKS, -TARGET_CHANGE_RIGHT_BLOCKS));
            json.add("new_target", targetJson(TARGET_CHANGE_FORWARD_BLOCKS, TARGET_CHANGE_RIGHT_BLOCKS));
            return json;
        }

        private JsonObject machineJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("initial_machine_id", this.initialMachineId == null ? "" : this.initialMachineId.toString());
            json.addProperty("switch_machine_id", this.switchMachineId == null ? "" : this.switchMachineId.toString());
            json.addProperty("final_machine_id", this.finalMachineId == null ? "" : this.finalMachineId.toString());
            json.addProperty("same_machine", this.sameMachine());
            return json;
        }

        private JsonObject summaryJson(final TargetChangeSnapshot response) {
            final JsonObject json = new JsonObject();
            json.addProperty("old_target_bearing_degrees", -targetBearingDegrees());
            json.addProperty("new_target_bearing_degrees", targetBearingDegrees());
            json.addProperty("desired_yaw_before", this.beforeSwitch == null ? 0.0 : this.beforeSwitch.desiredYawRate());
            json.addProperty("desired_yaw_after", this.afterSwitch == null ? 0.0 : this.afterSwitch.desiredYawRate());
            json.addProperty("desired_yaw_delta", this.desiredYawDelta());
            json.addProperty("observed_yaw_before", this.beforeSwitch == null ? 0.0 : this.beforeSwitch.observedYawRate());
            json.addProperty("observed_yaw_after", response == null ? 0.0 : response.observedYawRate());
            json.addProperty("observed_yaw_response_delta", this.observedYawDelta(response));
            json.addProperty("left_action_before", this.beforeSwitch == null ? 0.0 : this.beforeSwitch.leftAction());
            json.addProperty("right_action_before", this.beforeSwitch == null ? 0.0 : this.beforeSwitch.rightAction());
            json.addProperty("left_action_after", this.afterSwitch == null ? 0.0 : this.afterSwitch.leftAction());
            json.addProperty("right_action_after", this.afterSwitch == null ? 0.0 : this.afterSwitch.rightAction());
            json.addProperty("same_machine", this.sameMachine());
            json.addProperty("phase_continued", this.afterSwitch != null && this.beforeSwitch != null && this.afterSwitch.phaseRad() >= this.beforeSwitch.phaseRad());
            return json;
        }

        private JsonObject policyMetadata() {
            final JsonObject policyJson = new JsonObject();
            policyJson.addProperty("policy_type", "linear_tanh");
            policyJson.addProperty("morphology_id", MinecraftMachines.MOD_ID + ":" + DuopodInstance.MORPHOLOGY_TYPE);
            policyJson.addProperty("observation_schema_hash", DuopodSchemas.observationSpec().compatibilityHash());
            policyJson.addProperty("action_schema_hash", DuopodSchemas.actionSpec().compatibilityHash());
            policyJson.addProperty("genome_size", this.policy.genomeSize());
            return policyJson;
        }

        private JsonObject targetJson(final double forwardBlocks, final double rightBlocks) {
            final JsonObject json = new JsonObject();
            json.addProperty("forward_blocks", forwardBlocks);
            json.addProperty("right_blocks", rightBlocks);
            json.addProperty("bearing_degrees", Math.toDegrees(Math.atan2(rightBlocks, forwardBlocks)));
            json.addProperty("distance_blocks", Math.sqrt((forwardBlocks * forwardBlocks) + (rightBlocks * rightBlocks)));
            return json;
        }

        private TargetChangeSnapshot snapshot(final String label, final double[] action) {
            final EpisodeDefinition episode = this.currentEpisode();
            final EpisodeRuntime runtime = this.runtime();
            final Map<String, Object> diagnostics = this.morphology.diagnosticInfo(
                    this.machine,
                    episode,
                    runtime,
                    this.previousAction,
                    action);
            return new TargetChangeSnapshot(
                    label,
                    this.controlStep,
                    this.elapsedTicks,
                    this.phaseRad,
                    this.targetForwardBlocks,
                    this.targetRightBlocks,
                    Math.toDegrees(Math.atan2(this.targetRightBlocks, this.targetForwardBlocks)),
                    episode.command().desiredForwardVelocity(),
                    episode.command().desiredYawRate(),
                    number(diagnostics, "local_yaw_rate"),
                    action.length > 0 ? action[0] : 0.0,
                    action.length > 1 ? action[1] : 0.0,
                    this.machine.control().duopod().machineId(),
                    GSON.toJsonTree(diagnostics).getAsJsonObject());
        }

        private EpisodeRuntime runtime() {
            return new EpisodeRuntime(
                    this.controlStep,
                    this.controlStep,
                    this.controlStep,
                    this.elapsedTicks,
                    this.phaseRad,
                    this.controlTicks / 20.0,
                    this.previousAction,
                    0.0,
                    0.0,
                    Map.of(),
                    0);
        }

        private EpisodeDefinition currentEpisode() {
            final LocomotionCommand command = TARGET_COMMAND_GENERATOR.commandForTarget(
                    this.machine.control().getBasePosition(),
                    this.machine.control().getBaseOrientation(),
                    this.machine.control().duopod().modelForwardDirection(),
                    this.targetPosition);
            return new EpisodeDefinition(
                    this.controlStep,
                    this.controlStep,
                    EnvironmentTaskMode.COMMAND_TRACKING,
                    new CurriculumStage("duopod_target_change_validation", 0, "target-change validation"),
                    command,
                    this.maximumControlSteps,
                    0.0,
                    DuopodTrainingScenarios.DEFAULT_GAIT_FREQUENCY_HZ,
                    TerrainProfile.none());
        }

        private Vec3 targetRelativeToBody(final double forwardBlocks, final double rightBlocks) {
            return DuopodKinematics.horizontalTargetPosition(
                    this.machine.control().getBasePosition(),
                    this.machine.control().getBaseOrientation(),
                    this.machine.control().duopod().modelForwardDirection(),
                    forwardBlocks,
                    rightBlocks);
        }

        private boolean sameMachine() {
            return this.initialMachineId != null
                    && this.finalMachineId != null
                    && this.initialMachineId.equals(this.finalMachineId)
                    && (this.switchMachineId == null || this.initialMachineId.equals(this.switchMachineId));
        }

        private double desiredYawDelta() {
            return (this.afterSwitch == null ? 0.0 : this.afterSwitch.desiredYawRate())
                    - (this.beforeSwitch == null ? 0.0 : this.beforeSwitch.desiredYawRate());
        }

        private double observedYawDelta(final TargetChangeSnapshot response) {
            return (response == null ? 0.0 : response.observedYawRate())
                    - (this.beforeSwitch == null ? 0.0 : this.beforeSwitch.observedYawRate());
        }

        void close(final ServerLevel level) {
            if (this.morphology != null && this.machine != null) {
                this.morphology.destroy(this.machine);
            }
            this.morphology = null;
            this.machine = null;
        }

        UUID validationId() {
            return this.validationId;
        }

        ResourceKey<Level> dimension() {
            return this.dimension;
        }

        String lastFailure() {
            return this.lastFailure;
        }

        private static double targetBearingDegrees() {
            return Math.toDegrees(Math.atan2(TARGET_CHANGE_RIGHT_BLOCKS, TARGET_CHANGE_FORWARD_BLOCKS));
        }

        private static double number(final Map<String, Object> diagnostics, final String key) {
            final Object value = diagnostics.get(key);
            if (value instanceof final Number number) {
                final double result = number.doubleValue();
                return Double.isFinite(result) ? result : 0.0;
            }
            return 0.0;
        }
    }

    private record TargetChangeSnapshot(
            String label,
            int controlStep,
            int elapsedTicks,
            double phaseRad,
            double targetForwardBlocks,
            double targetRightBlocks,
            double targetBearingDegrees,
            double desiredForwardVelocity,
            double desiredYawRate,
            double observedYawRate,
            double leftAction,
            double rightAction,
            UUID machineId,
            JsonObject diagnostics
    ) {
        private JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("label", this.label);
            json.addProperty("control_step", this.controlStep);
            json.addProperty("elapsed_ticks", this.elapsedTicks);
            json.addProperty("phase_rad", this.phaseRad);
            json.addProperty("target_forward_blocks", this.targetForwardBlocks);
            json.addProperty("target_right_blocks", this.targetRightBlocks);
            json.addProperty("target_bearing_degrees", this.targetBearingDegrees);
            json.addProperty("desired_forward_velocity", this.desiredForwardVelocity);
            json.addProperty("desired_yaw_rate", this.desiredYawRate);
            json.addProperty("observed_yaw_rate", this.observedYawRate);
            json.addProperty("left_action", this.leftAction);
            json.addProperty("right_action", this.rightAction);
            json.addProperty("machine_id", this.machineId.toString());
            json.add("diagnostics", this.diagnostics);
            return json;
        }
    }

    private static final class LookTargetMode {
        private final UUID playerId;
        private BlockPos lastBlock;
        private int ticksUntilNextUpdate;

        private LookTargetMode(final UUID playerId) {
            this.playerId = playerId;
        }
    }

    private static final class RewardVisualizationFrame {
        private int samples;
        private double reward;
        private double positiveComponents;
        private double negativeComponents;
        private double desiredForward;
        private double desiredLateral;
        private double desiredYaw;
        private double actualForward;
        private double actualLateral;
        private double actualYaw;
        private double forwardError;
        private double lateralError;
        private double yawError;

        private void add(final double reward, final Map<String, Object> info) {
            this.samples++;
            this.reward += reward;
            this.positiveComponents += positiveRewardComponentSum(info);
            this.negativeComponents += negativeRewardComponentSum(info);
            final double desiredForward = finiteNumberOrDefault(info, "desired_forward_velocity", 0.0);
            final double desiredLateral = finiteNumberOrDefault(info, "desired_lateral_velocity", 0.0);
            final double desiredYaw = finiteNumberOrDefault(info, "desired_yaw_rate", 0.0);
            final double actualForward = finiteNumberOrDefault(info, "local_forward_velocity", 0.0);
            final double actualLateral = finiteNumberOrDefault(info, "local_lateral_velocity", 0.0);
            final double actualYaw = finiteNumberOrDefault(info, "local_yaw_rate", 0.0);
            this.desiredForward += desiredForward;
            this.desiredLateral += desiredLateral;
            this.desiredYaw += desiredYaw;
            this.actualForward += actualForward;
            this.actualLateral += actualLateral;
            this.actualYaw += actualYaw;
            this.forwardError += Math.abs(actualForward - desiredForward);
            this.lateralError += Math.abs(actualLateral - desiredLateral);
            this.yawError += Math.abs(actualYaw - desiredYaw);
        }

        private boolean hasSamples() {
            return this.samples > 0;
        }

        private Component message() {
            final double n = Math.max(1, this.samples);
            return Component.literal(String.format(Locale.ROOT,
                    "Goal F/L/Y %.2f/%.2f/%.2f | Actual %.2f/%.2f/%.2f | Reward %+.3f Good %+.3f Bad %+.3f | Err %.2f/%.2f/%.2f",
                    this.desiredForward / n,
                    this.desiredLateral / n,
                    this.desiredYaw / n,
                    this.actualForward / n,
                    this.actualLateral / n,
                    this.actualYaw / n,
                    this.reward / n,
                    this.positiveComponents / n,
                    this.negativeComponents / n,
                    this.forwardError / n,
                    this.lateralError / n,
                    this.yawError / n));
        }
    }

    private static final class ReplayRun {
        private final UUID ownerId;
        private final ResourceKey<Level> dimension;
        private final MinecraftMachinesDuopodTrainingMorphology morphology;
        private final MinecraftMachinesLiveDuopod machine;
        private final GenomePolicy policy;
        private final double[] genome;
        private final String curriculumStage;
        private final int controlTicks;
        private final int spawnWarmupTicks;
        private Vec3 targetPosition;
        private double[] previousAction = new double[DuopodSchemas.actionSpec().size()];
        private double[] actionBeforePrevious = new double[DuopodSchemas.actionSpec().size()];
        private double[] handoffObservation;
        private double phaseRad;
        private int controlStep;
        private int elapsedTicks;
        private int ticksUntilNextControl;
        private int spawnWarmupTicksRemaining;

        private ReplayRun(
                final UUID ownerId,
                final ResourceKey<Level> dimension,
                final MinecraftMachinesDuopodTrainingMorphology morphology,
                final MinecraftMachinesLiveDuopod machine,
                final GenomePolicy policy,
                final double[] genome,
                final String curriculumStage,
                final int controlTicks,
                final int spawnWarmupTicks,
                final Vec3 targetPosition
        ) {
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.morphology = morphology;
            this.machine = machine;
            this.policy = policy;
            this.genome = Arrays.copyOf(genome, genome.length);
            this.curriculumStage = curriculumStage == null || curriculumStage.isBlank()
                    ? DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE
                    : DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage);
            if (controlTicks < 1) {
                throw new IllegalArgumentException("replay controlTicks must be positive");
            }
            this.controlTicks = controlTicks;
            if (spawnWarmupTicks < 0) {
                throw new IllegalArgumentException("replay spawn warmup ticks must be non-negative");
            }
            this.spawnWarmupTicks = spawnWarmupTicks;
            this.spawnWarmupTicksRemaining = spawnWarmupTicks;
            if (this.genome.length != this.policy.genomeSize()) {
                throw new IllegalArgumentException("replay genome length does not match policy " + policyType(this.policy));
            }
            this.targetPosition = targetPosition;
        }

        static ReplayRun spawn(
                final ServerLevel level,
                final UUID ownerId,
                final BlockPos origin,
                final Direction forwardDirection,
                final double[] genome,
                final String curriculumStage,
                final String policyType,
                final int controlTicks,
                final int spawnWarmupTicks
        ) {
            final String stage = curriculumStage == null || curriculumStage.isBlank()
                    ? DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE
                    : DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage);
            final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                    level,
                    origin,
                    forwardDirection,
                    UUID.randomUUID(),
                    0);
            final MinecraftMachinesLiveDuopod machine = morphology.spawn(new TrainingSpawnContext(
                    0,
                    0L,
                    level.getGameTime(),
                    new CurriculumStage(stage, 0, "replay"),
                    TerrainProfile.none()));
            final Vec3 target = defaultTarget(machine);
            final ReplayRun replay = new ReplayRun(
                    ownerId,
                    level.dimension(),
                    morphology,
                    machine,
                    duopodPolicy(stage, policyType),
                    genome,
                    stage,
                    controlTicks,
                    spawnWarmupTicks,
                    target);
            replay.machine.control().commandNeutral();
            if (replay.spawnWarmupTicks == 0) {
                replay.captureNeutralHandoffObservation();
            }
            return replay;
        }

        boolean tick(final MinecraftServer server) {
            final ServerLevel level = server.getLevel(this.dimension);
            if (level == null || !this.machine.control().isValid()) {
                this.clear();
                return false;
            }

            if (this.spawnWarmupTicksRemaining > 0) {
                this.spawnWarmupTicksRemaining--;
                if (this.spawnWarmupTicksRemaining == 0) {
                    this.captureNeutralHandoffObservation();
                }
                return true;
            }

            this.elapsedTicks++;

            if (this.ticksUntilNextControl > 0) {
                this.ticksUntilNextControl--;
                return true;
            }

            final EpisodeDefinition episode = this.currentEpisode();
            final EpisodeRuntime runtime = this.runtime(episode);
            if (this.controlStep > 0 && shouldRenderRewardVisualization(this.controlStep)) {
                final Map<String, Object> info = new java.util.LinkedHashMap<>(this.morphology.diagnosticInfo(
                        this.machine,
                        episode,
                        runtime,
                        this.actionBeforePrevious,
                        this.previousAction));
                final RewardBreakdown reward = this.morphology.calculateReward(this.machine, episode, runtime, this.actionBeforePrevious, this.previousAction);
                info.put("reward_components", reward.components());
                renderRewardVisualization(level, this.machine.control().duopod().modelForwardDirection(), reward.total(), info);
                final RewardVisualizationFrame frame = new RewardVisualizationFrame();
                frame.add(reward.total(), info);
                sendRewardVisualizationMessage(server, this.ownerId, this.dimension, frame);
            }
            this.morphology.beforeControlStep(this.machine, episode, runtime);
            final double[] observation = this.handoffObservation == null
                    ? this.morphology.observe(this.machine, episode, runtime)
                    : this.handoffObservation;
            this.handoffObservation = null;
            final double[] action = curriculumAction(
                    this.curriculumStage,
                    rampInitialAction(this.policy.action(this.genome, observation), this.controlStep),
                    this.previousAction);
            this.morphology.applyAction(this.machine, action);
            this.actionBeforePrevious = Arrays.copyOf(this.previousAction, this.previousAction.length);
            this.previousAction = action;
            this.controlStep++;
            this.phaseRad = positiveModulo(
                    this.phaseRad + replayPhaseAdvanceRadians(this.controlTicks, episode.gaitFrequencyHz()),
                    Math.PI * 2.0);
            this.ticksUntilNextControl = replayControlHoldCountdown(this.controlTicks);
            return true;
        }

        private void captureNeutralHandoffObservation() {
            this.machine.control().commandNeutral();
            final EpisodeDefinition episode = this.currentEpisode();
            final EpisodeRuntime runtime = this.runtime(episode);
            this.morphology.onEpisodeUpdated(this.machine, episode);
            this.handoffObservation = this.morphology.observe(this.machine, episode, runtime);
        }

        void setTargetRelative(final double forwardBlocks, final double rightBlocks) {
            this.targetPosition = DuopodKinematics.horizontalTargetPosition(
                    this.machine.control().getBasePosition(),
                    this.machine.control().getBaseOrientation(),
                    this.machine.control().duopod().modelForwardDirection(),
                    forwardBlocks,
                    rightBlocks);
        }

        void setTargetWorld(final Vec3 targetPosition) {
            this.targetPosition = targetPosition;
        }

        DuopodKinematics.LocalOffset targetLocalOffset() {
            return DuopodKinematics.horizontalLocalOffset(
                    this.machine.control().getBasePosition(),
                    this.machine.control().getBaseOrientation(),
                    this.machine.control().duopod().modelForwardDirection(),
                    this.targetPosition);
        }

        Vec3 targetPosition() {
            return this.targetPosition;
        }

        ResourceKey<Level> dimension() {
            return this.dimension;
        }

        boolean usesTarget() {
            return !DuopodTrainingScenarios.isBalanceCurriculumStage(this.curriculumStage)
                    && !DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(this.curriculumStage);
        }

        void clear() {
            this.morphology.destroy(this.machine);
        }

        private EpisodeDefinition currentEpisode() {
            if (!this.usesTarget()) {
                if (DuopodTrainingScenarios.WALK_FORWARD_STAGE.equals(this.curriculumStage)) {
                    return DuopodTrainingScenarios.commandEpisode(
                            DuopodTrainingScenarios.WALK_FORWARD_STAGE,
                            this.controlStep,
                            this.controlStep,
                            0,
                            Integer.MAX_VALUE,
                            1);
                }
                return new EpisodeDefinition(
                        this.controlStep,
                        this.controlStep,
                        EnvironmentTaskMode.BALANCE,
                        new CurriculumStage(this.curriculumStage, 0, "balance replay"),
                        LocomotionCommand.ZERO,
                        Integer.MAX_VALUE,
                        0.0,
                        DuopodTrainingScenarios.DEFAULT_GAIT_FREQUENCY_HZ,
                        TerrainProfile.none());
            }
            final LocomotionCommand command = TARGET_COMMAND_GENERATOR.commandForTarget(
                    this.machine.control().getBasePosition(),
                    this.machine.control().getBaseOrientation(),
                    this.machine.control().duopod().modelForwardDirection(),
                    this.targetPosition);
            return new EpisodeDefinition(
                    this.controlStep,
                    this.controlStep,
                    EnvironmentTaskMode.COMMAND_TRACKING,
                    new CurriculumStage("duopod_replay", 0, "target replay"),
                    command,
                    Integer.MAX_VALUE,
                    0.0,
                    DuopodTrainingScenarios.DEFAULT_GAIT_FREQUENCY_HZ,
                    TerrainProfile.none());
        }

        private EpisodeRuntime runtime(final EpisodeDefinition episode) {
            return new EpisodeRuntime(
                    episode.episodeId(),
                    episode.seed(),
                    this.controlStep,
                    this.elapsedTicks,
                    this.phaseRad,
                    this.controlTicks / 20.0,
                    this.previousAction,
                    0.0,
                    0.0,
                    Map.of(),
                    0);
        }

        private static Vec3 defaultTarget(final MinecraftMachinesLiveDuopod machine) {
            return DuopodKinematics.horizontalTargetPosition(
                    machine.control().getBasePosition(),
                    machine.control().getBaseOrientation(),
                    machine.control().duopod().modelForwardDirection(),
                    10.0,
                    0.0);
        }
    }

    private record BestGenome(
            double[] genome,
            double meanScore,
            double worstScore,
            double successRate,
            double failureRate,
            double aggregateFitness,
            UUID runId,
            long discoveryRunSeed,
            int generation,
            String createdAt,
            String policyType,
            String curriculumStage,
            Config trainingConfig,
            JsonObject trainingConfigSnapshot,
            String fitnessContract,
            DistributionSnapshot distribution,
            EvaluationMetadata evaluationMetadata
    ) {
        private BestGenome {
            genome = Arrays.copyOf(genome, genome.length);
            curriculumStage = curriculumStage == null || curriculumStage.isBlank()
                    ? DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE
                    : DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage);
            policyType = normalizePolicyType(policyType, curriculumStage);
            trainingConfig = trainingConfig == null ? Config.DEFAULT : trainingConfig;
            trainingConfigSnapshot = trainingConfigSnapshot == null
                    ? trainingConfig.toJson()
                    : trainingConfigSnapshot.deepCopy();
            fitnessContract = fitnessContract == null || fitnessContract.isBlank()
                    ? UNKNOWN_FITNESS_CONTRACT
                    : fitnessContract;
            evaluationMetadata = evaluationMetadata == null ? EvaluationMetadata.defaultHeldOut() : evaluationMetadata;
            if (genome.length != duopodPolicy(curriculumStage, policyType).genomeSize()) {
                throw new IllegalArgumentException("checkpoint genome length mismatch");
            }
            for (final double value : genome) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("checkpoint genome contains a non-finite value");
                }
            }
        }

        @Override
        public double[] genome() {
            return Arrays.copyOf(this.genome, this.genome.length);
        }

        @Override
        public JsonObject trainingConfigSnapshot() {
            return this.trainingConfigSnapshot.deepCopy();
        }

        private String trainingArenaId() {
            return this.trainingConfigSnapshot.has("trainingArena")
                    ? this.trainingConfigSnapshot.get("trainingArena").getAsString()
                    : "unspecified";
        }

        private String trainingSlotLayoutId() {
            return this.trainingConfigSnapshot.has("trainingSlotLayout")
                    ? this.trainingConfigSnapshot.get("trainingSlotLayout").getAsString()
                    : "unspecified";
        }

        private BestGenome withDistribution(final DistributionSnapshot currentDistribution) {
            return new BestGenome(
                    this.genome,
                    this.meanScore,
                    this.worstScore,
                    this.successRate,
                    this.failureRate,
                    this.aggregateFitness,
                    this.runId,
                    this.discoveryRunSeed,
                    this.generation,
                    this.createdAt,
                    this.policyType,
                    this.curriculumStage,
                    this.trainingConfig,
                    this.trainingConfigSnapshot,
                    this.fitnessContract,
                    currentDistribution,
                    this.evaluationMetadata);
        }

        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("format", "minecraft_machines_duopod_cem_checkpoint_v4");
            json.addProperty("formatVersion", 4);
            json.addProperty("createdAt", this.createdAt);
            json.addProperty("policyType", this.policyType);
            json.addProperty("morphologyId", MinecraftMachines.MOD_ID + ":" + DuopodInstance.MORPHOLOGY_TYPE);
            json.add("observationSpec", specJson(
                    DuopodSchemas.observationSpec().schemaId(),
                    DuopodSchemas.observationSpec().schemaVersion(),
                    DuopodSchemas.observationSpec().size(),
                    DuopodSchemas.observationSpec().compatibilityHash()));
            json.add("actionSpec", specJson(
                    DuopodSchemas.actionSpec().schemaId(),
                    DuopodSchemas.actionSpec().schemaVersion(),
                    DuopodSchemas.actionSpec().size(),
                    DuopodSchemas.actionSpec().compatibilityHash()));
            json.addProperty("observationSchemaHash", DuopodSchemas.observationSpec().compatibilityHash());
            json.addProperty("actionSchemaHash", DuopodSchemas.actionSpec().compatibilityHash());
            json.addProperty("curriculumStage", this.curriculumStage);
            json.addProperty("meanScore", this.meanScore);
            json.addProperty("worstScore", this.worstScore);
            json.addProperty("successRate", this.successRate);
            json.addProperty("failureRate", this.failureRate);
            json.addProperty("aggregateFitness", this.aggregateFitness);
            json.addProperty("runId", this.runId.toString());
            // Keep runSeed as the backward-compatible champion-discovery seed. The
            // optimizer can later resume from that champion with a different stream;
            // exposing it separately prevents a save from relabelling old evidence.
            json.addProperty("runSeed", this.discoveryRunSeed);
            json.addProperty("discoveryRunSeed", this.discoveryRunSeed);
            json.addProperty("generation", this.generation);
            json.add("trainingConfig", this.trainingConfigSnapshot.deepCopy());
            json.addProperty("fitnessContract", this.fitnessContract);
            if (this.distribution != null) {
                json.addProperty("optimizerSeed", this.distribution.seed());
                json.add("distribution", this.distribution.toJson());
            }
            json.add("evaluationMetadata", this.evaluationMetadata.toJson());
            final JsonArray genomeArray = new JsonArray(this.genome.length);
            for (final double value : this.genome) {
                genomeArray.add(value);
            }
            json.add("genome", genomeArray);
            return json;
        }

        static BestGenome fromJson(final JsonObject json) {
            final String format = json.get("format").getAsString();
            if (!"minecraft_machines_duopod_cem_checkpoint_v4".equals(format)
                    && !"minecraft_machines_duopod_cem_checkpoint_v3".equals(format)
                    && !"minecraft_machines_duopod_cem_checkpoint_v2".equals(format)
                    && !"minecraft_machines_duopod_cem_best_v1".equals(format)) {
                throw new IllegalArgumentException("unsupported checkpoint format");
            }
            final String observationHash = json.has("observationSchemaHash")
                    ? json.get("observationSchemaHash").getAsString()
                    : json.getAsJsonObject("observationSpec").get("compatibilityHash").getAsString();
            final String actionHash = json.has("actionSchemaHash")
                    ? json.get("actionSchemaHash").getAsString()
                    : json.getAsJsonObject("actionSpec").get("compatibilityHash").getAsString();
            if (!DuopodSchemas.observationSpec().compatibilityHash().equals(observationHash)
                    || !DuopodSchemas.actionSpec().compatibilityHash().equals(actionHash)) {
                throw new IllegalArgumentException("checkpoint schema hash does not match current Duopod schema");
            }
            final JsonArray genomeArray = json.getAsJsonArray("genome");
            final double[] genome = new double[genomeArray.size()];
            for (int i = 0; i < genome.length; i++) {
                genome[i] = genomeArray.get(i).getAsDouble();
            }
            final JsonObject trainingConfigJson = json.has("trainingConfig")
                    ? json.getAsJsonObject("trainingConfig").deepCopy()
                    : Config.DEFAULT.toJson();
            return new BestGenome(
                    genome,
                    json.get("meanScore").getAsDouble(),
                    json.get("worstScore").getAsDouble(),
                    json.get("successRate").getAsDouble(),
                    json.has("failureRate") ? json.get("failureRate").getAsDouble() : 0.0,
                    json.get("aggregateFitness").getAsDouble(),
                    UUID.fromString(json.get("runId").getAsString()),
                    json.has("discoveryRunSeed")
                            ? json.get("discoveryRunSeed").getAsLong()
                            : json.has("runSeed")
                            ? json.get("runSeed").getAsLong()
                            : json.has("distribution")
                            ? json.getAsJsonObject("distribution").get("seed").getAsLong()
                            : 0L,
                    json.get("generation").getAsInt(),
                    json.has("createdAt") ? json.get("createdAt").getAsString() : Instant.now().toString(),
                    json.has("policyType") ? json.get("policyType").getAsString() : LINEAR_TANH_POLICY_TYPE,
                    json.has("curriculumStage") ? json.get("curriculumStage").getAsString() : DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE,
                    Config.fromJsonOrDefault(trainingConfigJson),
                    trainingConfigJson,
                    json.has("fitnessContract") ? json.get("fitnessContract").getAsString() : UNKNOWN_FITNESS_CONTRACT,
                    json.has("distribution") ? DistributionSnapshot.fromJson(json.getAsJsonObject("distribution")) : null,
                    json.has("evaluationMetadata") ? EvaluationMetadata.fromJson(json.getAsJsonObject("evaluationMetadata")) : EvaluationMetadata.defaultHeldOut());
        }
    }

    private record DistributionSnapshot(
            double[] mean,
            double[] standardDeviation,
            double[] lowerBounds,
            double[] upperBounds,
            double[] minimumStandardDeviation,
            double[] maximumStandardDeviation,
            long seed,
            int generation,
            double bestFitness
    ) {
        private DistributionSnapshot {
            final int size = mean.length;
            if (size < 1
                    || standardDeviation.length != size
                    || lowerBounds.length != size
                    || upperBounds.length != size
                    || minimumStandardDeviation.length != size
                    || maximumStandardDeviation.length != size) {
                throw new IllegalArgumentException("checkpoint distribution arrays must have one consistent nonzero length");
            }
            mean = Arrays.copyOf(mean, mean.length);
            standardDeviation = Arrays.copyOf(standardDeviation, standardDeviation.length);
            lowerBounds = Arrays.copyOf(lowerBounds, lowerBounds.length);
            upperBounds = Arrays.copyOf(upperBounds, upperBounds.length);
            minimumStandardDeviation = Arrays.copyOf(minimumStandardDeviation, minimumStandardDeviation.length);
            maximumStandardDeviation = Arrays.copyOf(maximumStandardDeviation, maximumStandardDeviation.length);
            for (int i = 0; i < size; i++) {
                if (!Double.isFinite(mean[i])
                        || !Double.isFinite(standardDeviation[i])
                        || !Double.isFinite(lowerBounds[i])
                        || !Double.isFinite(upperBounds[i])
                        || !Double.isFinite(minimumStandardDeviation[i])
                        || !Double.isFinite(maximumStandardDeviation[i])) {
                    throw new IllegalArgumentException("checkpoint distribution contains a non-finite value at index " + i);
                }
                if (lowerBounds[i] >= upperBounds[i]
                        || standardDeviation[i] < 0.0
                        || minimumStandardDeviation[i] < 0.0
                        || maximumStandardDeviation[i] < minimumStandardDeviation[i]) {
                    throw new IllegalArgumentException("checkpoint distribution contains invalid bounds at index " + i);
                }
            }
            if (generation < 0 || (!Double.isFinite(bestFitness) && bestFitness != Double.NEGATIVE_INFINITY)) {
                throw new IllegalArgumentException("checkpoint distribution generation or best fitness is invalid");
            }
        }

        static DistributionSnapshot from(final ContinuousCemDistribution distribution) {
            return new DistributionSnapshot(
                    distribution.mean(),
                    distribution.standardDeviation(),
                    distribution.lowerBounds(),
                    distribution.upperBounds(),
                    distribution.minimumStandardDeviation(),
                    distribution.maximumStandardDeviation(),
                    distribution.seed(),
                    distribution.generation(),
                    distribution.bestFitness());
        }

        ContinuousCemDistribution toDistribution(final long seed, final double[] bestGenome, final double bestFitness) {
            return new ContinuousCemDistribution(
                    this.mean,
                    this.standardDeviation,
                    this.lowerBounds,
                    this.upperBounds,
                    this.minimumStandardDeviation,
                    this.maximumStandardDeviation,
                    seed,
                    this.generation,
                    bestGenome,
                    bestFitness);
        }

        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.add("mean", doubleArrayJson(this.mean));
            json.add("standardDeviation", doubleArrayJson(this.standardDeviation));
            json.add("lowerBounds", doubleArrayJson(this.lowerBounds));
            json.add("upperBounds", doubleArrayJson(this.upperBounds));
            json.add("minimumStandardDeviation", doubleArrayJson(this.minimumStandardDeviation));
            json.add("maximumStandardDeviation", doubleArrayJson(this.maximumStandardDeviation));
            json.addProperty("seed", this.seed);
            json.addProperty("generation", this.generation);
            json.addProperty("bestFitness", this.bestFitness);
            return json;
        }

        static DistributionSnapshot fromJson(final JsonObject json) {
            return new DistributionSnapshot(
                    readDoubleArray(json, "mean"),
                    readDoubleArray(json, "standardDeviation"),
                    readDoubleArray(json, "lowerBounds"),
                    readDoubleArray(json, "upperBounds"),
                    readDoubleArray(json, "minimumStandardDeviation"),
                    json.has("maximumStandardDeviation")
                            ? readDoubleArray(json, "maximumStandardDeviation")
                            : defaultMaximumStandardDeviation(readDoubleArray(json, "lowerBounds"), readDoubleArray(json, "upperBounds")),
                    json.get("seed").getAsLong(),
                    json.get("generation").getAsInt(),
                    json.get("bestFitness").getAsDouble());
        }
    }

    private record EvaluationMetadata(
            String heldOutManifestId,
            String heldOutManifestHash,
            String latestEvaluationPath
    ) {
        static EvaluationMetadata defaultHeldOut() {
            final DuopodEvaluationManifest manifest = DuopodEvaluationManifests.heldOutPointGoals();
            return new EvaluationMetadata(
                    manifest.id(),
                    manifest.compatibilityHash(),
                    "minecraft_machines/duopod_cem_evaluation_latest.json");
        }

        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("heldOutManifestId", this.heldOutManifestId);
            json.addProperty("heldOutManifestHash", this.heldOutManifestHash);
            json.addProperty("latestEvaluationPath", this.latestEvaluationPath);
            return json;
        }

        static EvaluationMetadata fromJson(final JsonObject json) {
            return new EvaluationMetadata(
                    json.has("heldOutManifestId") ? json.get("heldOutManifestId").getAsString() : DuopodEvaluationManifests.heldOutPointGoals().id(),
                    json.has("heldOutManifestHash") ? json.get("heldOutManifestHash").getAsString() : DuopodEvaluationManifests.heldOutPointGoals().compatibilityHash(),
                    json.has("latestEvaluationPath") ? json.get("latestEvaluationPath").getAsString() : "minecraft_machines/duopod_cem_evaluation_latest.json");
        }
    }

    private static ServerPlayer owner(final MinecraftServer server, final UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(ownerId);
    }

    private static Direction parseHorizontalDirection(final String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    private static double positiveModulo(final double value, final double modulus) {
        final double result = value % modulus;
        return result < 0.0 ? result + modulus : result;
    }
}
