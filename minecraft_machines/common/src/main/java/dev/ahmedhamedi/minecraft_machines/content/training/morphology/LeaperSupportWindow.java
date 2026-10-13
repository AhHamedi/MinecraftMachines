package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

/** Debounced partial footing with a two-tick edge grace period; consumed by takeoff. */
public final class LeaperSupportWindow {
    private int stable, missing;
    private boolean ready;

    public void update(int feet, double verticalSpeed, double upright, double anchorError) {
        boolean safe =
                Double.isFinite(verticalSpeed)
                        && Double.isFinite(upright)
                        && Double.isFinite(anchorError)
                        && Math.abs(verticalSpeed) <= 3
                        && upright >= .7
                        && anchorError <= .11;
        if (!safe) {
            reset();
        } else if (feet > 0) {
            missing = 0;
            ready = ++stable >= 2;
        } else {
            stable = 0;
            if (++missing > 2) ready = false;
        }
    }

    public boolean ready() {
        return ready;
    }

    public void reset() {
        stable = missing = 0;
        ready = false;
    }
}
