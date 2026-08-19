package dev.ahmedhamedi.minecraft_machines.content.training.bridge;

import dev.ahmedhamedi.minecraft_machines.content.training.api.ActionSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ObservationSpec;

import java.util.Objects;

public record BridgeSpecs(
        int protocolVersion,
        String modVersion,
        String morphologyId,
        ObservationSpec observationSpec,
        ActionSpec actionSpec,
        int slotCount,
        int controlTicks,
        String curriculumStage,
        long seed
) {
    public BridgeSpecs {
        if (protocolVersion != TrainingBridgeProtocol.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unsupported protocol version " + protocolVersion);
        }
        modVersion = requireText("modVersion", modVersion);
        morphologyId = requireText("morphologyId", morphologyId);
        observationSpec = Objects.requireNonNull(observationSpec, "observationSpec");
        actionSpec = Objects.requireNonNull(actionSpec, "actionSpec");
        if (slotCount < 1) {
            throw new IllegalArgumentException("slotCount must be positive");
        }
        if (controlTicks < 1) {
            throw new IllegalArgumentException("controlTicks must be positive");
        }
        curriculumStage = requireText("curriculumStage", curriculumStage);
    }

    private static String requireText(final String name, final String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
