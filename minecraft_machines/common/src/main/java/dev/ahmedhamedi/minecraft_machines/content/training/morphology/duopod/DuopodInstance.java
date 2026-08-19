package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

public record DuopodInstance(
        UUID machineId,
        UUID batchId,
        UUID baseSubLevelId,
        UUID leftChildSubLevelId,
        UUID rightChildSubLevelId,
        BlockPos leftServoPosition,
        BlockPos rightServoPosition,
        UUID leftServoInstanceId,
        UUID rightServoInstanceId,
        Vector3d leftHoneyTipLocalOffset,
        Vector3d rightHoneyTipLocalOffset,
        Vec3 spawnPosition,
        Quaterniond spawnOrientation,
        Direction modelForwardDirection
) {
    public static final String MORPHOLOGY_TYPE = "duopod";

    public DuopodInstance {
        leftServoPosition = leftServoPosition.immutable();
        rightServoPosition = rightServoPosition.immutable();
        leftHoneyTipLocalOffset = new Vector3d(leftHoneyTipLocalOffset);
        rightHoneyTipLocalOffset = new Vector3d(rightHoneyTipLocalOffset);
        spawnOrientation = new Quaterniond(spawnOrientation);
        if (modelForwardDirection.getAxis().isVertical()) {
            throw new IllegalArgumentException("modelForwardDirection must be horizontal");
        }
    }

    @Override
    public Quaterniond spawnOrientation() {
        return new Quaterniond(this.spawnOrientation);
    }

    public Quaterniondc spawnOrientationView() {
        return this.spawnOrientation;
    }

    @Override
    public Vector3d leftHoneyTipLocalOffset() {
        return new Vector3d(this.leftHoneyTipLocalOffset);
    }

    @Override
    public Vector3d rightHoneyTipLocalOffset() {
        return new Vector3d(this.rightHoneyTipLocalOffset);
    }

    public Vector3dc leftHoneyTipLocalOffsetView() {
        return this.leftHoneyTipLocalOffset;
    }

    public Vector3dc rightHoneyTipLocalOffsetView() {
        return this.rightHoneyTipLocalOffset;
    }

    public Direction modelRightDirection() {
        return DuopodKinematics.modelRightDirection(this.modelForwardDirection);
    }

    public DuopodInstance withServoPositions(final BlockPos leftServoPosition, final BlockPos rightServoPosition) {
        return new DuopodInstance(
                this.machineId,
                this.batchId,
                this.baseSubLevelId,
                this.leftChildSubLevelId,
                this.rightChildSubLevelId,
                leftServoPosition,
                rightServoPosition,
                this.leftServoInstanceId,
                this.rightServoInstanceId,
                this.leftHoneyTipLocalOffset,
                this.rightHoneyTipLocalOffset,
                this.spawnPosition,
                this.spawnOrientation,
                this.modelForwardDirection);
    }
}
