package dev.ahmedhamedi.minecraft_machines.content.training.bridge;

import com.google.gson.JsonObject;
import dev.ahmedhamedi.minecraft_machines.content.training.api.VectorFieldSpec;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record StepResponse(
        double[][] observations,
        double[] rewards,
        boolean[] terminated,
        boolean[] truncated,
        List<JsonObject> infos,
        double[][] resetObservations
) {
    public StepResponse {
        observations = copy("observations", observations);
        rewards = copy("rewards", rewards);
        terminated = copy("terminated", terminated);
        truncated = copy("truncated", truncated);
        resetObservations = copy("resetObservations", resetObservations);
        infos = copyInfos(infos);
        final int slots = observations.length;
        if (rewards.length != slots || terminated.length != slots || truncated.length != slots || infos.size() != slots || resetObservations.length != slots) {
            throw new IllegalArgumentException("step response slot dimensions mismatch");
        }
        requireFinite("observations", observations);
        requireFinite("rewards", rewards);
        requireFinite("resetObservations", resetObservations);
    }

    public void validateAgainst(final BridgeSpecs specs) {
        Objects.requireNonNull(specs, "specs");
        if (this.observations.length != specs.slotCount()) {
            throw new IllegalArgumentException("slot count mismatch");
        }
        for (int slot = 0; slot < this.observations.length; slot++) {
            if (this.observations[slot].length != specs.observationSpec().size()) {
                throw new IllegalArgumentException("observation size mismatch at slot " + slot);
            }
            if (this.resetObservations[slot].length != 0 && this.resetObservations[slot].length != specs.observationSpec().size()) {
                throw new IllegalArgumentException("reset observation size mismatch at slot " + slot);
            }
            validateObservationBounds("observation", slot, this.observations[slot], specs.observationSpec().fields());
            if (this.resetObservations[slot].length != 0) {
                validateObservationBounds("reset observation", slot, this.resetObservations[slot], specs.observationSpec().fields());
            }
        }
    }

    @Override
    public double[][] observations() {
        return copy("observations", this.observations);
    }

    @Override
    public double[] rewards() {
        return Arrays.copyOf(this.rewards, this.rewards.length);
    }

    @Override
    public boolean[] terminated() {
        return Arrays.copyOf(this.terminated, this.terminated.length);
    }

    @Override
    public boolean[] truncated() {
        return Arrays.copyOf(this.truncated, this.truncated.length);
    }

    @Override
    public List<JsonObject> infos() {
        return this.infos.stream().map(JsonObject::deepCopy).toList();
    }

    @Override
    public double[][] resetObservations() {
        return copy("resetObservations", this.resetObservations);
    }

    private static double[][] copy(final String name, final double[][] source) {
        if (source == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        final double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            if (source[i] == null) {
                throw new IllegalArgumentException(name + " row " + i + " must not be null");
            }
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }

    private static double[] copy(final String name, final double[] source) {
        if (source == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return Arrays.copyOf(source, source.length);
    }

    private static boolean[] copy(final String name, final boolean[] source) {
        if (source == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return Arrays.copyOf(source, source.length);
    }

    private static List<JsonObject> copyInfos(final List<JsonObject> source) {
        if (source == null) {
            throw new IllegalArgumentException("infos must not be null");
        }
        final List<JsonObject> copy = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            final JsonObject info = source.get(i);
            if (info == null) {
                throw new IllegalArgumentException("info " + i + " must not be null");
            }
            copy.add(info.deepCopy());
        }
        return List.copyOf(copy);
    }

    private static void requireFinite(final String name, final double[][] values) {
        for (int row = 0; row < values.length; row++) {
            for (int column = 0; column < values[row].length; column++) {
                if (!Double.isFinite(values[row][column])) {
                    throw new IllegalArgumentException(name + " contains non-finite value at [" + row + "][" + column + "]");
                }
            }
        }
    }

    private static void requireFinite(final String name, final double[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                throw new IllegalArgumentException(name + " contains non-finite value at [" + i + "]");
            }
        }
    }

    private static void validateObservationBounds(
            final String kind,
            final int slot,
            final double[] values,
            final List<VectorFieldSpec> fields
    ) {
        for (int field = 0; field < fields.size(); field++) {
            final VectorFieldSpec spec = fields.get(field);
            final double value = values[field];
            if (value < spec.minimum() || value > spec.maximum()) {
                throw new IllegalArgumentException(kind + " out of bounds at slot " + slot
                        + " field " + spec.name() + ": " + value
                        + " not in [" + spec.minimum() + ", " + spec.maximum() + "]");
            }
        }
    }
}
