package dev.ahmedhamedi.minecraft_machines.content.training.api;

public record ServoTelemetrySample(
        String servoName,
        double targetAngleDegrees,
        double actualAngleDegrees,
        double angleErrorDegrees,
        double angularVelocityRadPerSecond,
        double generatedSpeedRpm,
        double estimatedTorque,
        double jointLoad,
        double minimumAngleDegrees,
        double maximumAngleDegrees,
        double maximumTorque,
        boolean enabled,
        boolean attached,
        boolean validConstraint,
        double previousAction
) {
    public ServoTelemetrySample {
        if (servoName == null || servoName.isBlank()) {
            throw new IllegalArgumentException("servoName must not be blank");
        }
        if (!Double.isFinite(minimumAngleDegrees) || !Double.isFinite(maximumAngleDegrees) || minimumAngleDegrees >= maximumAngleDegrees) {
            throw new IllegalArgumentException("servo angle limits must be finite and ordered");
        }
        if (!Double.isFinite(maximumTorque) || maximumTorque < 0.0) {
            throw new IllegalArgumentException("maximumTorque must be finite and non-negative");
        }
    }
}
