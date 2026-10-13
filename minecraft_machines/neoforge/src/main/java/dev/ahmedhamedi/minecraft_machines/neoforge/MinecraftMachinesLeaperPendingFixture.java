package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Native fixture edits only; navigation state is observed, never assigned. */
final class MinecraftMachinesLeaperPendingFixture {
    static final String VERSION = "pending-support-transition-v1";
    static final String SPEC_SHA256 =
            "7966faabd9a9e8333f690a6fd1cde047788215f6b7a7b4536b0727e6e2b58aa7";
    final MinecraftMachinesLeaperPredatorNavigator nav;
    final MinecraftMachinesLeaper rig;
    final Vector3d start;
    final Map<String, Object> evidence = new LinkedHashMap<>();
    final LinkedHashMap<BlockPos, BlockState> saved = new LinkedHashMap<>();
    Vector3d target;
    boolean preconditions, removed, restored, restorationVerified;
    boolean pending, invalidPreserved, automaticallyResolved;
    boolean cleanupNeeded;
    int deadline, retryTick;
    long removalServerTick;
    String originalHash = "", failure = "";

    MinecraftMachinesLeaperPendingFixture(
            MinecraftMachinesLeaperPredatorNavigator nav, Vector3d start) {
        this.nav = nav;
        this.rig = nav.rig;
        this.start = new Vector3d(start);
    }

    static List<Double> point(Vector3d p) {
        return p == null ? List.of() : List.of(p.x, p.y, p.z);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            failure = message;
            throw new IllegalStateException(
                    (preconditions ? "Pending invariant: " : "Fixture precondition: ") + message);
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash fixture evidence", e);
        }
    }

    private String blockHash(boolean current) {
        var text = new StringBuilder();
        for (var entry : saved.entrySet()) {
            var pos = entry.getKey();
            var state = current ? nav.level.getBlockState(pos) : entry.getValue();
            var properties = new TreeMap<String, String>();
            state.getValues()
                    .forEach((key, value) -> properties.put(key.getName(), value.toString()));
            text.append(pos.getX())
                    .append(',')
                    .append(pos.getY())
                    .append(',')
                    .append(pos.getZ())
                    .append(':')
                    .append(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                    .append(properties)
                    .append('\n');
        }
        return digest(text.toString());
    }

    void begin(int tick) {
        require(tick == 100, "event must run exactly at test tick 100");
        var p = rig.position();
        double reference =
                rig.corePivot().y
                        - nav.control.standingHeight()
                                * Math.sqrt(
                                        1
                                                + nav.control.gradientX * nav.control.gradientX
                                                + nav.control.gradientZ * nav.control.gradientZ);
        require(Double.isFinite(p.x + p.y + p.z + reference), "nonfinite event reference");
        require(
                rig.valid()
                        && nav.goal != null
                        && !nav.route.isEmpty()
                        && nav.pendingGoal == null
                        && !nav.arrived,
                "original route is not active and unqueued");
        double tx = start.x - 24, tz = start.z - 35;
        double ty = nav.map.height(tx, tz, start.y + 10);
        require(Double.isFinite(ty), "new destination has no dry voxel surface");
        target = new Vector3d(tx, ty, tz);
        int minX = (int) Math.floor(p.x) - 8, maxX = (int) Math.floor(p.x) + 8;
        int minZ = (int) Math.floor(p.z) - 8, maxZ = (int) Math.floor(p.z) + 8;
        int minY = Math.max(nav.level.getMinBuildHeight(), (int) Math.floor(reference - 10));
        int maxY = Math.min(nav.level.getMaxBuildHeight() - 1, (int) Math.ceil(reference + 10));
        int ox = (int) Math.floor(start.x), oz = (int) Math.floor(start.z);
        require(
                minY <= maxY && minY < reference - 5.5 && maxY > reference + 5.5,
                "vertical patch does not cover support search band");
        require(
                minX >= ox - 64 && maxX <= ox + 64 && minZ >= oz - 64 && maxZ <= oz + 64,
                "patch leaves the disposable fixture arena");
        int goalX = (int) Math.floor(tx), goalZ = (int) Math.floor(tz);
        require(
                maxX < goalX - 8 || minX > goalX + 8 || maxZ < goalZ - 8 || minZ > goalZ + 8,
                "patch intersects new destination support square");
        for (int x = goalX - 8; x <= goalX + 8; x++)
            for (int z = goalZ - 8; z <= goalZ + 8; z++)
                require(
                        Double.isFinite(nav.map.height(x + .5, z + .5, ty + 5)),
                        "new destination support square is not dry");
        int modified = 0;
        boolean support = false;
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                for (int y = minY; y <= maxY; y++) {
                    var pos = new BlockPos(x, y, z);
                    require(nav.level.hasChunk(x >> 4, z >> 4), "patch chunk is not loaded");
                    var state = nav.level.getBlockState(pos);
                    require(
                            nav.level.getBlockEntity(pos) == null && !state.hasBlockEntity(),
                            "patch contains block entity");
                    require(state.getFluidState().isEmpty(), "patch contains fluid");
                    saved.put(pos, state);
                    if (!state.isAir()) modified++;
                    if (!state.getCollisionShape(nav.level, pos).isEmpty()) support = true;
                }
        require(support && modified > 0, "patch contains no native support blocks");
        var oldGoal = new Vector3d(nav.goal);
        var oldRoute = nav.route;
        var oldRouteContent = List.copyOf(oldRoute);
        var routeSnapshot = new ArrayList<List<Double>>();
        for (var waypoint : oldRoute)
            routeSnapshot.add(List.of(waypoint.x(), waypoint.y(), waypoint.z()));
        originalHash = blockHash(false);
        removalServerTick = nav.level.getGameTime();
        evidence.put("event_test_tick", tick);
        evidence.put("event_server_tick", removalServerTick);
        evidence.put("patch_bounds", List.of(minX, minY, minZ, maxX, maxY, maxZ));
        evidence.put("reference_height", reference);
        evidence.put("modified_block_count", modified);
        evidence.put("original_block_sha256", originalHash);
        evidence.put("original_goal", point(oldGoal));
        evidence.put("valid_target", point(target));
        evidence.put("invalid_target", List.of(tx, ty + 20, tz));
        evidence.put("original_route", routeSnapshot);
        evidence.put("original_route_sha256", digest(routeSnapshot.toString()));
        evidence.put("event_position", point(p));
        var toes = new ArrayList<List<Double>>();
        for (int i = 0; i < 4; i++) toes.add(point(rig.toe(i)));
        evidence.put("event_toes", toes);
        // Set before the first edit so even a partial mutation is restored by cleanup.
        cleanupNeeded = true;
        for (var entry : saved.entrySet())
            if (!entry.getValue().isAir())
                nav.level.setBlockAndUpdate(entry.getKey(), Blocks.AIR.defaultBlockState());
        removed = true;
        // Fresh native queries: every column in all reachable support rings is now empty.
        for (var pos : saved.keySet()) {
            var state = nav.level.getBlockState(pos);
            require(
                    state.getCollisionShape(nav.level, pos).isEmpty()
                            && state.getFluidState().isEmpty(),
                    "native support removal did not take effect");
        }
        var fresh = new MinecraftMachinesLeaperPredatorMap(nav.level, reference + 11);
        for (double radius : new double[] {0, 3.2, 3.3, 4.4, 5, 5.5, 6.2})
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                require(
                        fresh.surfaces(
                                        p.x + radius * Math.cos(a),
                                        p.z + radius * Math.sin(a),
                                        reference - 5.5,
                                        reference + 5.5)
                                .isEmpty(),
                        "reachable support surface remains");
            }
        preconditions = true;
        boolean accepted = nav.go(tx, ty, tz);
        evidence.put("valid_request_accepted", accepted);
        evidence.put("pending_after_valid", point(nav.pendingGoal));
        evidence.put("reason_after_valid", nav.reason);
        pending =
                accepted
                        && target.equals(nav.pendingGoal)
                        && oldGoal.equals(nav.goal)
                        && oldRoute == nav.route
                        && oldRouteContent.equals(nav.route)
                        && !nav.arrived
                        && nav.reason.equals(
                                "Waiting for a supported stance to plan the selected destination");
        require(pending, "real retarget did not preserve old route and enter pending state");
        deadline = nav.pendingDeadline;
        retryTick = nav.pendingRetryTick;
        evidence.put("pending_created_nav_tick", nav.tick);
        evidence.put("pending_retry_tick", retryTick);
        evidence.put("pending_deadline", deadline);
        var oldPending = new Vector3d(nav.pendingGoal);
        boolean rejected = !nav.go(tx, ty + 20, tz);
        invalidPreserved =
                rejected
                        && oldPending.equals(nav.pendingGoal)
                        && oldGoal.equals(nav.goal)
                        && oldRoute == nav.route
                        && oldRouteContent.equals(nav.route)
                        && nav.pendingDeadline == deadline
                        && nav.pendingRetryTick == retryTick
                        && !nav.arrived;
        evidence.put("invalid_request_rejected", rejected);
        evidence.put("pending_after_invalid", point(nav.pendingGoal));
        require(
                invalidPreserved,
                "invalid request changed existing goal, route, pending request or deadline");
    }

    void restore(int tick, boolean cleanup) {
        if (!cleanupNeeded) return;
        RuntimeException first = null;
        // Try every restoration even if one block update throws.
        for (var entry : saved.entrySet()) {
            try {
                nav.level.setBlockAndUpdate(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
        }
        String restoredHash = blockHash(true);
        restored = true;
        restorationVerified = originalHash.equals(restoredHash);
        cleanupNeeded = !restorationVerified;
        evidence.put("restored_block_sha256", restoredHash);
        evidence.put("restore_test_tick", tick);
        evidence.put("restore_server_tick", nav.level.getGameTime());
        evidence.put("restored_during_cleanup", cleanup);
        boolean interval = tick == 101 && nav.level.getGameTime() == removalServerTick + 1;
        evidence.put("one_native_tick_elapsed", interval);
        if (first != null) throw first;
        require(restorationVerified, "restored world differs from exact original block snapshot");
        if (!cleanup) require(interval, "support transition did not span exactly one native tick");
    }

    void observe() {
        if (!pending || automaticallyResolved) return;
        if (nav.pendingGoal == null) {
            require(
                    restored
                            && restorationVerified
                            && nav.tick >= retryTick
                            && nav.tick <= deadline
                            && target.equals(nav.goal),
                    "pending request ended without automatic successful retry");
            automaticallyResolved = true;
            evidence.put("automatic_resolution_nav_tick", nav.tick);
            evidence.put("resolved_goal", point(nav.goal));
        } else {
            require(
                    target.equals(nav.pendingGoal) && !nav.arrived,
                    "pending destination changed or false arrival");
            require(nav.tick < deadline, "pending request expired without resolution");
        }
    }

    boolean complete() {
        return preconditions
                && removed
                && restored
                && restorationVerified
                && pending
                && invalidPreserved
                && automaticallyResolved
                && Boolean.TRUE.equals(evidence.get("one_native_tick_elapsed"))
                && !Boolean.TRUE.equals(evidence.get("restored_during_cleanup"));
    }

    void arrival(int firstTick, int completionTick, double error) {
        evidence.put("arrival_window_start_tick", firstTick);
        evidence.put("arrival_completion_tick", completionTick);
        evidence.put("arrival_error", error);
    }

    Map<String, Object> report() {
        var out = new LinkedHashMap<>(evidence);
        out.put("interaction_fixture_version", VERSION);
        out.put("fixture_spec_sha256", SPEC_SHA256);
        out.put("fixture_preconditions_passed", preconditions);
        out.put("fixture_failure_reason", failure);
        out.put("support_removed", removed);
        out.put("support_restored", restored);
        out.put("support_restoration_verified", restorationVerified);
        out.put("automatic_pending_resolution", automaticallyResolved);
        return out;
    }
}
