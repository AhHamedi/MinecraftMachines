package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Holonomic destination feedback: translation and facing are controlled independently. */
public final class LeaperPointController {
    public static final String VERSION = "leaper_point_holonomic_v1";
    public static final int PARAMETERS = 10;

    public record Command(
            double forward,
            double strafe,
            double turn,
            double distance,
            double speed,
            boolean arrived) {}

    private final double[] p;
    private double goalX,
            goalZ,
            lastX,
            lastZ,
            lastHeading,
            vx,
            vz,
            yawRate,
            forward,
            strafe,
            turn,
            settled,
            facing;
    private boolean initialized, holding;

    public LeaperPointController(double[] parameters, double x, double z) {
        if (parameters.length != PARAMETERS)
            throw new IllegalArgumentException("Expected ten navigation parameters");
        for (double v : parameters)
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite parameters");
        p = parameters.clone();
        setGoal(x, z);
    }

    public void setGoal(double x, double z) {
        if (!Double.isFinite(x + z)) throw new IllegalArgumentException("Nonfinite destination");
        goalX = x;
        goalZ = z;
        holding = false;
        settled = 0;
    }

    public double goalX() {
        return goalX;
    }

    public double goalZ() {
        return goalZ;
    }

    public Command update(double x, double z, double heading, double dt) {
        return update(x, z, heading, dt, 1);
    }

    public Command update(double x, double z, double heading, double dt, double speedScale) {
        if (!Double.isFinite(speedScale) || speedScale < .25 || speedScale > 1.3)
            throw new IllegalArgumentException("Invalid speed scale");
        if (!Double.isFinite(x + z + heading + dt) || dt <= 0 || dt > .5)
            throw new IllegalArgumentException("Invalid observation");
        if (initialized) {
            double alpha = 1 - Math.exp(-dt / .5);
            vx += alpha * ((x - lastX) / dt - vx);
            vz += alpha * ((z - lastZ) / dt - vz);
            yawRate += alpha * (LeaperGait.wrap(heading - lastHeading) / dt - yawRate);
        } else facing = heading;
        initialized = true;
        lastX = x;
        lastZ = z;
        lastHeading = heading;
        double dx = goalX - x,
                dz = goalZ - z,
                distance = Math.hypot(dx, dz),
                speed = Math.hypot(vx, vz);
        double stopRadius = range(p[4], .12, .30);
        if (distance <= stopRadius) holding = true;
        if (distance > .48) holding = false;
        double desiredF = 0, desiredS = 0, desiredT = 0;
        if (!holding) {
            double lookahead = range(p[3], .1, 1.5) * speedScale,
                    ex = dx - lookahead * vx,
                    ez = dz - lookahead * vz;
            double norm = Math.hypot(ex, ez);
            double amplitude =
                    range(p[0], .7, 1.25)
                            * Math.clamp(
                                    (distance - stopRadius)
                                            / Math.max(
                                                    .5,
                                                    range(p[1], .8, 4) * speedScale * speedScale),
                                    range(p[2], .15, .6),
                                    1);
            double wx = norm < 1e-9 ? 0 : ex / norm * amplitude,
                    wz = norm < 1e-9 ? 0 : ez / norm * amplitude;
            double c = Math.cos(heading), s = Math.sin(heading);
            desiredF = s * wx - c * wz;
            desiredS = (c * wx + s * wz) * range(p[8], .6, 1.4);
            if (distance > 3) facing = Math.atan2(dx, -dz);
            desiredT =
                    Math.clamp(
                                    range(p[6], .4, 2) * LeaperGait.wrap(facing - heading)
                                            - range(p[9], 0, 2) * yawRate,
                                    -range(p[7], .2, .9),
                                    range(p[7], .2, .9))
                            * Math.clamp((distance - .5) / 3, 0, 1);
        }
        double slew = range(p[5], .3, 2) * dt;
        forward += Math.clamp(desiredF - forward, -slew, slew);
        strafe += Math.clamp(desiredS - strafe, -slew, slew);
        turn += Math.clamp(desiredT - turn, -slew, slew);
        settled =
                holding
                                && distance <= .48
                                && speed < .12
                                && Math.abs(forward) + Math.abs(strafe) + Math.abs(turn) < .001
                        ? settled + dt
                        : 0;
        return new Command(forward, strafe, turn, distance, speed, settled >= 1.5);
    }

    private static double range(double v, double a, double b) {
        return a + (b - a) * (.5 + .5 * Math.tanh(v));
    }
}
