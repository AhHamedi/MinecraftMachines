package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Bounded horizontal interception from observed prey motion, in seconds and blocks. */
public final class LeaperPursuitTarget {
    public record Aim(double x, double z) {}

    private double x, z, vx, vz;
    private int observedTick;
    private boolean observed;

    public boolean observe(double x, double z, int tick) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return false;
        if (observed && tick > observedTick) {
            double dt = (tick - observedTick) * .05;
            double mx = (x - this.x) / dt, mz = (z - this.z) / dt;
            double speed = Math.hypot(mx, mz);
            // Stops, reversals and teleports must not leave a phantom running target.
            if (speed > 16 || tick - observedTick > 20) vx = vz = 0;
            else if (speed < .05 || mx * vx + mz * vz <= 0) {
                vx = mx;
                vz = mz;
            } else {
                double blend = Math.min(1, dt / .15);
                vx += blend * (mx - vx);
                vz += blend * (mz - vz);
            }
        }
        this.x = x;
        this.z = z;
        observedTick = tick;
        observed = true;
        return true;
    }

    public boolean moving(int tick) {
        return observed && tick - observedTick <= 10 && Math.hypot(vx, vz) > .5;
    }

    public double speedAlong(double dx, double dz, int tick) {
        return moving(tick) ? (vx * dx + vz * dz) / Math.max(.001, Math.hypot(dx, dz)) : 0;
    }

    public double distance(double hunterX, double hunterZ) {
        return observed ? Math.hypot(x - hunterX, z - hunterZ) : 0;
    }

    public Aim aim(double hunterX, double hunterZ, double chaseSpeed, int tick) {
        if (!moving(tick)) return new Aim(x, z);
        double rx = x - hunterX, rz = z - hunterZ;
        double a = vx * vx + vz * vz - chaseSpeed * chaseSpeed;
        double b = 2 * (rx * vx + rz * vz), c = rx * rx + rz * rz;
        double seconds = 1;
        if (Math.abs(a) < 1e-6) {
            if (b < -1e-6) seconds = -c / b;
        } else {
            double discriminant = b * b - 4 * a * c;
            if (discriminant >= 0) {
                double root = Math.sqrt(discriminant);
                double t1 = (-b - root) / (2 * a), t2 = (-b + root) / (2 * a);
                if (t1 > 0) seconds = t1;
                if (t2 > 0 && (t1 <= 0 || t2 < t1)) seconds = t2;
            }
        }
        seconds = Math.clamp(seconds + .15, .15, 1);
        seconds = Math.min(seconds, 6 / Math.max(.001, Math.hypot(vx, vz)));
        return new Aim(x + vx * seconds, z + vz * seconds);
    }

    public void clear() {
        observed = false;
        vx = vz = 0;
    }
}
