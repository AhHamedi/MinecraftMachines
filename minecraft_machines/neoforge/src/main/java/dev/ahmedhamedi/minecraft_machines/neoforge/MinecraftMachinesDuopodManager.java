package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodKinematics;
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
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MinecraftMachinesDuopodManager {
    private static final int TELEMETRY_INTERVAL_TICKS = 20;
    private static final int SWEEP_STAGE_TICKS = 100;
    private static final int SPAWN_SEARCH_RADIUS_BLOCKS = 16;
    private static final double SWEEP_FREQUENCY_HZ = 1.0;
    private static final double MIN_FORWARD_AUTHORITY = 0.05;
    private static final double MIN_YAW_AUTHORITY_RAD = 0.02;

    private static final List<TrackedDuopod> DUOPODS = new ArrayList<>();
    private static final Set<UUID> TELEMETRY_PLAYERS = new HashSet<>();
    private static ControlSweep activeSweep;

    private MinecraftMachinesDuopodManager() {
    }

    public static int spawn(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }

        final Direction forward = player.getDirection();
        final ServerLevel level = player.serverLevel();
        final BlockPos requested = player.blockPosition().relative(forward, 5);
        final BlockPos centerPos = MinecraftMachinesDuopodSpawner.findDuopodSpawnNear(
                level,
                requested,
                forward,
                SPAWN_SEARCH_RADIUS_BLOCKS);
        if (centerPos == null) {
            source.sendFailure(Component.literal("Could not find clear surface space for a Duopod spawn within "
                    + SPAWN_SEARCH_RADIUS_BLOCKS + " blocks of " + requested.toShortString() + "."));
            return 0;
        }

        final MinecraftMachinesDuopodSpawner.SpawnResult result = MinecraftMachinesDuopodSpawner.spawn(level, centerPos, forward, UUID.randomUUID());
        if (!result.success() || result.duopod() == null) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        DUOPODS.add(new TrackedDuopod(level.dimension(), result.duopod()));
        source.sendSuccess(() -> Component.literal("Spawned Duopod %s at %s: one base body, two independent servo/cog/swivel limbs."
                .formatted(result.duopod().machineId(), centerPos.toShortString())), true);
        return 1;
    }

    public static int removeNearest(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        final Optional<TrackedDuopod> nearest = nearest(player.serverLevel(), player.position());
        if (nearest.isEmpty()) {
            source.sendFailure(Component.literal("No tracked Duopod found in this dimension."));
            return 0;
        }

        final TrackedDuopod tracked = nearest.get();
        MinecraftMachinesDuopodSpawner.removeDuopod(player.serverLevel(), tracked.duopod());
        DUOPODS.remove(tracked);
        source.sendSuccess(() -> Component.literal("Removed Duopod " + tracked.duopod().machineId() + "."), true);
        return 1;
    }

    public static int telemetry(final CommandSourceStack source, final boolean enabled) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        if (enabled) {
            TELEMETRY_PLAYERS.add(player.getUUID());
        } else {
            TELEMETRY_PLAYERS.remove(player.getUUID());
        }
        source.sendSuccess(() -> Component.literal("Duopod telemetry " + (enabled ? "enabled" : "disabled") + "."), false);
        return 1;
    }

    public static int controlSweep(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        final Direction forward = player.getDirection();
        return startControlSweep(
                source,
                player.serverLevel(),
                player.blockPosition().relative(forward, 5),
                forward,
                player.getUUID());
    }

    public static int controlSweepAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName
    ) {
        final Direction forward = parseHorizontalDirection(forwardName);
        if (forward == null) {
            source.sendFailure(Component.literal("forward must be one of north, south, east, or west"));
            return 0;
        }
        return startControlSweep(source, source.getLevel(), new BlockPos(x, y, z), forward, null);
    }

    private static int startControlSweep(
            final CommandSourceStack source,
            final ServerLevel level,
            final BlockPos requested,
            final Direction forward,
            final UUID ownerId
    ) {
        if (activeSweep != null) {
            source.sendFailure(Component.literal("A Duopod control sweep is already active."));
            return 0;
        }

        final BlockPos centerPos = MinecraftMachinesDuopodSpawner.findDuopodSpawnNear(
                level,
                requested,
                forward,
                SPAWN_SEARCH_RADIUS_BLOCKS);
        if (centerPos == null) {
            source.sendFailure(Component.literal("Could not find clear surface space for a Duopod control sweep within "
                    + SPAWN_SEARCH_RADIUS_BLOCKS + " blocks of " + requested.toShortString() + "."));
            return 0;
        }

        final MinecraftMachinesDuopodSpawner.SpawnResult result = MinecraftMachinesDuopodSpawner.spawn(level, centerPos, forward, UUID.randomUUID());
        if (!result.success() || result.duopod() == null) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        final MinecraftMachinesDuopodControl control = new MinecraftMachinesDuopodControl(level, result.duopod());
        activeSweep = new ControlSweep(ownerId, level.dimension(), control, level.getGameTime());
        DUOPODS.add(new TrackedDuopod(level.dimension(), result.duopod()));
        source.sendSuccess(() -> Component.literal("Started Duopod control sweep for " + result.duopod().machineId() + "."), true);
        return 1;
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

    public static void tick(final ServerTickEvent.Pre event) {
        final MinecraftServer server = event.getServer();
        tickSweep(server);
        tickTelemetry(server);
        DUOPODS.removeIf(tracked -> {
            final ServerLevel level = server.getLevel(tracked.dimension());
            return level == null || MinecraftMachinesDuopodSpawner.getServerSubLevel(level, tracked.duopod().baseSubLevelId()) == null;
        });
    }

    static List<TrackedDuopod> trackedDuopods() {
        return List.copyOf(DUOPODS);
    }

    private static void tickSweep(final MinecraftServer server) {
        final ControlSweep sweep = activeSweep;
        if (sweep == null) {
            return;
        }
        final ServerLevel level = server.getLevel(sweep.dimension());
        if (level == null) {
            activeSweep = null;
            return;
        }
        if (!sweep.tick(level)) {
            activeSweep = null;
            DUOPODS.removeIf(tracked -> tracked.duopod().machineId().equals(sweep.control().duopod().machineId()));
        }
    }

    private static void tickTelemetry(final MinecraftServer server) {
        if (TELEMETRY_PLAYERS.isEmpty()) {
            return;
        }
        for (final UUID playerId : Set.copyOf(TELEMETRY_PLAYERS)) {
            final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                TELEMETRY_PLAYERS.remove(playerId);
                continue;
            }
            final ServerLevel level = player.serverLevel();
            if (level.getGameTime() % TELEMETRY_INTERVAL_TICKS != 0L) {
                continue;
            }
            nearest(level, player.position()).ifPresent(tracked -> {
                final MinecraftMachinesDuopodControl control = new MinecraftMachinesDuopodControl(level, tracked.duopod());
                final Vec3 pos = control.getBasePosition();
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "Duopod %s pos=(%.2f %.2f %.2f) left=%.2fdeg right=%.2fdeg leftLoad=%.2f rightLoad=%.2f valid=%s",
                        tracked.duopod().machineId(),
                        pos.x,
                        pos.y,
                        pos.z,
                        control.getLeftActualAngleDegrees(),
                        control.getRightActualAngleDegrees(),
                        control.getLeftLoad(),
                        control.getRightLoad(),
                        control.isValid())));
            });
        }
    }

    private static Optional<TrackedDuopod> nearest(final ServerLevel level, final Vec3 position) {
        return DUOPODS.stream()
                .filter(tracked -> tracked.dimension().equals(level.dimension()))
                .min(Comparator.comparingDouble(tracked -> {
                    final Vec3 bodyPosition = MinecraftMachinesDuopodSpawner.getBodyPosition(level, tracked.duopod().baseSubLevelId());
                    return bodyPosition == null ? Double.MAX_VALUE : bodyPosition.distanceToSqr(position);
                }));
    }

    record TrackedDuopod(ResourceKey<Level> dimension, DuopodInstance duopod) {
    }

    private enum SweepPattern {
        EQUAL_IN_PHASE,
        LEFT_STRONGER,
        RIGHT_STRONGER,
        OPPOSITE_SIGNED,
        LEFT_PHASE_LEADS,
        RIGHT_PHASE_LEADS
    }

    private record SweepMeasurement(
            SweepPattern pattern,
            double horizontalDisplacement,
            double forwardDisplacement,
            double lateralDisplacement,
            double signedYawChange,
            double averageForwardVelocity,
            double averageYawRate,
            double maxAbsLeftAngleDegrees,
            double maxAbsRightAngleDegrees,
            double maxAbsLeftAngularVelocity,
            double maxAbsRightAngularVelocity,
            double peakLeftLoad,
            double peakRightLoad,
            boolean valid
    ) {
        String compactLine() {
            return String.format(Locale.ROOT,
                    "%s h=%.3f f=%.3f lat=%.3f yaw=%.4f vf=%.3f yr=%.4f leftDeg=%.2f rightDeg=%.2f leftVel=%.3f rightVel=%.3f leftLoad=%.3f rightLoad=%.3f valid=%s",
                    this.pattern,
                    this.horizontalDisplacement,
                    this.forwardDisplacement,
                    this.lateralDisplacement,
                    this.signedYawChange,
                    this.averageForwardVelocity,
                    this.averageYawRate,
                    this.maxAbsLeftAngleDegrees,
                    this.maxAbsRightAngleDegrees,
                    this.maxAbsLeftAngularVelocity,
                    this.maxAbsRightAngularVelocity,
                    this.peakLeftLoad,
                    this.peakRightLoad,
                    this.valid);
        }
    }

    private static final class ControlSweep {
        private final UUID ownerId;
        private final ResourceKey<Level> dimension;
        private final MinecraftMachinesDuopodControl control;
        private final long startGameTime;
        private final List<SweepMeasurement> results = new ArrayList<>();
        private SweepPattern currentPattern = SweepPattern.EQUAL_IN_PHASE;
        private long stageStartTick;
        private Vec3 stageStartPosition;
        private Quaterniond stageStartOrientation;
        private double maxAbsLeftAngleDegrees;
        private double maxAbsRightAngleDegrees;
        private double maxAbsLeftAngularVelocity;
        private double maxAbsRightAngularVelocity;
        private double peakLeftLoad;
        private double peakRightLoad;

        private ControlSweep(
                final UUID ownerId,
                final ResourceKey<Level> dimension,
                final MinecraftMachinesDuopodControl control,
                final long startGameTime
        ) {
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.control = control;
            this.startGameTime = startGameTime;
            this.stageStartTick = startGameTime;
            this.stageStartPosition = control.getBasePosition();
            this.stageStartOrientation = new Quaterniond(control.getBaseOrientation());
        }

        boolean tick(final ServerLevel level) {
            final long elapsedInStage = level.getGameTime() - this.stageStartTick;
            if (elapsedInStage >= SWEEP_STAGE_TICKS) {
                this.finishStage(level);
                final int next = this.currentPattern.ordinal() + 1;
                if (next >= SweepPattern.values().length) {
                    this.finish(level);
                    this.control.commandNeutral();
                    this.control.destroy();
                    return false;
                }
                this.currentPattern = SweepPattern.values()[next];
                this.stageStartTick = level.getGameTime();
                this.stageStartPosition = this.control.getBasePosition();
                this.stageStartOrientation = new Quaterniond(this.control.getBaseOrientation());
                this.resetStageTelemetry();
            }

            final double elapsedSeconds = (level.getGameTime() - this.stageStartTick) / 20.0;
            final double phase = Math.PI * 2.0 * SWEEP_FREQUENCY_HZ * elapsedSeconds;
            final double[] action = actionFor(this.currentPattern, phase);
            this.control.setNormalizedAction(action[0], action[1]);
            this.sampleStageTelemetry();
            return true;
        }

        private void finishStage(final ServerLevel level) {
            final Vec3 endPosition = this.control.getBasePosition();
            final Quaterniond endOrientation = new Quaterniond(this.control.getBaseOrientation());
            final DuopodKinematics.Axes startAxes = DuopodKinematics.worldAxes(this.control.duopod().modelForwardDirection(), this.stageStartOrientation);
            final Vector3d displacement = new Vector3d(
                    endPosition.x - this.stageStartPosition.x,
                    0.0,
                    endPosition.z - this.stageStartPosition.z);
            final double forward = displacement.dot(startAxes.forward());
            final double lateral = displacement.dot(startAxes.right());
            final double horizontal = Math.hypot(forward, lateral);
            final double yaw = signedYawDelta(this.control.duopod().modelForwardDirection(), this.stageStartOrientation, endOrientation);
            final double seconds = SWEEP_STAGE_TICKS / 20.0;
            final SweepMeasurement measurement = new SweepMeasurement(
                    this.currentPattern,
                    horizontal,
                    forward,
                    lateral,
                    yaw,
                    forward / seconds,
                    yaw / seconds,
                    this.maxAbsLeftAngleDegrees,
                    this.maxAbsRightAngleDegrees,
                    this.maxAbsLeftAngularVelocity,
                    this.maxAbsRightAngularVelocity,
                    this.peakLeftLoad,
                    this.peakRightLoad,
                    this.control.isValid());
            this.results.add(measurement);
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CONTROL_SWEEP pattern={} result=\"{}\"", this.currentPattern, measurement.compactLine());
        }

        private void resetStageTelemetry() {
            this.maxAbsLeftAngleDegrees = 0.0;
            this.maxAbsRightAngleDegrees = 0.0;
            this.maxAbsLeftAngularVelocity = 0.0;
            this.maxAbsRightAngularVelocity = 0.0;
            this.peakLeftLoad = 0.0;
            this.peakRightLoad = 0.0;
        }

        private void sampleStageTelemetry() {
            this.maxAbsLeftAngleDegrees = Math.max(this.maxAbsLeftAngleDegrees, Math.abs(this.control.getLeftActualAngleDegrees()));
            this.maxAbsRightAngleDegrees = Math.max(this.maxAbsRightAngleDegrees, Math.abs(this.control.getRightActualAngleDegrees()));
            this.maxAbsLeftAngularVelocity = Math.max(this.maxAbsLeftAngularVelocity, Math.abs(this.control.getLeftAngularVelocityRadPerSecond()));
            this.maxAbsRightAngularVelocity = Math.max(this.maxAbsRightAngularVelocity, Math.abs(this.control.getRightAngularVelocityRadPerSecond()));
            this.peakLeftLoad = Math.max(this.peakLeftLoad, Math.abs(this.control.getLeftLoad()));
            this.peakRightLoad = Math.max(this.peakRightLoad, Math.abs(this.control.getRightLoad()));
        }

        private void finish(final ServerLevel level) {
            final ServerPlayer player = this.ownerId == null ? null : level.getServer().getPlayerList().getPlayer(this.ownerId);
            final Map<SweepPattern, SweepMeasurement> byPattern = new EnumMap<>(SweepPattern.class);
            this.results.forEach(result -> byPattern.put(result.pattern(), result));
            final double bestForward = this.results.stream().mapToDouble(SweepMeasurement::forwardDisplacement).max().orElse(0.0);
            final double bestPositiveYaw = this.results.stream().mapToDouble(SweepMeasurement::signedYawChange).max().orElse(0.0);
            final double bestNegativeYaw = this.results.stream().mapToDouble(SweepMeasurement::signedYawChange).min().orElse(0.0);
            final boolean passed = bestForward >= MIN_FORWARD_AUTHORITY
                    && bestPositiveYaw >= MIN_YAW_AUTHORITY_RAD
                    && bestNegativeYaw <= -MIN_YAW_AUTHORITY_RAD
                    && this.results.stream().allMatch(SweepMeasurement::valid);
            final String summary = "Duopod control sweep " + (passed ? "passed" : "finished below threshold")
                    + ": bestForward=%.3f bestPositiveYaw=%.4f bestNegativeYaw=%.4f".formatted(bestForward, bestPositiveYaw, bestNegativeYaw);
            MinecraftMachines.LOGGER.info("MM_DUOPOD_CONTROL_SWEEP_END passed={} bestForward={} bestPositiveYaw={} bestNegativeYaw={}",
                    passed,
                    bestForward,
                    bestPositiveYaw,
                    bestNegativeYaw);
            if (player != null) {
                player.sendSystemMessage(Component.literal(summary));
                this.results.forEach(result -> player.sendSystemMessage(Component.literal(result.compactLine())));
            }
        }

        private ResourceKey<Level> dimension() {
            return this.dimension;
        }

        private MinecraftMachinesDuopodControl control() {
            return this.control;
        }

        private static double[] actionFor(final SweepPattern pattern, final double phase) {
            return switch (pattern) {
                case EQUAL_IN_PHASE -> new double[]{0.70 * Math.sin(phase), 0.70 * Math.sin(phase)};
                case LEFT_STRONGER -> new double[]{0.85 * Math.sin(phase), 0.35 * Math.sin(phase)};
                case RIGHT_STRONGER -> new double[]{0.35 * Math.sin(phase), 0.85 * Math.sin(phase)};
                case OPPOSITE_SIGNED -> new double[]{0.70 * Math.sin(phase), -0.70 * Math.sin(phase)};
                case LEFT_PHASE_LEADS -> new double[]{0.70 * Math.sin(phase + Math.PI / 3.0), 0.70 * Math.sin(phase)};
                case RIGHT_PHASE_LEADS -> new double[]{0.70 * Math.sin(phase), 0.70 * Math.sin(phase + Math.PI / 3.0)};
            };
        }

        private static double signedYawDelta(
                final Direction modelForwardDirection,
                final Quaterniond startOrientation,
                final Quaterniond endOrientation
        ) {
            final Vector3dc startForward = DuopodKinematics.worldAxes(modelForwardDirection, startOrientation).forward();
            final Vector3dc endForward = DuopodKinematics.worldAxes(modelForwardDirection, endOrientation).forward();
            final double startX = startForward.x();
            final double startZ = startForward.z();
            final double endX = endForward.x();
            final double endZ = endForward.z();
            final double crossY = (startZ * endX) - (startX * endZ);
            final double dot = (startX * endX) + (startZ * endZ);
            return Math.atan2(crossY, dot);
        }
    }
}
