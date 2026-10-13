package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/**
 * Learned feedback gains map a world-space destination and measured motion to bounded gait
 * commands.
 */
public final class QuadrupedPointController {
    public static final int PARAMETERS = 10;
    public static final String VERSION = "quadruped_point_displacement_feedback_v2";

    public record Command(
            double forward, double turn, double distance, double speed, boolean arrived) {}

    private final double[] p;
    private double goalX, goalZ, lastX, lastZ, lastHeading, vx, vz, yawRate, forward, turn, settled;
    private boolean initialized, holding, reversing;

    public QuadrupedPointController(double[] parameters, double goalX, double goalZ) {
        if (parameters.length != PARAMETERS)
            throw new IllegalArgumentException("Expected ten navigation parameters");
        for (double parameter : parameters)
            if (!Double.isFinite(parameter))
                throw new IllegalArgumentException("Nonfinite navigation parameter");
        p = parameters.clone();
        setGoal(goalX, goalZ);
    }

    public void setGoal(double x, double z) {
        if (!Double.isFinite(x + z)) throw new IllegalArgumentException("Nonfinite destination");
        goalX = x;
        goalZ = z;
        holding = false;
        reversing = false;
        settled = 0;
    }

    public double goalX() {
        return goalX;
    }

    public double goalZ() {
        return goalZ;
    }

    public Command update(double x, double z, double heading, double dt) {
        if (!Double.isFinite(x + z + heading + dt) || dt <= 0 || dt > .5)
            throw new IllegalArgumentException("Invalid navigation observation or timestep");
        if (initialized) {
            double alpha = 1 - Math.exp(-dt / .3);
            vx += alpha * ((x - lastX) / dt - vx);
            vz += alpha * ((z - lastZ) / dt - vz);
            yawRate += alpha * (wrap(heading - lastHeading) / dt - yawRate);
        }
        initialized = true;
        lastX = x;
        lastZ = z;
        lastHeading = heading;
        double dx = goalX - x,
                dz = goalZ - z,
                distance = Math.hypot(dx, dz),
                speed = Math.hypot(vx, vz);
        double stopRadius = range(p[6], .12, .28);
        if (distance <= stopRadius) holding = true;
        if (distance > .35) holding = false;
        if (holding) {
            // Release the step amplitude progressively rather than snapping every foot to its rest
            // pose.
            forward += Math.clamp(-forward, -range(p[8], .3, 3) * dt, range(p[8], .3, 3) * dt);
            turn += Math.clamp(-turn, -range(p[9], .4, 4) * dt, range(p[9], .4, 4) * dt);
        } else {
            double lookahead = range(p[7], .05, .65);
            double error = wrap(Math.atan2(dx - lookahead * vx, -(dz - lookahead * vz)) - heading);
            double directError = wrap(Math.atan2(dx, -dz) - heading);
            // Back into nearby points behind the body; hysteresis prevents direction chatter.
            if (distance < 4 && Math.abs(directError) > 2) reversing = true;
            if (distance > 6 || Math.abs(directError) < 1.2) reversing = false;
            if (reversing) error = wrap(error + Math.PI);
            // A finite final step crosses the contact-friction dead zone; the hold latch then
            // brakes.
            double desiredForward =
                    (reversing ? -1 : 1)
                            * range(p[5], .65, 1.3)
                            * Math.clamp((distance - stopRadius) / range(p[4], .75, 4), .18, 1)
                            * Math.pow(Math.max(0, Math.cos(error)), range(p[3], .5, 4));
            double desiredTurn =
                    Math.clamp(
                            range(p[0], .35, 3) * error - range(p[1], 0, 1.5) * yawRate,
                            -range(p[2], .3, 1.1),
                            range(p[2], .3, 1.1));
            forward +=
                    Math.clamp(
                            desiredForward - forward,
                            -range(p[8], .3, 3) * dt,
                            range(p[8], .3, 3) * dt);
            turn +=
                    Math.clamp(
                            desiredTurn - turn, -range(p[9], .4, 4) * dt, range(p[9], .4, 4) * dt);
        }
        settled =
                holding
                                && distance <= .35
                                && speed <= .15
                                && Math.abs(forward) + Math.abs(turn) < .001
                        ? settled + dt
                        : 0;
        return new Command(forward, turn, distance, speed, settled >= 1);
    }

    public static double wrap(double radians) {
        return Math.atan2(Math.sin(radians), Math.cos(radians));
    }

    private static double range(double value, double low, double high) {
        return low + (high - low) * (Math.tanh(value) + 1) / 2;
    }
}
