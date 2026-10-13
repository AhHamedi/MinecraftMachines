package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Parameterized foot trajectory with analytic two-link IK and measured attitude feedback. */
public final class QuadrupedGaitPolicy {
    public static final int PARAMETERS = 10;
    public static final String VERSION = "quadruped_ik_heading_feedback_v2";
    private QuadrupedGaitPolicy() {}

    public static double[] action(double[] genome, double seconds, double upX, double upZ, double speedScale) {
        return action(genome, seconds, upX, upZ, speedScale, 0);
    }

    public static double[] action(double[] genome, double seconds, double upX, double upZ, double speedScale, double steering) {
        if (!Double.isFinite(steering)) throw new IllegalArgumentException("Nonfinite heading correction");
        if (genome.length != PARAMETERS) throw new IllegalArgumentException("Expected ten gait parameters");
        for (double v : genome) if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite genome");
        if (!Double.isFinite(seconds + upX + upZ + speedScale)) throw new IllegalArgumentException("Nonfinite state");
        double stride = range(genome[0], 0.15, 1.65) * Math.clamp(speedScale, 0, 1.5);
        double lift = range(genome[1], 0.10, 0.65) * Math.clamp(speedScale * 2, 0, 1);
        double period = range(genome[2], 0.8, 2.6);
        double duty = range(genome[3], 0.55, 0.85);
        double height = range(genome[4], 2.8, 3.3);
        double offset = 0.35 * Math.tanh(genome[5]);
        double rightPhase = range(genome[6], 0.35, 0.65);
        double rearPhase = range(genome[7], 0.45, 0.9);
        double pitchGain = range(genome[8], 0, 2);
        double rollGain = range(genome[9], 0, 2);
        double[] action = new double[8];
        for (int leg = 0; leg < 4; leg++) {
            double phase = seconds / period + (leg % 2 == 0 ? 0 : rightPhase) + (leg < 2 ? 0 : rearPhase);
            phase -= Math.floor(phase);
            double legStride = stride * (1 + (leg % 2 == 0 ? -1 : 1) * Math.clamp(steering, -0.5, 0.5));
            double forward, down;
            if (phase < duty) {
                forward = legStride * (0.5 - phase / duty);
                down = height;
            } else {
                double swing = (phase - duty) / (1 - duty);
                forward = -0.5 * legStride * Math.cos(Math.PI * swing);
                down = height - lift * Math.sin(Math.PI * swing);
            }
            forward += offset;
            down += (leg < 2 ? -1 : 1) * pitchGain * upZ + (leg % 2 == 0 ? -1 : 1) * rollGain * upX;
            double[] pair = inverseKinematics(forward, down);
            action[leg * 2] = pair[0];
            action[leg * 2 + 1] = pair[1];
        }
        return action;
    }

    public static double[] inverseKinematics(double forward, double down) {
        if (!Double.isFinite(forward + down)) throw new IllegalArgumentException("Nonfinite foot position");
        // Joint centers are two blocks apart; lower-link foot sole is 1.5 blocks from knee.
        double radius = Math.hypot(forward, down);
        if (radius > 3.45) { forward *= 3.45 / radius; down *= 3.45 / radius; }
        double knee = Math.acos(Math.clamp((forward * forward + down * down - 6.25) / 6, -1, 1));
        double hip = Math.atan2(forward, down) - Math.atan2(1.5 * Math.sin(knee), 2 + 1.5 * Math.cos(knee));
        return new double[]{Math.clamp(hip, -1.3, 1.3), Math.clamp(knee, -1.3, 1.3)};
    }

    private static double range(double value, double low, double high) { return low + (high - low) * (Math.tanh(value) + 1) / 2; }
}
