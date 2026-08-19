package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class MinecraftMachinesServoDemo {
    private static final double AMPLITUDE_DEGREES = 80.0;
    private static final int PERIOD_TICKS = 80;
    private static final Map<UUID, DemoServo> ACTIVE_DEMOS = new HashMap<>();

    private MinecraftMachinesServoDemo() {
    }

    public static void start(final ServerLevel level, final BlockPos servoPos) {
        final UUID demoId = UUID.randomUUID();
        UUID servoInstanceId = null;
        UUID attachedSubLevelId = null;
        final BlockEntity blockEntity = level.getBlockEntity(servoPos);
        if (blockEntity instanceof final RoboticServoJointBlockEntity servo) {
            servoInstanceId = servo.getServoInstanceId();
            attachedSubLevelId = servo.getAttachedSubLevelId();
        }

        ACTIVE_DEMOS.put(demoId, new DemoServo(demoId,
                level.dimension(),
                servoPos.immutable(),
                servoInstanceId,
                attachedSubLevelId,
                level.getGameTime()));
    }

    public static int stopAll() {
        final int count = ACTIVE_DEMOS.size();
        ACTIVE_DEMOS.clear();
        return count;
    }

    public static void tick(final ServerTickEvent.Pre event) {
        if (ACTIVE_DEMOS.isEmpty()) {
            return;
        }

        final MinecraftServer server = event.getServer();
        final Iterator<Map.Entry<UUID, DemoServo>> iterator = ACTIVE_DEMOS.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, DemoServo> entry = iterator.next();
            final DemoServo demo = entry.getValue();
            final ServerLevel level = server.getLevel(demo.dimension());
            if (level == null) {
                iterator.remove();
                continue;
            }

            final RoboticServoJointBlockEntity servo = MinecraftMachinesServoLocator.resolve(level,
                    demo.servoPos(),
                    demo.servoInstanceId(),
                    demo.attachedSubLevelId());
            if (servo == null) {
                iterator.remove();
                continue;
            }
            if (!servo.getBlockPos().equals(demo.servoPos()) || !sameAttachedSubLevel(demo, servo)) {
                entry.setValue(new DemoServo(
                        demo.demoId(),
                        demo.dimension(),
                        servo.getBlockPos().immutable(),
                        servo.getServoInstanceId(),
                        servo.getAttachedSubLevelId(),
                        demo.startGameTime()
                ));
            }

            final double elapsed = level.getGameTime() - demo.startGameTime();
            final double phase = (elapsed % PERIOD_TICKS) / PERIOD_TICKS;
            final double angleDegrees = AMPLITUDE_DEGREES * Math.sin(phase * 2.0 * Math.PI);
            servo.setTargetAngleDegrees(angleDegrees);
        }
    }

    private static boolean sameAttachedSubLevel(final DemoServo demo, final RoboticServoJointBlockEntity servo) {
        final UUID current = servo.getAttachedSubLevelId();
        return demo.attachedSubLevelId() == null ? current == null : demo.attachedSubLevelId().equals(current);
    }

    private record DemoServo(
            UUID demoId,
            ResourceKey<Level> dimension,
            BlockPos servoPos,
            @Nullable UUID servoInstanceId,
            @Nullable UUID attachedSubLevelId,
            long startGameTime
    ) {
    }
}
