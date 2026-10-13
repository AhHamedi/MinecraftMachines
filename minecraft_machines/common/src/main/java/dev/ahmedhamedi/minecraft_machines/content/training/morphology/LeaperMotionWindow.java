package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** One second of observed displacement, independent of residual contact-solver velocity. */
public final class LeaperMotionWindow {
    private final double[] x = new double[21], z = new double[21];
    private int count, index;

    public void update(double px, double pz) {
        if (!Double.isFinite(px + pz)) throw new IllegalArgumentException("Nonfinite position");
        x[index] = px;
        z[index] = pz;
        index = (index + 1) % x.length;
        count = Math.min(count + 1, x.length);
    }

    public boolean stationary() {
        if (count < x.length) return false;
        int latest = (index + x.length - 1) % x.length;
        for (int i = 0; i < count; i++)
            if (Math.hypot(x[i] - x[latest], z[i] - z[latest]) > .08) return false;
        return true;
    }
}
