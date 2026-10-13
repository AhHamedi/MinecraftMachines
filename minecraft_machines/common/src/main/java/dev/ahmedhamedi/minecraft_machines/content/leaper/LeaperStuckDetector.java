package dev.ahmedhamedi.minecraft_machines.content.leaper;

/** Sustained lack of displacement while trying to travel, independent of solver velocity. */
public final class LeaperStuckDetector {
    public static final int STALL_TICKS = 60;
    private double x, y, z;
    private int ticks;

    public boolean update(boolean wantsToMove, boolean grounded, double px, double py, double pz) {
        if (!wantsToMove || !grounded || !Double.isFinite(px + py + pz)) {
            reset();
            return false;
        }
        if (ticks == 0 || Math.hypot(px - x, pz - z) > .75 || Math.abs(py - y) > 1) {
            x = px; y = py; z = pz; ticks = 1;
        } else ticks = Math.min(STALL_TICKS, ticks + 1);
        return ticks >= STALL_TICKS;
    }

    public void reset() { ticks = 0; }
}
