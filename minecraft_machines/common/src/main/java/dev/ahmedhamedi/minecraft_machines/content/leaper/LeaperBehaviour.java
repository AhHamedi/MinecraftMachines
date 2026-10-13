package dev.ahmedhamedi.minecraft_machines.content.leaper;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-authoritative awareness and pursuit memory, independent of Minecraft and locomotion.
 * Positions come exclusively from sensory evidence; callers must never supply an unseen
 * player's live position as visible evidence. All durations use server ticks, not update counts.
 */
public final class LeaperBehaviour {
    public static final int VISUAL_CONFIRM_TICKS = 12;
    public static final int LOST_SIGHT_TICKS = 400;
    public static final int NO_PROGRESS_TICKS = 900;
    public static final int SEARCH_TICKS = 900;
    public static final int FAILED_PURSUIT_COOLDOWN_TICKS = 80;
    public static final int RETURN_CALM_TICKS = 40;
    public static final double TERRITORY_RADIUS = 512;

    public enum Mode { PATROLLING, ALERT, HUNTING }

    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Behaviour positions must be finite");
        }
    }

    public record Evidence(UUID player, Point position, double strength, boolean visible) {
        public Evidence {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(position, "position");
            if (!Double.isFinite(strength) || strength < 0 || strength > 1)
                throw new IllegalArgumentException("Evidence strength must be between zero and one");
        }
    }

    private Point home;
    private Point destination;
    private Mode mode = Mode.PATROLLING;
    private UUID target;
    private String reason = "patrolling";
    private double suspicion;
    private double visualConfirmation;
    private int lastTick;
    private boolean hasTick;
    private int memoryTick;
    private boolean hasMemory;
    private int lastSeenTick;
    private int alertSince;
    private int lastProgressTick;
    private UUID progressTarget;
    private Point progressGoal;
    private double progressDistance;
    private boolean returningHome;
    private int calmUntil;
    private UUID failedTarget;
    private Point failedPosition;
    private double failedDistance;
    private int failedUntil;

    public LeaperBehaviour(Point home) {
        patrol(home);
    }

    /**
     * Update every server tick or at a fixed coarser cadence (normally four ticks).
     * {@code arrived} refers to the remembered destination, including vertical reach,
     * or to a navigation-validated supported approach near home during return.
     * {@code blocked} means traversal cannot advance. A committed leap pauses the
     * progress timeout, but does not grant vision or stop sensory memory from aging.
     */
    public void tick(int tick, Point body, Evidence evidence, boolean arrived,
                     boolean blocked, boolean committedMotion) {
        Objects.requireNonNull(body, "body");
        if (hasTick && tick < lastTick)
            throw new IllegalArgumentException("Behaviour ticks must be monotonic");
        int elapsed = hasTick ? Math.min(20, tick - lastTick) : 1;
        lastTick = tick;
        hasTick = true;

        if (distance(body, home) > TERRITORY_RADIUS && mode != Mode.PATROLLING) {
            returnHome(tick, body, "territory-limit");
            return;
        }
        // A distant sight must not repeatedly restart a chase beyond the home territory.
        if (evidence != null && (evidence.strength() <= 0
                || distance(evidence.position(), home) > TERRITORY_RADIUS)) evidence = null;

        if (mode == Mode.PATROLLING) {
            if (returningHome) {
                // Navigation may settle at a supported approach near an obstructed home.
                // Accept its verified arrival within a bounded home area; otherwise the
                // navigator holds still while the brain waits forever for the exact anchor.
                if (distance(body, home) <= 4 || arrived && distance(body, home) <= 8) {
                    returningHome = false;
                    calmUntil = tick + RETURN_CALM_TICKS;
                    reason = "patrolling";
                }
                // Going home is a movement intention, not sensory blindness. A real
                // visual contact can interrupt it; territory returns retain a margin
                // so a player at the boundary cannot repeatedly reverse the decision.
                if (returningHome && (evidence == null || !evidence.visible()
                        || reason.equals("territory-limit") && distance(body, home) > TERRITORY_RADIUS - 16))
                    return;
            }
            if (distance(body, home) > TERRITORY_RADIUS || evidence == null
                    || tick < calmUntil && !evidence.visible()
                    || suppressed(evidence, body, tick)) return;
            target = evidence.player();
            destination = evidence.position();
            memoryTick = tick;
            hasMemory = true;
            beginAlert(tick, evidence.visible() ? "visual-glimpse" : "heard-player");
        }

        boolean sameTarget = evidence != null && evidence.player().equals(target);
        boolean visible = sameTarget && evidence.visible();
        if (mode == Mode.HUNTING) {
            if (visible) remember(evidence.position(), tick, true);
            // Even audible movement cannot replace a hunt's last *seen* position.
            if (tick - lastSeenTick >= LOST_SIGHT_TICKS) {
                beginAlert(tick, "lost-sight");
                return;
            }
            updateProgress(tick, elapsed, body, arrived, committedMotion);
            if (tick - lastProgressTick >= NO_PROGRESS_TICKS) {
                failedTarget = target;
                failedPosition = destination;
                failedDistance = distance(body, destination);
                failedUntil = tick + FAILED_PURSUIT_COOLDOWN_TICKS;
                beginAlert(tick, blocked ? "blocked-path" : "no-progress");
            }
            return;
        }

        // The deadline belongs to the search episode, never to its latest stimulus.
        if (tick - alertSince >= SEARCH_TICKS) {
            returnHome(tick, body, "search-exhausted");
            return;
        }
        if (target.equals(progressTarget))
            updateProgress(tick, elapsed, body, false, committedMotion);
        // Perception prefers the current suspect. When it instead finds a different
        // visible player, investigate that actual contact without renewing the search
        // deadline or carrying another player's visual confirmation across identities.
        if (evidence != null && !sameTarget && evidence.visible()) {
            target = evidence.player();
            sameTarget = true;
            visible = true;
            visualConfirmation = 0;
            suspicion = .55;
            reason = "new-visual-contact";
        }
        if (sameTarget) {
            remember(evidence.position(), tick, visible);
            suspicion = Math.max(suspicion, .2 + evidence.strength() * .55);
        } else {
            suspicion = Math.max(.1, suspicion - elapsed / (double) SEARCH_TICKS);
        }
        if (visible && !suppressed(evidence, body, tick)) {
            visualConfirmation = Math.min(VISUAL_CONFIRM_TICKS,
                    visualConfirmation + elapsed * (.35 + .65 * evidence.strength()));
            suspicion = Math.max(suspicion, .3 + .65 * visualConfirmation / VISUAL_CONFIRM_TICKS);
            if (visualConfirmation >= VISUAL_CONFIRM_TICKS) {
                beginHunt(tick, body, "confirmed-sight");
                return;
            }
        } else {
            visualConfirmation = Math.max(0, visualConfirmation - elapsed * .5);
        }
        if (arrived && !visible) reason = "searching-last-known-position";
    }

    /** Explicit operator override: commands may order a hunt without natural perception. */
    public void forceHunt(int tick, UUID target, Point point) {
        this.target = Objects.requireNonNull(target, "target");
        destination = Objects.requireNonNull(point, "point");
        remember(point, tick, true);
        lastTick = tick;
        hasTick = true;
        beginHunt(tick, null, "ordered-hunt");
    }

    /** Establish a new territory and discard all previous pursuit and search memory. */
    public void patrol(Point home) {
        this.home = Objects.requireNonNull(home, "home");
        destination = home;
        mode = Mode.PATROLLING;
        target = null;
        reason = "patrolling";
        suspicion = 0;
        visualConfirmation = 0;
        hasTick = false;
        hasMemory = false;
        returningHome = false;
        calmUntil = 0;
        progressGoal = null;
        progressTarget = null;
        failedTarget = null;
        failedPosition = null;
    }

    public Mode mode() { return mode; }
    public Point destination() { return destination; }
    public UUID target() { return target; }
    public String reason() { return reason; }
    public double suspicion() { return suspicion; }
    public boolean returningHome() { return returningHome; }

    /** Integer.MAX_VALUE denotes no remembered evidence. */
    public int memoryAge(int tick) { return hasMemory ? Math.max(0, tick - memoryTick) : Integer.MAX_VALUE; }

    private void remember(Point point, int tick, boolean seen) {
        destination = point;
        memoryTick = tick;
        hasMemory = true;
        if (seen) lastSeenTick = tick;
    }

    private void beginAlert(int tick, String reason) {
        mode = Mode.ALERT;
        returningHome = false;
        alertSince = tick;
        visualConfirmation = 0;
        suspicion = .55;
        this.reason = reason;
    }

    private void beginHunt(int tick, Point body, String reason) {
        boolean continuingPursuit = body != null && mode == Mode.ALERT
                && target.equals(progressTarget) && failedTarget == null;
        mode = Mode.HUNTING;
        suspicion = 1;
        visualConfirmation = VISUAL_CONFIRM_TICKS;
        this.reason = reason;
        returningHome = false;
        // Ducking briefly behind cover must not give an unreachable pursuit a fresh
        // patience budget every time the same stationary prey becomes visible again.
        if (!continuingPursuit) {
            lastProgressTick = tick;
            progressTarget = target;
            progressGoal = body == null ? null : destination;
            progressDistance = body == null ? 0 : distance(body, progressGoal);
        }
        failedTarget = null;
        failedPosition = null;
    }

    private void updateProgress(int tick, int elapsed, Point body, boolean arrived, boolean committed) {
        if (progressGoal == null || arrived || distance(body, progressGoal) <= progressDistance - .75) {
            lastProgressTick = tick;
            progressGoal = destination;
            progressDistance = distance(body, progressGoal);
        } else if (committed) {
            lastProgressTick += elapsed;
        }
    }

    private boolean suppressed(Evidence evidence, Point body, int tick) {
        if (!evidence.player().equals(failedTarget)) return false;
        if (tick < failedUntil || !evidence.visible()) return true;
        double remaining = distance(body, evidence.position());
        // Time alone does not prove a route through a cliff has appeared. A changed
        // visible situation, or substantial physical approach, permits another try.
        boolean situationChanged = distance(evidence.position(), failedPosition) >= 6
                || remaining <= failedDistance - 4 || remaining <= 4;
        return !situationChanged;
    }

    private void returnHome(int tick, Point body, String reason) {
        mode = Mode.PATROLLING;
        target = null;
        destination = home;
        suspicion = 0;
        visualConfirmation = 0;
        hasMemory = false;
        progressGoal = null;
        progressTarget = null;
        returningHome = distance(body, home) > 4;
        calmUntil = tick + RETURN_CALM_TICKS;
        this.reason = reason;
    }

    private static double distance(Point a, Point b) {
        return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2)
                + Math.pow(a.z() - b.z(), 2));
    }
}
