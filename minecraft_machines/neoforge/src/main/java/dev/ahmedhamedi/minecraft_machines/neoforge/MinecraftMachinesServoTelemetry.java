package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ahmedhamedi.minecraft_machines.content.servo.ServoJointTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MinecraftMachinesServoTelemetry {
    private static final int DEFAULT_RADIUS = 32;
    private static final int DEFAULT_DURATION_TICKS = 240;
    private static final Map<UUID, Capture> CAPTURES = new HashMap<>();

    private MinecraftMachinesServoTelemetry() {
    }

    public static int startNearest(final ServerPlayer player) {
        final BlockPos servoPos = findNearestServo(player, DEFAULT_RADIUS);
        if (servoPos == null) {
            player.sendSystemMessage(Component.literal("No Robotic Servo Joint found within " + DEFAULT_RADIUS + " blocks."));
            return 0;
        }

        start(player, servoPos, DEFAULT_DURATION_TICKS);
        return 1;
    }

    public static void start(final ServerPlayer player, final BlockPos servoPos, final int durationTicks) {
        UUID servoInstanceId = null;
        UUID attachedSubLevelId = null;
        final BlockEntity blockEntity = player.serverLevel().getBlockEntity(servoPos);
        if (blockEntity instanceof final RoboticServoJointBlockEntity servo) {
            servoInstanceId = servo.getServoInstanceId();
            attachedSubLevelId = servo.getAttachedSubLevelId();
        }

        final Capture capture = new Capture(
                UUID.randomUUID(),
                player.getUUID(),
                player.serverLevel().dimension(),
                servoPos.immutable(),
                servoInstanceId,
                attachedSubLevelId,
                player.serverLevel().getGameTime(),
                Math.max(1, durationTicks)
        );

        CAPTURES.put(player.getUUID(), capture);
        MinecraftMachines.LOGGER.info("MM_SERVO_TELEMETRY_BEGIN capture={} player={} dimension={} pos={} durationTicks={}",
                capture.captureId(),
                player.getGameProfile().getName(),
                capture.dimension().location(),
                capture.servoPos().toShortString(),
                capture.durationTicks());
        player.sendSystemMessage(Component.literal("Started servo telemetry capture " + capture.captureId()
                + " at " + capture.servoPos().toShortString()
                + " for " + capture.durationTicks() + " ticks. Data is written to latest.log."));
    }

    public static int stop(final ServerPlayer player) {
        final Capture removed = CAPTURES.remove(player.getUUID());
        if (removed == null) {
            player.sendSystemMessage(Component.literal("No active servo telemetry capture."));
            return 0;
        }

        MinecraftMachines.LOGGER.info("MM_SERVO_TELEMETRY_CANCEL capture={} player={}",
                removed.captureId(),
                player.getGameProfile().getName());
        player.sendSystemMessage(Component.literal("Stopped servo telemetry capture " + removed.captureId() + "."));
        return 1;
    }

    public static int dumpNearest(final ServerPlayer player) {
        final BlockPos servoPos = findNearestServo(player, DEFAULT_RADIUS);
        if (servoPos == null) {
            player.sendSystemMessage(Component.literal("No Robotic Servo Joint found within " + DEFAULT_RADIUS + " blocks."));
            return 0;
        }

        final BlockEntity blockEntity = player.serverLevel().getBlockEntity(servoPos);
        if (blockEntity instanceof final RoboticServoJointBlockEntity servo) {
            logServoLine("MM_SERVO_TELEMETRY_ONCE", UUID.randomUUID(), player.serverLevel().getGameTime(), 0, servo);
            player.sendSystemMessage(Component.literal("Dumped servo telemetry for " + servoPos.toShortString() + " to latest.log."));
            return 1;
        }

        return 0;
    }

    public static void tick(final ServerTickEvent.Post event) {
        if (CAPTURES.isEmpty()) {
            return;
        }

        final MinecraftServer server = event.getServer();
        final Iterator<Map.Entry<UUID, Capture>> iterator = CAPTURES.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Capture> entry = iterator.next();
            final Capture capture = entry.getValue();
            final ServerLevel level = server.getLevel(capture.dimension());
            final ServerPlayer player = server.getPlayerList().getPlayer(capture.playerId());
            if (level == null || player == null) {
                MinecraftMachines.LOGGER.info("MM_SERVO_TELEMETRY_END capture={} reason=missing_level_or_player", capture.captureId());
                iterator.remove();
                continue;
            }

            final long elapsed = level.getGameTime() - capture.startGameTime();
            if (elapsed > capture.durationTicks()) {
                MinecraftMachines.LOGGER.info("MM_SERVO_TELEMETRY_END capture={} reason=complete", capture.captureId());
                player.sendSystemMessage(Component.literal("Servo telemetry capture complete: " + capture.captureId()));
                iterator.remove();
                continue;
            }

            final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                    capture.servoPos(),
                    capture.servoInstanceId(),
                    capture.attachedSubLevelId());
            if (servo == null) {
                MinecraftMachines.LOGGER.info("MM_SERVO_TELEMETRY_END capture={} reason=missing_servo", capture.captureId());
                player.sendSystemMessage(Component.literal("Servo telemetry capture stopped; servo block disappeared."));
                iterator.remove();
                continue;
            }
            if (!servo.getBlockPos().equals(capture.servoPos()) || !sameAttachedSubLevel(capture, servo)) {
                entry.setValue(new Capture(
                        capture.captureId(),
                        capture.playerId(),
                        capture.dimension(),
                        servo.getBlockPos().immutable(),
                        servo.getServoInstanceId(),
                        servo.getAttachedSubLevelId(),
                        capture.startGameTime(),
                        capture.durationTicks()
                ));
            }

            logServoLine("MM_SERVO_TELEMETRY", capture.captureId(), level.getGameTime(), elapsed, servo);
            if (elapsed % 20 == 0) {
                player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                        "Servo req %.1f eff %.1f act %.1f err %.1f vel %.1f torque %.0f",
                        servo.getRequestedTargetAngleDegrees(),
                        servo.getEffectiveTargetAngleDegrees(),
                        servo.getActualAngleDegrees(),
                        servo.getAngleErrorDegrees(),
                        servo.getAngularVelocityDegreesPerSecond(),
                        servo.getEstimatedTorque())), true);
            }
        }
    }

    private static @Nullable BlockPos findNearestServo(final ServerPlayer player, final int radius) {
        final ServerLevel level = player.serverLevel();
        final BlockPos center = player.blockPosition();
        final BlockPos min = center.offset(-radius, -radius, -radius);
        final BlockPos max = center.offset(radius, radius, radius);

        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (final BlockPos pos : BlockPos.betweenClosed(min, max)) {
            final BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof RoboticServoJointBlockEntity)) {
                continue;
            }

            final double distance = pos.distSqr(center);
            if (distance < nearestDistance) {
                nearest = pos.immutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static boolean sameAttachedSubLevel(final Capture capture, final RoboticServoJointBlockEntity servo) {
        final UUID current = servo.getAttachedSubLevelId();
        return capture.attachedSubLevelId() == null ? current == null : capture.attachedSubLevelId().equals(current);
    }

    private static void logServoLine(final String prefix, final UUID captureId, final long gameTime, final long elapsed, final RoboticServoJointBlockEntity servo) {
        final ServoJointTelemetry telemetry = servo.getTelemetry();
        final BlockPos linkPos = servo.getInternalLinkPos();
        final UUID subLevelId = servo.getAttachedSubLevelId();

        MinecraftMachines.LOGGER.info(String.format(Locale.ROOT,
                "%s capture=%s gameTime=%d elapsed=%d pos=%s assembled=%s validConstraint=%s enabled=%s requestedDeg=%.4f effectiveDeg=%.4f actualDeg=%.4f errorDeg=%.4f velocityDegS=%.4f estimatedTorque=%.4f jointLoad=%.4f minDeg=%.4f maxDeg=%.4f atMin=%s atMax=%s maxSpeedDegS=%.4f maxTorque=%.4f configStiffness=%.4f configDamping=%.4f passiveDamping=%.4f controlInertia=%.4f appliedTargetDeg=%.4f appliedStiffness=%.4f appliedDamping=%.4f appliedMaxTorque=%.4f forceLimited=%s linkPos=%s subLevel=%s",
                prefix,
                captureId,
                gameTime,
                elapsed,
                servo.getBlockPos().toShortString(),
                telemetry.assembled(),
                servo.hasValidConstraint(),
                telemetry.enabled(),
                servo.getRequestedTargetAngleDegrees(),
                servo.getEffectiveTargetAngleDegrees(),
                servo.getActualAngleDegrees(),
                servo.getAngleErrorDegrees(),
                servo.getAngularVelocityDegreesPerSecond(),
                telemetry.estimatedTorque(),
                telemetry.jointLoad(),
                servo.getMinimumAngleDegrees(),
                servo.getMaximumAngleDegrees(),
                telemetry.atMinimumLimit(),
                telemetry.atMaximumLimit(),
                servo.getMaxAngularSpeedDegreesPerSecond(),
                servo.getMaxTorque(),
                servo.getStiffness(),
                servo.getDamping(),
                servo.getPassiveDamping(),
                servo.getControlInertia(),
                servo.getAppliedMotorTargetAngleDegrees(),
                servo.getAppliedMotorStiffness(),
                servo.getAppliedMotorDamping(),
                servo.getAppliedMotorMaxTorque(),
                servo.isAppliedMotorForceLimited(),
                linkPos == null ? "null" : linkPos.toShortString(),
                subLevelId == null ? "null" : subLevelId));
    }

    private record Capture(
            UUID captureId,
            UUID playerId,
            ResourceKey<Level> dimension,
            BlockPos servoPos,
            @Nullable UUID servoInstanceId,
            @Nullable UUID attachedSubLevelId,
            long startGameTime,
            int durationTicks
    ) {
    }
}
