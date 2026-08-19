package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;

public final class PointTargetCommandGenerator {
    private final PointTargetCommandConfig config;

    public PointTargetCommandGenerator(final PointTargetCommandConfig config) {
        this.config = config;
    }

    public LocomotionCommand commandForTarget(
            final Vec3 basePosition,
            final Quaterniondc baseOrientation,
            final Direction modelForwardDirection,
            final Vec3 targetPosition
    ) {
        final DuopodKinematics.LocalOffset offset = DuopodKinematics.horizontalLocalOffset(
                basePosition,
                baseOrientation,
                modelForwardDirection,
                targetPosition);
        return this.commandForLocalOffset(offset.forward(), offset.right());
    }

    public LocomotionCommand commandForLocalOffset(final double localForward, final double localRight) {
        return PointTargetCommandMath.commandForLocalOffset(this.config, localForward, localRight);
    }

    public PointTargetCommandConfig config() {
        return this.config;
    }

}
