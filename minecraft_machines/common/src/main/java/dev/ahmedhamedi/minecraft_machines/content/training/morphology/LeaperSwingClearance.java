package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Adaptive lift-before-sweep profile; stance targets remain the caller's responsibility. */
public final class LeaperSwingClearance {
    private LeaperSwingClearance() {}

    public record Target(double height, double sweep, double blend) {}

    public static Target shape(
            double t,
            double departure,
            double landing,
            double obstacle,
            double baselineHeight,
            double baselineSweep) {
        t = Math.clamp(t, 0, 1);
        double blend = Math.clamp((obstacle - departure - .35) / .8, 0, 1);
        if (blend == 0) return new Target(baselineHeight, baselineSweep, blend);

        double top = Math.max(obstacle, Math.max(departure, landing)) + 1.2;
        double height =
                t < .15
                        ? departure + (top - departure) * smooth(t / .15)
                        : t < .70 ? top : top + (landing - top) * smooth((t - .70) / .30);
        double u = Math.clamp((t - .20) / .80, 0, 1);
        double sweep = -.5 * Math.cos(Math.PI * u);
        return new Target(
                baselineHeight + blend * (height - baselineHeight),
                baselineSweep + blend * (sweep - baselineSweep),
                blend);
    }

    /** Preserve obstacle clearance when the torso compresses, without a touchdown jump. */
    public static double sagCompensation(double t, double torsoHeightError) {
        double envelope = smooth(Math.min(t / .15, (1 - t) / .30));
        return Math.max(0, -torsoHeightError) * envelope;
    }

    private static double smooth(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
