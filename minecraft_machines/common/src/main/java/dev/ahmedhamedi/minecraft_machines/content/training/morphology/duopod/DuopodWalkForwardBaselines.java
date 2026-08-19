package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

/** Deterministic non-learned policies used by the fixed walk-forward benchmark. */
public final class DuopodWalkForwardBaselines {
    public static final double SCRIPTED_AMPLITUDE = 0.65;

    private static final int PHASE_SIN_INDEX = 0;
    private static final int PHASE_COS_INDEX = 1;

    private DuopodWalkForwardBaselines() {
    }

    public static double[] neutralAction() {
        return new double[DuopodSchemas.actionSpec().size()];
    }

    public static double[] alternatingSineAction(final double[] observation) {
        if (observation == null || observation.length != DuopodSchemas.observationSpec().size()) {
            throw new IllegalArgumentException("observation length mismatch");
        }
        final double phase = Math.atan2(
                finiteOrZero(observation[PHASE_SIN_INDEX]),
                finiteOrZero(observation[PHASE_COS_INDEX]));
        final double left = SCRIPTED_AMPLITUDE * Math.sin(phase);
        return new double[]{left, -left};
    }

    private static double finiteOrZero(final double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
