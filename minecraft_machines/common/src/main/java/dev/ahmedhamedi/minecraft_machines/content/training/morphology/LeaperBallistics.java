package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Constant-gravity takeoff solution. Does not move bodies or correct flight in progress. */
public final class LeaperBallistics {
    private LeaperBallistics() {}

    public record Arc(
            double x,
            double y,
            double z,
            double vx,
            double vy,
            double vz,
            double gravity,
            double drag,
            double seconds) {
        public double[] at(double t) {
            if (drag > 1e-9) {
                double travel = -Math.expm1(-drag * t) / drag;
                return new double[] {
                    x + vx * travel,
                    y + (vy + gravity / drag) * travel - gravity * t / drag,
                    z + vz * travel
                };
            }
            return new double[] {x + vx * t, y + vy * t - .5 * gravity * t * t, z + vz * t};
        }
    }

    public static Arc solve(
            double x,
            double y,
            double z,
            double tx,
            double ty,
            double tz,
            double gravity,
            double clearance) {
        for (double v : new double[] {x, y, z, tx, ty, tz, gravity, clearance})
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite trajectory");
        if (gravity <= 0 || clearance <= 0)
            throw new IllegalArgumentException("Positive gravity and clearance required");
        double apex = Math.max(y, ty) + clearance;
        double vy = Math.sqrt(2 * gravity * (apex - y));
        double seconds = (vy + Math.sqrt(2 * gravity * (apex - ty))) / gravity;
        return new Arc(x, y, z, (tx - x) / seconds, vy, (tz - z) / seconds, gravity, 0, seconds);
    }

    public static Arc solve(
            double x,
            double y,
            double z,
            double tx,
            double ty,
            double tz,
            double gravity,
            double clearance,
            double drag) {
        var vacuum = solve(x, y, z, tx, ty, tz, gravity, clearance);
        if (!Double.isFinite(drag) || drag < 0) throw new IllegalArgumentException("Invalid drag");
        if (drag < 1e-9) return vacuum;
        double height = Math.max(y, ty) + clearance - y;
        double lo = 0, hi = Math.max(1, vacuum.vy());
        while (apex(hi, gravity, drag) < height) hi *= 2;
        for (int i = 0; i < 64; i++) {
            double mid = (lo + hi) / 2;
            if (apex(mid, gravity, drag) < height) lo = mid;
            else hi = mid;
        }
        double vy = (lo + hi) / 2;
        double topTime = Math.log1p(drag * vy / gravity) / drag;
        var vertical = new Arc(x, y, z, 0, vy, 0, gravity, drag, 0);
        lo = topTime;
        hi = Math.max(topTime * 2, 1);
        while (vertical.at(hi)[1] > ty) hi *= 2;
        for (int i = 0; i < 64; i++) {
            double mid = (lo + hi) / 2;
            if (vertical.at(mid)[1] > ty) lo = mid;
            else hi = mid;
        }
        double seconds = (lo + hi) / 2, travel = -Math.expm1(-drag * seconds) / drag;
        return new Arc(x, y, z, (tx - x) / travel, vy, (tz - z) / travel, gravity, drag, seconds);
    }

    private static double apex(double vy, double gravity, double drag) {
        return vy / drag - gravity * Math.log1p(drag * vy / gravity) / (drag * drag);
    }

    /** A direct pounce with a chosen travel time, retaining native gravity and drag. */
    public static Arc timed(
            double x,
            double y,
            double z,
            double tx,
            double ty,
            double tz,
            double gravity,
            double drag,
            double seconds) {
        for (double v : new double[] {x, y, z, tx, ty, tz, gravity, drag, seconds})
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite pounce");
        if (gravity <= 0 || drag < 0 || seconds <= 0)
            throw new IllegalArgumentException("Invalid pounce dynamics");
        double travel = drag < 1e-9 ? seconds : -Math.expm1(-drag * seconds) / drag;
        double vy =
                drag < 1e-9
                        ? (ty - y) / seconds + .5 * gravity * seconds
                        : (ty - y + gravity * seconds / drag) / travel - gravity / drag;
        return new Arc(x, y, z, (tx - x) / travel, vy, (tz - z) / travel, gravity, drag, seconds);
    }
}
