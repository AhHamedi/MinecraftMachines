package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.List;

public final class ServoFirstObservationEncoder {
    public static final double MAX_COMMAND_FORWARD_VELOCITY = 4.0;
    public static final double MAX_COMMAND_LATERAL_VELOCITY = 4.0;
    public static final double MAX_COMMAND_YAW_RATE = 3.0;
    public static final double MAX_LOCAL_LINEAR_VELOCITY = 6.0;
    public static final double MAX_LOCAL_ANGULAR_RATE = 8.0;
    public static final double MAX_STANDING_HEIGHT_ERROR_BLOCKS = 0.75;
    public static final double MAX_CENTER_OF_MASS_HEIGHT_DELTA_BLOCKS = 2.0;
    public static final double MAX_CENTER_OF_MASS_DRIFT_BLOCKS = 2.0;
    public static final double MAX_SUPPORT_COM_ERROR_BLOCKS = 2.0;
    public static final double MAX_JOINT_ANGULAR_VELOCITY = Math.toRadians(120.0);
    public static final double MAX_GENERATED_SPEED_RPM = 20.0;
    public static final double MAX_SERVO_LIMIT_DEGREES = 120.0;

    private ServoFirstObservationEncoder() {
    }

    public static ServoFirstObservation encode(final ServoFirstObservationInput input, final List<String> expectedServoNames) {
        if (input.servos().size() != expectedServoNames.size()) {
            throw new IllegalArgumentException("servo telemetry count mismatch");
        }
        final int expectedSize = ServoTelemetrySchemaBuilder.observationSizeForServoCount(expectedServoNames.size());
        final double[] values = new double[expectedSize];
        boolean repaired = hasInvalidBaseValue(input);
        int index = 0;

        values[index++] = finiteOrZero(Math.sin(input.phaseRad()));
        values[index++] = finiteOrZero(Math.cos(input.phaseRad()));
        values[index++] = normalizeByLimit(input.command().desiredForwardVelocity(), MAX_COMMAND_FORWARD_VELOCITY);
        values[index++] = normalizeByLimit(input.command().desiredLateralVelocity(), MAX_COMMAND_LATERAL_VELOCITY);
        values[index++] = normalizeByLimit(input.command().desiredYawRate(), MAX_COMMAND_YAW_RATE);
        values[index++] = normalizeByLimit(input.localForwardVelocity(), MAX_LOCAL_LINEAR_VELOCITY);
        values[index++] = normalizeByLimit(input.localLateralVelocity(), MAX_LOCAL_LINEAR_VELOCITY);
        values[index++] = normalizeByLimit(input.localVerticalVelocity(), MAX_LOCAL_LINEAR_VELOCITY);
        values[index++] = normalizeByLimit(input.localRollRate(), MAX_LOCAL_ANGULAR_RATE);
        values[index++] = normalizeByLimit(input.localPitchRate(), MAX_LOCAL_ANGULAR_RATE);
        values[index++] = normalizeByLimit(input.localYawRate(), MAX_LOCAL_ANGULAR_RATE);
        values[index++] = clamp(input.projectedGravityForward());
        values[index++] = clamp(input.projectedGravityRight());
        values[index++] = normalizeByLimit(input.standingHeightError(), MAX_STANDING_HEIGHT_ERROR_BLOCKS);
        values[index++] = normalizeByLimit(input.centerOfMassHeightDelta(), MAX_CENTER_OF_MASS_HEIGHT_DELTA_BLOCKS);
        values[index++] = normalizeByLimit(input.centerOfMassForwardDrift(), MAX_CENTER_OF_MASS_DRIFT_BLOCKS);
        values[index++] = normalizeByLimit(input.centerOfMassLateralDrift(), MAX_CENTER_OF_MASS_DRIFT_BLOCKS);
        values[index++] = normalizeByLimit(input.supportComForwardError(), MAX_SUPPORT_COM_ERROR_BLOCKS);
        values[index++] = normalizeByLimit(input.supportComLateralError(), MAX_SUPPORT_COM_ERROR_BLOCKS);

        for (int servoIndex = 0; servoIndex < input.servos().size(); servoIndex++) {
            final ServoTelemetrySample servo = input.servos().get(servoIndex);
            if (!servo.servoName().equals(expectedServoNames.get(servoIndex))) {
                throw new IllegalArgumentException("servo order mismatch at index " + servoIndex);
            }
            repaired |= hasInvalidServoValue(servo);
            values[index++] = normalizeAngleByLimits(servo.targetAngleDegrees(), servo.minimumAngleDegrees(), servo.maximumAngleDegrees());
            values[index++] = normalizeAngleByLimits(servo.actualAngleDegrees(), servo.minimumAngleDegrees(), servo.maximumAngleDegrees());
            values[index++] = normalizeByLimit(servo.angleErrorDegrees(), Math.max(1.0, (servo.maximumAngleDegrees() - servo.minimumAngleDegrees()) * 0.5));
            values[index++] = normalizeByLimit(servo.angularVelocityRadPerSecond(), MAX_JOINT_ANGULAR_VELOCITY);
            values[index++] = normalizeByLimit(servo.generatedSpeedRpm(), MAX_GENERATED_SPEED_RPM);
            values[index++] = normalizeByLimit(servo.estimatedTorque(), Math.max(1.0, servo.maximumTorque()));
            values[index++] = normalizeByLimit(servo.jointLoad(), Math.max(1.0, servo.maximumTorque()));
            values[index++] = normalizeByLimit(servo.minimumAngleDegrees(), MAX_SERVO_LIMIT_DEGREES);
            values[index++] = normalizeByLimit(servo.maximumAngleDegrees(), MAX_SERVO_LIMIT_DEGREES);
            values[index++] = servo.enabled() ? 1.0 : -1.0;
            values[index++] = servo.attached() ? 1.0 : -1.0;
            values[index++] = servo.validConstraint() ? 1.0 : -1.0;
            values[index++] = clamp(servo.previousAction());
        }

        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                values[i] = 0.0;
                repaired = true;
            }
            values[i] = clamp(values[i]);
        }
        return new ServoFirstObservation(values, repaired);
    }

    public static double normalizeByLimit(final double value, final double positiveLimit) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (!Double.isFinite(positiveLimit) || positiveLimit <= 0.0) {
            throw new IllegalArgumentException("positiveLimit must be finite and positive");
        }
        return clamp(value / positiveLimit);
    }

    public static double normalizeAngleByLimits(final double valueDeg, final double minimumDeg, final double maximumDeg) {
        if (!Double.isFinite(valueDeg)) {
            return 0.0;
        }
        if (!Double.isFinite(minimumDeg) || !Double.isFinite(maximumDeg) || minimumDeg >= maximumDeg) {
            throw new IllegalArgumentException("servo angle limits must be finite and ordered");
        }
        final double center = (minimumDeg + maximumDeg) * 0.5;
        final double halfRange = (maximumDeg - minimumDeg) * 0.5;
        return clamp((valueDeg - center) / halfRange);
    }

    public static double clamp(final double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static boolean hasInvalidBaseValue(final ServoFirstObservationInput input) {
        return !Double.isFinite(input.phaseRad())
                || !Double.isFinite(input.localForwardVelocity())
                || !Double.isFinite(input.localLateralVelocity())
                || !Double.isFinite(input.localVerticalVelocity())
                || !Double.isFinite(input.localRollRate())
                || !Double.isFinite(input.localPitchRate())
                || !Double.isFinite(input.localYawRate())
                || !Double.isFinite(input.projectedGravityForward())
                || !Double.isFinite(input.projectedGravityRight())
                || !Double.isFinite(input.standingHeightError())
                || !Double.isFinite(input.centerOfMassHeightDelta())
                || !Double.isFinite(input.centerOfMassForwardDrift())
                || !Double.isFinite(input.centerOfMassLateralDrift())
                || !Double.isFinite(input.supportComForwardError())
                || !Double.isFinite(input.supportComLateralError());
    }

    private static boolean hasInvalidServoValue(final ServoTelemetrySample servo) {
        return !Double.isFinite(servo.targetAngleDegrees())
                || !Double.isFinite(servo.actualAngleDegrees())
                || !Double.isFinite(servo.angleErrorDegrees())
                || !Double.isFinite(servo.angularVelocityRadPerSecond())
                || !Double.isFinite(servo.generatedSpeedRpm())
                || !Double.isFinite(servo.estimatedTorque())
                || !Double.isFinite(servo.jointLoad())
                || !Double.isFinite(servo.previousAction());
    }

    private static double finiteOrZero(final double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
