package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic, conservative probes for a fresh phase-gait optimization run. */
public final class DuopodPhaseGaitSeeds {
    public static final double MAX_ABSOLUTE_GENE_VALUE = 3.0;

    private static final double[][] STABLE_BASIN_PROBES = {
            // center, center delta, amplitude, amplitude delta, right phase, common phase, speed gain
            {0.00, 0.00, -0.55, 0.00, 1.50, -0.20, 0.25},
            {0.00, 0.00, -0.55, 0.00, -1.50, 0.20, 0.25},
            {0.20, 0.00, -0.35, 0.10, 1.35, 0.15, 0.35},
            {0.20, 0.00, -0.35, 0.10, -1.35, -0.15, 0.35},
            {-0.20, 0.00, -0.35, -0.10, 1.35, -0.15, 0.35},
            {-0.20, 0.00, -0.35, -0.10, -1.35, 0.15, 0.35},
            {0.00, 0.15, -0.20, 0.15, 1.60, 0.00, 0.40},
            {0.00, -0.15, -0.20, -0.15, -1.60, 0.00, 0.40}
    };

    private DuopodPhaseGaitSeeds() {
    }

    /**
     * Returns stable-basin probes without a distribution-mean anchor. Callers
     * retain that anchor separately and may inject as many probes as fit.
     * Every invocation returns independent genome arrays.
     */
    public static List<double[]> stableBasinProbes() {
        final List<double[]> copies = new ArrayList<>(STABLE_BASIN_PROBES.length);
        for (final double[] probe : STABLE_BASIN_PROBES) {
            copies.add(Arrays.copyOf(probe, probe.length));
        }
        return List.copyOf(copies);
    }
}
