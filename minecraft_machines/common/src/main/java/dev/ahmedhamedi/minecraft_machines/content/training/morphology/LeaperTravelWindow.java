package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Detects repeated backtracking even when the body is moving too much to count as stationary. */
public final class LeaperTravelWindow {
    private final double[] x = new double[101], z = new double[101];
    private int index, count;

    public void update(double px, double pz) {
        if (!Double.isFinite(px + pz)) throw new IllegalArgumentException("Nonfinite position");
        x[index] = px;
        z[index] = pz;
        index = (index + 1) % x.length;
        count = Math.min(count + 1, x.length);
    }

    public void reset() {
        index = 0;
        count = 0;
    }

    public boolean looping() {
        if (count < x.length) return false;
        double path = 0;
        for (int i = 1; i < x.length; i++) {
            int a = (index + i - 1) % x.length, b = (index + i) % x.length;
            path += Math.hypot(x[b] - x[a], z[b] - z[a]);
        }
        int last = (index + x.length - 1) % x.length;
        return path > 8 && Math.hypot(x[last] - x[index], z[last] - z[index]) < .28 * path;
    }
}
