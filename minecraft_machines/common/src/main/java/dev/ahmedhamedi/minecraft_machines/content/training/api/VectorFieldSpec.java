package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.Objects;

public record VectorFieldSpec(
        String name,
        double minimum,
        double maximum,
        String unit,
        String description
) {
    public VectorFieldSpec {
        name = requireText("name", name);
        unit = Objects.requireNonNull(unit, "unit");
        description = Objects.requireNonNull(description, "description");
        requireFinite("minimum", minimum);
        requireFinite("maximum", maximum);
        if (minimum >= maximum) {
            throw new IllegalArgumentException("minimum must be lower than maximum");
        }
    }

    String compatibilityToken() {
        return this.name + "|" + this.minimum + "|" + this.maximum + "|" + this.unit + "|" + this.description;
    }

    private static String requireText(final String name, final String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
