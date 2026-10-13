package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Foot-space gait with exact 3-DOF inverse kinematics for the Leaper's radial legs. */
public final class LeaperGait {
    public static final String VERSION = "leaper_radial_ik_v1";
    public static final String SPRINT_VERSION = "leaper_radial_sprint_ik_v1";
    public static final int PARAMETERS = 10;
    public static final double UPPER = 4, LOWER = 4.5, HIP_RADIUS = 1.03, HIP_Y = .55;
    private static final double[] THETA = {-55, 55, -125, 125};

    private LeaperGait() {}

    public static double[] seed() {
        double[] p = new double[PARAMETERS];
        p[0] = inv(1.3, .5, 2.2);
        p[1] = inv(.4, .15, .85);
        p[2] = inv(4, 2.5, 6);
        p[3] = inv(.78, .65, .88);
        p[4] = inv(5.3, 4.6, 5.8);
        p[5] = inv(2.05, 1.7, 2.7);
        p[6] = 0;
        p[7] = inv(.75, .1, .9);
        p[8] = p[9] = -1;
        return p;
    }

    public static double[] action(
            double[] p,
            double seconds,
            double heading,
            double worldUpX,
            double worldUpZ,
            double forward,
            double turn) {
        return action(p, seconds, heading, worldUpX, worldUpZ, forward, 0, turn);
    }

    public static double[] action(
            double[] p,
            double seconds,
            double heading,
            double worldUpX,
            double worldUpZ,
            double forward,
            double strafe,
            double turn) {
        return action(p, seconds, heading, worldUpX, worldUpZ, forward, strafe, turn, false);
    }

    public static double[] action(
            double[] p,
            double seconds,
            double heading,
            double worldUpX,
            double worldUpZ,
            double forward,
            double strafe,
            double turn,
            boolean sprint) {
        if (p.length != PARAMETERS)
            throw new IllegalArgumentException("Expected ten gait parameters");
        for (double v : p)
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite genome");
        for (double v : new double[] {seconds, heading, worldUpX, worldUpZ, forward, strafe, turn})
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite observation");
        forward = Math.clamp(forward, -1.3, 1.3);
        turn = Math.clamp(turn, -1.3, 1.3);
        strafe = Math.clamp(strafe, -1.3, 1.3);
        double norm = Math.hypot(forward, strafe);
        if (norm > 1.3) {
            forward *= 1.3 / norm;
            strafe *= 1.3 / norm;
        }
        if (sprint) {
            // Long diagonal sweeps and simultaneous turning can demand more foot travel
            // than a straight sprint. Respect the measured direction-dependent envelope.
            norm = Math.hypot(forward, strafe);
            double mixed = norm < 1e-9 ? 0 : Math.abs(2 * forward * strafe) / (norm * norm);
            double translationLimit = 1.0 - .20 * mixed;
            if (norm > translationLimit) {
                forward *= translationLimit / norm;
                strafe *= translationLimit / norm;
            }
            double maximumFootTravel = 1;
            for (double degrees : THETA) {
                double theta = Math.toRadians(degrees);
                maximumFootTravel =
                        Math.max(
                                maximumFootTravel,
                                Math.hypot(
                                        strafe + turn * Math.cos(theta),
                                        -forward + turn * Math.sin(theta)));
            }
            forward /= maximumFootTravel;
            strafe /= maximumFootTravel;
            turn /= maximumFootTravel;
        }
        double stride = sprint ? range(p[0], 1.8, 5.0) : range(p[0], .5, 2.2);
        double lift = sprint ? range(p[1], .4, 1.5) : range(p[1], .15, .85);
        double period = period(p, sprint);
        double duty = sprint ? range(p[3], .40, .75) : range(p[3], .65, .88);
        double radius = range(p[4], 4.6, 5.8), down = range(p[5], 1.7, 2.7);
        double activity =
                Math.clamp(4 * Math.max(Math.hypot(forward, strafe), Math.abs(turn)), 0, 1);
        double c = Math.cos(heading), s = Math.sin(heading);
        double ux = c * worldUpX + s * worldUpZ, uz = -s * worldUpX + c * worldUpZ;
        double[] result = new double[12];
        for (int leg = 0; leg < 4; leg++) {
            double theta = Math.toRadians(THETA[leg]);
            double x = radius * Math.sin(theta), z = -radius * Math.cos(theta);
            double phase =
                    seconds / period
                            + ((leg % 2 == 1) ? range(p[6], .3, .7) : 0)
                            + (leg >= 2 ? range(p[7], .1, .9) : 0);
            phase -= Math.floor(phase);
            double sweep, rise = 0;
            if (phase < duty) sweep = .5 - phase / duty;
            else {
                double t = (phase - duty) / (1 - duty);
                sweep = -.5 * Math.cos(Math.PI * t);
                rise = lift * activity * Math.sin(Math.PI * t);
            }
            double footX = x + stride * sweep * (strafe - turn * z / radius);
            double footZ = z + stride * sweep * (-forward + turn * x / radius);
            double footY = -down + rise - range(p[8], 0, 1) * ux * x - range(p[9], 0, 1) * uz * z;
            double[] q = inverse(leg, footX, footY, footZ);
            result[3 * leg] = Math.clamp(q[0], -.6, .6);
            result[3 * leg + 1] = Math.clamp(q[1], -.65, .65);
            result[3 * leg + 2] = Math.clamp(q[2], -.85, .85);
        }
        return result;
    }

    /** Local foot tip relative to the core's model pivot, in blocks. */
    public static double[] forward(int leg, double yaw, double shoulder, double knee) {
        double theta = Math.toRadians(THETA[leg]), azimuth = theta - yaw;
        double a = Math.toRadians(27) + shoulder, b = Math.toRadians(-80) + shoulder + knee;
        double radial = UPPER * Math.cos(a) + LOWER * Math.cos(b);
        return new double[] {
            HIP_RADIUS * Math.sin(theta) + radial * Math.sin(azimuth),
            HIP_Y + UPPER * Math.sin(a) + LOWER * Math.sin(b),
            -HIP_RADIUS * Math.cos(theta) - radial * Math.cos(azimuth)
        };
    }

    public static double[] inverse(int leg, double x, double y, double z) {
        double theta = Math.toRadians(THETA[leg]);
        x -= HIP_RADIUS * Math.sin(theta);
        z += HIP_RADIUS * Math.cos(theta);
        y -= HIP_Y;
        double r = Math.hypot(x, z);
        double bend =
                -Math.acos(
                        Math.clamp(
                                (r * r + y * y - UPPER * UPPER - LOWER * LOWER)
                                        / (2 * UPPER * LOWER),
                                -1,
                                1));
        double shoulder =
                Math.atan2(y, r)
                        - Math.atan2(LOWER * Math.sin(bend), UPPER + LOWER * Math.cos(bend));
        return new double[] {
            wrap(theta - Math.atan2(x, -z)),
            shoulder - Math.toRadians(27),
            bend + Math.toRadians(107)
        };
    }

    /**
     * Advances the commanded toe along the Cartesian segment to the bounded requested pose. Angles
     * are radians; all arrays contain three entries and are left unchanged. The previous pose must
     * be inside the supplied limits and maxStep must be finite and nonnegative.
     *
     * <p>If joint limits obstruct Cartesian progress, falls back to one shared interpolation
     * fraction in joint space. That fallback preserves bounds and progress but does not preserve
     * the Cartesian path. This constrains command endpoints, not the physical servo sweep.
     */
    public static double[] rateLimitedCartesian(
            int leg,
            double[] previous,
            double[] requested,
            double[] min,
            double[] max,
            double maxStep) {
        if (leg < 0 || leg >= THETA.length)
            throw new IllegalArgumentException("Expected leg index 0..3");
        for (double[] values : new double[][] {previous, requested, min, max}) {
            if (values == null || values.length != 3)
                throw new IllegalArgumentException("Expected three joint angles or limits");
            for (double value : values)
                if (!Double.isFinite(value))
                    throw new IllegalArgumentException("Nonfinite angle or limit");
        }
        if (!Double.isFinite(maxStep) || maxStep < 0)
            throw new IllegalArgumentException("Expected finite nonnegative angular step");
        double[] goal = new double[3];
        for (int j = 0; j < 3; j++) {
            if (min[j] > max[j] || previous[j] < min[j] || previous[j] > max[j])
                throw new IllegalArgumentException("Previous pose must be inside ordered limits");
            goal[j] = Math.clamp(requested[j], min[j], max[j]);
        }
        if (maxStep == 0) return previous.clone();
        double[] start = forward(leg, previous[0], previous[1], previous[2]);
        double[] end = forward(leg, goal[0], goal[1], goal[2]);
        double[] endpoint = continuousInverse(leg, end, previous);
        // Preserve exact bounded endpoint angles, including angles on a joint limit, rather
        // than rejecting them because the inverse introduces floating-point roundoff.
        boolean sameBranch = endpoint != null;
        for (int j = 0; j < 3 && sameBranch; j++)
            sameBranch = Math.abs(endpoint[j] - goal[j]) < 1e-10;
        if (sameBranch && feasibleStep(goal, previous, min, max, maxStep)) return goal;
        if (feasibleStep(endpoint, previous, min, max, maxStep)) return endpoint;

        double low = 0, high = 1;
        double[] best = previous.clone();
        for (int iteration = 0; iteration < 48; iteration++) {
            double fraction = (low + high) * .5;
            double[] point = new double[3];
            for (int j = 0; j < 3; j++) point[j] = start[j] + fraction * (end[j] - start[j]);
            double[] candidate = continuousInverse(leg, point, previous);
            if (feasibleStep(candidate, previous, min, max, maxStep)) {
                low = fraction;
                best = candidate;
            } else high = fraction;
        }
        if (low <= 1e-8) {
            // A Cartesian chord can leave the joint box even with both endpoints inside it
            // (e.g. both shoulders at their upper limit). Avoid stalling there. This shared
            // joint-space fraction deliberately sacrifices the Cartesian path, without the
            // direction changes caused by independent per-joint clipping.
            double largestDelta = 0;
            for (int j = 0; j < 3; j++)
                largestDelta = Math.max(largestDelta, Math.abs(goal[j] - previous[j]));
            if (largestDelta <= maxStep) return goal;
            double fraction = (maxStep / largestDelta) * (1 - 1e-12);
            for (int j = 0; j < 3; j++) best[j] = previous[j] + fraction * (goal[j] - previous[j]);
        }
        // Never independently clip a component after selecting either interpolation fraction.
        return best;
    }

    private static boolean feasibleStep(
            double[] candidate, double[] previous, double[] min, double[] max, double maxStep) {
        if (candidate == null) return false;
        for (int j = 0; j < 3; j++)
            if (!Double.isFinite(candidate[j])
                    || candidate[j] < min[j]
                    || candidate[j] > max[j]
                    || Math.abs(candidate[j] - previous[j]) > maxStep) return false;
        return true;
    }

    private static double[] continuousInverse(int leg, double[] point, double[] previous) {
        double theta = Math.toRadians(THETA[leg]);
        double x = point[0] - HIP_RADIUS * Math.sin(theta);
        double y = point[1] - HIP_Y;
        double z = point[2] + HIP_RADIUS * Math.cos(theta);
        double radius = Math.hypot(x, z);
        double cosine =
                (radius * radius + y * y - UPPER * UPPER - LOWER * LOWER) / (2 * UPPER * LOWER);
        // Unlike inverse(), do not project an unreachable point onto the workspace boundary.
        if (cosine < -1 - 1e-12 || cosine > 1 + 1e-12) return null;
        double bendMagnitude = Math.acos(Math.clamp(cosine, -1, 1));
        double[] best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        // Signed radial reach matters for folded legs: hypot alone can flip yaw by pi.
        for (int radialSign : new int[] {1, -1}) {
            double yaw =
                    radius < 1e-12
                            ? previous[0]
                            : theta - Math.atan2(x, -z) + (radialSign < 0 ? Math.PI : 0);
            for (int bendSign : new int[] {-1, 1}) {
                double bend = bendSign * bendMagnitude;
                double shoulder =
                        Math.atan2(y, radialSign * radius)
                                - Math.atan2(
                                        LOWER * Math.sin(bend), UPPER + LOWER * Math.cos(bend));
                double[] candidate = {
                    yaw, shoulder - Math.toRadians(27), bend + Math.toRadians(107)
                };
                double distance = 0;
                for (int j = 0; j < 3; j++) {
                    candidate[j] = previous[j] + wrap(candidate[j] - previous[j]);
                    double delta = candidate[j] - previous[j];
                    distance += delta * delta;
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    public static double period(double[] p) {
        return range(p[2], 2.5, 6);
    }

    public static double period(double[] p, boolean sprint) {
        return sprint ? range(p[2], .55, 1.5) : period(p);
    }

    public static double[] sprintSeed(double[] original) {
        double[] p = original.clone();
        p[0] = inv(3.8, 1.8, 5.0);
        p[1] = inv(1.0, .4, 1.5);
        p[2] = inv(1.0, .55, 1.5);
        p[3] = inv(.58, .4, .75);
        return p;
    }

    public static double[] probeSeed(int preset) {
        var p = seed();
        if (preset > 0) {
            p[1] = inv(.65, .15, .85);
            p[2] = inv(5, 2.5, 6);
            p[3] = inv(.73, .65, .88);
        }
        if (preset > 1) p[0] = inv(1.9, .5, 2.2);
        if (preset > 2) {
            p[6] = inv(.5, .3, .7);
            p[7] = inv(.5, .1, .9);
        }
        return p;
    }

    public static double wrap(double x) {
        return Math.atan2(Math.sin(x), Math.cos(x));
    }

    private static double range(double x, double a, double b) {
        return a + (b - a) * (.5 + .5 * Math.tanh(x));
    }

    private static double inv(double x, double a, double b) {
        double t = 2 * (x - a) / (b - a) - 1;
        return .5 * Math.log((1 + t) / (1 - t));
    }
}
