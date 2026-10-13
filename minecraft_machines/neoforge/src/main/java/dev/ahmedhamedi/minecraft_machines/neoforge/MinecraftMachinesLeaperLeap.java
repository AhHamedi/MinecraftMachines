package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperBallistics;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperTraversalPolicy;

import net.minecraft.world.phys.AABB;

import org.joml.Vector3d;

import java.util.*;

/** Gameplay leap: one bounded native impulse, then an unassisted ballistic translation. */
final class MinecraftMachinesLeaperLeap {
    static final int RECOVERY_COOLDOWN_TICKS = 12;

    enum State {
        WALK,
        RETREAT,
        ALIGN,
        CROUCH,
        FLIGHT,
        LAND
    }

    record Landing(double heading, double coreY, double radius, double[][] feet, double[] angles) {}

    record Plan(Vector3d destination, LeaperBallistics.Arc arc, Landing landing) {
        Plan(Vector3d destination, LeaperBallistics.Arc arc) {
            this(destination, arc, null);
        }
    }

    final MinecraftMachinesLeaperPredatorNavigator nav;
    final MinecraftMachinesLeaper rig;
    final boolean enabled = MinecraftMachinesLeaperRuntime.motionEnabled("leaperLeap");
    final dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperSupportWindow
            footing =
                    new dev.ahmedhamedi.minecraft_machines.content.training.morphology
                            .LeaperSupportWindow();
    State state = State.WALK;
    Plan active;
    int stateTicks, cooldown, lastAttempt, lastProgress, launches, landings, aborts;
    int nextAutomaticLeapTick;
    int groundedTicks, airborneTicks, stableTicks, touchdownTick, stableLandingTick;
    int settledRecoveryTicks;
    boolean demonstratedAirborne;
    double bestDistance = Double.POSITIVE_INFINITY;
    Vector3d observedGoal;
    final List<Map<String, Object>> events = new ArrayList<>();

    MinecraftMachinesLeaperLeap(MinecraftMachinesLeaperPredatorNavigator nav) {
        this.nav = nav;
        rig = nav.rig;
    }

    boolean inAir() {
        return state == State.FLIGHT || state == State.LAND;
    }

    void cancelPreparation() {
        if (!inAir()) {
            state = State.WALK;
            search = null;
            retreat = null;
            active = null;
            cooldown = nav.tick + RECOVERY_COOLDOWN_TICKS;
        }
    }

    boolean supportedDestination(double x, double y, double z) {
        if (!Double.isFinite(x + y + z)) return false;
        double h = nav.map.padHeight(x, z, y + .55);
        return Double.isFinite(h) && Math.abs(h - y) < .55;
    }

    int supports() {
        int count = 0;
        for (int i = 0; i < 4; i++) {
            var toe = rig.toe(i);
            double h = nav.map.height(toe.x, toe.z, toe.y + .35 * rig.scale);
            if (Double.isFinite(h) && Math.abs(toe.y - h) < .42 * rig.scale) count++;
        }
        return count;
    }

    void observeFooting() {
        if (inAir()) footing.reset();
        else footing.update(supports(), rig.velocity().y, rig.upright(), rig.jointStrain());
    }

    boolean canPrepare() {
        return nav.apex.enabled ? footing.ready() : supports() >= 2 && rig.upright() >= .8;
    }

    /** Actual nearby toe surfaces can support a body straddling a hole. */
    double supportFloor() {
        double total = 0;
        int count = 0;
        for (int i = 0; i < 4; i++) {
            var toe = rig.toe(i);
            double h = nav.map.height(toe.x, toe.z, toe.y + .35 * rig.scale);
            if (Double.isFinite(h) && Math.abs(toe.y - h) < .42 * rig.scale) {
                total += h;
                count++;
            }
        }
        return count == 0 ? Double.NaN : total / count;
    }

    Plan plan(Vector3d target) {
        var search = new Search(target);
        while (!search.done) search.advance(Long.MAX_VALUE);
        return search.result;
    }

    /** Terrain reads stay on the server thread; collision checks resume next tick. */
    final class Search {
        final List<LeaperBallistics.Arc> candidates = new ArrayList<>();
        final Vector3d target, origin, com, startOffset, landingOffset, velocity;
        final double d, dx, dz, gravity, drag;
        double range, x, z, high, endY;
        int side, stretch, sample, samples, checked;
        boolean endpoint, done, shallowFallback;
        double[] previous;
        LeaperBallistics.Arc arc;
        Landing landing;
        Plan result;

        Search(Vector3d target) {
            shallowFallback = false;
            this.target = new Vector3d(target);
            origin = rig.corePivot();
            com = rig.assemblyPosition();
            startOffset = new Vector3d(com).sub(origin);
            landingOffset = rig.landingMassOffset(4.8, nav.control.policy.value[4]);
            velocity = rig.assemblyVelocity();
            var g = rig.gravity();
            gravity = -g.y;
            drag = rig.airDrag();
            d = Math.hypot(target.x - origin.x, target.z - origin.z);
            dx = (target.x - origin.x) / Math.max(.001, d);
            dz = (target.z - origin.z) / Math.max(.001, d);
            range = Math.min(72, d);
            done = !enabled || d < 4 || gravity < .1 || Math.hypot(g.x, g.z) > .001;
        }

        void nextEndpoint() {
            endpoint = false;
            arc = null;
            stretch = 0;
            if (++side == 3) {
                side = 0;
                range -= 2;
            }
        }

        void nextArc() {
            arc = null;
            if (++stretch == candidates.size()) nextEndpoint();
        }

        Landing landingAt(double floor) {
            double heading = Math.atan2(dx, -dz), c = Math.cos(heading), s = Math.sin(heading);
            double[] theta = {-55, 55, -125, 125};
            for (double radius : new double[] {4.8, 4.2, 3.6, 3.0}) {
                // Cache dry, exposed toe-pad candidates before testing chassis heights.
                var pads = new ArrayList<List<Vector3d>>();
                for (int leg = 0; leg < 4; leg++) {
                    double a = Math.toRadians(theta[leg]);
                    double lx = radius * rig.scale * Math.sin(a), lz = -radius * rig.scale * Math.cos(a);
                    var choices = new ArrayList<Vector3d>();
                    for (double[] shift :
                            new double[][] {{0, 0}, {-.35, 0}, {.35, 0}, {0, -.35}, {0, .35}}) {
                        double wx = x + c * lx - s * lz + shift[0];
                        double wz = z + s * lx + c * lz + shift[1];
                        double h = nav.map.padHeight(wx, wz, floor + 8);
                        if (Double.isFinite(h)) choices.add(new Vector3d(wx, h, wz));
                    }
                    pads.add(choices);
                }
                if (pads.stream().anyMatch(List::isEmpty)) continue;
                for (int rise = 0; rise <= 12; rise++) {
                    double coreY = floor + nav.control.standingHeight() + .12 + .25 * rise;
                    if (!nav.level.noCollision(
                            new AABB(x - 1.4 * rig.scale, coreY - 1.5 * rig.scale, z - 1.4 * rig.scale, x + 1.4 * rig.scale, coreY + 5.5 * rig.scale, z + 1.4 * rig.scale)))
                        continue;
                    double[][] feet = new double[4][3];
                    double[] angles = new double[12];
                    boolean reachable = true;
                    for (int leg = 0; leg < 4; leg++) {
                        boolean found = false;
                        for (var pad : pads.get(leg)) {
                            double lx = c * (pad.x - x) + s * (pad.z - z);
                            double ly = pad.y - coreY;
                            double lz = -s * (pad.x - x) + c * (pad.z - z);
                            if (!rig.reachableFoot(leg, lx, ly, lz)) continue;
                            var q = LeaperGait.inverse(leg, lx / rig.scale, ly / rig.scale, lz / rig.scale);
                            feet[leg] = new double[] {lx, ly, lz};
                            System.arraycopy(q, 0, angles, 3 * leg, 3);
                            found = true;
                            break;
                        }
                        if (!found) {
                            reachable = false;
                            break;
                        }
                    }
                    if (reachable) return new Landing(heading, coreY, radius, feet, angles);
                }
            }
            return null;
        }

        double launchAngle(LeaperBallistics.Arc candidate) {
            return Math.atan2(candidate.vy(), Math.hypot(candidate.vx(), candidate.vz()));
        }

        void rankArcs() {
            candidates.clear();
            // Native gravity/drag determine the attainable angle. Try the full range
            // first and shorten only when no >=55 degree arc fits the finite caps.
            // Within one endpoint prefer 70 degrees, including longer, higher paths
            // before falling back to a low arc under an obstruction.
            for (int step = 0; step <= 70; step++) {
                double seconds = 1 + step * .05;
                var candidate =
                        LeaperBallistics.timed(
                                com.x,
                                com.y,
                                com.z,
                                x + landingOffset.x,
                                endY + landingOffset.y,
                                z + landingOffset.z,
                                gravity,
                                drag,
                                seconds);
                var delta =
                        new Vector3d(
                                candidate.vx() - velocity.x,
                                candidate.vy() - velocity.y,
                                candidate.vz() - velocity.z);
                double endVertical =
                        drag < 1e-9
                                ? candidate.vy() - gravity * seconds
                                : (candidate.vy() + gravity / drag) * Math.exp(-drag * seconds)
                                        - gravity / drag;
                if (endVertical > -1
                        || delta.length() > 60
                        || Math.hypot(delta.x, delta.z) > 55
                        || Math.hypot(candidate.vx(), candidate.vz()) > 50
                        || candidate.vy() > 24
                        || candidate.vy() < 4
                        || delta.y < 0
                        || delta.y > 24
                        || !shallowFallback && launchAngle(candidate) < Math.toRadians(55))
                    continue;
                candidates.add(candidate);
            }
            candidates.sort(Comparator.comparingDouble(
                    candidate -> Math.abs(launchAngle(candidate) - Math.toRadians(70))));
        }

        Plan advance(long deadline) {
            while (!done && System.nanoTime() < deadline) {
                if (range < Math.min(6, d) - .01) {
                    if (shallowFallback) {
                        done = true;
                        break;
                    }
                    // A roof may prohibit every steep solution. Only after checking
                    // all supported steep endpoints may the original low-arc option win.
                    shallowFallback = true;
                    range = Math.min(72, d);
                    side = 0;
                    endpoint = false;
                    continue;
                }
                if (!endpoint) {
                    double lateral = side == 0 ? 0 : side == 1 ? -3 : 3;
                    x = origin.x + dx * range - dz * lateral;
                    z = origin.z + dz * range + dx * lateral;
                    double floor = nav.map.height(x, z, Math.max(target.y, origin.y) + 8, 72);
                    if (!Double.isFinite(floor)) {
                        nextEndpoint();
                        continue;
                    }
                    high = floor;
                    landing = landingAt(floor);
                    if (landing == null) {
                        nextEndpoint();
                        continue;
                    }
                    endY = landing.coreY();
                    landingOffset.set(rig.landingMassOffset(landing.angles(), landing.heading()));
                    if (endY - origin.y > 20 || endY - origin.y < -36) {
                        nextEndpoint();
                        continue;
                    }
                    endpoint = true;
                    rankArcs();
                    if (candidates.isEmpty()) {
                        nextEndpoint();
                        continue;
                    }
                }
                if (arc == null) {
                    arc = candidates.get(stretch);
                    samples = (int) Math.ceil(arc.seconds() / .0125);
                    sample = 1;
                    previous = arc.at(0);
                    previous[0] -= startOffset.x;
                    previous[1] -= startOffset.y;
                    previous[2] -= startOffset.z;
                }
                double fraction = sample / (double) samples;
                var point = arc.at(arc.seconds() * fraction);
                var offset = new Vector3d(startOffset).lerp(landingOffset, fraction);
                point[0] -= offset.x;
                point[1] -= offset.y;
                point[2] -= offset.z;
                // Landing limbs must meet the terrain. Taper the late-flight sweep to
                // the torso; per-leg reach is validated separately and native physics
                // resolves actual link contact, rather than treating the stance as solid.
                double halfWidth = 5.5 - 4.1 * Math.clamp((fraction - .60) / .25, 0, 1);
                // Departure feet already touch terrain. Begin with the torso envelope
                // and expand as the legs leave their contacts, just as the envelope
                // narrows again for landing. The roof/torso checks remain continuous.
                if (nav.apex.enabled)
                    halfWidth = Math.min(halfWidth, 1.4 + 4.1 * Math.clamp(fraction / .20, 0, 1));
                halfWidth *= rig.scale;
                var box =
                        new AABB(
                                Math.min(previous[0], point[0]) - halfWidth,
                                Math.min(previous[1], point[1]) - 1.5 * rig.scale,
                                Math.min(previous[2], point[2]) - halfWidth,
                                Math.max(previous[0], point[0]) + halfWidth,
                                Math.max(previous[1], point[1]) + 5.5 * rig.scale,
                                Math.max(previous[2], point[2]) + halfWidth);
                checked++;
                if (!nav.level.noCollision(box)) {
                    nextArc();
                    continue;
                }
                previous = point;
                if (++sample > samples) {
                    result = new Plan(new Vector3d(x, high, z), arc, landing);
                    done = true;
                }
            }
            return result;
        }
    }

    Search search;
    Vector3d retreat, retreatGoal;
    int retreats, lastRetreat, searchTicks;
    double maximumSearchMillis;

    Plan advanceSearch() {
        long began = System.nanoTime();
        var result = search.advance(began + 5_000_000);
        maximumSearchMillis = Math.max(maximumSearchMillis, (System.nanoTime() - began) / 1e6);
        return result;
    }

    void prepare(Plan plan) {
        active = plan;
        search = null;
        state = State.ALIGN;
        stateTicks = 0;
        lastAttempt = nav.tick;
        event("prepare");
    }

    boolean startRetreat() {
        if (!enabled || nav.goal == null || !canPrepare()) return false;
        var p = rig.corePivot();
        double floor = nav.map.height(p.x, p.z, p.y + .5);
        double dx = p.x - nav.goal.x, dz = p.z - nav.goal.z, d = Math.hypot(dx, dz);
        if (!Double.isFinite(floor) || nav.goal.y - floor < 3 || d < 1) return false;
        if (retreatGoal != null
                && retreatGoal.distanceSquared(nav.goal) < 1
                && nav.tick - lastRetreat < 300) return false;
        dx /= d;
        dz /= d;
        Vector3d candidate = null;
        for (int step = 1; step <= 14; step++) {
            double x = p.x + dx * step, z = p.z + dz * step;
            boolean clear = true;
            for (double lateral : new double[] {-3, 0, 3}) {
                double h = nav.map.height(x - dz * lateral, z + dx * lateral, floor + 2);
                if (!Double.isFinite(h) || Math.abs(h - floor) > .6) clear = false;
            }
            if (!clear
                    || !nav.level.noCollision(
                            new AABB(x - 1.4 * rig.scale, p.y - 1.2 * rig.scale, z - 1.4 * rig.scale, x + 1.4 * rig.scale, p.y + 1.5 * rig.scale, z + 1.4 * rig.scale)))
                break;
            if (step >= 6) candidate = new Vector3d(x, floor, z);
        }
        if (candidate == null) return false;
        retreat = candidate;
        retreatGoal = new Vector3d(nav.goal);
        lastRetreat = nav.tick;
        retreats++;
        state = State.RETREAT;
        stateTicks = 0;
        search = null;
        nav.reason = "Backing away to create launch room";
        event("retreat");
        return true;
    }

    void event(String type) {
        var p = rig.corePivot();
        var v = rig.velocity();
        var row = new LinkedHashMap<String, Object>();
        row.put("event", type);
        row.put("tick", nav.tick);
        row.put("position", new double[] {p.x, p.y, p.z});
        row.put("velocity", new double[] {v.x, v.y, v.z});
        row.put("supports", supports());
        row.put(
                "support_evidence",
                "native pose / collision-surface geometric estimate; not manifold query");
        if (active != null) {
            row.put(
                    "destination",
                    new double[] {
                        active.destination.x, active.destination.y, active.destination.z
                    });
            row.put("arc", active.arc);
        }
        events.add(row);
    }

    boolean recoveryPending;
    Search recoverySearch;
    int recoveryDeadline, recoverySector, recoveryAttempts, recoveryPlans;

    void requestRecoveryLeap() {
        recoveryPending = enabled;
        recoverySearch = null;
        recoverySector = 0;
        recoveryDeadline = nav.tick + 180;
    }

    boolean recoverAfterBurst() {
        if (!recoveryPending) return false;
        if (!enabled || nav.goal == null || nav.tick >= recoveryDeadline || recoverySector >= 8) {
            recoveryPending = false;
            recoverySearch = null;
            nav.replanAfterPulse();
            return false;
        }
        nav.control.step(0, 0, 0, .45);
        nav.reason = "Finding clear ground for escape leap";
        if (!canPrepare() || nav.pulseActive) return true;
        if (recoverySearch == null) {
            var p = rig.corePivot();
            double bearing = Math.atan2(nav.goal.z - p.z, nav.goal.x - p.x);
            int offset = recoverySector % 2 == 0 ? -recoverySector / 2 : (recoverySector + 1) / 2;
            double angle = bearing + offset * Math.PI / 4;
            double radius = Math.min(48, 22 * rig.scale);
            recoverySearch = new Search(new Vector3d(p.x + radius * Math.cos(angle),
                    p.y, p.z + radius * Math.sin(angle)));
            recoveryAttempts++;
        }
        var plan = recoverySearch.advance(System.nanoTime() + 3_000_000);
        if (plan != null) {
            recoveryPlans++;
            recoveryPending = false;
            recoverySearch = null;
            nav.planning = nav.followSearch = null;
            prepare(plan);
            nav.reason = "Leaping out of cleared obstruction";
            return false;
        }
        if (recoverySearch.done) { recoverySearch = null; recoverySector++; }
        return true;
    }

    boolean step() {
        if (!enabled) return false;
        var p = rig.corePivot();
        var velocity = rig.velocity();
        groundedTicks =
                supports() >= 1 && Math.abs(velocity.y) <= 2 && rig.upright() >= .7
                        ? groundedTicks + 1
                        : 0;
        if (state == State.WALK) {
            if (nav.goal == null || nav.tick <= 80 || nav.holdPosition) return false;
            // Close pursuit and ordinary stalls stay on the walking/recovery path.
            if (!nav.automaticLeapAllowed(nav.goal)) {
                search = null;
                return false;
            }
            double distance = Math.hypot(nav.goal.x - p.x, nav.goal.z - p.z);
            if (observedGoal == null || observedGoal.distanceSquared(nav.goal) > 1) {
                observedGoal = new Vector3d(nav.goal);
                bestDistance = distance;
                lastProgress = nav.tick;
            }
            if (distance < bestDistance - .5) {
                bestDistance = distance;
                lastProgress = nav.tick;
            }
            if (search == null) {
                if (nav.tick < cooldown
                        || nav.tick - lastAttempt < 12
                        || distance <= 4)
                    return false;
                lastAttempt = nav.tick;
                if (!canPrepare()) return false;
                search = new Search(nav.goal);
                searchTicks = 0;
            }
            searchTicks++;
            var checked = advanceSearch();
            if (checked == null) {
                if (search.done || searchTicks >= 30) {
                    search = null;
                    if (!startRetreat() && nav.apex.enabled) {
                        cooldown = nav.tick + 40;
                        nav.reason = "No clear takeoff and landing route";
                    }
                } else {
                    nav.control.step(0, 0, 0, .45);
                    return true;
                }
                return false;
            }
            prepare(checked);
        }
        stateTicks++;
        if (state == State.RETREAT) {
            if (nav.goal == null) {
                abort();
                return false;
            }
            double dx = retreat.x - p.x, dz = retreat.z - p.z, d = Math.hypot(dx, dz);
            if (d < 1.5 || stateTicks > 160) {
                state = State.WALK;
                search = null;
                nav.control.resumeAfterLeap();
                nav.planning = new MinecraftMachinesLeaperNavigationSearch(nav);
                event(d < 1.5 ? "retreat-complete" : "retreat-timeout");
                return true;
            }
            double n = Math.max(1, d), h = rig.heading();
            nav.control.step(
                    .7 * (Math.sin(h) * dx - Math.cos(h) * dz) / n,
                    .7 * (Math.cos(h) * dx + Math.sin(h) * dz) / n,
                    0,
                    .75);
            return true;
        }
        if (state == State.ALIGN) {
            if (nav.goal == null || stateTicks > 100) {
                abort();
                return false;
            }
            double err =
                    LeaperGait.wrap(
                            Math.atan2(active.destination.x - p.x, -(active.destination.z - p.z))
                                    - rig.heading());
            nav.control.step(0, 0, Math.clamp(err * 1.8, -1, 1), .85);
            if (Math.abs(err) < .45
                    && Math.hypot(velocity.x, velocity.z) < 4
                    && (nav.apex.enabled ? footing.ready() : groundedTicks >= 3)
                    && nav.tick >= cooldown) {
                state = State.CROUCH;
                stateTicks = 0;
            }
            return true;
        }
        if (state == State.CROUCH) {
            // Apex launches with an assembly impulse, so it does not need a deep,
            // symmetric crouch. Preserve the terrain-adapted support stance: lowering
            // the chassis here can invalidate an arc that was clear while standing,
            // causing an endless prepare / crouch / abort cycle on shorelines.
            if (nav.apex.enabled) nav.control.step(0, 0, 0, .45);
            else nav.control.leapPose(5.1, 1.8, true);
            if (stateTicks > 80 || nav.goal == null) {
                abort();
                return startRetreat();
            }
            if (stateTicks >= 4
                    && (nav.apex.enabled
                            ? footing.ready()
                            : supports() >= 1
                                    && Math.abs(velocity.y) < 1.5
                                    && rig.upright() > .7)) {
                if (search == null) search = new Search(nav.goal);
                var checked = advanceSearch();
                if (checked == null) {
                    if (search.done) {
                        abort();
                        startRetreat();
                        return true;
                    }
                    return true;
                }
                if (search.origin.distance(rig.corePivot()) > .35) {
                    search = null;
                    return true;
                }
                active = checked;
                search = null;
                var assemblyVelocity = rig.assemblyVelocity();
                var delta =
                        new Vector3d(
                                active.arc.vx() - assemblyVelocity.x,
                                active.arc.vy() - assemblyVelocity.y,
                                active.arc.vz() - assemblyVelocity.z);
                if (!delta.isFinite()
                        || delta.length() > 60
                        || Math.hypot(delta.x, delta.z) > 55
                        || delta.y < 0
                        || delta.y > 24) {
                    event("takeoff-replan");
                    search = null;
                    return true;
                }
                nav.control.setLandingFeet(
                        active.landing == null ? null : active.landing.feet(),
                        active.landing == null ? rig.heading() : active.landing.heading());
                rig.leapImpulse(delta);
                footing.reset();
                launches++;
                demonstratedAirborne = false;
                airborneTicks = stableTicks = touchdownTick = stableLandingTick = 0;
                settledRecoveryTicks = 0;
                state = State.FLIGHT;
                stateTicks = 0;
                nav.holdPosition = false;
                nav.arrived = false;
                event("takeoff");
            }
            return true;
        }
        if (state == State.FLIGHT) {
            MinecraftMachinesLeaperJumpFire.emit(nav.level, rig, stateTicks, active.arc.seconds());
            double fraction = stateTicks * .05 / active.arc.seconds();
            nav.control.pouncePose(fraction);
            airborneTicks = supports() == 0 ? airborneTicks + 1 : 0;
            if (airborneTicks >= 2) demonstratedAirborne = true;
            // Native impact can reverse vertical velocity between control samples. Renewed
            // support ends the airborne pose even during that rebound; LAND checks stability.
            if (demonstratedAirborne && fraction > .55 && supports() >= 1) {
                landings++;
                touchdownTick = nav.tick;
                state = State.LAND;
                stateTicks = 0;
                event("touchdown");
            } else if (stateTicks * .05 > active.arc.seconds() + 1) {
                // No repeated impulse if the landing was missed; retain native falling motion.
                aborts++;
                state = State.LAND;
                stateTicks = 0;
                event("flight-timeout");
            }
            return true;
        }
        if (state == State.LAND) {
            nav.control.leapPose(
                    4.8,
                    nav.control.policy.value[4]
                            - .35 * Math.sin(Math.PI * Math.min(1, stateTicks / 12.0)),
                    supports() >= (nav.apex.enabled ? 1 : 2) && stateTicks >= 1);
            stableTicks =
                    supports() >= 3
                                    && rig.upright() >= .85
                                    && Math.abs(velocity.y) <= .5
                                    && Math.hypot(velocity.x, velocity.z) <= 1
                            ? stableTicks + 1
                            : 0;
            // Resume locomotion on usable contact instead of waiting for a motionless
            // three-foot pose. The quiet-motion fallback handles a body wedged on terrain;
            // multiple samples keep a free-flight apex from masquerading as support.
            settledRecoveryTicks =
                    velocity.length() <= .75
                                    && rig.angularVelocity().length() <= 1
                                    && rig.upright() >= .65
                            ? settledRecoveryTicks + 1
                            : 0;
            boolean contactReady =
                    stateTicks >= 2
                            && supports() >= 1
                            && rig.upright() >= .60
                            && velocity.y >= -5
                            && velocity.y <= 4;
            if (contactReady || stateTicks >= 10 && settledRecoveryTicks >= 8) {
                // This timestamp marks a usable locomotion handoff, not a motionless pose.
                stableLandingTick = nav.tick;
                state = State.WALK;
                cooldown = nav.tick + RECOVERY_COOLDOWN_TICKS;
                nextAutomaticLeapTick = nav.tick + LeaperTraversalPolicy.WALK_AFTER_LANDING_TICKS;
                lastProgress = nav.tick;
                bestDistance = Double.POSITIVE_INFINITY;
                nav.control.resumeAfterLeap();
                nav.progress = new Vector3d(rig.position());
                nav.progressTick = nav.tick;
                nav.command = null;
                var nextGoal = nav.pendingGoal != null ? new Vector3d(nav.pendingGoal) : nav.goal;
                if (nextGoal != null) nav.requestGoal(nextGoal.x, nextGoal.y, nextGoal.z);
                event(contactReady ? "contact-resume" : "terrain-recovery");
            }
            return true;
        }
        return false;
    }

    private void abort() {
        aborts++;
        event("aborted");
        state = State.WALK;
        active = null;
        search = null;
        cooldown = nav.tick + RECOVERY_COOLDOWN_TICKS;
        nav.control.resumeAfterLeap();
    }
}
