package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Signed left/right foot trajectories; positive turn increases heading toward east. */
public final class QuadrupedDirectionalGait {
    public static final String VERSION = "quadruped_signed_stride_body_feedback_v1";

    private QuadrupedDirectionalGait() {}

    public static double[] action(
            double[] genome,
            double seconds,
            double heading,
            double worldUpX,
            double worldUpZ,
            double forward,
            double turn) {
        if (genome.length != QuadrupedGaitPolicy.PARAMETERS)
            throw new IllegalArgumentException("Expected ten gait parameters");
        for (double value : genome)
            if (!Double.isFinite(value))
                throw new IllegalArgumentException("Nonfinite gait parameter");
        if (!Double.isFinite(seconds + heading + worldUpX + worldUpZ + forward + turn))
            throw new IllegalArgumentException("Nonfinite directional gait input");
        double left = Math.clamp(forward + turn, -1.5, 1.5);
        double right = Math.clamp(forward - turn, -1.5, 1.5);
        double activity = Math.clamp(5 * Math.max(Math.abs(left), Math.abs(right)), 0, 1);
        double stride = range(genome[0], 0.15, 1.65);
        double lift = range(genome[1], 0.10, 0.65) * activity;
        double period = range(genome[2], 0.8, 2.6);
        double duty = range(genome[3], 0.55, 0.85);
        double height = range(genome[4], 2.8, 3.3);
        double offset = 0.35 * Math.tanh(genome[5]);
        double rightPhase = range(genome[6], 0.35, 0.65);
        double rearPhase = range(genome[7], 0.45, 0.9);
        double pitchGain = range(genome[8], 0, 2);
        double rollGain = range(genome[9], 0, 2);
        // Express measured tilt in the heading frame so balance feedback rotates with the robot.
        double c = Math.cos(heading), s = Math.sin(heading);
        double upX = c * worldUpX + s * worldUpZ;
        double upZ = -s * worldUpX + c * worldUpZ;
        double[] result = new double[8];
        for (int leg = 0; leg < 4; leg++) {
            double phase =
                    seconds / period + (leg % 2 == 0 ? 0 : rightPhase) + (leg < 2 ? 0 : rearPhase);
            phase -= Math.floor(phase);
            double legStride = stride * (leg % 2 == 0 ? left : right);
            double footForward, down;
            if (phase < duty) {
                footForward = legStride * (0.5 - phase / duty);
                down = height;
            } else {
                double swing = (phase - duty) / (1 - duty);
                footForward = -0.5 * legStride * Math.cos(Math.PI * swing);
                down = height - lift * Math.sin(Math.PI * swing);
            }
            footForward += offset;
            down += (leg < 2 ? -1 : 1) * pitchGain * upZ + (leg % 2 == 0 ? -1 : 1) * rollGain * upX;
            double[] pair = QuadrupedGaitPolicy.inverseKinematics(footForward, down);
            result[2 * leg] = pair[0];
            result[2 * leg + 1] = pair[1];
        }
        return result;
    }

    private static double range(double value, double low, double high) {
        return low + (high - low) * (Math.tanh(value) + 1) / 2;
    }
}
