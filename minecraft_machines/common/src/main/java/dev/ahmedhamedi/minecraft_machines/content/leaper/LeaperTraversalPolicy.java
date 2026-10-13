package dev.ahmedhamedi.minecraft_machines.content.leaper;

/** Automatic jumps solve a travel problem; failed walking alone is not a reason to jump. */
public final class LeaperTraversalPolicy {
    public static final double LONG_DISTANCE = 32;
    public static final double HEIGHT_CHANGE = 3;
    public static final int WALK_AFTER_LANDING_TICKS = 100;

    private LeaperTraversalPolicy() {}

    public static boolean shouldJump(double distance, double heightChange, boolean terrainGapOrLedge) {
        return Double.isFinite(distance) && Double.isFinite(heightChange) && distance >= 0
                && (distance >= LONG_DISTANCE
                        || distance > 1 && Math.abs(heightChange) >= HEIGHT_CHANGE
                        || distance > 4 && terrainGapOrLedge);
    }
}
