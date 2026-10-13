package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Mode;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/** Connects evidence and finite memory to the existing physical locomotion controller. */
final class MinecraftMachinesLeaperBehaviour {
    static final int SETTLE_TICKS = 80;
    final LeaperBehaviour brain;
    final MinecraftMachinesLeaperPerception senses = new MinecraftMachinesLeaperPerception();
    final double radius;
    private final Point home;
    private int tick, nextWaypoint = SETTLE_TICKS, waypointDeadline, waypointIndex;
    private Point waypoint, investigation;
    private Mode previousMode = Mode.PATROLLING;
    private boolean searching;
    private UUID allowedCreative;
    private UUID visibleTarget;

    MinecraftMachinesLeaperBehaviour(Point home, double radius) {
        this.home = home;
        this.radius = radius;
        brain = new LeaperBehaviour(home);
    }

    void forceHunt(UUID player, Point position) {
        allowedCreative = player; // Explicit testing command retains creative-mode compatibility.
        visibleTarget = null;
        waypoint = investigation = null;
        searching = false;
        tick = Math.max(tick, SETTLE_TICKS);
        brain.forceHunt(tick, player, position);
        nextWaypoint = tick;
    }

    Mode mode() { return brain.mode(); }

    boolean canAttack() { return mode() == Mode.HUNTING && brain.target() != null; }

    UUID attackTarget() { return brain.target(); }

    String status() {
        return switch (mode()) {
            case PATROLLING -> brain.returningHome() ? "Patrolling — returning home" : "Patrolling";
            case ALERT -> "Alert";
            case HUNTING -> "Hunting";
        };
    }

    String detail() {
        return status() + ": " + brain.reason() + String.format(java.util.Locale.ROOT,
                "; suspicion %.0f%%; memory %s; patrol radius %.0f",
                brain.suspicion() * 100,
                brain.target() == null ? "none" : String.format(java.util.Locale.ROOT,
                        "%.1fs", brain.memoryAge(tick) / 20.0), radius);
    }

    void step(ServerLevel level, MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperPredatorNavigator nav, String huntPace) {
        tick++;
        nav.behaviourAllowsLeaps = mode() != Mode.PATROLLING;
        nav.pace = mode() == Mode.PATROLLING ? "careful" : mode() == Mode.ALERT ? "fast" : huntPace;
        if (tick < SETTLE_TICKS || tick % 4 != 0) return;

        var p = rig.corePivot();
        double floor = nav.leap.supportFloor();
        if (!Double.isFinite(floor)) floor = p.y - nav.control.standingHeight();
        var body = new Point(p.x, floor, p.z);
        boolean atNavGoal = nav.goal != null && nav.planning == null && nav.followSearch == null
                && nav.escape == null && !nav.pulseActive && !nav.leap.inAir()
                && Math.hypot(body.x() - nav.goal.x, body.z() - nav.goal.z) <= 2.5
                && Math.abs(body.y() - nav.goal.y) < 2 && Math.abs(rig.assemblyVelocity().y) < 1.5;
        var evidence = mode() != Mode.PATROLLING
                ? MinecraftMachinesLeaperPursuitSight.sample(level, rig, brain.target(), allowedCreative) : null;
        if (evidence == null) evidence = senses.sample(level, rig, brain.target(), tick, allowedCreative);
        var destination = brain.destination();
        boolean arrived = destination != null && distance(body, destination) < 3.5
                && Math.abs(body.y() - destination.y()) < 2;
        // The home request may resolve to a supported point up to three blocks away.
        // Use the same settled destination as navigation, rather than demanding that the
        // chassis walk through cover to the original anchor after navigation has arrived.
        if (brain.returningHome() && waypoint != null && (nav.arrived || atNavGoal) && nav.goal != null
                && distance(waypoint, home) < .01 && Math.abs(waypoint.y() - home.y()) < .01
                && Math.hypot(nav.goal.x - home.x(), nav.goal.z - home.z()) <= 3.1
                && Math.abs(nav.goal.y - home.y()) <= 4.5) arrived = true;
        brain.tick(tick, body, evidence, arrived,
                nav.routeBlocked && nav.planning == null && nav.followSearch == null,
                nav.leap.inAir() || nav.pulseActive);
        visibleTarget = evidence != null && evidence.visible()
                && evidence.player().equals(brain.target()) ? evidence.player() : null;

        if (mode() != previousMode) {
            boolean continueSearch = previousMode == Mode.HUNTING && mode() == Mode.ALERT && searching;
            Point continuedWaypoint = waypoint;
            // Awareness changes do not invalidate an already checked movement route.
            // Only a completed search abandons the current travel intention.
            if (mode() == Mode.PATROLLING) nav.stop();
            waypoint = investigation = null;
            searching = false;
            nextWaypoint = tick;
            if (previousMode == Mode.HUNTING && mode() == Mode.ALERT) {
                investigation = brain.destination();
                if (continueSearch) {
                    searching = true;
                    waypoint = continuedWaypoint;
                } else if (nav.goal != null) {
                    waypoint = investigation;
                    waypointDeadline = tick + 160;
                }
            }
            previousMode = mode();
        }
        rig.setBehaviourMode(mode());
        nav.behaviourAllowsLeaps = mode() != Mode.PATROLLING;
        nav.pace = mode() == Mode.PATROLLING ? "careful" : mode() == Mode.ALERT ? "fast" : huntPace;

        if (mode() == Mode.HUNTING) {
            var memory = brain.destination();
            if (visibleTarget != null) {
                searching = false;
                waypoint = null;
                nav.pursue(memory.x(), memory.y(), memory.z());
            } else {
                if (nav.hunting || !searching && (waypoint == null || distance(waypoint, memory) > 1)) {
                    nav.hunting = false;
                    nav.prey.clear();
                    nav.followSearch = null;
                    waypoint = memory;
                    if (nav.goal == null || Math.hypot(nav.goal.x - memory.x(), nav.goal.z - memory.z()) > 2
                            || Math.abs(nav.goal.y - memory.y()) > 1.5)
                        navigateMemory(nav, memory);
                }
                boolean reachedApproach = (nav.arrived || atNavGoal) && nav.goal != null
                        && Math.hypot(nav.goal.x - memory.x(), nav.goal.z - memory.z()) <= 12;
                boolean noApproach = nav.planning == null && nav.followSearch == null && nav.escape == null
                        && (nav.routeBlocked || nav.goal == null || nav.arrived) && brain.memoryAge(tick) >= 40;
                if (!searching && (arrived || reachedApproach || noApproach) && brain.memoryAge(tick) >= 20
                        && !nav.leap.inAir() && !nav.pulseActive) {
                    // Sweep supported points around frozen memory to regain sight.
                    // This never reads the hidden player's current coordinates.
                    searching = true;
                    waypoint = null;
                    nextWaypoint = tick;
                }
                if (searching) wander(level, nav, body,
                        MinecraftMachinesLeaperPursuitGround.searchCenter(nav, memory, body),
                        8 + Math.clamp(brain.memoryAge(tick) / 40.0, 0, 10), 8);
            }
            return;
        }

        if (mode() == Mode.ALERT) {
            var memory = brain.destination();
            if (investigation == null || distance(investigation, memory) > 3
                    || Math.abs(investigation.y() - memory.y()) > 2) {
                investigation = memory;
                searching = false;
                nextWaypoint = tick;
                waypoint = null;
            }
            if (!searching) {
                if (waypoint == null && tick >= nextWaypoint) {
                    waypoint = memory;
                    waypointDeadline = tick + 160;
                    if (!navigateMemory(nav, memory)) waypointDeadline = tick + 40;
                }
                if (waypoint != null && (nav.arrived || arrived || tick >= waypointDeadline)) {
                    nav.stop();
                    waypoint = null;
                    searching = true;
                    nextWaypoint = tick + 24;
                }
                return;
            }
            wander(level, nav, body, MinecraftMachinesLeaperPursuitGround.searchCenter(nav, memory, body),
                    12 + Math.clamp((brain.memoryAge(tick) - LeaperBehaviour.LOST_SIGHT_TICKS) / 40.0, 0, 16), 12);
        } else if (brain.returningHome()) {
            boolean reachedLeg = waypoint != null && atNavGoal;
            boolean failedRoute = nav.routeBlocked && nav.planning == null && nav.escape == null
                    && !nav.pulseActive && (nav.reason.startsWith("Route search exhausted")
                    || nav.reason.startsWith("No safe recovery jump")
                    || nav.reason.startsWith("No traversable route")
                    || nav.reason.startsWith("Destination has no reachable"));
            if (waypoint != null && failedRoute) {
                waypoint = null;
                nextWaypoint = tick + 20;
            }
            if (waypoint == null && tick >= nextWaypoint || waypoint != null && (nav.arrived || reachedLeg
                    || tick >= waypointDeadline && nav.escape == null && nav.planning == null)) {
                // Consume arrival only for an outstanding leg. A rejected next leg
                // leaves the old navigator holding at arrival; resetting on that old
                // flag would retry the same unsupported approach forever.
                if (waypoint != null && (nav.arrived || reachedLeg)) {
                    waypointIndex = 0;
                    nav.stop();
                }
                Point approach = home;
                if (distance(body, home) > 24) {
                    // Recede toward the fixed home in short, fully planned legs. A remote
                    // home must not repeatedly exhaust an expensive whole-territory search.
                    // Broad steps suit open ground; banks and narrow exits need a
                    // shorter sideways approach before home becomes reachable.
                    int candidate = Math.floorMod(waypointIndex++, 36);
                    int i = candidate % 9;
                    int offset = i % 2 == 0 ? -(i / 2) : (i + 1) / 2;
                    double angle = Math.atan2(home.z() - body.z(), home.x() - body.x()) + offset * Math.PI / 6;
                    double leg = switch (candidate / 9) { case 0 -> 20; case 1 -> 12; case 2 -> 8; default -> 6; };
                    approach = new Point(body.x() + Math.cos(angle) * leg,
                            body.y() + Math.clamp(home.y() - body.y(), -2, 2),
                            body.z() + Math.sin(angle) * leg);
                }
                waypoint = approach;
                waypointDeadline = tick + 480;
                if (!navigateMemory(nav, approach)) {
                    waypoint = null;
                    nextWaypoint = tick + 4;
                }
            }
        } else {
            wander(level, nav, body, home, radius, 40);
        }
    }

    private void wander(ServerLevel level, MinecraftMachinesLeaperPredatorNavigator nav,
            Point body, Point center, double range, int pause) {
        if (waypoint != null) {
            if (nav.planning != null || nav.followSearch != null || nav.escape != null || nav.pulseActive || nav.leap.inAir()) return;
            boolean rejected = nav.routeBlocked && (nav.reason.startsWith("Destination has no reachable")
                    || nav.reason.startsWith("No safe recovery jump")
                    || nav.reason.startsWith("No traversable route") || nav.reason.startsWith("Route search exhausted"));
            // Patrol visits an area; it need not settle into the exact planted-foot
            // hold used by a manual goto. Continue at a nearby checked waypoint even
            // when foot-contact estimates flicker on uneven ground.
            boolean reached = nav.goal != null && Math.hypot(body.x() - nav.goal.x, body.z() - nav.goal.z) <= 2.5
                    && Math.abs(body.y() - nav.goal.y) < 2;
            if (!nav.arrived && !reached && !rejected && tick < waypointDeadline) return;
            nav.stop();
            waypoint = null;
            nextWaypoint = tick + (rejected ? 8 : mode() == Mode.PATROLLING ? 16 + Math.floorMod(waypointIndex, 3) * 8 : pause);
        }
        if (tick < nextWaypoint) return;
        var planner = nav.planner(center.y());
        // At most two terrain candidates per sensing update; unsupported locations back off.
        for (int attempt = 0; attempt < 2; attempt++) {
            int i = waypointIndex++;
            double angle = i * 2.399963229728653 + home.x() * .13 + home.z() * .17;
            boolean nearby = mode() == Mode.PATROLLING && i % 3 == 2;
            double r = nearby ? 4 + Math.floorMod(i, 5)
                    : range * (.25 + .65 * Math.floorMod(i * 37, 101) / 100.0);
            double x = (nearby ? body.x() : center.x()) + Math.cos(angle) * r;
            double z = (nearby ? body.z() : center.z()) + Math.sin(angle) * r;
            if (mode() == Mode.PATROLLING && Math.hypot(x - home.x(), z - home.z()) > radius) continue;
            if (Math.hypot(x - home.x(), z - home.z()) > LeaperBehaviour.TERRITORY_RADIUS - 8) continue;
            var terrain = new MinecraftMachinesLeaperPredatorMap(level, center.y() + 4,
                    nav.control.standingHeight(), (nav.control.policy.value[3] + .5 * nav.control.policy.value[0] + .5) * nav.rig.scale);
            terrain.terrainAwareSweep = nav.control.terrainAwareSweep;
            terrain.scale = nav.rig.scale;
            for (double y : terrain.surfaces(x, z, center.y() - 4, center.y() + 4)) {
                if (!Double.isFinite(planner.stanceFloor(x, y, z)) || Math.hypot(x - body.x(), z - body.z()) < 4)
                    continue;
                if (nav.go(x, y, z)) {
                    waypoint = new Point(x, y, z);
                    waypointDeadline = tick + (mode() == Mode.PATROLLING ? 320 : 100);
                    return;
                }
            }
        }
        nextWaypoint = tick + 20;
    }

    private boolean navigateMemory(MinecraftMachinesLeaperPredatorNavigator nav, Point point) {
        if (point == null || !nav.level.hasChunkAt(BlockPos.containing(point.x(), point.y(), point.z())))
            return false;
        double floor = MinecraftMachinesLeaperPursuitGround.floor(nav, point.x(), point.y(), point.z());
        if (Double.isFinite(floor) && MinecraftMachinesLeaperBehaviourMovement.go(nav, point.x(), floor, point.z())) return true;
        // A glimpse at an edge or coarse sound clue may lack support at its exact center.
        // Resolve the first dry surface below the remembered position, including flight.
        // This uses stored evidence and terrain, never an unseen player's live position.
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2;
            double x = point.x() + Math.cos(a) * 3, z = point.z() + Math.sin(a) * 3;
            if (!nav.level.hasChunkAt(BlockPos.containing(x, point.y(), z))) continue;
            floor = MinecraftMachinesLeaperPursuitGround.floor(nav, x, point.y(), z);
            if (Double.isFinite(floor) && MinecraftMachinesLeaperBehaviourMovement.go(nav, x, floor, z)) return true;
        }
        return false;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
