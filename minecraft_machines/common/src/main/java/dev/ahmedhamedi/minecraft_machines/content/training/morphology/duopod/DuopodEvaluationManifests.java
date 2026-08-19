package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DuopodEvaluationManifests {
    public static final String EVALUATION_CONTRACT_ID = "duopod_planar_evaluation_v1";
    public static final int EVALUATION_CONTRACT_VERSION = 1;
    public static final String HELD_OUT_POINT_GOALS_ID = "duopod_held_out_point_goals_v3";
    private static final double[] HELD_OUT_BEARINGS_DEGREES = {-75.0, -45.0, -15.0, 0.0, 15.0, 45.0, 75.0};
    private static final double[] HELD_OUT_DISTANCES_BLOCKS = {6.0, 10.0, 12.0};

    private DuopodEvaluationManifests() {
    }

    public static DuopodEvaluationManifest heldOutPointGoals() {
        final List<DuopodEvaluationScenario> scenarios = new ArrayList<>(
                HELD_OUT_BEARINGS_DEGREES.length * HELD_OUT_DISTANCES_BLOCKS.length);
        for (final double distance : HELD_OUT_DISTANCES_BLOCKS) {
            for (final double bearing : HELD_OUT_BEARINGS_DEGREES) {
                scenarios.add(new DuopodEvaluationScenario(
                        scenarioId(bearing, distance),
                        bearing,
                        distance,
                        0.0,
                        DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE));
            }
        }
        return new DuopodEvaluationManifest(HELD_OUT_POINT_GOALS_ID, 3, scenarios);
    }

    private static String scenarioId(final double bearingDegrees, final double distanceBlocks) {
        return String.format(
                Locale.ROOT,
                "bearing_%+04.0f_distance_%02.0f",
                bearingDegrees,
                distanceBlocks)
                .replace("+", "p")
                .replace("-", "m");
    }
}
