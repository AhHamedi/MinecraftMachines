package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

final class MinecraftMachinesLiveDuopod {
    private final int slotIndex;
    private final UUID batchId;
    private MinecraftMachinesDuopodControl control;
    private boolean freshFromSpawn = true;
    private double standingHeightReferenceY = Double.NaN;
    private Vec3 standingCenterOfMassReference = null;

    MinecraftMachinesLiveDuopod(
            final int slotIndex,
            final UUID batchId,
            final MinecraftMachinesDuopodControl control
    ) {
        this.slotIndex = slotIndex;
        this.batchId = batchId;
        this.control = control;
    }

    int slotIndex() {
        return this.slotIndex;
    }

    UUID batchId() {
        return this.batchId;
    }

    MinecraftMachinesDuopodControl control() {
        return this.control;
    }

    void setControl(final MinecraftMachinesDuopodControl control) {
        this.control = control;
        this.freshFromSpawn = true;
        this.standingHeightReferenceY = Double.NaN;
        this.standingCenterOfMassReference = null;
    }

    boolean freshFromSpawn() {
        return this.freshFromSpawn;
    }

    void markResetConsumed() {
        this.freshFromSpawn = false;
    }

    void calibrateStandingHeightReference() {
        final Vec3 basePosition = this.control.getBasePosition();
        if (Double.isFinite(basePosition.y)) {
            this.standingHeightReferenceY = basePosition.y;
        }
        final Vec3 centerOfMassPosition = this.control.getAggregateCenterOfMassPosition();
        if (Double.isFinite(centerOfMassPosition.x)
                && Double.isFinite(centerOfMassPosition.y)
                && Double.isFinite(centerOfMassPosition.z)) {
            this.standingCenterOfMassReference = centerOfMassPosition;
        }
    }

    double standingHeightReferenceY() {
        return Double.isFinite(this.standingHeightReferenceY)
                ? this.standingHeightReferenceY
                : this.control.duopod().spawnPosition().y;
    }

    Vec3 standingCenterOfMassReference() {
        return this.standingCenterOfMassReference == null
                ? this.control.getAggregateCenterOfMassPosition()
                : this.standingCenterOfMassReference;
    }
}
