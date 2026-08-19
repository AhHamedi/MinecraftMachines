package dev.ahmedhamedi.minecraft_machines.content.servo;

public record ServoJointTelemetry(
        boolean assembled,
        boolean enabled,
        double actualAngleRad,
        double angularVelocityRadPerSecond,
        double requestedTargetAngleRad,
        double effectiveTargetAngleRad,
        double angleErrorRad,
        double estimatedTorque,
        double jointLoad,
        boolean atMinimumLimit,
        boolean atMaximumLimit
) {
}
