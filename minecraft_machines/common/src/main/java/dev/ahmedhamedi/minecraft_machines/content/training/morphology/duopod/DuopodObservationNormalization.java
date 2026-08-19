package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public final class DuopodObservationNormalization {
    public static final double MAX_COMMAND_FORWARD_VELOCITY = 4.0;
    public static final double MAX_COMMAND_LATERAL_VELOCITY = 4.0;
    public static final double MAX_COMMAND_YAW_RATE = 3.0;
    public static final double MAX_LOCAL_LINEAR_VELOCITY = 6.0;
    public static final double MAX_LOCAL_ANGULAR_RATE = 8.0;
    public static final double MAX_JOINT_ANGULAR_VELOCITY = Math.toRadians(720.0);

    private DuopodObservationNormalization() {
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
}
