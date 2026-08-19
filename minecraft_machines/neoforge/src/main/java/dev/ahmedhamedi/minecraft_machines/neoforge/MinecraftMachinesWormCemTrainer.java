package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ahmedhamedi.minecraft_machines.content.worm.training.ActiveWormEpisode;
import dev.ahmedhamedi.minecraft_machines.content.worm.training.ScoredWormCandidate;
import dev.ahmedhamedi.minecraft_machines.content.worm.training.WormCemCandidate;
import dev.ahmedhamedi.minecraft_machines.content.worm.training.WormCemConfig;
import dev.ahmedhamedi.minecraft_machines.content.worm.training.WormCemDistribution;
import dev.ahmedhamedi.minecraft_machines.content.worm.training.WormInstance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public final class MinecraftMachinesWormCemTrainer {
    private static final Direction DEFAULT_FORWARD_DIRECTION = Direction.EAST;
    private static final Direction DEFAULT_SIDE_DIRECTION = Direction.SOUTH;
    private static final double SIDEWAYS_PENALTY = 0.15;
    private static final ServoControl DEFAULT_SERVO_CONTROL = new ServoControl(360.0, 4_000.0, 900.0, 1_000_000.0);

    private static @Nullable TrainingRun activeRun;
    private static @Nullable ReplayRun replayRun;
    private static @Nullable ScoredWormCandidate bestEver;
    private static ServoControl servoControl = DEFAULT_SERVO_CONTROL;

    private MinecraftMachinesWormCemTrainer() {
    }

    public static int start(final CommandSourceStack source, final WormCemConfig config) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        if (activeRun != null) {
            source.sendFailure(Component.literal("A worm CEM training run is already active. Use /mm worm_cem stop first."));
            return 0;
        }

        clearReplay(player.server);
        final TrainingRun run = new TrainingRun(
                UUID.randomUUID(),
                player.getUUID(),
                player.serverLevel().dimension(),
                player.blockPosition().relative(DEFAULT_FORWARD_DIRECTION, 5),
                config,
                WormCemDistribution.initial(),
                new Random(player.serverLevel().getGameTime() ^ player.getUUID().getMostSignificantBits())
        );

        if (!run.spawnGeneration(player.serverLevel())) {
            source.sendFailure(Component.literal(run.lastFailure()));
            run.clear(player.serverLevel());
            return 0;
        }

        activeRun = run;
        MinecraftMachines.LOGGER.info("MM_WORM_CEM_BEGIN run={} player={} dimension={} origin={} config={} servoControl={} forward={} side={} distribution={}",
                run.runId(),
                player.getGameProfile().getName(),
                run.dimension().location(),
                run.origin().toShortString(),
                config,
                servoControl.compactDescription(),
                DEFAULT_FORWARD_DIRECTION,
                DEFAULT_SIDE_DIRECTION,
                run.distribution().compactDescription());
        source.sendSuccess(() -> Component.literal("Started worm CEM run %s: pop=%d elite=%d episode=%d ticks generations=%d spacing=%d forward=+X."
                .formatted(run.runId(), config.populationSize(), config.eliteCount(), config.episodeLengthTicks(), config.maxGenerations(), config.spacingBlocks())), true);
        return 1;
    }

    public static int stop(final CommandSourceStack source) {
        final TrainingRun run = activeRun;
        if (run == null) {
            source.sendFailure(Component.literal("No active worm CEM training run."));
            return 0;
        }

        final ServerLevel level = source.getServer().getLevel(run.dimension());
        if (level != null) {
            run.clear(level);
        }
        activeRun = null;
        MinecraftMachines.LOGGER.info("MM_WORM_CEM_STOP run={} reason=command", run.runId());
        source.sendSuccess(() -> Component.literal("Stopped worm CEM run " + run.runId() + "."), true);
        return 1;
    }

    public static int status(final CommandSourceStack source) {
        final TrainingRun run = activeRun;
        if (run == null) {
            final String best = bestEver == null
                    ? "No best candidate recorded yet."
                    : "Best score %.3f with %s.".formatted(bestEver.score(), bestEver.candidate().compactDescription());
            final String replay = replayRun == null ? "No replay active." : "Replay active: " + replayRun.candidate().compactDescription();
            source.sendSuccess(() -> Component.literal("Worm CEM inactive. Servo " + servoControl.compactDescription() + ". " + best + " " + replay), false);
            return 1;
        }

        final ServerLevel level = source.getServer().getLevel(run.dimension());
        final long elapsed = level == null ? 0L : Math.max(0L, level.getGameTime() - run.generationStartGameTime());
        final String best = bestEver == null
                ? "none"
                : "%.3f %s".formatted(bestEver.score(), bestEver.candidate().compactDescription());
        source.sendSuccess(() -> Component.literal("Worm CEM run %s gen=%d/%d tick=%d/%d pop=%d best=%s servo=%s distribution=%s"
                .formatted(run.runId(),
                        run.generation() + 1,
                        run.config().maxGenerations(),
                        elapsed,
                        run.config().episodeLengthTicks(),
                        run.episodes().size(),
                        best,
                        servoControl.compactDescription(),
                        run.distribution().compactDescription())), false);
        return 1;
    }

    public static int servoStatus(final CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Worm CEM servo control: " + servoControl.compactDescription()), false);
        return 1;
    }

    public static int setServoControl(
            final CommandSourceStack source,
            final double maxSpeedDegS,
            final double stiffness,
            final double damping,
            final double maxTorque
    ) {
        servoControl = new ServoControl(maxSpeedDegS, stiffness, damping, maxTorque);
        final int applied = applyServoControlToActiveRuns(source.getServer(), false);
        MinecraftMachines.LOGGER.info("MM_WORM_CEM_SERVO_CONTROL_SET {} applied={}", servoControl.compactDescription(), applied);
        source.sendSuccess(() -> Component.literal("Set worm CEM servo control to %s. Applied to %d active servo(s)."
                .formatted(servoControl.compactDescription(), applied)), true);
        return 1;
    }

    public static int resetServoControl(final CommandSourceStack source) {
        servoControl = DEFAULT_SERVO_CONTROL;
        final int applied = applyServoControlToActiveRuns(source.getServer(), false);
        MinecraftMachines.LOGGER.info("MM_WORM_CEM_SERVO_CONTROL_RESET {} applied={}", servoControl.compactDescription(), applied);
        source.sendSuccess(() -> Component.literal("Reset worm CEM servo control to %s. Applied to %d active servo(s)."
                .formatted(servoControl.compactDescription(), applied)), true);
        return 1;
    }

    public static int replayBest(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        if (bestEver == null) {
            source.sendFailure(Component.literal("No best worm CEM candidate exists yet."));
            return 0;
        }

        clearReplay(player.server);
        final BlockPos origin = player.blockPosition().relative(DEFAULT_FORWARD_DIRECTION, 5);
        final ReplayRun replay = ReplayRun.spawn(player.serverLevel(), origin, bestEver.candidate());
        if (replay == null) {
            source.sendFailure(Component.literal("Could not spawn replay worm near " + origin.toShortString() + "."));
            return 0;
        }

        replayRun = replay;
        MinecraftMachines.LOGGER.info("MM_WORM_CEM_REPLAY_BEGIN candidate={} score={}",
                bestEver.candidate().compactDescription(),
                bestEver.score());
        source.sendSuccess(() -> Component.literal("Replaying best worm candidate: score %.3f %s."
                .formatted(bestEver.score(), bestEver.candidate().compactDescription())), true);
        return 1;
    }

    public static int clear(final CommandSourceStack source) {
        int cleared = 0;
        final TrainingRun run = activeRun;
        if (run != null) {
            final ServerLevel level = source.getServer().getLevel(run.dimension());
            if (level != null) {
                run.clear(level);
                cleared++;
            }
            activeRun = null;
        }
        if (clearReplay(source.getServer())) {
            cleared++;
        }

        source.sendSuccess(() -> Component.literal("Cleared worm CEM trainer bodies."), true);
        return cleared;
    }

    public static void tick(final ServerTickEvent.Pre event) {
        final MinecraftServer server = event.getServer();
        final TrainingRun run = activeRun;
        if (run != null && !run.tick(server)) {
            activeRun = null;
        }

        final ReplayRun replay = replayRun;
        if (replay != null && !replay.tick(server)) {
            replayRun = null;
        }
    }

    private static boolean clearReplay(final MinecraftServer server) {
        final ReplayRun replay = replayRun;
        if (replay == null) {
            return false;
        }

        final ServerLevel level = server.getLevel(replay.dimension());
        if (level != null) {
            replay.clear(level);
        }
        replayRun = null;
        return true;
    }

    private static int applyServoControlToActiveRuns(final MinecraftServer server, final boolean resetTarget) {
        int applied = 0;
        final TrainingRun run = activeRun;
        if (run != null) {
            final ServerLevel level = server.getLevel(run.dimension());
            if (level != null) {
                applied += run.applyServoControl(level, resetTarget);
            }
        }

        final ReplayRun replay = replayRun;
        if (replay != null) {
            final ServerLevel level = server.getLevel(replay.dimension());
            if (level != null) {
                applied += replay.applyServoControl(level, resetTarget);
            }
        }
        return applied;
    }

    private static void configureTrainingServo(final RoboticServoJointBlockEntity servo, final boolean resetTarget) {
        servo.setEnabled(true);
        servo.setAngleLimitsDegrees(-180.0, 180.0);
        servo.setMaxAngularSpeedDegreesPerSecond(servoControl.maxSpeedDegS());
        servo.setServoGains(servoControl.stiffness(), servoControl.damping());
        servo.setMaxTorque(servoControl.maxTorque());
        if (resetTarget) {
            servo.setTargetAngleDegrees(0.0);
        }
    }

    private static void driveServo(final ServerLevel level, final ActiveWormEpisode episode, final double elapsedSeconds) {
        final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                episode.worm().servoPos(),
                episode.worm().servoInstanceId(),
                episode.worm().childSubLevelId());
        if (servo == null) {
            return;
        }

        final double targetAngle = episode.candidate().targetAngleDegrees(
                elapsedSeconds,
                servo.getMinimumAngleDegrees(),
                servo.getMaximumAngleDegrees());
        servo.setTargetAngleDegrees(targetAngle);
    }

    private static ActiveWormEpisode refreshServoPosition(final ServerLevel level, final ActiveWormEpisode episode) {
        final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                episode.worm().servoPos(),
                episode.worm().servoInstanceId(),
                episode.worm().childSubLevelId());
        if (servo == null || servo.getBlockPos().equals(episode.worm().servoPos())) {
            return episode;
        }
        return episode.withWorm(episode.worm().withServoPos(servo.getBlockPos().immutable()));
    }

    private static ScoredWormCandidate scoreEpisode(final ServerLevel level, final ActiveWormEpisode episode) {
        final Vec3 end = MinecraftMachinesWormSpawner.getBodyPosition(level, episode.worm().baseSubLevelId());
        final Vec3 effectiveEnd = end == null ? episode.startPosition() : end;
        final Vec3 displacement = effectiveEnd.subtract(episode.startPosition());
        final Vec3 forward = directionVector(episode.worm().forwardDirection());
        final Vec3 side = directionVector(episode.worm().sideDirection());
        final double forwardDisplacement = displacement.dot(forward);
        final double sidewaysDisplacement = displacement.dot(side);
        final double score = forwardDisplacement - SIDEWAYS_PENALTY * Math.abs(sidewaysDisplacement);
        return new ScoredWormCandidate(episode.candidate(), score, forwardDisplacement, sidewaysDisplacement);
    }

    private static void logScoredCandidate(
            final String prefix,
            final TrainingRun run,
            final int index,
            final ActiveWormEpisode episode,
            final ScoredWormCandidate scored,
            final @Nullable RoboticServoJointBlockEntity servo
    ) {
        MinecraftMachines.LOGGER.info(String.format(Locale.ROOT,
                "%s run=%s generation=%d index=%d worm=%s score=%.5f forward=%.5f sideways=%.5f candidate=\"%s\" servoPos=%s actualDeg=%s targetDeg=%s errorDeg=%s velocityDegS=%s torque=%s load=%s",
                prefix,
                run.runId(),
                run.generation() + 1,
                index,
                episode.worm().wormId(),
                scored.score(),
                scored.forwardDisplacement(),
                scored.sidewaysDisplacement(),
                scored.candidate().compactDescription(),
                servo == null ? "missing" : servo.getBlockPos().toShortString(),
                servo == null ? "missing" : format(servo.getActualAngleDegrees()),
                servo == null ? "missing" : format(servo.getTargetAngleDegrees()),
                servo == null ? "missing" : format(servo.getAngleErrorDegrees()),
                servo == null ? "missing" : format(servo.getAngularVelocityDegreesPerSecond()),
                servo == null ? "missing" : format(servo.getEstimatedTorque()),
                servo == null ? "missing" : format(servo.getJointLoad())));
    }

    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static Vec3 directionVector(final Direction direction) {
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static @Nullable ServerPlayer owner(final MinecraftServer server, final UUID ownerId) {
        return server.getPlayerList().getPlayer(ownerId);
    }

    private static final class TrainingRun {
        private final UUID runId;
        private final UUID ownerId;
        private final ResourceKey<Level> dimension;
        private final BlockPos origin;
        private final WormCemConfig config;
        private final Random random;
        private WormCemDistribution distribution;
        private List<ActiveWormEpisode> episodes = List.of();
        private long generationStartGameTime;
        private int generation;
        private String lastFailure = "unknown failure";

        private TrainingRun(
                final UUID runId,
                final UUID ownerId,
                final ResourceKey<Level> dimension,
                final BlockPos origin,
                final WormCemConfig config,
                final WormCemDistribution distribution,
                final Random random
        ) {
            this.runId = runId;
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.origin = origin.immutable();
            this.config = config;
            this.distribution = distribution;
            this.random = random;
        }

        boolean spawnGeneration(final ServerLevel level) {
            this.clear(level);

            final List<ActiveWormEpisode> spawned = new ArrayList<>(this.config.populationSize());
            for (int i = 0; i < this.config.populationSize(); i++) {
                final BlockPos start = this.origin.relative(DEFAULT_SIDE_DIRECTION, i * this.config.spacingBlocks());
                final BlockPos servoPos = MinecraftMachinesWormSpawner.findWormSpawn(level, start, DEFAULT_FORWARD_DIRECTION, DEFAULT_SIDE_DIRECTION);
                if (servoPos == null) {
                    this.lastFailure = "Could not find clear surface space for worm " + i + " near " + start.toShortString();
                    spawned.forEach(episode -> MinecraftMachinesWormSpawner.removeWorm(level, episode.worm()));
                    this.episodes = List.of();
                    return false;
                }

                final MinecraftMachinesWormSpawner.SpawnResult spawnResult = MinecraftMachinesWormSpawner.spawn(
                        level,
                        servoPos,
                        DEFAULT_FORWARD_DIRECTION,
                        DEFAULT_SIDE_DIRECTION,
                        false);
                if (!spawnResult.success() || spawnResult.worm() == null) {
                    this.lastFailure = "Failed to spawn worm " + i + ": " + spawnResult.message();
                    spawned.forEach(episode -> MinecraftMachinesWormSpawner.removeWorm(level, episode.worm()));
                    this.episodes = List.of();
                    return false;
                }

                final WormInstance worm = spawnResult.worm();
                final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                        worm.servoPos(),
                        worm.servoInstanceId(),
                        worm.childSubLevelId());
                if (servo == null) {
                    this.lastFailure = "Failed to resolve training servo for worm " + i + " at " + worm.servoPos().toShortString();
                    MinecraftMachinesWormSpawner.removeWorm(level, worm);
                    spawned.forEach(episode -> MinecraftMachinesWormSpawner.removeWorm(level, episode.worm()));
                    this.episodes = List.of();
                    return false;
                }
                configureTrainingServo(servo, true);

                final Vec3 startPosition = MinecraftMachinesWormSpawner.getBodyPosition(level, worm.baseSubLevelId());
                if (startPosition == null) {
                    this.lastFailure = "Failed to read start pose for worm " + i;
                    MinecraftMachinesWormSpawner.removeWorm(level, worm);
                    spawned.forEach(episode -> MinecraftMachinesWormSpawner.removeWorm(level, episode.worm()));
                    this.episodes = List.of();
                    return false;
                }
                spawned.add(new ActiveWormEpisode(worm, this.distribution.sample(this.random), startPosition));
            }

            this.episodes = spawned;
            this.generationStartGameTime = level.getGameTime();
            MinecraftMachines.LOGGER.info("MM_WORM_CEM_GENERATION_START run={} generation={} population={} distribution={}",
                    this.runId,
                    this.generation + 1,
                    this.episodes.size(),
                    this.distribution.compactDescription());
            return true;
        }

        boolean tick(final MinecraftServer server) {
            final ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                MinecraftMachines.LOGGER.info("MM_WORM_CEM_END run={} reason=missing_level", this.runId);
                return false;
            }

            final long elapsedTicks = level.getGameTime() - this.generationStartGameTime;
            if (elapsedTicks >= this.config.episodeLengthTicks()) {
                return this.finishGeneration(server, level);
            }

            final double elapsedSeconds = elapsedTicks / 20.0;
            final List<ActiveWormEpisode> refreshed = new ArrayList<>(this.episodes.size());
            for (final ActiveWormEpisode episode : this.episodes) {
                final ActiveWormEpisode current = refreshServoPosition(level, episode);
                driveServo(level, current, elapsedSeconds);
                refreshed.add(current);
            }
            this.episodes = refreshed;
            return true;
        }

        private boolean finishGeneration(final MinecraftServer server, final ServerLevel level) {
            final List<ScoredWormCandidate> scored = new ArrayList<>(this.episodes.size());
            for (int i = 0; i < this.episodes.size(); i++) {
                final ActiveWormEpisode episode = refreshServoPosition(level, this.episodes.get(i));
                final ScoredWormCandidate candidate = scoreEpisode(level, episode);
                scored.add(candidate);
                final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                        episode.worm().servoPos(),
                        episode.worm().servoInstanceId(),
                        episode.worm().childSubLevelId());
                logScoredCandidate("MM_WORM_CEM_CANDIDATE", this, i, episode, candidate, servo);
            }

            final ScoredWormCandidate generationBest = scored.stream()
                    .max(Comparator.comparingDouble(ScoredWormCandidate::score))
                    .orElseThrow();
            if (bestEver == null || generationBest.score() > bestEver.score()) {
                bestEver = generationBest;
                MinecraftMachines.LOGGER.info("MM_WORM_CEM_BEST run={} generation={} score={} candidate={}",
                        this.runId,
                        this.generation + 1,
                        generationBest.score(),
                        generationBest.candidate().compactDescription());
            }

            final WormCemDistribution previousDistribution = this.distribution;
            this.distribution = this.distribution.update(scored, this.config.eliteCount());
            MinecraftMachines.LOGGER.info("MM_WORM_CEM_GENERATION_END run={} generation={} bestScore={} bestCandidate=\"{}\" oldDistribution=\"{}\" newDistribution=\"{}\"",
                    this.runId,
                    this.generation + 1,
                    generationBest.score(),
                    generationBest.candidate().compactDescription(),
                    previousDistribution.compactDescription(),
                    this.distribution.compactDescription());

            final ServerPlayer player = owner(server, this.ownerId);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Worm CEM gen %d/%d best %.3f: %s"
                        .formatted(this.generation + 1,
                                this.config.maxGenerations(),
                                generationBest.score(),
                                generationBest.candidate().compactDescription())));
            }

            this.clear(level);
            this.generation++;
            if (this.generation >= this.config.maxGenerations()) {
                MinecraftMachines.LOGGER.info("MM_WORM_CEM_END run={} reason=complete best={}",
                        this.runId,
                        bestEver == null ? "none" : bestEver.candidate().compactDescription());
                if (player != null) {
                    player.sendSystemMessage(Component.literal("Worm CEM complete. Best score %.3f: %s"
                            .formatted(bestEver == null ? 0.0 : bestEver.score(),
                                    bestEver == null ? "none" : bestEver.candidate().compactDescription())));
                }
                return false;
            }

            if (!this.spawnGeneration(level)) {
                MinecraftMachines.LOGGER.info("MM_WORM_CEM_END run={} reason=spawn_failed failure={}", this.runId, this.lastFailure);
                if (player != null) {
                    player.sendSystemMessage(Component.literal("Worm CEM stopped: " + this.lastFailure));
                }
                return false;
            }
            return true;
        }

        void clear(final ServerLevel level) {
            this.episodes.forEach(episode -> MinecraftMachinesWormSpawner.removeWorm(level, episode.worm()));
            this.episodes = List.of();
        }

        int applyServoControl(final ServerLevel level, final boolean resetTarget) {
            int applied = 0;
            final List<ActiveWormEpisode> refreshed = new ArrayList<>(this.episodes.size());
            for (final ActiveWormEpisode episode : this.episodes) {
                final ActiveWormEpisode current = refreshServoPosition(level, episode);
                final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                        current.worm().servoPos(),
                        current.worm().servoInstanceId(),
                        current.worm().childSubLevelId());
                if (servo != null) {
                    configureTrainingServo(servo, resetTarget);
                    applied++;
                }
                refreshed.add(current);
            }
            this.episodes = refreshed;
            return applied;
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

        WormCemConfig config() {
            return this.config;
        }

        WormCemDistribution distribution() {
            return this.distribution;
        }

        List<ActiveWormEpisode> episodes() {
            return this.episodes;
        }

        long generationStartGameTime() {
            return this.generationStartGameTime;
        }

        int generation() {
            return this.generation;
        }

        String lastFailure() {
            return this.lastFailure;
        }
    }

    private record ReplayRun(
            ResourceKey<Level> dimension,
            WormCemCandidate candidate,
            ActiveWormEpisode episode,
            long startGameTime
    ) {
        static @Nullable ReplayRun spawn(final ServerLevel level, final BlockPos origin, final WormCemCandidate candidate) {
            final BlockPos servoPos = MinecraftMachinesWormSpawner.findWormSpawn(level, origin, DEFAULT_FORWARD_DIRECTION, DEFAULT_SIDE_DIRECTION);
            if (servoPos == null) {
                return null;
            }

            final MinecraftMachinesWormSpawner.SpawnResult spawnResult = MinecraftMachinesWormSpawner.spawn(
                    level,
                    servoPos,
                    DEFAULT_FORWARD_DIRECTION,
                    DEFAULT_SIDE_DIRECTION,
                    false);
            if (!spawnResult.success() || spawnResult.worm() == null) {
                return null;
            }

            final WormInstance worm = spawnResult.worm();
            final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                    worm.servoPos(),
                    worm.servoInstanceId(),
                    worm.childSubLevelId());
            if (servo == null) {
                MinecraftMachinesWormSpawner.removeWorm(level, worm);
                return null;
            }
            configureTrainingServo(servo, true);

            final Vec3 startPosition = MinecraftMachinesWormSpawner.getBodyPosition(level, worm.baseSubLevelId());
            if (startPosition == null) {
                MinecraftMachinesWormSpawner.removeWorm(level, worm);
                return null;
            }

            return new ReplayRun(level.dimension(), candidate, new ActiveWormEpisode(worm, candidate, startPosition), level.getGameTime());
        }

        boolean tick(final MinecraftServer server) {
            final ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                return false;
            }

            final ActiveWormEpisode current = refreshServoPosition(level, this.episode);
            driveServo(level, current, (level.getGameTime() - this.startGameTime) / 20.0);
            if (current != this.episode) {
                replayRun = new ReplayRun(this.dimension, this.candidate, current, this.startGameTime);
            }
            return true;
        }

        void clear(final ServerLevel level) {
            MinecraftMachinesWormSpawner.removeWorm(level, this.episode.worm());
        }

        int applyServoControl(final ServerLevel level, final boolean resetTarget) {
            final ActiveWormEpisode current = refreshServoPosition(level, this.episode);
            final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                    current.worm().servoPos(),
                    current.worm().servoInstanceId(),
                    current.worm().childSubLevelId());
            if (servo == null) {
                return 0;
            }
            configureTrainingServo(servo, resetTarget);
            if (current != this.episode) {
                replayRun = new ReplayRun(this.dimension, this.candidate, current, this.startGameTime);
            }
            return 1;
        }
    }

    private record ServoControl(
            double maxSpeedDegS,
            double stiffness,
            double damping,
            double maxTorque
    ) {
        ServoControl {
            requireFinitePositive("maxSpeedDegS", maxSpeedDegS);
            requireFiniteNonNegative("stiffness", stiffness);
            requireFiniteNonNegative("damping", damping);
            requireFiniteNonNegative("maxTorque", maxTorque);
        }

        String compactDescription() {
            return String.format(Locale.ROOT,
                    "maxSpeedDegS=%.1f stiffness=%.1f damping=%.1f maxTorque=%.0f",
                    this.maxSpeedDegS,
                    this.stiffness,
                    this.damping,
                    this.maxTorque);
        }

        private static void requireFinitePositive(final String name, final double value) {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(name + " must be finite and positive");
            }
        }

        private static void requireFiniteNonNegative(final String name, final double value) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            }
        }
    }
}
