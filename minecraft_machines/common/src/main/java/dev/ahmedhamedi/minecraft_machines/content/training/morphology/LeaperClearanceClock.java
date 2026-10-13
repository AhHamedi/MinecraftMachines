package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

import java.util.Arrays;

/** Bounded lift-before-travel feedback using measured toe height. */
public final class LeaperClearanceClock {
    private final long[] cycle = new long[4];
    private final int[] spent = new int[4];
    private long heldTicks;

    public LeaperClearanceClock() {
        Arrays.fill(cycle, Long.MIN_VALUE);
    }

    public long heldTicks() {
        return heldTicks;
    }

    public double advance(
            double phase,
            double increment,
            double duty,
            double lr,
            double fb,
            double[] toe,
            double[] departure,
            double[] obstacle,
            double pauseSeconds) {
        if (pauseSeconds <= 0) return phase + increment;
        boolean wait = false;
        int budget = (int) Math.ceil(pauseSeconds / .05);
        for (int i = 0; i < 4; i++) {
            double shifted = phase + (i % 2 == 1 ? lr : 0) + (i >= 2 ? fb : 0);
            long id = (long) Math.floor(shifted);
            if (cycle[i] != id) {
                cycle[i] = id;
                spent[i] = 0;
            }
            double swing = (shifted - id - duty) / (1 - duty);
            if (swing >= .20
                    && swing < .65
                    && obstacle[i] - departure[i] > .5
                    && toe[i] < obstacle[i] + .25
                    && spent[i] < budget) {
                spent[i]++;
                wait = true;
            }
        }
        if (wait) heldTicks++;
        // Creep plus the per-stroke budget prevents an unreachable ledge deadlocking gait.
        return phase + increment * (wait ? .05 : 1);
    }
}
