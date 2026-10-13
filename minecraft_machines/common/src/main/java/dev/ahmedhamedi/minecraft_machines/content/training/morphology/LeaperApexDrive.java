package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Game-assisted acceleration, expressed as bounded world-space velocity increments at 20 Hz. */
public final class LeaperApexDrive {
    public static final double CRUISE = 27, ACCELERATION = 42, BRAKING = 54;

    public record Push(double x, double z) {}

    public static double approachSpeed(double distance, double cruise) {
        double remaining = Math.max(0, distance - 1.6);
        return Math.min(cruise, Math.min(4 * remaining, Math.sqrt(2 * BRAKING * remaining)));
    }

    public static Push accelerate(double vx, double vz, double desiredX, double desiredZ) {
        double dx = (desiredX - vx) * .4, dz = (desiredZ - vz) * .4;
        double cap =
                (Math.hypot(desiredX, desiredZ) < Math.hypot(vx, vz) ? BRAKING : ACCELERATION)
                        * .05;
        double scale = Math.min(1, cap / Math.max(.0001, Math.hypot(dx, dz)));
        return new Push(dx * scale, dz * scale);
    }

    public static double lookahead(double speed) {
        return Math.clamp(4 + .3 * speed + speed * speed / (2 * BRAKING), 6, 24);
    }
}
