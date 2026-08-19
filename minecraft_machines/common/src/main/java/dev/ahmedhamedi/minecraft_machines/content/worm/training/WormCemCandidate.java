package dev.ahmedhamedi.minecraft_machines.content.worm.training;

import java.util.Locale;

public record WormCemCandidate(
        double amplitudeDeg,
        double frequencyHz,
        double phaseRad,
        double biasDeg
) {
    public static final double MIN_AMPLITUDE_DEG = 0.0;
    public static final double MAX_AMPLITUDE_DEG = 85.0;
    public static final double MIN_FREQUENCY_HZ = 0.15;
    public static final double MAX_FREQUENCY_HZ = 3.5;
    public static final double MIN_PHASE_RAD = 0.0;
    public static final double MAX_PHASE_RAD = Math.PI * 2.0;
    public static final double MIN_BIAS_DEG = -60.0;
    public static final double MAX_BIAS_DEG = 60.0;

    public WormCemCandidate {
        requireFinite("amplitudeDeg", amplitudeDeg);
        requireFinite("frequencyHz", frequencyHz);
        requireFinite("phaseRad", phaseRad);
        requireFinite("biasDeg", biasDeg);
    }

    public static WormCemCandidate clamped(
            final double amplitudeDeg,
            final double frequencyHz,
            final double phaseRad,
            final double biasDeg
    ) {
        return new WormCemCandidate(
                clamp(amplitudeDeg, MIN_AMPLITUDE_DEG, MAX_AMPLITUDE_DEG),
                clamp(frequencyHz, MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ),
                clamp(phaseRad, MIN_PHASE_RAD, MAX_PHASE_RAD),
                clamp(biasDeg, MIN_BIAS_DEG, MAX_BIAS_DEG)
        );
    }

    public double targetAngleDegrees(final double elapsedSeconds) {
        requireFinite("elapsedSeconds", elapsedSeconds);
        return this.biasDeg + this.amplitudeDeg * Math.sin((Math.PI * 2.0 * this.frequencyHz * elapsedSeconds) + this.phaseRad);
    }

    public double targetAngleDegrees(final double elapsedSeconds, final double minimumDegrees, final double maximumDegrees) {
        return clamp(this.targetAngleDegrees(elapsedSeconds), minimumDegrees, maximumDegrees);
    }

    public String compactDescription() {
        return String.format(Locale.ROOT,
                "amp=%.2f freq=%.3f phase=%.3f bias=%.2f",
                this.amplitudeDeg,
                this.frequencyHz,
                this.phaseRad,
                this.biasDeg);
    }

    static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
