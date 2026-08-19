package dev.ahmedhamedi.minecraft_machines.content.training.evaluation;

import java.util.Objects;

/**
 * Retains the latest active value, then permanently freezes the first terminal value and step.
 */
public final class FirstTerminalSnapshot<T> {
    private T value;
    private boolean completed;
    private int terminalStep = -1;

    public boolean record(final T nextValue, final boolean terminal, final int controlStep) {
        Objects.requireNonNull(nextValue, "nextValue");
        if (controlStep < 0) {
            throw new IllegalArgumentException("controlStep must be non-negative");
        }
        if (this.completed) {
            return false;
        }
        this.value = nextValue;
        if (terminal) {
            this.completed = true;
            this.terminalStep = controlStep;
        }
        return true;
    }

    public T value() {
        return this.value;
    }

    public boolean completed() {
        return this.completed;
    }

    public int terminalStep() {
        return this.terminalStep;
    }
}
