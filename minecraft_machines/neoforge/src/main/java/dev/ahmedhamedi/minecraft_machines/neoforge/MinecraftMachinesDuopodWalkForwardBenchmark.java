package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ResetStrategy;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.GenomePolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchReset;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchStep;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.LocomotionVectorEnvironment;
import dev.ahmedhamedi.minecraft_machines.content.training.evaluation.FirstTerminalSnapshot;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodPhaseGaitPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodSchemas;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodTrainingScenarios;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodWalkForwardBaselines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodWalkForwardBenchmarkAcceptance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Runs the controlled learned-vs-baselines walk-forward benchmark. */
final class MinecraftMachinesDuopodWalkForwardBenchmark {
    static final int DEFAULT_MAX_CONTROL_STEPS = 200;
    static final int DEFAULT_CONTROL_TICKS = 4;
    static final int DEFAULT_SPAWN_WARMUP_TICKS = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final String FORMAT = "minecraft_machines_duopod_walk_forward_benchmark_v3";
    private static final int FORMAT_VERSION = 3;
    private static final String ACCEPTANCE_RULE = "post_warmup_stability_adjusted_anti_ballistic_v2";
    private static final int ACCEPTANCE_RULE_VERSION = 2;
    private static final String LATEST_MANIFEST_FORMAT =
            "minecraft_machines_duopod_walk_forward_benchmark_latest_manifest_v1";
    private static final int LATEST_MANIFEST_FORMAT_VERSION = 1;
    private static final String LATEST_JSON_FILENAME = "duopod_cem_walk_forward_benchmark_latest.json";
    private static final String LATEST_CSV_FILENAME = "duopod_cem_walk_forward_benchmark_latest.csv";
    private static final String LATEST_MANIFEST_FILENAME =
            "duopod_cem_walk_forward_benchmark_latest_manifest.json";
    private static final String IMMUTABLE_ARTIFACT_PREFIX = "duopod_cem_walk_forward_benchmark_";
    static final int SLOT_SPACING_BLOCKS = 12;
    private static final long CHUNK_ACTIVATION_TIMEOUT_TICKS = 200L;
    private static final double PAIRED_ORIGIN_TOLERANCE_BLOCKS = 1.0e-6;
    private static final int INITIAL_ACTION_RAMP_CONTROL_STEPS = 5;
    private static final double ACTION_SATURATION_THRESHOLD = 0.98;
    private static final int SPEED_COUNT = DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.size();
    private static final int CONTROLLER_COUNT = DuopodWalkForwardBenchmarkAcceptance.CONTROLLERS.size();
    private static final int BATCH_SLOT_COUNT = SPEED_COUNT;
    private static final int SLOT_COUNT = CONTROLLER_COUNT * SPEED_COUNT;

    private static BenchmarkRun activeRun;

    private MinecraftMachinesDuopodWalkForwardBenchmark() {
    }

    static int start(
            final CommandSourceStack source,
            final ServerLevel level,
            final UUID ownerId,
            final String ownerName,
            final BlockPos origin,
            final Direction forward,
            final double[] learnedGenome,
            final int controlTicks,
            final int spawnWarmupTicks,
            final int maximumControlSteps,
            final CheckpointMetadata checkpoint
    ) {
        if (activeRun != null) {
            source.sendFailure(Component.literal("A Duopod walk-forward benchmark is already active."));
            return 0;
        }
        final BenchmarkRun run;
        try {
            run = new BenchmarkRun(
                    ownerId,
                    level.dimension(),
                    origin,
                    forward,
                    learnedGenome,
                    controlTicks,
                    spawnWarmupTicks,
                    maximumControlSteps,
                    checkpoint);
        } catch (final IllegalArgumentException e) {
            source.sendFailure(Component.literal("Cannot start Duopod walk-forward benchmark: " + e.getMessage()));
            return 0;
        }
        if (!run.start(level)) {
            source.sendFailure(Component.literal("Cannot start Duopod walk-forward benchmark: " + run.lastFailure()));
            run.close();
            return 0;
        }
        activeRun = run;
        MinecraftMachines.LOGGER.info(
                "MM_DUOPOD_WALK_FORWARD_BENCHMARK_BEGIN benchmarkId={} player={} dimension={} origin={} forward={} controlTicks={} warmupTicks={} maxControlSteps={} slots={}",
                run.benchmarkId,
                ownerName,
                level.dimension().location(),
                origin.toShortString(),
                forward,
                controlTicks,
                spawnWarmupTicks,
                maximumControlSteps,
                SLOT_COUNT);
        source.sendSuccess(() -> Component.literal(
                "Started fixed Duopod walk-forward benchmark %s: learned, neutral, and scripted baselines at 0.50/0.80/1.10 speed (%d episodes)."
                        .formatted(run.benchmarkId, SLOT_COUNT)), true);
        return 1;
    }

    static void tick(final MinecraftServer server) {
        final BenchmarkRun run = activeRun;
        if (run != null && !run.tick(server)) {
            if (run.close()) {
                activeRun = null;
            }
        }
    }

    static int stopActive(final MinecraftServer server, final String reason) {
        final BenchmarkRun run = activeRun;
        if (run == null) {
            return 0;
        }
        if (run.close()) {
            activeRun = null;
            MinecraftMachines.LOGGER.info("MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason={}", reason);
        } else {
            MinecraftMachines.LOGGER.warn(
                    "MM_DUOPOD_WALK_FORWARD_BENCHMARK_CLEANUP_PENDING reason={} failure={}",
                    reason,
                    run.lastFailure());
        }
        return 1;
    }

    static boolean hasActiveRun() {
        return activeRun != null;
    }

    record CheckpointMetadata(
            UUID runId,
            int generation,
            double aggregateFitness,
            double failureRate,
            String createdAt,
            String fitnessContract,
            String trainingArenaId,
            String trainingSlotLayoutId,
            int trainingSlotSpacingBlocks,
            int trainingControlTicks,
            int trainingSpawnWarmupTicks,
            int trainingMaximumControlSteps
    ) {
        CheckpointMetadata(
                final UUID runId,
                final int generation,
                final double aggregateFitness,
                final double failureRate,
                final String createdAt
        ) {
            this(
                    runId,
                    generation,
                    aggregateFitness,
                    failureRate,
                    createdAt,
                    "unspecified",
                    "unspecified",
                    "unspecified",
                    0,
                    0,
                    0,
                    0);
        }

        CheckpointMetadata {
            createdAt = createdAt == null ? "" : createdAt;
            fitnessContract = fitnessContract == null || fitnessContract.isBlank()
                    ? "unspecified"
                    : fitnessContract;
            trainingArenaId = trainingArenaId == null || trainingArenaId.isBlank()
                    ? "unspecified"
                    : trainingArenaId;
            trainingSlotLayoutId = trainingSlotLayoutId == null || trainingSlotLayoutId.isBlank()
                    ? "unspecified"
                    : trainingSlotLayoutId;
        }
    }

    private static final class BenchmarkRun {
        private final UUID ownerId;
        private final UUID benchmarkId = UUID.randomUUID();
        private final ResourceKey<Level> dimension;
        private final BlockPos origin;
        private final Direction forwardDirection;
        private final double[] learnedGenome;
        private final int controlTicks;
        private final int spawnWarmupTicks;
        private final int maximumControlSteps;
        private final CheckpointMetadata checkpoint;
        private final GenomePolicy learnedPolicy = new DuopodPhaseGaitPolicy();
        private final String genomeSha256;
        private final List<FirstTerminalSnapshot<Map<String, Object>>> snapshots = new ArrayList<>(SLOT_COUNT);
        private final double[] episodeReturns = new double[SLOT_COUNT];
        private final double[] initialBaseY = new double[SLOT_COUNT];
        private final double[] minimumBodyUp = new double[SLOT_COUNT];
        private final double[] peakVerticalExcursion = new double[SLOT_COUNT];
        private final double[] actionTotalVariation = new double[SLOT_COUNT];
        private final long[] saturatedActionValues = new long[SLOT_COUNT];
        private final long[] actionValueCounts = new long[SLOT_COUNT];
        private final double[][] previousActions = new double[SLOT_COUNT][DuopodSchemas.actionSpec().size()];
        private final boolean[] metricSamplesValid = new boolean[SLOT_COUNT];
        private final int[] completedControlSteps = new int[SLOT_COUNT];
        private final long[] episodeIds = new long[SLOT_COUNT];
        private final long[] episodeSeeds = new long[SLOT_COUNT];
        private final double[][] spawnOrigins = new double[SLOT_COUNT][3];
        private final double[][] postWarmupOrigins = new double[SLOT_COUNT][3];
        private final double[] postWarmupForwardBaselines = new double[SLOT_COUNT];
        private final double[] postWarmupLateralBaselines = new double[SLOT_COUNT];
        private final boolean[] arenaEscapes = new boolean[SLOT_COUNT];
        private final long[] physicsTickPoseSamples = new long[SLOT_COUNT];

        private LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod> environment;
        private MinecraftMachinesTrainingChunkLease chunkLease;
        private MinecraftMachinesDuopodFlatArena flatArena;
        private double[][] observations;
        private String worldName;
        private long worldSeed;
        private long chunkActivationStartedGameTime;
        private int currentControllerIndex;
        private int warmupTicksRemaining;
        private int finishedControlSteps;
        private int ticksUntilFinish;
        private boolean spawnPending;
        private boolean stepPending;
        private boolean cleanupPending;
        private boolean closed;
        private String lastFailure = "unknown failure";

        private BenchmarkRun(
                final UUID ownerId,
                final ResourceKey<Level> dimension,
                final BlockPos origin,
                final Direction forwardDirection,
                final double[] learnedGenome,
                final int controlTicks,
                final int spawnWarmupTicks,
                final int maximumControlSteps,
                final CheckpointMetadata checkpoint
        ) {
            if (forwardDirection.getAxis().isVertical()) {
                throw new IllegalArgumentException("forward direction must be horizontal");
            }
            if (learnedGenome == null || learnedGenome.length != this.learnedPolicy.genomeSize()) {
                throw new IllegalArgumentException("learned genome does not match " + DuopodPhaseGaitPolicy.POLICY_TYPE);
            }
            for (final double value : learnedGenome) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("learned genome contains a non-finite value");
                }
            }
            if (controlTicks < 1 || spawnWarmupTicks < 0 || maximumControlSteps < 1) {
                throw new IllegalArgumentException("control ticks and max steps must be positive; warmup must be non-negative");
            }
            if (checkpoint == null
                    || checkpoint.runId() == null
                    || !MinecraftMachinesDuopodCemTrainer.CEM_FITNESS_CONTRACT.equals(checkpoint.fitnessContract())
                    || !MinecraftMachinesDuopodFlatArena.ARENA_ID.equals(checkpoint.trainingArenaId())
                    || !MinecraftMachinesDuopodFlatArena.SLOT_LAYOUT_ID.equals(checkpoint.trainingSlotLayoutId())
                    || checkpoint.trainingSlotSpacingBlocks() != SLOT_SPACING_BLOCKS
                    || checkpoint.trainingControlTicks() != DEFAULT_CONTROL_TICKS
                    || checkpoint.trainingSpawnWarmupTicks() != DEFAULT_SPAWN_WARMUP_TICKS
                    || checkpoint.trainingMaximumControlSteps() != DEFAULT_MAX_CONTROL_STEPS
                    || controlTicks != DEFAULT_CONTROL_TICKS
                    || spawnWarmupTicks != DEFAULT_SPAWN_WARMUP_TICKS
                    || maximumControlSteps != DEFAULT_MAX_CONTROL_STEPS) {
                throw new IllegalArgumentException(
                        "walk-forward benchmark requires the exact current fitness, arena, slot-layout, spacing, cadence, warmup, and horizon contract");
            }
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.origin = origin.immutable();
            this.forwardDirection = forwardDirection;
            this.learnedGenome = Arrays.copyOf(learnedGenome, learnedGenome.length);
            this.genomeSha256 = canonicalGenomeSha256(this.learnedGenome);
            this.controlTicks = controlTicks;
            this.spawnWarmupTicks = spawnWarmupTicks;
            this.maximumControlSteps = maximumControlSteps;
            this.checkpoint = checkpoint;
            Arrays.fill(this.initialBaseY, Double.NaN);
            Arrays.fill(this.postWarmupForwardBaselines, Double.NaN);
            Arrays.fill(this.postWarmupLateralBaselines, Double.NaN);
            Arrays.fill(this.minimumBodyUp, Double.POSITIVE_INFINITY);
            Arrays.fill(this.metricSamplesValid, true);
            for (final double[] spawnOrigin : this.spawnOrigins) {
                Arrays.fill(spawnOrigin, Double.NaN);
            }
            for (final double[] postWarmupOrigin : this.postWarmupOrigins) {
                Arrays.fill(postWarmupOrigin, Double.NaN);
            }
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                this.snapshots.add(new FirstTerminalSnapshot<>());
            }
        }

        private boolean start(final ServerLevel level) {
            try {
                this.chunkLease = MinecraftMachinesTrainingChunkLease.acquire(
                        level,
                        this.origin,
                        this.forwardDirection,
                        BATCH_SLOT_COUNT,
                        SLOT_SPACING_BLOCKS,
                        this.maximumControlSteps,
                        this.controlTicks,
                        true);
            } catch (final RuntimeException e) {
                this.lastFailure = "Could not load the benchmark corridor: " + e.getMessage();
                this.close();
                return false;
            }
            this.worldName = level.getServer().getWorldData().getLevelName();
            this.worldSeed = level.getSeed();
            this.chunkActivationStartedGameTime = level.getGameTime();
            this.currentControllerIndex = 0;
            this.spawnPending = true;
            return true;
        }

        private boolean initializeEnvironment(final ServerLevel level) {
            try {
                if (this.flatArena == null) {
                    throw new IllegalStateException("controlled benchmark arena is unavailable");
                }
                final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                        level,
                        this.flatArena.morphologyOrigin(),
                        this.forwardDirection,
                        UUID.randomUUID(),
                        this.flatArena.slotSpacingBlocks(),
                        true);
                this.environment = new LocomotionVectorEnvironment<>(
                        morphology,
                        BATCH_SLOT_COUNT,
                        this.controlTicks,
                        ResetStrategy.RESPAWN,
                        this::episodeForLane,
                        false);
                final List<EpisodeDefinition> episodes = new ArrayList<>(BATCH_SLOT_COUNT);
                for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                    episodes.add(this.episodeForLane(lane, 0L));
                }
                final EnvironmentBatchReset reset = this.environment.resetAll(episodes);
                this.observations = reset.observations();
                for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                    final int episodeSlot = this.episodeSlot(lane);
                    final Map<String, Object> info = snapshotCopy(reset.infos().get(lane));
                    this.snapshots.get(episodeSlot).record(info, false, 0);
                    this.captureEpisodeProvenance(episodeSlot, episodes.get(lane), info);
                    if (this.spawnWarmupTicks == 0) {
                        this.capturePostWarmupBaseline(episodeSlot, info);
                    } else {
                        this.captureInitialBaseHeight(episodeSlot, info);
                        this.recordPoseMetrics(episodeSlot, info);
                    }
                }
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                this.close();
                return false;
            }
            this.warmupTicksRemaining = this.spawnWarmupTicks;
            this.finishedControlSteps = 0;
            this.ticksUntilFinish = 0;
            this.stepPending = false;
            return true;
        }

        private boolean tick(final MinecraftServer server) {
            if (this.cleanupPending || this.closed) {
                return false;
            }
            final ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                this.lastFailure = "benchmark dimension is unavailable";
                MinecraftMachines.LOGGER.info("MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=missing_state failure={}", this.lastFailure);
                this.close();
                return false;
            }
            if (this.spawnPending) {
                if (this.chunkLease == null) {
                    this.lastFailure = "benchmark chunk lease is unavailable";
                    MinecraftMachines.LOGGER.info(
                            "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=missing_state failure={}",
                            this.lastFailure);
                    this.close();
                    return false;
                }
                final boolean readyForSpawn;
                try {
                    readyForSpawn = this.chunkLease.readyForSpawn();
                } catch (final RuntimeException e) {
                    this.lastFailure = "Could not activate the benchmark corridor: " + e.getMessage();
                    MinecraftMachines.LOGGER.info(
                            "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=chunk_activation_failed failure={}",
                            this.lastFailure);
                    this.close();
                    return false;
                }
                if (!readyForSpawn) {
                    if (chunkActivationTimedOut(
                            this.chunkActivationStartedGameTime,
                            level.getGameTime())) {
                        this.lastFailure = "benchmark chunk corridor did not become ticking within "
                                + CHUNK_ACTIVATION_TIMEOUT_TICKS + " server ticks";
                        MinecraftMachines.LOGGER.info(
                                "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=chunk_activation_timeout failure={}",
                                this.lastFailure);
                        this.close();
                        return false;
                    }
                    return true;
                }
                if (this.flatArena == null) {
                    try {
                        this.flatArena = MinecraftMachinesDuopodFlatArena.prepare(
                                level,
                                this.origin,
                                this.forwardDirection,
                                BATCH_SLOT_COUNT,
                                SLOT_SPACING_BLOCKS,
                                this.maximumControlSteps,
                                this.controlTicks);
                    } catch (final RuntimeException e) {
                        this.lastFailure = "Could not prepare the controlled benchmark arena: " + e.getMessage();
                        MinecraftMachines.LOGGER.info(
                                "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=arena_prepare_failed failure={}",
                                this.lastFailure);
                        this.close();
                        return false;
                    }
                }
                // Clear this before construction so a partial or failed reset is
                // never retried against entities left behind by the first attempt.
                this.spawnPending = false;
                if (!this.initializeEnvironment(level)) {
                    MinecraftMachines.LOGGER.info(
                            "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=spawn_failed failure={}",
                            this.lastFailure);
                    return false;
                }
            }
            if (this.environment == null) {
                this.lastFailure = "benchmark environment is unavailable";
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=missing_state failure={}",
                        this.lastFailure);
                this.close();
                return false;
            }
            if (this.warmupTicksRemaining > 0) {
                this.warmupTicksRemaining--;
                if (this.warmupTicksRemaining == 0 && !this.finishWarmup(level)) {
                    return false;
                }
                return true;
            }
            if (this.stepPending) {
                if (!this.sampleHeldActionPoseMetrics()) {
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

        private boolean sampleHeldActionPoseMetrics() {
            final List<Map<String, Object>> infos;
            try {
                infos = this.environment.sampleDiagnosticInfos();
            } catch (final RuntimeException e) {
                this.lastFailure = "Could not sample held-action benchmark poses: " + e.getMessage();
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=physics_pose_sample_failed failure={}",
                        this.lastFailure);
                this.close();
                return false;
            }
            if (infos.size() != BATCH_SLOT_COUNT) {
                this.lastFailure = "Held-action benchmark diagnostic count did not match the active lane count";
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=physics_pose_sample_failed failure={}",
                        this.lastFailure);
                this.close();
                return false;
            }
            for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                final int episodeSlot = this.episodeSlot(lane);
                if (this.snapshots.get(episodeSlot).completed()) {
                    continue;
                }
                this.recordPoseMetrics(episodeSlot, infos.get(lane));
                this.physicsTickPoseSamples[episodeSlot]++;
            }
            return true;
        }

        private boolean finishWarmup(final ServerLevel level) {
            try {
                final EnvironmentBatchReset refreshed = this.environment.updateEpisodes((slot, episode) -> episode);
                this.observations = refreshed.observations();
                for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                    final int episodeSlot = this.episodeSlot(lane);
                    final Map<String, Object> info = snapshotCopy(refreshed.infos().get(lane));
                    this.snapshots.get(episodeSlot).record(info, false, 0);
                    this.capturePostWarmupBaseline(episodeSlot, info);
                }
                return true;
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=warmup_refresh_failed failure={}",
                        this.lastFailure);
                this.close();
                return false;
            }
        }

        private boolean beginControlStep(final ServerLevel level) {
            final double[][] actions = new double[BATCH_SLOT_COUNT][];
            for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                final int episodeSlot = this.episodeSlot(lane);
                if (this.snapshots.get(episodeSlot).completed()) {
                    actions[lane] = DuopodWalkForwardBaselines.neutralAction();
                    continue;
                }
                actions[lane] = rampInitialAction(this.controllerAction(lane), this.finishedControlSteps);
                this.recordActionMetrics(episodeSlot, actions[lane]);
            }
            try {
                this.environment.beginStep(actions);
            } catch (final RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=begin_failed failure={}",
                        this.lastFailure);
                this.close();
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
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=finish_failed failure={}",
                        this.lastFailure);
                this.close();
                return false;
            }
            this.stepPending = false;
            this.finishedControlSteps++;
            this.observations = step.observations();
            for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                final int episodeSlot = this.episodeSlot(lane);
                final FirstTerminalSnapshot<Map<String, Object>> snapshot = this.snapshots.get(episodeSlot);
                if (snapshot.completed()) {
                    continue;
                }
                final Map<String, Object> info = snapshotCopy(step.infos().get(lane));
                if (snapshot.record(info, step.terminated()[lane] || step.truncated()[lane], this.finishedControlSteps)) {
                    this.episodeReturns[episodeSlot] += step.rewards()[lane];
                    this.recordPoseMetrics(episodeSlot, info);
                }
            }
            if (this.currentBatchCompleted() || this.finishedControlSteps >= this.maximumControlSteps) {
                this.captureCompletedControlSteps();
                if (!this.closeEnvironment()) {
                    MinecraftMachines.LOGGER.info(
                            "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=batch_cleanup_failed failure={}",
                            this.lastFailure);
                    this.close();
                    return false;
                }
                if (this.currentControllerIndex + 1 < CONTROLLER_COUNT) {
                    this.currentControllerIndex++;
                    this.spawnPending = true;
                    MinecraftMachines.LOGGER.info(
                            "MM_DUOPOD_WALK_FORWARD_BENCHMARK_BATCH controller={} batch={}/{}",
                            currentController(),
                            this.currentControllerIndex + 1,
                            CONTROLLER_COUNT);
                    return true;
                }
                this.finishBenchmark(server, level);
                return false;
            }
            // Match CEM's accounted control cadence: set the next targets before
            // this server tick reaches physics instead of leaking the old action.
            return this.beginControlStep(level);
        }

        private void finishBenchmark(final MinecraftServer server, final ServerLevel level) {
            try {
                final List<EpisodeRecord> records = this.episodeRecords();
                validatePairedProvenance(records);
                final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> measured = records.stream()
                        .map(EpisodeRecord::acceptanceResult)
                        .toList();
                final DuopodWalkForwardBenchmarkAcceptance.Assessment acceptance =
                        DuopodWalkForwardBenchmarkAcceptance.assess(measured);
                final String generatedAt = Instant.now().toString();
                final String csvContents = this.csv(records, this.benchmarkId, generatedAt);
                final PublishedBenchmarkArtifacts published = publishArtifactBundle(
                        benchmarkDirectory(server),
                        this.benchmarkId,
                        generatedAt,
                        this.payload(records, acceptance, this.benchmarkId, generatedAt),
                        csvContents);
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=complete benchmarkId={} accepted={} learnedMargin={} immutablePath={} latestManifest={}",
                        this.benchmarkId,
                        acceptance.accepted(),
                        format(acceptance.learnedMarginBlocks()),
                        published.immutableJsonPath(),
                        published.latestManifestPath());
                final ServerPlayer player = owner(server, this.ownerId);
                if (player != null) {
                    player.sendSystemMessage(Component.literal(
                            "Duopod walk-forward benchmark %s complete: accepted=%s learned-strongest-baseline margin=%s blocks; saved %s"
                                    .formatted(
                                            this.benchmarkId,
                                            acceptance.accepted(),
                                            format(acceptance.learnedMarginBlocks()),
                                            published.immutableJsonPath())));
                }
            } catch (final IOException | RuntimeException e) {
                this.lastFailure = e.getMessage();
                MinecraftMachines.LOGGER.info(
                        "MM_DUOPOD_WALK_FORWARD_BENCHMARK_END reason=write_failed failure={}",
                        this.lastFailure);
                final ServerPlayer player = owner(server, this.ownerId);
                if (player != null) {
                    player.sendSystemMessage(Component.literal("Duopod walk-forward benchmark failed: " + this.lastFailure));
                }
            } finally {
                this.close();
            }
        }

        private double[] controllerAction(final int lane) {
            return switch (currentController()) {
                case DuopodWalkForwardBenchmarkAcceptance.LEARNED_CONTROLLER ->
                        this.learnedPolicy.action(this.learnedGenome, this.observations[lane]);
                case DuopodWalkForwardBenchmarkAcceptance.NEUTRAL_CONTROLLER ->
                        DuopodWalkForwardBaselines.neutralAction();
                case DuopodWalkForwardBenchmarkAcceptance.SCRIPTED_CONTROLLER ->
                        DuopodWalkForwardBaselines.alternatingSineAction(this.observations[lane]);
                default -> throw new IllegalStateException("unknown benchmark controller batch " + this.currentControllerIndex);
            };
        }

        private void captureInitialBaseHeight(final int slot, final Map<String, Object> info) {
            final double baseY = finiteNumber(info, "base_position_y");
            this.initialBaseY[slot] = baseY;
            if (!Double.isFinite(baseY)) {
                this.metricSamplesValid[slot] = false;
            }
        }

        private void capturePostWarmupBaseline(final int slot, final Map<String, Object> info) {
            this.postWarmupOrigins[slot][0] = finiteNumber(info, "base_position_x");
            this.postWarmupOrigins[slot][1] = finiteNumber(info, "base_position_y");
            this.postWarmupOrigins[slot][2] = finiteNumber(info, "base_position_z");
            this.postWarmupForwardBaselines[slot] = finiteNumber(
                    info,
                    "forward_displacement_from_spawn_blocks");
            this.postWarmupLateralBaselines[slot] = finiteNumber(
                    info,
                    "lateral_displacement_from_spawn_blocks");
            if (!finite(
                    this.postWarmupOrigins[slot][0],
                    this.postWarmupOrigins[slot][1],
                    this.postWarmupOrigins[slot][2],
                    this.postWarmupForwardBaselines[slot],
                    this.postWarmupLateralBaselines[slot])) {
                this.metricSamplesValid[slot] = false;
            }
            this.captureInitialBaseHeight(slot, info);
            this.minimumBodyUp[slot] = Double.POSITIVE_INFINITY;
            this.peakVerticalExcursion[slot] = 0.0;
            this.recordPoseMetrics(slot, info);
        }

        private void captureEpisodeProvenance(
                final int episodeSlot,
                final EpisodeDefinition episode,
                final Map<String, Object> info
        ) {
            this.episodeIds[episodeSlot] = episode.episodeId();
            this.episodeSeeds[episodeSlot] = episode.seed();
            this.spawnOrigins[episodeSlot][0] = finiteNumber(info, "base_position_x");
            this.spawnOrigins[episodeSlot][1] = finiteNumber(info, "base_position_y");
            this.spawnOrigins[episodeSlot][2] = finiteNumber(info, "base_position_z");
            if (!finite(this.spawnOrigins[episodeSlot])) {
                this.metricSamplesValid[episodeSlot] = false;
            }
        }

        private void recordPoseMetrics(final int slot, final Map<String, Object> info) {
            this.recordArenaEscape(slot, info);
            final double bodyUp = finiteNumber(info, "body_up_dot_world_up");
            final double baseY = finiteNumber(info, "base_position_y");
            if (!Double.isFinite(bodyUp) || !Double.isFinite(baseY) || !Double.isFinite(this.initialBaseY[slot])) {
                this.metricSamplesValid[slot] = false;
                return;
            }
            this.minimumBodyUp[slot] = accumulateMinimumBodyUp(this.minimumBodyUp[slot], bodyUp);
            this.peakVerticalExcursion[slot] = accumulatePeakVerticalExcursion(
                    this.peakVerticalExcursion[slot],
                    this.initialBaseY[slot],
                    baseY);
        }

        private void recordArenaEscape(final int slot, final Map<String, Object> info) {
            if (this.flatArena == null || !Double.isFinite(this.postWarmupForwardBaselines[slot])) {
                return;
            }
            final double rawForward = finiteNumber(info, "forward_displacement_from_spawn_blocks");
            final double rawLateral = finiteNumber(info, "lateral_displacement_from_spawn_blocks");
            if (!finite(rawForward, rawLateral)) {
                this.metricSamplesValid[slot] = false;
                return;
            }
            if (outsideArena(
                    rawForward,
                    rawLateral,
                    this.flatArena.minimumForwardOffset(),
                    this.flatArena.maximumForwardOffset(),
                    MinecraftMachinesDuopodFlatArena.LANE_HALF_WIDTH_BLOCKS)) {
                this.arenaEscapes[slot] = true;
            }
        }

        private void recordActionMetrics(final int slot, final double[] action) {
            for (int i = 0; i < action.length; i++) {
                this.actionTotalVariation[slot] += Math.abs(action[i] - this.previousActions[slot][i]);
                if (Math.abs(action[i]) >= ACTION_SATURATION_THRESHOLD) {
                    this.saturatedActionValues[slot]++;
                }
                this.actionValueCounts[slot]++;
            }
            this.previousActions[slot] = Arrays.copyOf(action, action.length);
        }

        private List<EpisodeRecord> episodeRecords() {
            final List<EpisodeRecord> records = new ArrayList<>(SLOT_COUNT);
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                final FirstTerminalSnapshot<Map<String, Object>> snapshot = this.snapshots.get(slot);
                final Map<String, Object> info = snapshot.value() == null ? Map.of() : snapshot.value();
                final String reason = stringValue(
                        info,
                        "termination_reason",
                        snapshot.completed() ? "UNKNOWN_TERMINAL" : "BENCHMARK_STEP_LIMIT_WITHOUT_TERMINAL");
                final double terminalSpawnRelativeForward = finiteNumber(
                        info,
                        "forward_displacement_from_spawn_blocks");
                final double terminalSpawnRelativeLateral = finiteNumber(
                        info,
                        "lateral_displacement_from_spawn_blocks");
                final double forward = terminalSpawnRelativeForward - this.postWarmupForwardBaselines[slot];
                final double lateral = terminalSpawnRelativeLateral - this.postWarmupLateralBaselines[slot];
                final double minimumUp = this.minimumBodyUp[slot] == Double.POSITIVE_INFINITY
                        ? Double.NaN
                        : this.minimumBodyUp[slot];
                final double saturation = this.actionValueCounts[slot] <= 0
                        ? Double.NaN
                        : this.saturatedActionValues[slot] / (double) this.actionValueCounts[slot];
                final int recordedControlSteps = snapshot.terminalStep() >= 0
                        ? snapshot.terminalStep()
                        : this.completedControlSteps[slot];
                final boolean metricsValid = this.metricSamplesValid[slot]
                        && completePhysicsTickPoseSampling(
                        this.physicsTickPoseSamples[slot],
                        recordedControlSteps,
                        this.controlTicks)
                        && finite(
                        forward,
                        lateral,
                        terminalSpawnRelativeForward,
                        terminalSpawnRelativeLateral,
                        this.postWarmupForwardBaselines[slot],
                        this.postWarmupLateralBaselines[slot],
                        minimumUp,
                        this.peakVerticalExcursion[slot],
                        this.actionTotalVariation[slot], saturation);
                final var acceptanceResult = new DuopodWalkForwardBenchmarkAcceptance.EpisodeResult(
                        controller(slot),
                        speed(slot),
                        recordedControlSteps,
                        this.episodeReturns[slot],
                        metricsValid ? forward : Double.NaN,
                        metricsValid ? lateral : Double.NaN,
                        metricsValid ? minimumUp : Double.NaN,
                        metricsValid ? this.peakVerticalExcursion[slot] : Double.NaN,
                        metricsValid ? this.actionTotalVariation[slot] : Double.NaN,
                        metricsValid ? saturation : Double.NaN,
                        "MACHINE_FAILURE".equals(reason),
                        this.arenaEscapes[slot],
                        snapshot.completed());
                records.add(new EpisodeRecord(
                        slot,
                        this.episodeIds[slot],
                        this.episodeSeeds[slot],
                        Arrays.copyOf(this.spawnOrigins[slot], this.spawnOrigins[slot].length),
                        Arrays.copyOf(this.postWarmupOrigins[slot], this.postWarmupOrigins[slot].length),
                        this.postWarmupForwardBaselines[slot],
                        this.postWarmupLateralBaselines[slot],
                        terminalSpawnRelativeForward,
                        terminalSpawnRelativeLateral,
                        this.physicsTickPoseSamples[slot],
                        reason,
                        booleanValue(info, "terminated"),
                        booleanValue(info, "truncated"),
                        stringValue(info, "health_message", ""),
                        acceptanceResult));
            }
            return records;
        }

        private JsonObject payload(
                final List<EpisodeRecord> records,
                final DuopodWalkForwardBenchmarkAcceptance.Assessment acceptance,
                final UUID benchmarkId,
                final String generatedAt
        ) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("format", FORMAT);
            payload.addProperty("format_version", FORMAT_VERSION);
            payload.addProperty("benchmark_id", benchmarkId.toString());
            payload.addProperty("generated_at", generatedAt);
            payload.add("code_revision", JsonNull.INSTANCE);
            payload.addProperty("code_revision_status", "unavailable_in_runtime_artifact");
            payload.add("protocol", this.protocolJson());
            payload.add("policy", this.policyJson());
            payload.add("summary_by_controller", summariesJson(records));
            payload.add("acceptance", acceptanceJson(acceptance));
            final JsonArray episodes = new JsonArray(records.size());
            for (final EpisodeRecord record : records) {
                episodes.add(episodeJson(record));
            }
            payload.add("episodes", episodes);
            return payload;
        }

        private JsonObject protocolJson() {
            final JsonObject protocol = new JsonObject();
            final JsonArray controllers = new JsonArray();
            DuopodWalkForwardBenchmarkAcceptance.CONTROLLERS.forEach(controllers::add);
            protocol.add("controllers", controllers);
            final JsonArray speeds = new JsonArray();
            DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.forEach(speeds::add);
            protocol.add("requested_forward_speeds", speeds);
            protocol.addProperty("episode_count", SLOT_COUNT);
            protocol.addProperty("controller_batch_count", CONTROLLER_COUNT);
            protocol.addProperty("lanes_per_batch", BATCH_SLOT_COUNT);
            protocol.addProperty("control_ticks", this.controlTicks);
            protocol.addProperty("spawn_warmup_ticks", this.spawnWarmupTicks);
            protocol.addProperty("chunk_activation_timeout_ticks", CHUNK_ACTIVATION_TIMEOUT_TICKS);
            protocol.addProperty("maximum_control_steps", this.maximumControlSteps);
            protocol.addProperty("slot_spacing_blocks", SLOT_SPACING_BLOCKS);
            protocol.addProperty("arena_id", MinecraftMachinesDuopodFlatArena.ARENA_ID);
            protocol.addProperty("slot_layout_id", MinecraftMachinesDuopodFlatArena.SLOT_LAYOUT_ID);
            protocol.addProperty("benchmark_protocol_eligibility", "enforced_before_benchmark_start");
            protocol.addProperty("arena_changed_block_count", this.flatArena == null ? 0 : this.flatArena.changedBlockCount());
            protocol.addProperty("arena_support_y", this.flatArena == null ? this.origin.getY() - 1 : this.flatArena.supportY());
            protocol.addProperty("arena_lane_half_width_blocks", MinecraftMachinesDuopodFlatArena.LANE_HALF_WIDTH_BLOCKS);
            protocol.addProperty("arena_minimum_forward_offset", this.flatArena == null ? -8 : this.flatArena.minimumForwardOffset());
            protocol.addProperty("arena_maximum_forward_offset", this.flatArena == null ? 0 : this.flatArena.maximumForwardOffset());
            protocol.addProperty("physical_pairing", "controllers run sequentially over the same three speed-indexed lanes");
            protocol.addProperty("post_warmup_pairing_tolerance_blocks", PAIRED_ORIGIN_TOLERANCE_BLOCKS);
            protocol.addProperty("post_warmup_pairing_validation", "same-lane world origin and spawn-frame displacement baseline must match across controllers");
            protocol.addProperty("batch_cleanup", "each controller environment is destroyed before the next batch spawns");
            final JsonArray lanes = new JsonArray(BATCH_SLOT_COUNT);
            for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                final JsonObject laneJson = new JsonObject();
                laneJson.addProperty("lane", lane);
                laneJson.addProperty("requested_forward_speed", DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.get(lane));
                laneJson.addProperty("episode_id", pairedEpisodeId(lane, 0L));
                laneJson.addProperty("seed", pairedEpisodeSeed(lane));
                if (this.flatArena != null) {
                    laneJson.add("requested_origin", blockPosJson(this.flatArena.requestedOriginForSlot(lane)));
                    laneJson.add("expected_spawn_center", blockPosJson(this.flatArena.expectedSpawnCenterForSlot(lane)));
                }
                lanes.add(laneJson);
            }
            protocol.add("lanes", lanes);
            final JsonObject world = new JsonObject();
            world.addProperty("level_name", this.worldName);
            world.addProperty("seed", this.worldSeed);
            world.addProperty("dimension", this.dimension.location().toString());
            protocol.add("world", world);
            protocol.addProperty("initial_action_ramp_control_steps", INITIAL_ACTION_RAMP_CONTROL_STEPS);
            protocol.addProperty("action_saturation_threshold", ACTION_SATURATION_THRESHOLD);
            protocol.addProperty("scripted_amplitude", DuopodWalkForwardBaselines.SCRIPTED_AMPLITUDE);
            protocol.addProperty("scripted_policy", "left=0.65*sin(phase), right=-left");
            protocol.addProperty("coordinate_frame", "per-episode Duopod spawn frame rebased at the post-warmup controller handoff");
            protocol.addProperty("displacement_measurement", "terminal spawn-frame displacement minus post-warmup spawn-frame baseline");
            protocol.addProperty("vertical_excursion_measurement", "absolute base-height excursion from the post-warmup controller-handoff origin");
            protocol.addProperty("arena_escape_measurement", "post-warmup raw spawn-frame position outside the assigned flat-lane extents");
            protocol.addProperty("pose_metric_sampling_cadence", "every server physics tick during each held-action interval");
            protocol.addProperty("pose_metric_samples_per_control_step", this.controlTicks);
            protocol.addProperty("pose_metric_sample_completeness", "each episode must contain control_steps * control_ticks held-action pose samples");
            protocol.addProperty("terminal_snapshot", "first terminal or truncation state is frozen");
            protocol.addProperty("seed_schedule", "same fixed seed for all controllers at each requested speed");
            protocol.addProperty("acceptance_rule", ACCEPTANCE_RULE);
            protocol.addProperty("acceptance_rule_version", ACCEPTANCE_RULE_VERSION);
            return protocol;
        }

        private JsonObject policyJson() {
            final JsonObject policy = new JsonObject();
            policy.addProperty("policy_type", DuopodPhaseGaitPolicy.POLICY_TYPE);
            policy.addProperty("morphology_id", MinecraftMachines.MOD_ID + ":" + DuopodInstance.MORPHOLOGY_TYPE);
            policy.addProperty("observation_schema_hash", DuopodSchemas.observationSpec().compatibilityHash());
            policy.addProperty("action_schema_hash", DuopodSchemas.actionSpec().compatibilityHash());
            policy.addProperty("genome_size", this.learnedPolicy.genomeSize());
            policy.addProperty("genome_sha256", this.genomeSha256);
            policy.addProperty("genome_sha256_encoding", "concatenated IEEE-754 binary64 big-endian values");
            if (this.checkpoint != null) {
                policy.addProperty("checkpoint_genome_sha256", this.genomeSha256);
                if (this.checkpoint.runId() != null) {
                    policy.addProperty("checkpoint_run_id", this.checkpoint.runId().toString());
                }
                policy.addProperty("checkpoint_generation", this.checkpoint.generation());
                addFiniteOrNull(policy, "checkpoint_aggregate_fitness", this.checkpoint.aggregateFitness());
                addFiniteOrNull(policy, "checkpoint_failure_rate", this.checkpoint.failureRate());
                policy.addProperty("checkpoint_created_at", this.checkpoint.createdAt());
                policy.addProperty("checkpoint_fitness_contract", this.checkpoint.fitnessContract());
                final JsonObject trainingArena = new JsonObject();
                trainingArena.addProperty("arena_id", this.checkpoint.trainingArenaId());
                trainingArena.addProperty("slot_layout_id", this.checkpoint.trainingSlotLayoutId());
                trainingArena.addProperty("slot_spacing_blocks", this.checkpoint.trainingSlotSpacingBlocks());
                trainingArena.addProperty("control_ticks", this.checkpoint.trainingControlTicks());
                trainingArena.addProperty("spawn_warmup_ticks", this.checkpoint.trainingSpawnWarmupTicks());
                trainingArena.addProperty("maximum_control_steps", this.checkpoint.trainingMaximumControlSteps());
                policy.add("checkpoint_training_arena", trainingArena);
            }
            return policy;
        }

        private String csv(
                final List<EpisodeRecord> records,
                final UUID benchmarkId,
                final String generatedAt
        ) {
            final StringBuilder csv = new StringBuilder();
            csv.append("slot,controller,requested_forward_speed,episode_id,seed,spawn_origin_x,spawn_origin_y,spawn_origin_z,")
                    .append("post_warmup_origin_x,post_warmup_origin_y,post_warmup_origin_z,")
                    .append("post_warmup_forward_baseline_blocks,post_warmup_lateral_baseline_blocks,")
                    .append("terminal_spawn_relative_forward_blocks,terminal_spawn_relative_lateral_blocks,")
                    .append("control_steps,episode_return,forward_displacement_blocks,lateral_displacement_blocks,machine_failure,arena_escape,")
                    .append("termination_reason,minimum_body_up,peak_vertical_excursion_blocks,")
                    .append("action_total_variation,action_saturation_fraction,terminal_captured,")
                    .append("physics_tick_pose_samples,benchmark_id,generated_at\n");
            for (final EpisodeRecord record : records) {
                final var result = record.acceptanceResult();
                csv.append(record.slot()).append(',')
                        .append(result.controller()).append(',')
                        .append(formatCsv(result.requestedForwardSpeed())).append(',')
                        .append(record.episodeId()).append(',')
                        .append(record.seed()).append(',')
                        .append(formatCsv(record.spawnOrigin()[0])).append(',')
                        .append(formatCsv(record.spawnOrigin()[1])).append(',')
                        .append(formatCsv(record.spawnOrigin()[2])).append(',')
                        .append(formatCsv(record.postWarmupOrigin()[0])).append(',')
                        .append(formatCsv(record.postWarmupOrigin()[1])).append(',')
                        .append(formatCsv(record.postWarmupOrigin()[2])).append(',')
                        .append(formatCsv(record.postWarmupForwardBaselineBlocks())).append(',')
                        .append(formatCsv(record.postWarmupLateralBaselineBlocks())).append(',')
                        .append(formatCsv(record.terminalSpawnRelativeForwardDisplacementBlocks())).append(',')
                        .append(formatCsv(record.terminalSpawnRelativeLateralDisplacementBlocks())).append(',')
                        .append(result.controlSteps()).append(',')
                        .append(formatCsv(result.episodeReturn())).append(',')
                        .append(formatCsv(result.forwardDisplacementBlocks())).append(',')
                        .append(formatCsv(result.lateralDisplacementBlocks())).append(',')
                        .append(result.machineFailure()).append(',')
                        .append(result.arenaEscape()).append(',')
                        .append(record.terminationReason()).append(',')
                        .append(formatCsv(result.minimumBodyUp())).append(',')
                        .append(formatCsv(result.peakVerticalExcursionBlocks())).append(',')
                        .append(formatCsv(result.actionTotalVariation())).append(',')
                        .append(formatCsv(result.actionSaturationFraction())).append(',')
                        .append(result.terminalCaptured()).append(',')
                        .append(record.physicsTickPoseSamples()).append(',')
                        .append(benchmarkId).append(',')
                        .append(generatedAt).append('\n');
            }
            return csv.toString();
        }

        private EpisodeDefinition episodeForLane(final int lane, final long episodeIndex) {
            checkLane(lane);
            return DuopodTrainingScenarios.commandEpisode(
                    DuopodTrainingScenarios.WALK_FORWARD_STAGE,
                    pairedEpisodeId(lane, episodeIndex),
                    pairedEpisodeSeed(lane),
                    lane,
                    this.maximumControlSteps,
                    lane);
        }

        private boolean currentBatchCompleted() {
            for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                if (!this.snapshots.get(this.episodeSlot(lane)).completed()) {
                    return false;
                }
            }
            return true;
        }

        private int episodeSlot(final int lane) {
            checkLane(lane);
            return pairedEpisodeSlot(this.currentControllerIndex, lane);
        }

        private String currentController() {
            return DuopodWalkForwardBenchmarkAcceptance.CONTROLLERS.get(this.currentControllerIndex);
        }

        private void captureCompletedControlSteps() {
            for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
                this.completedControlSteps[this.episodeSlot(lane)] = this.finishedControlSteps;
            }
        }

        private boolean closeEnvironment() {
            if (this.environment == null) {
                this.observations = null;
                this.stepPending = false;
                this.ticksUntilFinish = 0;
                return true;
            }
            try {
                this.environment.close();
                this.environment = null;
                this.observations = null;
                return true;
            } catch (final RuntimeException e) {
                this.lastFailure = "Could not clean up controller batch: " + e.getMessage();
                return false;
            } finally {
                this.stepPending = false;
                this.ticksUntilFinish = 0;
            }
        }

        private boolean close() {
            if (this.closed) {
                return true;
            }
            this.cleanupPending = true;
            this.spawnPending = false;
            this.stepPending = false;
            this.ticksUntilFinish = 0;
            if (!this.closeEnvironment()) {
                return false;
            }
            if (this.flatArena != null) {
                try {
                    this.flatArena.close();
                    this.flatArena = null;
                } catch (final RuntimeException e) {
                    this.lastFailure = "Could not restore controlled benchmark arena: " + e.getMessage();
                    MinecraftMachines.LOGGER.error(
                            "MM_DUOPOD_WALK_FORWARD_BENCHMARK_CLEANUP_FAILURE failure={}",
                            this.lastFailure,
                            e);
                    // Retain both the retryable arena and its chunk lease. The
                    // static tick path will call close again on the next tick.
                    return false;
                }
            }
            if (this.chunkLease != null) {
                try {
                    this.chunkLease.close();
                    this.chunkLease = null;
                } catch (final RuntimeException e) {
                    this.lastFailure = "Could not release benchmark chunk lease: " + e.getMessage();
                    MinecraftMachines.LOGGER.error(
                            "MM_DUOPOD_WALK_FORWARD_BENCHMARK_CLEANUP_FAILURE failure={}",
                            this.lastFailure,
                            e);
                    return false;
                }
            }
            this.closed = true;
            this.cleanupPending = false;
            return true;
        }

        private String lastFailure() {
            return this.lastFailure;
        }
    }

    private record EpisodeRecord(
            int slot,
            long episodeId,
            long seed,
            double[] spawnOrigin,
            double[] postWarmupOrigin,
            double postWarmupForwardBaselineBlocks,
            double postWarmupLateralBaselineBlocks,
            double terminalSpawnRelativeForwardDisplacementBlocks,
            double terminalSpawnRelativeLateralDisplacementBlocks,
            long physicsTickPoseSamples,
            String terminationReason,
            boolean terminated,
            boolean truncated,
            String healthMessage,
            DuopodWalkForwardBenchmarkAcceptance.EpisodeResult acceptanceResult
    ) {
    }

    private static JsonObject summariesJson(final List<EpisodeRecord> records) {
        final JsonObject summaries = new JsonObject();
        for (final String controller : DuopodWalkForwardBenchmarkAcceptance.CONTROLLERS) {
            final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> results = records.stream()
                    .map(EpisodeRecord::acceptanceResult)
                    .filter(result -> controller.equals(result.controller()))
                    .toList();
            final JsonObject summary = new JsonObject();
            summary.addProperty("episodes", results.size());
            summary.addProperty("failures", results.stream().filter(DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::machineFailure).count());
            summary.addProperty("arena_escapes", results.stream().filter(DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::arenaEscape).count());
            addFiniteOrNull(summary, "mean_return", mean(results, DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::episodeReturn));
            addFiniteOrNull(summary, "mean_forward_displacement_blocks", mean(results, DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::forwardDisplacementBlocks));
            addFiniteOrNull(summary, "mean_lateral_displacement_blocks", mean(results, DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::lateralDisplacementBlocks));
            addFiniteOrNull(summary, "mean_absolute_lateral_displacement_blocks", mean(results, result -> Math.abs(result.lateralDisplacementBlocks())));
            addFiniteOrNull(summary, "minimum_body_up", results.stream().mapToDouble(DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::minimumBodyUp).min().orElse(Double.NaN));
            addFiniteOrNull(summary, "peak_vertical_excursion_blocks", results.stream().mapToDouble(DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::peakVerticalExcursionBlocks).max().orElse(Double.NaN));
            addFiniteOrNull(summary, "mean_action_total_variation", mean(results, DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::actionTotalVariation));
            addFiniteOrNull(summary, "mean_action_saturation_fraction", mean(results, DuopodWalkForwardBenchmarkAcceptance.EpisodeResult::actionSaturationFraction));
            summaries.add(controller, summary);
        }
        return summaries;
    }

    private static void validatePairedProvenance(final List<EpisodeRecord> records) {
        if (records.size() != SLOT_COUNT) {
            throw new IllegalStateException("paired benchmark produced " + records.size() + " records instead of " + SLOT_COUNT);
        }
        for (int lane = 0; lane < BATCH_SLOT_COUNT; lane++) {
            final EpisodeRecord reference = records.get(pairedEpisodeSlot(0, lane));
            if (reference.episodeId() != pairedEpisodeId(lane, 0L)
                    || reference.seed() != pairedEpisodeSeed(lane)
                    || !finite(reference.spawnOrigin())
                    || !finite(reference.postWarmupOrigin())
                    || !finite(
                    reference.postWarmupForwardBaselineBlocks(),
                    reference.postWarmupLateralBaselineBlocks())) {
                throw new IllegalStateException("invalid learned-controller provenance for benchmark lane " + lane);
            }
            for (int controllerIndex = 1; controllerIndex < CONTROLLER_COUNT; controllerIndex++) {
                final EpisodeRecord paired = records.get(pairedEpisodeSlot(controllerIndex, lane));
                if (paired.episodeId() != reference.episodeId() || paired.seed() != reference.seed()) {
                    throw new IllegalStateException("controller batch did not reuse episode identity and seed for lane " + lane);
                }
                for (int axis = 0; axis < reference.spawnOrigin().length; axis++) {
                    if (Math.abs(paired.spawnOrigin()[axis] - reference.spawnOrigin()[axis])
                            > PAIRED_ORIGIN_TOLERANCE_BLOCKS) {
                        throw new IllegalStateException("controller batch did not reuse physical spawn origin for lane " + lane);
                    }
                    if (Math.abs(paired.postWarmupOrigin()[axis] - reference.postWarmupOrigin()[axis])
                            > PAIRED_ORIGIN_TOLERANCE_BLOCKS) {
                        throw new IllegalStateException("controller batch did not reach the same post-warmup origin for lane " + lane);
                    }
                }
                if (Math.abs(paired.postWarmupForwardBaselineBlocks()
                        - reference.postWarmupForwardBaselineBlocks()) > PAIRED_ORIGIN_TOLERANCE_BLOCKS
                        || Math.abs(paired.postWarmupLateralBaselineBlocks()
                        - reference.postWarmupLateralBaselineBlocks()) > PAIRED_ORIGIN_TOLERANCE_BLOCKS) {
                    throw new IllegalStateException("controller batch did not reuse the post-warmup displacement baseline for lane " + lane);
                }
            }
        }
    }

    private static JsonObject acceptanceJson(final DuopodWalkForwardBenchmarkAcceptance.Assessment assessment) {
        final JsonObject json = new JsonObject();
        json.addProperty("rule", ACCEPTANCE_RULE);
        json.addProperty("rule_version", ACCEPTANCE_RULE_VERSION);
        json.addProperty("accepted", assessment.accepted());
        json.addProperty("coverage_valid", assessment.coverageValid());
        json.addProperty("invariants_valid", assessment.invariantsValid());
        final JsonObject thresholds = new JsonObject();
        thresholds.addProperty("required_learned_margin_over_stronger_baseline_blocks", DuopodWalkForwardBenchmarkAcceptance.REQUIRED_LEARNED_MARGIN_BLOCKS);
        thresholds.addProperty("required_minimum_body_up", DuopodWalkForwardBenchmarkAcceptance.REQUIRED_MINIMUM_BODY_UP);
        thresholds.addProperty("maximum_learned_peak_vertical_excursion_blocks",
                DuopodWalkForwardBenchmarkAcceptance.MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS);
        thresholds.addProperty("required_learned_failures", 0);
        thresholds.addProperty("required_learned_arena_escapes", 0);
        thresholds.addProperty("require_positive_progress_at_every_speed", true);
        thresholds.addProperty("unstable_baseline_positive_forward_credit_capped_at_zero", true);
        thresholds.addProperty(
                "baseline_benchmark_stability",
                "no machine failure, no arena escape, minimum body-up and maximum vertical-excursion thresholds satisfied");
        json.add("thresholds", thresholds);
        final JsonObject observed = new JsonObject();
        addFiniteOrNull(observed, "learned_mean_forward_blocks", assessment.learnedMeanForwardBlocks());
        addFiniteOrNull(observed, "neutral_stability_adjusted_comparison_mean_forward_blocks",
                assessment.neutralStabilityAdjustedComparisonMeanForwardBlocks());
        addFiniteOrNull(observed, "scripted_stability_adjusted_comparison_mean_forward_blocks",
                assessment.scriptedStabilityAdjustedComparisonMeanForwardBlocks());
        addFiniteOrNull(observed, "stronger_baseline_stability_adjusted_comparison_mean_forward_blocks",
                assessment.strongerBaselineStabilityAdjustedComparisonMeanForwardBlocks());
        addFiniteOrNull(observed, "learned_minus_neutral_stability_adjusted_comparison_blocks",
                assessment.learnedNeutralMarginBlocks());
        addFiniteOrNull(observed, "learned_minus_scripted_stability_adjusted_comparison_blocks",
                assessment.learnedScriptedMarginBlocks());
        addFiniteOrNull(observed, "learned_minus_stronger_baseline_stability_adjusted_comparison_blocks",
                assessment.learnedMarginBlocks());
        observed.addProperty("learned_failures", assessment.learnedFailures());
        addFiniteOrNull(observed, "learned_minimum_body_up", assessment.learnedMinimumBodyUp());
        addFiniteOrNull(
                observed,
                "learned_maximum_peak_vertical_excursion_blocks",
                assessment.learnedMaximumPeakVerticalExcursionBlocks());
        observed.addProperty("learned_arena_escapes", assessment.learnedArenaEscapeCount());
        json.add("observed", observed);
        final JsonObject criteria = new JsonObject();
        assessment.criteria().forEach(criteria::addProperty);
        json.add("criteria", criteria);
        final JsonArray failed = new JsonArray();
        assessment.failedCriteria().forEach(failed::add);
        json.add("failed_criteria", failed);
        return json;
    }

    private static JsonObject episodeJson(final EpisodeRecord record) {
        final var result = record.acceptanceResult();
        final JsonObject json = new JsonObject();
        json.addProperty("slot", record.slot());
        json.addProperty("lane", Math.floorMod(record.slot(), SPEED_COUNT));
        json.addProperty("controller", result.controller());
        json.addProperty("requested_forward_speed", result.requestedForwardSpeed());
        json.addProperty("episode_id", record.episodeId());
        json.addProperty("seed", record.seed());
        final JsonObject spawnOrigin = new JsonObject();
        addFiniteOrNull(spawnOrigin, "x", record.spawnOrigin()[0]);
        addFiniteOrNull(spawnOrigin, "y", record.spawnOrigin()[1]);
        addFiniteOrNull(spawnOrigin, "z", record.spawnOrigin()[2]);
        json.add("spawn_origin", spawnOrigin);
        final JsonObject postWarmupOrigin = new JsonObject();
        addFiniteOrNull(postWarmupOrigin, "x", record.postWarmupOrigin()[0]);
        addFiniteOrNull(postWarmupOrigin, "y", record.postWarmupOrigin()[1]);
        addFiniteOrNull(postWarmupOrigin, "z", record.postWarmupOrigin()[2]);
        json.add("post_warmup_origin", postWarmupOrigin);
        final JsonObject postWarmupBaseline = new JsonObject();
        addFiniteOrNull(postWarmupBaseline, "forward_displacement_from_spawn_blocks", record.postWarmupForwardBaselineBlocks());
        addFiniteOrNull(postWarmupBaseline, "lateral_displacement_from_spawn_blocks", record.postWarmupLateralBaselineBlocks());
        json.add("post_warmup_baseline", postWarmupBaseline);
        final JsonObject terminalSpawnRelative = new JsonObject();
        addFiniteOrNull(terminalSpawnRelative, "forward_displacement_blocks", record.terminalSpawnRelativeForwardDisplacementBlocks());
        addFiniteOrNull(terminalSpawnRelative, "lateral_displacement_blocks", record.terminalSpawnRelativeLateralDisplacementBlocks());
        json.add("terminal_spawn_relative_displacement", terminalSpawnRelative);
        json.addProperty("control_steps", result.controlSteps());
        json.addProperty("physics_tick_pose_samples", record.physicsTickPoseSamples());
        addFiniteOrNull(json, "return", result.episodeReturn());
        addFiniteOrNull(json, "forward_displacement_blocks", result.forwardDisplacementBlocks());
        addFiniteOrNull(json, "lateral_displacement_blocks", result.lateralDisplacementBlocks());
        json.addProperty("machine_failure", result.machineFailure());
        json.addProperty("arena_escape", result.arenaEscape());
        json.addProperty("termination_reason", record.terminationReason());
        json.addProperty("terminated", record.terminated());
        json.addProperty("truncated", record.truncated());
        json.addProperty("health_message", record.healthMessage());
        addFiniteOrNull(json, "minimum_body_up", result.minimumBodyUp());
        addFiniteOrNull(json, "peak_vertical_excursion_blocks", result.peakVerticalExcursionBlocks());
        addFiniteOrNull(json, "action_total_variation", result.actionTotalVariation());
        addFiniteOrNull(json, "action_saturation_fraction", result.actionSaturationFraction());
        json.addProperty("terminal_captured", result.terminalCaptured());
        return json;
    }

    private static JsonObject blockPosJson(final BlockPos pos) {
        final JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static double[] rampInitialAction(final double[] action, final int controlStep) {
        final double scale = Math.min(1.0, Math.max(
                0.0,
                (controlStep + 1) / (double) INITIAL_ACTION_RAMP_CONTROL_STEPS));
        if (scale >= 1.0) {
            return action;
        }
        final double[] scaled = Arrays.copyOf(action, action.length);
        for (int i = 0; i < scaled.length; i++) {
            scaled[i] *= scale;
        }
        return scaled;
    }

    private static String controller(final int slot) {
        return DuopodWalkForwardBenchmarkAcceptance.CONTROLLERS.get(slot / SPEED_COUNT);
    }

    private static double speed(final int slot) {
        return DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.get(Math.floorMod(slot, SPEED_COUNT));
    }

    static int pairedEpisodeSlot(final int controllerIndex, final int lane) {
        if (controllerIndex < 0 || controllerIndex >= CONTROLLER_COUNT) {
            throw new IndexOutOfBoundsException("controller " + controllerIndex + " outside 0.." + (CONTROLLER_COUNT - 1));
        }
        checkLane(lane);
        return controllerIndex * SPEED_COUNT + lane;
    }

    static long pairedEpisodeId(final int lane, final long episodeIndex) {
        checkLane(lane);
        if (episodeIndex < 0L) {
            throw new IllegalArgumentException("episodeIndex must be non-negative");
        }
        return 8_100_000L + lane + episodeIndex * BATCH_SLOT_COUNT;
    }

    static long pairedEpisodeSeed(final int lane) {
        checkLane(lane);
        return 8_200_000L + lane;
    }

    static double accumulateMinimumBodyUp(final double currentMinimum, final double bodyUpSample) {
        return Math.min(currentMinimum, bodyUpSample);
    }

    static double accumulatePeakVerticalExcursion(
            final double currentPeak,
            final double postWarmupBaseY,
            final double baseYSample
    ) {
        return Math.max(currentPeak, Math.abs(baseYSample - postWarmupBaseY));
    }

    static boolean outsideArena(
            final double rawForward,
            final double rawLateral,
            final double minimumForward,
            final double maximumForward,
            final double laneHalfWidth
    ) {
        return rawForward < minimumForward
                || rawForward > maximumForward
                || Math.abs(rawLateral) > laneHalfWidth;
    }

    static boolean completePhysicsTickPoseSampling(
            final long sampleCount,
            final int controlSteps,
            final int controlTicks
    ) {
        return sampleCount >= 0L
                && controlSteps > 0
                && controlTicks > 0
                && sampleCount == (long) controlSteps * controlTicks;
    }

    static String canonicalGenomeSha256(final double[] genome) {
        if (genome == null) {
            throw new IllegalArgumentException("genome is required");
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        final ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES);
        for (final double value : genome) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("genome contains a non-finite value");
            }
            bytes.clear();
            bytes.putLong(Double.doubleToLongBits(value));
            digest.update(bytes.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static boolean chunkActivationTimedOut(final long activationStartedGameTime, final long currentGameTime) {
        return currentGameTime >= activationStartedGameTime
                && currentGameTime - activationStartedGameTime >= CHUNK_ACTIVATION_TIMEOUT_TICKS;
    }

    private static void checkLane(final int lane) {
        if (lane < 0 || lane >= BATCH_SLOT_COUNT) {
            throw new IndexOutOfBoundsException("lane " + lane + " outside 0.." + (BATCH_SLOT_COUNT - 1));
        }
    }

    private static double mean(
            final List<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> results,
            final java.util.function.ToDoubleFunction<DuopodWalkForwardBenchmarkAcceptance.EpisodeResult> value
    ) {
        return results.stream().mapToDouble(value).average().orElse(Double.NaN);
    }

    private static Map<String, Object> snapshotCopy(final Map<String, Object> info) {
        return new LinkedHashMap<>(info);
    }

    private static double finiteNumber(final Map<String, Object> info, final String key) {
        final Object value = info.get(key);
        if (value instanceof final Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        return Double.NaN;
    }

    private static boolean booleanValue(final Map<String, Object> info, final String key) {
        return Boolean.TRUE.equals(info.get(key));
    }

    private static String stringValue(final Map<String, Object> info, final String key, final String fallback) {
        final Object value = info.get(key);
        return value instanceof final String string ? string : fallback;
    }

    private static boolean finite(final double... values) {
        for (final double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static void addFiniteOrNull(final JsonObject json, final String key, final double value) {
        if (Double.isFinite(value)) {
            json.addProperty(key, value);
        } else {
            json.add(key, JsonNull.INSTANCE);
        }
    }

    private static Path benchmarkDirectory(final MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(MinecraftMachines.MOD_ID);
    }

    static PublishedBenchmarkArtifacts publishArtifactBundle(
            final Path directory,
            final UUID benchmarkId,
            final String generatedAt,
            final JsonObject basePayload,
            final String csvContents
    ) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(benchmarkId, "benchmarkId");
        Objects.requireNonNull(basePayload, "basePayload");
        Objects.requireNonNull(csvContents, "csvContents");
        if (generatedAt == null || generatedAt.isBlank()) {
            throw new IllegalArgumentException("generatedAt is required");
        }
        validateCsvBenchmarkId(csvContents, benchmarkId);
        Files.createDirectories(directory);

        final String immutableBaseName = IMMUTABLE_ARTIFACT_PREFIX + benchmarkId;
        final Path immutableJsonPath = directory.resolve(immutableBaseName + ".json");
        final Path immutableCsvPath = directory.resolve(immutableBaseName + ".csv");
        if (Files.exists(immutableJsonPath) || Files.exists(immutableCsvPath)) {
            throw new FileAlreadyExistsException(
                    "immutable benchmark artifacts already exist for benchmark " + benchmarkId);
        }

        final String csvSha256 = sha256Utf8(csvContents);
        final JsonObject payload = basePayload.deepCopy();
        payload.addProperty("benchmark_id", benchmarkId.toString());
        payload.addProperty("generated_at", generatedAt);
        final JsonObject integrity = new JsonObject();
        integrity.addProperty("hash_algorithm", "SHA-256");
        integrity.addProperty("immutable_json_file", immutableJsonPath.getFileName().toString());
        integrity.addProperty("immutable_csv_file", immutableCsvPath.getFileName().toString());
        integrity.addProperty("immutable_csv_sha256", csvSha256);
        integrity.addProperty("json_sha256_location", LATEST_MANIFEST_FILENAME);
        integrity.addProperty("latest_manifest_file", LATEST_MANIFEST_FILENAME);
        integrity.addProperty("latest_compatibility_json_file", LATEST_JSON_FILENAME);
        integrity.addProperty("latest_compatibility_csv_file", LATEST_CSV_FILENAME);
        integrity.addProperty(
                "pairing_contract",
                "benchmark_id must match JSON, every CSV row, and the latest manifest; manifest hashes both files");
        payload.add("artifact_integrity", integrity);

        // Keep published text artifacts POSIX-friendly and byte-stable when
        // copied into source control. The manifest hashes this exact payload.
        final String jsonContents = GSON.toJson(payload) + "\n";
        final String jsonSha256 = sha256Utf8(jsonContents);
        final JsonObject manifest = latestManifest(
                benchmarkId,
                generatedAt,
                payload,
                immutableJsonPath,
                immutableCsvPath,
                jsonContents,
                csvContents,
                jsonSha256,
                csvSha256);
        final String manifestContents = GSON.toJson(manifest) + "\n";

        writeImmutable(immutableJsonPath, jsonContents);
        try {
            writeImmutable(immutableCsvPath, csvContents);
        } catch (final IOException e) {
            // The manifest remains unchanged, so a lone immutable JSON is an
            // explicit uncommitted artifact rather than a silently promoted pair.
            throw e;
        }

        final Path latestJsonPath = directory.resolve(LATEST_JSON_FILENAME);
        final Path latestCsvPath = directory.resolve(LATEST_CSV_FILENAME);
        final Path latestManifestPath = directory.resolve(LATEST_MANIFEST_FILENAME);
        // These compatibility files are intentionally updated before the
        // manifest. A crash can leave a detectable hash/benchmark-id mismatch,
        // but can never move the manifest commit marker to an incomplete pair.
        writeAtomically(latestJsonPath, jsonContents);
        writeAtomically(latestCsvPath, csvContents);
        writeAtomically(latestManifestPath, manifestContents);

        return new PublishedBenchmarkArtifacts(
                benchmarkId,
                immutableJsonPath,
                immutableCsvPath,
                latestJsonPath,
                latestCsvPath,
                latestManifestPath,
                jsonSha256,
                csvSha256);
    }

    private static JsonObject latestManifest(
            final UUID benchmarkId,
            final String generatedAt,
            final JsonObject payload,
            final Path immutableJsonPath,
            final Path immutableCsvPath,
            final String jsonContents,
            final String csvContents,
            final String jsonSha256,
            final String csvSha256
    ) {
        final JsonObject manifest = new JsonObject();
        manifest.addProperty("format", LATEST_MANIFEST_FORMAT);
        manifest.addProperty("format_version", LATEST_MANIFEST_FORMAT_VERSION);
        manifest.addProperty("benchmark_id", benchmarkId.toString());
        manifest.addProperty("generated_at", generatedAt);
        manifest.addProperty("hash_algorithm", "SHA-256");
        manifest.addProperty("commit_complete", true);
        manifest.addProperty(
                "commit_semantics",
                "written atomically after immutable and compatibility JSON/CSV files; readers must verify both hashes and benchmark IDs");

        final JsonObject contract = new JsonObject();
        copyPrimitive(payload, contract, "format", "benchmark_format");
        copyPrimitive(payload, contract, "format_version", "benchmark_format_version");
        if (payload.has("protocol") && payload.get("protocol").isJsonObject()) {
            final JsonObject protocol = payload.getAsJsonObject("protocol");
            copyPrimitive(protocol, contract, "acceptance_rule", "acceptance_rule");
            copyPrimitive(protocol, contract, "acceptance_rule_version", "acceptance_rule_version");
        }
        manifest.add("contract", contract);

        final JsonObject immutable = new JsonObject();
        immutable.add("json", artifactDescriptor(
                immutableJsonPath.getFileName().toString(),
                jsonSha256,
                jsonContents.getBytes(StandardCharsets.UTF_8).length));
        immutable.add("csv", artifactDescriptor(
                immutableCsvPath.getFileName().toString(),
                csvSha256,
                csvContents.getBytes(StandardCharsets.UTF_8).length));
        manifest.add("immutable_artifacts", immutable);

        final JsonObject compatibility = new JsonObject();
        compatibility.add("json", artifactDescriptor(
                LATEST_JSON_FILENAME,
                jsonSha256,
                jsonContents.getBytes(StandardCharsets.UTF_8).length));
        compatibility.add("csv", artifactDescriptor(
                LATEST_CSV_FILENAME,
                csvSha256,
                csvContents.getBytes(StandardCharsets.UTF_8).length));
        manifest.add("compatibility_latest", compatibility);

        final JsonObject provenance = new JsonObject();
        if (payload.has("policy") && payload.get("policy").isJsonObject()) {
            final JsonObject policy = payload.getAsJsonObject("policy");
            copyPrimitive(policy, provenance, "genome_sha256", "genome_sha256");
            copyPrimitive(policy, provenance, "checkpoint_run_id", "checkpoint_run_id");
            copyPrimitive(policy, provenance, "checkpoint_generation", "checkpoint_generation");
            copyPrimitive(policy, provenance, "checkpoint_fitness_contract", "checkpoint_fitness_contract");
        }
        if (payload.has("protocol") && payload.get("protocol").isJsonObject()) {
            final JsonObject protocol = payload.getAsJsonObject("protocol");
            if (protocol.has("world") && protocol.get("world").isJsonObject()) {
                provenance.add("world", protocol.getAsJsonObject("world").deepCopy());
            }
            copyPrimitive(protocol, provenance, "arena_id", "arena_id");
        }
        manifest.add("provenance", provenance);
        return manifest;
    }

    private static JsonObject artifactDescriptor(
            final String filename,
            final String sha256,
            final int byteCount
    ) {
        final JsonObject descriptor = new JsonObject();
        descriptor.addProperty("filename", filename);
        descriptor.addProperty("sha256", sha256);
        descriptor.addProperty("bytes", byteCount);
        return descriptor;
    }

    private static void copyPrimitive(
            final JsonObject source,
            final JsonObject destination,
            final String sourceName,
            final String destinationName
    ) {
        if (source.has(sourceName) && source.get(sourceName).isJsonPrimitive()) {
            destination.add(destinationName, source.get(sourceName).deepCopy());
        }
    }

    private static void validateCsvBenchmarkId(final String csvContents, final UUID benchmarkId) {
        final String[] lines = csvContents.split("\\R", -1);
        if (lines.length < 2) {
            throw new IllegalArgumentException("benchmark CSV must contain a header and at least one row");
        }
        final String[] header = lines[0].split(",", -1);
        int benchmarkIdColumn = -1;
        for (int column = 0; column < header.length; column++) {
            if ("benchmark_id".equals(header[column])) {
                benchmarkIdColumn = column;
                break;
            }
        }
        if (benchmarkIdColumn < 0) {
            throw new IllegalArgumentException("benchmark CSV must contain a benchmark_id column");
        }
        int rowCount = 0;
        for (int line = 1; line < lines.length; line++) {
            if (lines[line].isBlank()) {
                continue;
            }
            final String[] columns = lines[line].split(",", -1);
            if (columns.length != header.length
                    || !benchmarkId.toString().equals(columns[benchmarkIdColumn])) {
                throw new IllegalArgumentException(
                        "benchmark CSV row " + line + " does not carry the shared benchmark ID");
            }
            rowCount++;
        }
        if (rowCount == 0) {
            throw new IllegalArgumentException("benchmark CSV must contain at least one evidence row");
        }
    }

    static String sha256Utf8(final String contents) {
        Objects.requireNonNull(contents, "contents");
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        return HexFormat.of().formatHex(digest.digest(contents.getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeImmutable(final Path path, final String contents) throws IOException {
        Files.createDirectories(path.getParent());
        final Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                writer.write(contents);
            }
            forceFile(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path);
            }
            forceDirectory(path.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeAtomically(final Path path, final String contents) throws IOException {
        Files.createDirectories(path.getParent());
        final Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(
                temporary,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writer.write(contents);
        }
        forceFile(temporary);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
        forceDirectory(path.getParent());
    }

    private static void forceFile(final Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(final Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    record PublishedBenchmarkArtifacts(
            UUID benchmarkId,
            Path immutableJsonPath,
            Path immutableCsvPath,
            Path latestJsonPath,
            Path latestCsvPath,
            Path latestManifestPath,
            String jsonSha256,
            String csvSha256
    ) {
    }

    private static ServerPlayer owner(final MinecraftServer server, final UUID ownerId) {
        return ownerId == null ? null : server.getPlayerList().getPlayer(ownerId);
    }

    private static String format(final double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.4f", value) : "invalid";
    }

    private static String formatCsv(final double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.8f", value) : "";
    }
}
