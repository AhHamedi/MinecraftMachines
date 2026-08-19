package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.List;
import java.util.Objects;

public record ActionSpec(
        String schemaId,
        int schemaVersion,
        List<VectorFieldSpec> fields
) {
    public ActionSpec {
        schemaId = requireText("schemaId", schemaId);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }
    }

    public int size() {
        return this.fields.size();
    }

    public String compatibilityHash() {
        return ObservationSpec.compatibilityHash("action", this.schemaId, this.schemaVersion, this.fields);
    }

    private static String requireText(final String name, final String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
