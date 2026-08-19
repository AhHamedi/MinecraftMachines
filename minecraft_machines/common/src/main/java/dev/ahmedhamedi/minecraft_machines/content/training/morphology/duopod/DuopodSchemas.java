package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.ActionSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ObservationSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoTelemetrySchemaBuilder;

import java.util.List;

public final class DuopodSchemas {
    public static final String OBSERVATION_SCHEMA_ID = "minecraft_machines:duopod_locomotion";
    public static final int OBSERVATION_SCHEMA_VERSION = 6;
    public static final String ACTION_SCHEMA_ID = "minecraft_machines:duopod_servo_targets";
    public static final int ACTION_SCHEMA_VERSION = 3;
    public static final List<String> SERVO_NAMES = List.of("left", "right");

    private static final ObservationSpec OBSERVATION_SPEC = ServoTelemetrySchemaBuilder.observationSpec(
            OBSERVATION_SCHEMA_ID,
            OBSERVATION_SCHEMA_VERSION,
            SERVO_NAMES
    );

    private static final ActionSpec ACTION_SPEC = ServoTelemetrySchemaBuilder.actionSpec(
            ACTION_SCHEMA_ID,
            ACTION_SCHEMA_VERSION,
            SERVO_NAMES
    );

    private DuopodSchemas() {
    }

    public static ObservationSpec observationSpec() {
        return OBSERVATION_SPEC;
    }

    public static ActionSpec actionSpec() {
        return ACTION_SPEC;
    }
}
