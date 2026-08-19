package dev.ahmedhamedi.minecraft_machines.content.training.bridge;

import java.util.Arrays;
import java.util.Objects;

public record StepRequest(
        String sessionId,
        String observationSchemaHash,
        String actionSchemaHash,
        double[][] actions
) {
    public StepRequest {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (observationSchemaHash == null || observationSchemaHash.isBlank()) {
            throw new IllegalArgumentException("observationSchemaHash must not be blank");
        }
        if (actionSchemaHash == null || actionSchemaHash.isBlank()) {
            throw new IllegalArgumentException("actionSchemaHash must not be blank");
        }
        actions = copy(actions);
    }

    public void validateAgainst(final BridgeSpecs specs) {
        Objects.requireNonNull(specs, "specs");
        if (!specs.observationSpec().compatibilityHash().equals(this.observationSchemaHash)) {
            throw new IllegalArgumentException("observation schema hash mismatch");
        }
        if (!specs.actionSpec().compatibilityHash().equals(this.actionSchemaHash)) {
            throw new IllegalArgumentException("action schema hash mismatch");
        }
        if (this.actions.length != specs.slotCount()) {
            throw new IllegalArgumentException("slot count mismatch");
        }
        for (int slot = 0; slot < this.actions.length; slot++) {
            if (this.actions[slot].length != specs.actionSpec().size()) {
                throw new IllegalArgumentException("action size mismatch at slot " + slot);
            }
            for (final double value : this.actions[slot]) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("actions must be finite");
                }
            }
        }
    }

    @Override
    public double[][] actions() {
        return copy(this.actions);
    }

    private static double[][] copy(final double[][] source) {
        if (source == null) {
            throw new IllegalArgumentException("actions must not be null");
        }
        final double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            if (source[i] == null) {
                throw new IllegalArgumentException("action row " + i + " must not be null");
            }
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }
}
