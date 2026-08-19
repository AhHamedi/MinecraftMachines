package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ServoTelemetrySchemaBuilder {
    public static final List<String> BASE_OBSERVATION_FIELD_NAMES = List.of(
            "phase_sin",
            "phase_cos",
            "desired_forward_velocity",
            "desired_lateral_velocity",
            "desired_yaw_rate",
            "local_forward_velocity",
            "local_lateral_velocity",
            "local_vertical_velocity",
            "local_roll_rate",
            "local_pitch_rate",
            "local_yaw_rate",
            "projected_gravity_forward",
            "projected_gravity_right",
            "standing_height_error",
            "center_of_mass_height_delta",
            "center_of_mass_forward_drift",
            "center_of_mass_lateral_drift",
            "support_com_forward_error",
            "support_com_lateral_error"
    );

    public static final List<String> SERVO_OBSERVATION_SUFFIXES = List.of(
            "target_angle",
            "actual_angle",
            "angle_error",
            "angular_velocity",
            "generated_speed",
            "estimated_torque",
            "joint_load",
            "minimum_angle_limit",
            "maximum_angle_limit",
            "enabled",
            "attached",
            "valid_constraint",
            "previous_action"
    );

    private ServoTelemetrySchemaBuilder() {
    }

    public static ObservationSpec observationSpec(
            final String schemaId,
            final int schemaVersion,
            final List<String> servoNames
    ) {
        final List<String> names = validateServoNames(servoNames);
        final List<VectorFieldSpec> fields = new ArrayList<>(BASE_OBSERVATION_FIELD_NAMES.size() + names.size() * SERVO_OBSERVATION_SUFFIXES.size());
        for (final String name : BASE_OBSERVATION_FIELD_NAMES) {
            fields.add(normalized(name, baseDescription(name)));
        }
        for (final String servoName : names) {
            for (final String suffix : SERVO_OBSERVATION_SUFFIXES) {
                fields.add(normalized(servoName + "_" + suffix, "Robotic Servo Joint telemetry: " + servoName + " " + suffix.replace('_', ' ')));
            }
        }
        return new ObservationSpec(schemaId, schemaVersion, fields);
    }

    public static ActionSpec actionSpec(
            final String schemaId,
            final int schemaVersion,
            final List<String> servoNames
    ) {
        final List<String> names = validateServoNames(servoNames);
        final List<VectorFieldSpec> fields = new ArrayList<>(names.size());
        for (final String servoName : names) {
            fields.add(normalized(servoName + "_target", "normalized target angle for discovered servo " + servoName));
        }
        return new ActionSpec(schemaId, schemaVersion, fields);
    }

    public static int observationSizeForServoCount(final int servoCount) {
        if (servoCount < 1) {
            throw new IllegalArgumentException("servoCount must be positive");
        }
        return BASE_OBSERVATION_FIELD_NAMES.size() + servoCount * SERVO_OBSERVATION_SUFFIXES.size();
    }

    private static List<String> validateServoNames(final List<String> servoNames) {
        Objects.requireNonNull(servoNames, "servoNames");
        if (servoNames.isEmpty()) {
            throw new IllegalArgumentException("at least one servo is required");
        }
        final List<String> names = new ArrayList<>(servoNames.size());
        for (final String servoName : servoNames) {
            if (servoName == null || servoName.isBlank()) {
                throw new IllegalArgumentException("servo names must not be blank");
            }
            if (!servoName.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("servo names must use lowercase snake_case: " + servoName);
            }
            if (names.contains(servoName)) {
                throw new IllegalArgumentException("servo names must be unique: " + servoName);
            }
            names.add(servoName);
        }
        return names;
    }

    private static VectorFieldSpec normalized(final String name, final String description) {
        return new VectorFieldSpec(name, -1.0, 1.0, "normalized", description);
    }

    private static String baseDescription(final String name) {
        return switch (name) {
            case "phase_sin" -> "sin(episode gait phase)";
            case "phase_cos" -> "cos(episode gait phase)";
            case "desired_forward_velocity" -> "commanded local forward velocity";
            case "desired_lateral_velocity" -> "commanded local lateral velocity";
            case "desired_yaw_rate" -> "commanded local yaw rate";
            case "local_forward_velocity" -> "measured local forward velocity";
            case "local_lateral_velocity" -> "measured local lateral velocity";
            case "local_vertical_velocity" -> "measured local vertical velocity";
            case "local_roll_rate" -> "measured local roll rate";
            case "local_pitch_rate" -> "measured local pitch rate";
            case "local_yaw_rate" -> "measured local yaw rate";
            case "projected_gravity_forward" -> "world down projected onto local forward";
            case "projected_gravity_right" -> "world down projected onto local right";
            case "standing_height_error" -> "base height minus configured target standing height";
            case "center_of_mass_height_delta" -> "aggregate center of mass height change from calibrated balance start";
            case "center_of_mass_forward_drift" -> "aggregate center of mass drift from calibrated balance start along local forward";
            case "center_of_mass_lateral_drift" -> "aggregate center of mass drift from calibrated balance start along local right";
            case "support_com_forward_error" -> "center of mass offset from support segment along local forward";
            case "support_com_lateral_error" -> "center of mass offset from support segment along local right";
            default -> name;
        };
    }
}
