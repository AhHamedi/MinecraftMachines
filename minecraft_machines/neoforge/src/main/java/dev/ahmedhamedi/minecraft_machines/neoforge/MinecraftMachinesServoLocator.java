package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

final class MinecraftMachinesServoLocator {
    private MinecraftMachinesServoLocator() {
    }

    static @Nullable RoboticServoJointBlockEntity resolve(final ServerLevel level, final BlockPos lastKnownPos, @Nullable final UUID attachedSubLevelId) {
        return resolve(level, lastKnownPos, null, attachedSubLevelId);
    }

    static @Nullable RoboticServoJointBlockEntity resolve(
            final ServerLevel level,
            final BlockPos lastKnownPos,
            @Nullable final UUID servoInstanceId,
            @Nullable final UUID attachedSubLevelId
    ) {
        final BlockEntity blockEntity = level.getBlockEntity(lastKnownPos);
        if (blockEntity instanceof final RoboticServoJointBlockEntity servo
                && (servoInstanceId == null || servoInstanceId.equals(servo.getServoInstanceId()))) {
            return servo;
        }

        if (servoInstanceId != null) {
            final RoboticServoJointBlockEntity movedServo = findByServoInstanceId(level, servoInstanceId);
            if (movedServo != null) {
                return movedServo;
            }
        }

        return attachedSubLevelId == null ? null : findByAttachedSubLevelId(level, attachedSubLevelId);
    }

    private static @Nullable RoboticServoJointBlockEntity findByServoInstanceId(final ServerLevel level, final UUID servoInstanceId) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            for (final BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
                if (actor instanceof final RoboticServoJointBlockEntity servo && servoInstanceId.equals(servo.getServoInstanceId())) {
                    return servo;
                }
            }
        }

        return null;
    }

    private static @Nullable RoboticServoJointBlockEntity findByAttachedSubLevelId(final ServerLevel level, final UUID attachedSubLevelId) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            for (final BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
                if (actor instanceof final RoboticServoJointBlockEntity servo && attachedSubLevelId.equals(servo.getAttachedSubLevelId())) {
                    return servo;
                }
            }
        }

        return null;
    }
}
