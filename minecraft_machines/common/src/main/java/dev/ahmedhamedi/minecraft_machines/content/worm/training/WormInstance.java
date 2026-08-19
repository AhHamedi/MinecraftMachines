package dev.ahmedhamedi.minecraft_machines.content.worm.training;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.UUID;

public record WormInstance(
        UUID wormId,
        BlockPos servoPos,
        UUID servoInstanceId,
        UUID baseSubLevelId,
        UUID childSubLevelId,
        Direction forwardDirection,
        Direction sideDirection
) {
    public WormInstance withServoPos(final BlockPos servoPos) {
        return new WormInstance(
                this.wormId,
                servoPos,
                this.servoInstanceId,
                this.baseSubLevelId,
                this.childSubLevelId,
                this.forwardDirection,
                this.sideDirection);
    }
}
