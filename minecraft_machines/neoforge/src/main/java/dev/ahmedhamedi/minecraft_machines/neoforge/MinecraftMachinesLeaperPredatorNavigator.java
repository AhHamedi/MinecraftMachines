package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperTraversalPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPredatorPlanner;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPursuitTarget;

import net.minecraft.server.level.ServerLevel;

import org.joml.Vector3d;

import java.util.*;

/** Continuous pursuit with collision-aware replanning and a motor-driven back-out when wedged. */
final class MinecraftMachinesLeaperPredatorNavigator {
    static final double ARRIVAL_RADIUS = 2;
    static final double ARRIVAL_RELEASE_RADIUS = 2.5;
    final MinecraftMachinesLeaper rig;
    final ServerLevel level;
    final MinecraftMachinesLeaperTerrainMap map;
    final MinecraftMachinesLeaperPredatorControl control;
    final LeaperPointController feedback;
    List<LeaperPredatorPlanner.Point> route = List.of();
    Vector3d goal, progress, pendingGoal;
    int pendingRetryTick, pendingDeadline;
    LeaperPointController.Command command;
    String pace = "horrific", reason = "";
    int tick, index, replans, recoveries, backUntil, progressTick;
    double distance, terminalX, terminalZ, goalStanceFloor;
    boolean arrived;
    final List<double[]> obstacles = new ArrayList<>();
    boolean holdPosition;
    int holdReleaseTicks;
    boolean routeBlocked;
    final Deque<Vector3d> safeTrail = new ArrayDeque<>();
    Vector3d escape;
    final dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperMotionWindow motion =
            new dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperMotionWindow();
    int approachIndex, plans;
    double maximumPlanMillis, totalPlanMillis;
    final LeaperPursuitTarget prey = new LeaperPursuitTarget();
    boolean hunting;
    boolean behaviourAllowsLeaps = true;
    boolean pulseActive;
    boolean waitingForClearingPulse;
    final MinecraftMachinesLeaperApexDrive apex = new MinecraftMachinesLeaperApexDrive(this);

    boolean movingPrey() {
        return hunting && prey.moving(tick);
    }

    String travelPace() {
        return (hunting || apex.enabled) && pace.equals("horrific") ? "super_horrific" : pace;
    }

    final MinecraftMachinesLeaperLeap leap;
    final MinecraftMachinesLeaperClimbAssist climbAssist =
            new MinecraftMachinesLeaperClimbAssist(this);
    double lastFacing, facingRate, sprintForward, sprintStrafe;

    // Shared with the native turn fixture so stationary and pursuit measurements use
    // the same bounded yaw feedback. Native angular Y has the opposite heading sign.
    double turnCommand(double error, boolean sprint) {
        return sprint
                ? Math.clamp(2 * error + .4 * rig.angularVelocity().y, -1, 1)
                : Math.clamp(1.6 * error - .18 * facingRate, -1, 1);
    }

    MinecraftMachinesLeaperPredatorNavigator(
            MinecraftMachinesLeaper rig,
            ServerLevel level,
            MinecraftMachinesLeaperPredatorPolicy policy) {
        this.rig = rig;
        apex.enabled = rig.apexVersion;
        this.level = level;
        map = new MinecraftMachinesLeaperTerrainMap(level);
        map.padScale = rig.scale;
        control = new MinecraftMachinesLeaperPredatorControl(rig, map, policy);
        leap = new MinecraftMachinesLeaperLeap(this);
        lastFacing = rig.heading();
        var p = rig.position();
        motion.update(p.x, p.z);
        feedback =
                new LeaperPointController(
                        MinecraftMachinesLeaperModel.load("horrific").navigation(), p.x, p.z);
        progress = new Vector3d(p);
        safeTrail.addLast(new Vector3d(p));
    }

    LeaperPredatorPlanner planner(double y) {
        var p = rig.position();
        double sensedFloor =
                apex.enabled ? map.height(p.x, p.z, rig.corePivot().y + .3) : Double.NaN;
        var terrain =
                new MinecraftMachinesLeaperPredatorMap(
                        level,
                        Math.max(p.y, y) + 7,
                        control.standingHeight(),
                        (control.policy.value[3] + .5 * control.policy.value[0] + .5) * rig.scale);
        terrain.terrainAwareSweep = control.terrainAwareSweep;
        terrain.scale = rig.scale;
        obstacles.removeIf(b -> b[2] < tick);
        return new LeaperPredatorPlanner(
                new LeaperPredatorPlanner.Terrain() {
                    public double bodyScale() { return rig.scale; }
                    public double chassisStepClearance() {
                        return control.terrainAwareSweep ? control.standingHeight() - 1.5 * rig.scale : 1;
                    }

                    public boolean adaptiveStanceSampling() {
                        return control.terrainAwareSweep;
                    }

                    public double height(double x, double z) {
                        return terrain.height(x, z);
                    }

                    public List<Double> surfaces(
                            double x, double z, double minimum, double maximum) {
                        return terrain.surfaces(x, z, minimum, maximum);
                    }

                    public boolean bodyClear(double x, double floor, double z) {
                        return bodyClear(x, floor, z, 0, 0);
                    }

                    public boolean bodyClear(
                            double x, double floor, double z, double gx, double gz) {
                        for (var b : obstacles)
                            if (Math.hypot(x - b[0], z - b[1]) < 1.5) return false;
                        return terrain.bodyClear(x, floor, z, gx, gz);
                    }
                },
                p.x,
                Double.isFinite(sensedFloor)
                        ? sensedFloor
                        : rig.corePivot().y
                                - control.standingHeight()
                                        * Math.sqrt(
                                                1
                                                        + control.gradientX * control.gradientX
                                                        + control.gradientZ * control.gradientZ),
                p.z);
    }

    MinecraftMachinesLeaperNavigationSearch planning;
    MinecraftMachinesLeaperNavigationSearch followSearch;
    double maximumRequestMillis;

    boolean go(double x, double y, double z) {
        boolean wasHunting = hunting;
        hunting = false;
        boolean accepted = requestGoal(x, y, z);
        if (accepted) prey.clear();
        else hunting = wasHunting;
        return accepted;
    }

    boolean requestGoal(double x, double y, double z) {
        long began = System.nanoTime();
        try {
            var p = rig.position();
            if (!Double.isFinite(x + y + z)
                    || Math.hypot(x - p.x, z - p.z) > 192
                    || !leap.supportedDestination(x, y, z)) {
                reason = "Select a supported surface within 192 blocks";
                return false;
            }
            if (leap.inAir()) {
                pendingGoal = new Vector3d(x, y, z);
                pendingRetryTick = tick;
                pendingDeadline = tick + 300;
                return true;
            }
            leap.cancelPreparation();
            followSearch = null;
            goal = new Vector3d(x, y, z);
            distance = Math.hypot(x - p.x, z - p.z);
            goalStanceFloor = y;
            pendingGoal = null;
            terminalX = terminalZ = 0;
            command = null;
            arrived = false;
            sprintForward = sprintStrafe = 0;
            holdPosition = false;
            holdReleaseTicks = 0;
            routeBlocked = true;
            route = List.of();
            index = 0;
            backUntil = 0;
            escape = null;
            planning = new MinecraftMachinesLeaperNavigationSearch(this);
            reason = "Planning route";
            plans++;
            return true;
        } finally {
            maximumRequestMillis =
                    Math.max(maximumRequestMillis, (System.nanoTime() - began) / 1e6);
        }
    }

    private boolean planNow(double x, double y, double z) {
        return requestGoal(x, y, z);
    }

    int followPlanTick = -40;

    boolean pursue(double x, double y, double z) {
        if (!Double.isFinite(x + y + z)) return false;
        hunting = true;
        prey.observe(x, z, tick);
        var current = rig.position();
        var aim =
                prey.aim(
                        current.x, current.z, travelPace().equals("super_horrific") ? 12 : 4, tick);
        double px = aim.x(), pz = aim.z();
        // Follow updates are coalesced. Explicit block clicks still call go() immediately.
        // Finish the current search, run-up and launch instead of restarting every player step.
        if (!leap.inAir()
                && (planning != null
                        || escape != null && tick < backUntil
                        || followSearch != null
                        || leap.state != MinecraftMachinesLeaperLeap.State.WALK
                        || goal != null && tick - followPlanTick < 4)) return true;
        double range = Math.hypot(px - current.x, pz - current.z);
        if (range > 160) {
            px = current.x + (px - current.x) * 160 / range;
            pz = current.z + (pz - current.z) * 160 / range;
        }
        double floor = MinecraftMachinesLeaperPursuitGround.floor(this, px, y, pz);
        if (Double.isFinite(floor) && followDestination(px, floor, pz)) return true;
        // A predicted point may be over a ledge; prefer the player's actual dry surface.
        floor = MinecraftMachinesLeaperPursuitGround.floor(this, x, y, z);
        if (Double.isFinite(floor) && followDestination(x, floor, z)) return true;
        // Approach a supported point near a player standing at an edge.
        // Spread fallbacks across updates rather than doing a synchronous search.
        for (int attempt = 0; attempt < 2; attempt++) {
            int i = Math.floorMod(approachIndex++, 64), sector = i % 8;
            int offset = sector % 2 == 0 ? -sector / 2 : (sector + 1) / 2;
            double radius = (3 + 4 * (i / 8)) * rig.scale;
            double angle = Math.atan2(current.z - z, current.x - x) + offset * Math.PI / 4;
            double ax = x + radius * Math.cos(angle), az = z + radius * Math.sin(angle);
            double ay = MinecraftMachinesLeaperPursuitGround.floor(this, ax, y, az);
            if (Double.isFinite(ay) && followDestination(ax, ay, az)) return true;
        }
        return false;
    }

    private boolean followDestination(double x, double floor, double z) {
        // A dry block under the player is insufficient: the Leaper needs room for
        // its whole scaled stance. Reject before accepting an asynchronous route,
        // so pursue() can advance to alternate approach points this same update.
        if (!Double.isFinite(planner(floor).stanceFloor(x, floor, z))) return false;
        var requested = pendingGoal != null ? pendingGoal : goal;
        // Compare resolved support surfaces, so jumping above one point does not replan it.
        if (requested != null
                && !routeBlocked
                && Math.hypot(requested.x - x, requested.z - z) < .6
                && Math.abs(requested.y - floor) < .75) return true;
        if (!leap.inAir() && !route.isEmpty() && !routeBlocked && !holdPosition) {
            followSearch =
                    new MinecraftMachinesLeaperNavigationSearch(
                            this, new Vector3d(x, floor, z), true);
            followPlanTick = tick;
            plans++;
            return true;
        }
        double previousForward = sprintForward, previousStrafe = sprintStrafe;
        int previousCooldown = leap.cooldown;
        if (!requestGoal(x, floor, z)) return false;
        followPlanTick = tick;
        sprintForward = previousForward;
        sprintStrafe = previousStrafe;
        // Pursuit refreshes must not perpetually postpone an otherwise eligible pounce.
        leap.cooldown = previousCooldown;
        return true;
    }

    void stop() {
        waitingForClearingPulse = false;
        hunting = false;
        prey.clear();
        followSearch = null;
        leap.cancelPreparation();
        planning = null;
        sprintForward = sprintStrafe = 0;
        pendingGoal = null;
        pendingRetryTick = pendingDeadline = 0;
        goal = null;
        command = null;
        route = List.of();
        arrived = false;
        reason = "";
        backUntil = 0;
        holdPosition = false;
        routeBlocked = false;
        escape = null;
    }

    double automaticLeapDistance() {
        return LeaperTraversalPolicy.LONG_DISTANCE;
    }

    boolean automaticLeapAllowed(Vector3d target) {
        if (!behaviourAllowsLeaps || !leap.enabled || pulseActive || tick < leap.nextAutomaticLeapTick || target == null)
            return false;
        var p = rig.corePivot();
        double floor = leap.supportFloor();
        if (!Double.isFinite(floor)) floor = p.y - control.standingHeight();
        return LeaperTraversalPolicy.shouldJump(
                Math.hypot(target.x - p.x, target.z - p.z), target.y - floor,
                apex.needsLeap(target));
    }

    void replanAfterPulse() {
        var target = pendingGoal != null ? pendingGoal : goal;
        if (target == null || leap.inAir()) return;
        waitingForClearingPulse = false;
        goal = new Vector3d(target);
        pendingGoal = null;
        pendingRetryTick = pendingDeadline = 0;
        obstacles.clear();
        escape = null;
        backUntil = assistStallTicks = 0;
        followSearch = null;
        command = null;
        holdPosition = arrived = false;
        routeBlocked = true;
        planning = new MinecraftMachinesLeaperNavigationSearch(this, target, false);
        plans++;
        reason = "Rechecking terrain after clearing pulse";
    }

    private void advance(Vector3d p) {
        double lookahead = travelPace().equals("super_horrific") && distance > 14 ? 12 : 5;
        if (apex.enabled)
            lookahead =
                    dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperApexDrive
                            .lookahead(Math.hypot((control.measuredX * rig.scale), (control.measuredZ * rig.scale)));
        LeaperPredatorPlanner visibility = null;
        while (index < route.size() - 1
                && Math.hypot(route.get(index).x() - p.x, route.get(index).z() - p.z) < lookahead) {
            var next = route.get(index + 1);
            if (visibility == null) visibility = planner(goal.y);
            // Nearby corners still require a traversable connection from the actual body.
            if (Math.hypot(route.get(index).x() - p.x, route.get(index).z() - p.z) > 1.25
                    && !visibility.shortcutTo(next.x(), next.y(), next.z())) break;
            index++;
        }
    }

    private void recover(Vector3d p) {
        recoveries++;
        escape = null;
        var trail = safeTrail.descendingIterator();
        while (trail.hasNext()) {
            var point = trail.next();
            if (Math.hypot(point.x - p.x, point.z - p.z) > 4) {
                escape = new Vector3d(point);
                break;
            }
        }
        backUntil = tick + 60;
    }

    int assistStallTicks;

    private void assistTerrain(double forward, double strafe) {
        if (apex.step()) return;
        if (climbAssist.step(forward, strafe)) return;
        double demand = Math.hypot(forward, strafe);
        var velocity = rig.assemblyVelocity();
        if (goal == null
                || arrived
                || holdPosition
                || planning != null
                || routeBlocked
                || leap.state != MinecraftMachinesLeaperLeap.State.WALK
                || tick < backUntil
                || demand < .2
                || rig.upright() < .55
                || leap.supports() < 1) {
            assistStallTicks = 0;
            return;
        }
        double h = rig.heading();
        double dx = (Math.sin(h) * forward + Math.cos(h) * strafe) / demand;
        double dz = (-Math.cos(h) * forward + Math.sin(h) * strafe) / demand;
        double along = velocity.x * dx + velocity.z * dz;
        assistStallTicks = along < .6 ? assistStallTicks + 1 : 0;
        boolean rough = control.clearGroundBlend < .8;
        if (tick % 2 != 0 || !rough && assistStallTicks < 20 || distance <= 3) return;
        double ceilingSpeed =
                pace.equals("super_horrific") ? 5 : pace.equals("careful") ? 1.5 : 3.5;
        if (Math.hypot(velocity.x, velocity.z) >= ceilingSpeed || Math.abs(velocity.y) > 3) return;
        double push = Math.clamp(ceilingSpeed - along, 0, .3) * Math.min(1, demand * 2);
        var core = rig.corePivot();
        double floor = map.height(core.x, core.z, core.y + .3);
        double ahead = map.height(core.x + 1.5 * dx, core.z + 1.5 * dz, core.y + 3);
        boolean headroom =
                level.noCollision(
                        new net.minecraft.world.phys.AABB(
                                core.x - 1.3,
                                core.y + 1.4,
                                core.z - 1.3,
                                core.x + 1.3,
                                core.y + 2.1,
                                core.z + 1.3));
        double up =
                Double.isFinite(floor)
                                && Double.isFinite(ahead)
                                && ahead > floor + .4
                                && velocity.y < 2
                                && headroom
                        ? .45
                        : 0;
        if (push > 0) rig.terrainAssistImpulse(new Vector3d(dx * push, up, dz * push));
    }

    void step() {
        ++tick;
        leap.observeFooting();
        if (waitingForClearingPulse) {
            control.step(0, 0, 0, .35);
            apex.brake();
            return;
        }
        if (leap.recoverAfterBurst()) { apex.brake(); return; }
        control.apex = apex.enabled;
        control.apexTargetSpeed = apex.enabled ? apex.requestedSpeed / rig.scale : 0;
        apex.steer();
        if (followSearch != null) {
            if (leap.state != MinecraftMachinesLeaperLeap.State.WALK) followSearch = null;
            else {
                long began = System.nanoTime();
                followSearch.advance(began + 5_000_000);
                maximumPlanMillis = Math.max(maximumPlanMillis, (System.nanoTime() - began) / 1e6);
                if (followSearch.done) followSearch = null;
            }
        }
        String activePace = travelPace();
        control.setPace(activePace);
        control.sprintAllowed =
                goal != null
                        && planning == null
                        && !holdPosition
                        && (movingPrey()
                                || Math.hypot(goal.x - rig.position().x, goal.z - rig.position().z)
                                        > 12)
                        && leap.state == MinecraftMachinesLeaperLeap.State.WALK
                        && tick >= backUntil;
        if (planning != null && !leap.inAir()) {
            if (tick > 80) {
                long began = System.nanoTime();
                planning.advance(began + 5_000_000);
                double millis = (System.nanoTime() - began) / 1e6;
                maximumPlanMillis = Math.max(maximumPlanMillis, millis);
                totalPlanMillis += millis;
                if (planning.done) planning = null;
            }
            control.step(0, 0, 0, .45);
            apex.brake();
            return;
        }
        var p = rig.position();
        motion.update(p.x, p.z);
        if (leap.step()) {
            apex.brake();
            return;
        }
        if (goal != null && route.isEmpty()) {
            if (MinecraftMachinesLeaperEscape.step(this)) return;
            // A leap can physically reach an irregular stance that the conservative
            // walking graph rejects. Arrival depends on the live rig, not on whether
            // that graph can reconstruct a route after touchdown.
            distance = Math.hypot(p.x - goal.x, p.z - goal.z);
            var velocity = rig.assemblyVelocity();
            double heightError = Math.abs(rig.corePivot().y - goal.y - control.standingHeight());
            if (apex.enabled
                    && !movingPrey()
                    && pendingGoal == null
                    && distance <= ARRIVAL_RADIUS
                    && heightError < 1.25
                    && Math.hypot(velocity.x, velocity.z) <= 1
                    && Math.abs(velocity.y) <= .75
                    && leap.supports() >= 1
                    && rig.upright() >= .65
                    && rig.jointStrain() <= .11) {
                arrived = holdPosition = true;
                holdReleaseTicks = 0;
                routeBlocked = false;
                reason = "Arrived on supported footing";
            } else if (holdPosition) {
                holdReleaseTicks =
                        distance > ARRIVAL_RELEASE_RADIUS || heightError >= 1.25
                                ? holdReleaseTicks + 1
                                : 0;
                if (holdReleaseTicks >= 20) requestGoal(goal.x, goal.y, goal.z);
            }
            // A body can stand on its real feet while the ideal walking footprint
            // has no start node. Do not brake here forever or require the travel
            // goal itself to be a useful escape landing (it may be very close).
            if (!arrived && routeBlocked && !pulseActive && leap.enabled
                    && leap.state == MinecraftMachinesLeaperLeap.State.WALK
                    && !leap.recoveryPending && tick >= leap.cooldown
                    && tick - leap.lastAttempt >= 80 && motion.stationary() && leap.canPrepare()) {
                leap.lastAttempt = tick;
                leap.requestRecoveryLeap();
                reason = "Finding a supported escape landing";
            }
            control.step(0, 0, 0, .45);
            apex.brake();
            return;
        }
        if (pendingGoal != null && (tick >= pendingRetryTick || tick >= pendingDeadline)) {
            var requested = new Vector3d(pendingGoal);
            if (planNow(requested.x, requested.y, requested.z)) {
                pendingGoal = null;
            } else if (tick >= pendingDeadline || !reason.equals("No supported escape stance")) {
                var failure = reason;
                stop();
                reason =
                        "Leaper stopped: the selected destination could not be planned. " + failure;
            } else {
                pendingRetryTick = tick + 10;
                reason = "Waiting for a supported stance to plan the selected destination";
            }
        }
        if (tick == 81) {
            progress = new Vector3d(p);
            progressTick = tick;
        }
        if (goal != null && tick > 80) {
            distance = Math.hypot(p.x - goal.x, p.z - goal.z);
            double expectedHeight =
                    control.standingHeight()
                            * Math.sqrt(
                                    1
                                            + control.gradientX * control.gradientX
                                            + control.gradientZ * control.gradientZ);
            boolean goalPose =
                    Math.abs(rig.corePivot().y - goalStanceFloor - expectedHeight) < 1.25;
            double velocity = Math.hypot(rig.velocity().x, rig.velocity().z);
            if (!movingPrey() && pendingGoal == null && distance <= ARRIVAL_RADIUS && goalPose)
                holdPosition = true;
            if (movingPrey()) holdPosition = arrived = false;
            boolean grounded =
                    Math.abs(rig.velocity().y) < .75
                            && (apex.enabled ? leap.footing.ready() : leap.supports() >= 2);
            // Keep the stopping decision through touchdown/rebound. Re-enter pursuit only
            // after a sustained, grounded departure, not a single vertical gait sample.
            if (holdPosition && grounded && (distance > ARRIVAL_RELEASE_RADIUS || !goalPose))
                holdReleaseTicks++;
            else holdReleaseTicks = 0;
            if (pendingGoal != null || holdReleaseTicks >= 20) {
                holdPosition = false;
                holdReleaseTicks = 0;
            }
            if (holdPosition) {
                command = null;
                sprintForward = sprintStrafe = 0;
                terminalX = terminalZ = 0;
                arrived =
                        pendingGoal == null
                                && distance <= ARRIVAL_RELEASE_RADIUS
                                && goalPose
                                && grounded
                                && (velocity < 1 || motion.stationary())
                                && rig.upright() > .65;
                routeBlocked = false;
                backUntil = 0;
                control.step(0, 0, 0, pace.equals("careful") ? .45 : pace.equals("fast") ? .75 : 1);
                apex.brake();
                return;
            }
        }
        if (goal != null && tick > 80 && (command == null || tick % 2 == 1)) {
            distance = Math.hypot(p.x - goal.x, p.z - goal.z);
            advance(p);
            if (tick - progressTick >= 100 && distance > 3) {
                if (Math.hypot(p.x - progress.x, p.z - progress.z) < 1.2) {
                    var target = route.get(index);
                    double dx = target.x() - p.x,
                            dz = target.z() - p.z,
                            n = Math.max(.01, Math.hypot(dx, dz));
                    obstacles.add(new double[] {p.x + 3 * dx / n, p.z + 3 * dz / n, tick + 600});
                    recover(p);
                }
                progress = new Vector3d(p);
                progressTick = tick;
            }
            if (tick >= backUntil && (tick % (apex.enabled ? 4 : 20) == 1 || tick == backUntil)) {
                var target = route.get(index);
                // Steering looks ahead; progress must be measured against the route already
                // traversed, not just the short segment immediately before that distant target.
                double deviation = Math.hypot(p.x - route.get(0).x(), p.z - route.get(0).z());
                for (int segment = 1; segment <= index; segment++) {
                    var from = route.get(segment - 1);
                    var to = route.get(segment);
                    double dx = to.x() - from.x(), dz = to.z() - from.z();
                    double along =
                            Math.clamp(
                                    ((p.x - from.x()) * dx + (p.z - from.z()) * dz)
                                            / Math.max(.01, dx * dx + dz * dz),
                                    0,
                                    1);
                    deviation =
                            Math.min(
                                    deviation,
                                    Math.hypot(
                                            p.x - from.x() - along * dx,
                                            p.z - from.z() - along * dz));
                }
                // Replanning is sliced across ticks; a local edge check handles new obstacles.
                boolean blocked =
                        !planner(goal.y)
                                .segmentAheadTo(
                                        target.x(),
                                        target.y(),
                                        target.z(),
                                        apex.enabled
                                                ? dev.ahmedhamedi.minecraft_machines.content
                                                        .training.morphology.LeaperApexDrive
                                                        .lookahead(
                                                                Math.hypot(
                                                                        (control.measuredX * rig.scale),
                                                                        (control.measuredZ * rig.scale)))
                                                : 6);
                if (deviation > 4 || blocked || tick == backUntil || routeBlocked) {
                    var old = new Vector3d(goal);
                    replans++;
                    planNow(old.x, old.y, old.z);
                    control.step(0, 0, 0, .45);
                    apex.brake();
                    return;
                } else if (safeTrail.isEmpty() || p.distance(safeTrail.getLast()) > 1) {
                    safeTrail.addLast(new Vector3d(p));
                    while (safeTrail.size() > 40) safeTrail.removeFirst();
                }
            }
            if (tick >= backUntil && !routeBlocked) {
                advance(p);
                var target = route.get(index);
                if (feedback.goalX() != target.x() || feedback.goalZ() != target.z())
                    feedback.setGoal(target.x(), target.z());
                command =
                        feedback.update(
                                p.x,
                                p.z,
                                rig.heading(),
                                .1,
                                pace.equals("careful") ? .45 : pace.equals("fast") ? .75 : 1);
                double velocity = Math.hypot(rig.velocity().x, rig.velocity().z);
                if (!movingPrey() && index == route.size() - 1 && distance < 5) {
                    double dx = goal.x - p.x, dz = goal.z - p.z;
                    double wx = .32 * dx - .12 * (control.measuredX * rig.scale);
                    double wz = .32 * dz - .12 * (control.measuredZ * rig.scale);
                    double radial = (wx * dx + wz * dz) / Math.max(.001, distance * distance);
                    if (radial < 0) {
                        wx -= radial * dx;
                        wz -= radial * dz;
                    }
                    double norm = Math.max(1, Math.hypot(wx, wz) / .65);
                    wx /= norm;
                    wz /= norm;
                    double c = Math.cos(rig.heading()), s = Math.sin(rig.heading());
                    command =
                            new LeaperPointController.Command(
                                    s * wx - c * wz,
                                    c * wx + s * wz,
                                    distance < 3 ? 0 : command.turn(),
                                    distance,
                                    velocity,
                                    false);
                }
                arrived =
                        !movingPrey()
                                && pendingGoal == null
                                && index == route.size() - 1
                                && distance <= ARRIVAL_RADIUS
                                && (velocity < 1 || motion.stationary())
                                && Math.abs(
                                                rig.corePivot().y
                                                        - goalStanceFloor
                                                        - control.standingHeight()
                                                                * Math.sqrt(
                                                                        1
                                                                                + control.gradientX
                                                                                        * control.gradientX
                                                                                + control.gradientZ
                                                                                        * control.gradientZ))
                                        < 1.25
                                && rig.upright() > .65;
            }
        }
        double speed = pace.equals("careful") ? .45 : pace.equals("fast") ? .75 : 1;
        double f = command == null ? 0 : command.forward(),
                s = command == null ? 0 : command.strafe(),
                t = command == null ? 0 : command.turn() * .4;
        if (tick < backUntil) {
            if (escape != null) {
                double dx = escape.x - p.x, dz = escape.z - p.z;
                double n = Math.max(1, Math.hypot(dx, dz));
                f = .65 * (Math.sin(rig.heading()) * dx - Math.cos(rig.heading()) * dz) / n;
                s = .65 * (Math.cos(rig.heading()) * dx + Math.sin(rig.heading()) * dz) / n;
                t = 0;
            } else {
                f = -.5 * f;
                s = -.5 * s;
                t = .2;
            }
        } else if (routeBlocked) {
            f = s = t = 0;
        }
        if (activePace.equals("super_horrific")
                && goal != null
                && distance > 2
                && tick >= backUntil
                && !routeBlocked) {
            var target = route.get(index);
            double dx = target.x() - p.x,
                    dz = target.z() - p.z,
                    n = Math.max(.001, Math.hypot(dx, dz));
            double closingSpeed =
                    ((control.measuredX * rig.scale) * dx + (control.measuredZ * rig.scale) * dz) / n
                            - prey.speedAlong(dx, dz, tick);
            double drive =
                    movingPrey()
                            ? Math.clamp(
                                    (distance + 2 - .6 * Math.max(0, closingSpeed)) / 8, .35, 1)
                            : Math.clamp((distance - 2) / 12, .35, 1);
            double h = rig.heading();
            double desiredF = drive * (Math.sin(h) * dx - Math.cos(h) * dz) / n;
            double desiredS = drive * (Math.cos(h) * dx + Math.sin(h) * dz) / n;
            sprintForward += Math.clamp(desiredF - sprintForward, -.05, .05);
            sprintStrafe += Math.clamp(desiredS - sprintStrafe, -.05, .05);
            f = sprintForward;
            s = sprintStrafe;
            if (movingPrey() || distance > 12) speed = Math.clamp(Math.hypot(f, s), .25, 1);
        }
        if (control.agile && goal != null && distance > 3 && tick >= backUntil && !routeBlocked) {
            var target = route.get(index);
            double error =
                    dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait.wrap(
                            Math.atan2(target.x() - p.x, -(target.z() - p.z)) - rig.heading());
            double rate =
                    dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait.wrap(
                                    rig.heading() - lastFacing)
                            / .05;
            facingRate += .2 * (rate - facingRate);
            t = turnCommand(error, false);
            if (activePace.equals("super_horrific")
                    && distance > 12
                    && index > 0
                    && !movingPrey()) {
                var from = route.get(index - 1);
                error =
                        dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait
                                .wrap(
                                        Math.atan2(target.x() - from.x(), -(target.z() - from.z()))
                                                - rig.heading());
                t = turnCommand(error, true);
            }
            double align = .12 + .88 * Math.pow(Math.max(0, Math.cos(error)), 2);
            f *= align;
            s *= align;
        }
        lastFacing = rig.heading();
        control.step(f, s, t, speed);
        assistTerrain(f, s);
    }
}
