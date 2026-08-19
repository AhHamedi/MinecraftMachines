package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.Objects;

public record MachineHealth(
        boolean valid,
        TerminationReason reason,
        String message
) {
    public static final MachineHealth VALID = new MachineHealth(true, TerminationReason.NONE, "ok");

    public MachineHealth {
        reason = Objects.requireNonNull(reason, "reason");
        message = Objects.requireNonNull(message, "message");
        if (valid && reason != TerminationReason.NONE) {
            throw new IllegalArgumentException("valid health must use NONE reason");
        }
        if (!valid && reason == TerminationReason.NONE) {
            throw new IllegalArgumentException("invalid health needs a concrete reason");
        }
    }

    public static MachineHealth failure(final TerminationReason reason, final String message) {
        return new MachineHealth(false, reason, message);
    }
}
