package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPredatorPlanner;

import org.joml.Vector3d;

import java.util.List;

/** Cooperative planning: every world read runs on the server thread, in a short work slice. */
final class MinecraftMachinesLeaperNavigationSearch {
    final MinecraftMachinesLeaperPredatorNavigator nav;
    final Vector3d target;
    final boolean background;
    LeaperPredatorPlanner ground;
    LeaperPredatorPlanner.Search groundSearch;
    MinecraftMachinesLeaperLeap.Search jump;
    boolean triedJump, triedGround, done;
    int ticks;
    String failure = "No traversable route";

    MinecraftMachinesLeaperNavigationSearch(MinecraftMachinesLeaperPredatorNavigator nav) {
        this(nav, nav.goal, false);
    }

    MinecraftMachinesLeaperNavigationSearch(
            MinecraftMachinesLeaperPredatorNavigator nav, Vector3d target, boolean background) {
        this.nav = nav;
        this.target = new Vector3d(target);
        this.background = background;
        if (nav.automaticLeapAllowed(target)) startJump();
        else startGround();
    }

    void startJump() {
        triedJump = true;
        ticks = 0;
        jump = nav.leap.new Search(target);
    }

    void startGround() {
        triedGround = true;
        ticks = 0;
        ground = nav.planner(target.y);
        groundSearch = ground.searchDirectFirst(target.x, target.y, target.z);
    }

    void advance(long deadline) {
        if (done) return;
        ticks++;
        // A pulse or a closer follow target can make an uncommitted jump unnecessary.
        if (jump != null && !MinecraftMachinesLeaperRecoveryJump.allowed(nav, target, background, failure)) {
            jump = null;
            if (!triedGround) startGround();
        }
        if (jump != null) {
            var result = jump.advance(deadline);
            if (result != null) {
                nav.goal = new Vector3d(target);
                nav.holdPosition = nav.arrived = false;
                nav.route =
                        List.of(
                                new LeaperPredatorPlanner.Point(
                                        nav.rig.position().x,
                                        nav.rig.corePivot().y - nav.control.standingHeight(),
                                        nav.rig.position().z),
                                new LeaperPredatorPlanner.Point(target.x, target.y, target.z));
                nav.index = 0;
                nav.routeBlocked = true;
                nav.goalStanceFloor = target.y;
                nav.leap.prepare(result);
                nav.reason = nav.behaviourAllowsLeaps ? "Pounce route ready" : "Recovery jump route ready";
                done = true;
                return;
            }
            if (!jump.done && ticks < 30) return;
            jump = null;
            if (failure.equals("No supported escape stance")) failure = "No safe recovery jump";
            // A vertical bank can require launch room even when the target is otherwise valid.
            if (!background && nav.leap.startRetreat()) {
                done = true;
                return;
            }
            if (!triedGround) {
                startGround();
                return;
            }
        }
        if (groundSearch != null) {
            // Use the available slice on the one-voxel grid. The same wall-clock
            // deadline remains authoritative; four nodes prematurely starved hills.
            var result = groundSearch.advance(128, deadline);
            if (result != null && result.found()) {
                // A background search starts behind the moving body. Join the nearest
                // validated segment instead of steering back to that obsolete origin.
                var position = nav.rig.position();
                int join = 0;
                double nearest = Double.POSITIVE_INFINITY;
                Vector3d connection = null;
                for (int i = 1; i < result.points().size(); i++) {
                    var a = result.points().get(i - 1);
                    var b = result.points().get(i);
                    double dx = b.x() - a.x(), dz = b.z() - a.z();
                    double along =
                            Math.clamp(
                                    ((position.x - a.x()) * dx + (position.z - a.z()) * dz)
                                            / Math.max(.0001, dx * dx + dz * dz),
                                    0,
                                    1);
                    var point =
                            new Vector3d(
                                    a.x() + along * dx,
                                    a.y() + along * (b.y() - a.y()),
                                    a.z() + along * dz);
                    double gap = Math.hypot(position.x - point.x, position.z - point.z);
                    if (gap < nearest) {
                        nearest = gap;
                        join = i;
                        connection = point;
                    }
                }
                if (background
                        && (connection == null
                                || nearest > 6
                                || !nav.planner(connection.y)
                                        .segmentTo(connection.x, connection.y, connection.z))) {
                    done = true;
                    return;
                }
                nav.goal = new Vector3d(target);
                nav.holdPosition = nav.arrived = false;
                nav.route = result.points();
                nav.index = join;
                nav.command = null;
                nav.routeBlocked = false;
                nav.goalStanceFloor = ground.stanceFloor(target.x, target.y, target.z);
                if (!background) {
                    nav.progress = new Vector3d(nav.rig.position());
                    nav.progressTick = nav.tick;
                }
                nav.reason = "Terrain route ready";
                done = true;
                return;
            }
            if (result == null && ticks < 80) return;
            failure = result == null ? "Route search exhausted its time budget" : result.reason();
            groundSearch = null;
            if (!triedJump && MinecraftMachinesLeaperRecoveryJump.allowed(nav, target, background, failure)) {
                startJump();
                return;
            }
        }
        if (!background) {
            nav.routeBlocked = true;
            nav.command = null;
            nav.reason = failure;
        }
        done = true;
    }
}
