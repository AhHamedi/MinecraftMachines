package dev.ahmedhamedi.minecraft_machines.content.leaper;

/** Server tick clock shared with the renderer. One committed blast, then a recovery/cooldown. */
public final class LeaperShockwaveClock {
    public static final int CHARGE_TICKS = 60;
    public static final int END_TICKS = 96;
    public static final int COOLDOWN_TICKS = 80;
    public static final double TRIGGER_RADIUS = 10;
    public static final double DAMAGE_RADIUS = 5;
    public static final double LAUNCH_RADIUS = 10;
    public static final double BLOCK_RADIUS = 10;
    public enum Event { NONE, START, BLAST, END }
    private int age = -1;
    private int cooldown = 40;

    public Event tick(boolean nearby, boolean grounded) {
        if (active()) {
            age++;
            if (age == CHARGE_TICKS) return Event.BLAST;
            if (age >= END_TICKS) {
                age = -1;
                cooldown = COOLDOWN_TICKS;
                return Event.END;
            }
        } else if (cooldown > 0) cooldown--;
        else if (nearby && grounded) {
            age = 0;
            return Event.START;
        }
        return Event.NONE;
    }

    public boolean active() { return age >= 0; }
    public int age() { return age; }
    public int cooldown() { return cooldown; }
    public void cancel() { age = -1; cooldown = COOLDOWN_TICKS; }

    /** Distance to the player's occupied vertical segment, with a ten-block trigger boundary. */
    public static boolean inRange(double dx, double dy, double dz) {
        return inRange(dx, dy, dz, TRIGGER_RADIUS);
    }

    public static boolean inRange(double dx, double dy, double dz, double radius) {
        return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                && Double.isFinite(radius) && radius >= 0
                && dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
