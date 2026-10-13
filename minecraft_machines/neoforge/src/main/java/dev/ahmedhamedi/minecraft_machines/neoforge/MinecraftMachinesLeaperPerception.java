package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/** Server-side, evidence-only senses. Neither a nearby player nor a remembered UUID is sight. */
final class MinecraftMachinesLeaperPerception {
    private static final double MAX_SIGHT = 38;
    private static final int MAX_CANDIDATES = 8, MAX_SOUND_NODES = 256;
    private static final Direction[] SOUND_DIRECTIONS = Direction.values();
    private final Map<UUID, Motion> motion = new HashMap<>();

    LeaperBehaviour.Evidence sample(
            ServerLevel level, MinecraftMachinesLeaper rig, UUID preferredTarget, int tick) {
        return sample(level, rig, preferredTarget, tick, null);
    }

    /** The explicitly selected /hunt player may be creative; passive acquisition never is. */
    LeaperBehaviour.Evidence sample(
            ServerLevel level,
            MinecraftMachinesLeaper rig,
            UUID preferredTarget,
            int tick,
            UUID allowedCreative) {
        var orientation = rig.coreOrientation();
        // Centre of the front optical disk, just outside its -1.0625 model surface.
        var optical = orientation.transform(new Vector3d(0, 0, -1.08 * rig.scale)).add(rig.corePivot());
        var facing = orientation.transform(new Vector3d(0, 0, -1));
        var eye = new Vec3(optical.x, optical.y, optical.z);
        var forward = new Vec3(facing.x, facing.y, facing.z);
        var candidates = new ArrayList<ServerPlayer>();
        var order =
                Comparator.comparingInt((ServerPlayer p) -> p.getUUID().equals(preferredTarget) ? 0 : 1)
                        .thenComparingDouble(p -> p.position().distanceToSqr(eye));
        for (var player : level.players()) {
            if (!eligible(player, allowedCreative)
                    || player.position().distanceToSqr(eye) > (MAX_SIGHT + 2) * (MAX_SIGHT + 2))
                continue;
            candidates.add(player);
            candidates.sort(order);
            if (candidates.size() > MAX_CANDIDATES) candidates.removeLast();
        }
        var active = new HashSet<UUID>();
        LeaperBehaviour.Evidence best = null;
        for (var player : candidates) {
            active.add(player.getUUID());
            var prior = motion.get(player.getUUID());
            boolean noise =
                    prior != null
                            && tick - prior.lastNoise >= (player.isSprinting() ? 8 : 12)
                            && makesMovementNoise(
                                    prior.position,
                                    player.position(),
                                    tick - prior.tick,
                                    player.isShiftKeyDown(),
                                    player.onGround());
            motion.put(
                    player.getUUID(),
                    new Motion(player.position(), tick, noise ? tick : prior == null ? tick : prior.lastNoise));
            var evidence = sense(level, rig, eye, forward, player, noise);
            if (evidence == null) continue;
            if (best == null || evidence.visible() && !best.visible()
                    || evidence.visible() == best.visible() && (!best.player().equals(preferredTarget)
                        && (evidence.player().equals(preferredTarget) || evidence.strength() > best.strength()))) {
                best = evidence;
            }
        }
        motion.keySet().retainAll(active);
        return best;
    }

    static boolean eligible(Player player, UUID allowedCreative) {
        return player.isAlive()
                && !player.isSpectator()
                && (!player.isCreative() || player.getUUID().equals(allowedCreative));
    }

    static LeaperBehaviour.Evidence sense(
            ServerLevel level,
            MinecraftMachinesLeaper rig,
            Vec3 eye,
            Vec3 forward,
            Player player,
            boolean noise) {
        var targetEye = player.getEyePosition();
        var lightPos = BlockPos.containing(targetEye);
        if (!level.hasChunkAt(lightPos)) return null;
        int light = level.getMaxLocalRawBrightness(lightPos);
        double possible =
                visionStrength(eye, forward, targetEye, light, player.isShiftKeyDown(), player.isInvisible(), 2);
        if (possible > 0) {
            int seen = visible(level, eye, targetEye, rig) ? 1 : 0;
            var torso = player.position().add(0, player.getBbHeight() * .52, 0);
            if (visible(level, eye, torso, rig)) seen++;
            if (seen > 0)
                return new LeaperBehaviour.Evidence(
                        player.getUUID(), point(player.position()), possible * (seen == 2 ? 1 : .65), true);
        }
        if (!noise) return null;
        var source = player.position().add(0, .25, 0);
        Vec3 listener = eye;
        if (rig != null) {
            // Ground-level sound reaches the legs. Measuring from the high optical disk
            // incorrectly rejects normal footsteps as being on a different floor.
            double[] feet = new double[4];
            for (int i = 0; i < 4; i++) feet[i] = rig.toe(i).y;
            java.util.Arrays.sort(feet);
            listener = new Vec3(eye.x, (feet[1] + feet[2]) * .5 + .75, eye.z);
        }
        double range = player.isSprinting() ? 16 : 8;
        if (Math.abs(source.y - listener.y) > 3 || listener.distanceToSqr(source) > range * range) return null;
        int distance = soundPathDistance(level, listener, source, (int) range + 2, rig);
        if (distance < 0) return null;
        // A footstep locates a small area, not an invisible player's exact moving coordinates.
        var clue =
                new LeaperBehaviour.Point(
                        Math.floor(source.x / 4) * 4 + 2,
                        Math.floor(player.getY() / 2) * 2,
                        Math.floor(source.z / 4) * 4 + 2);
        double strength = (player.isSprinting() ? .32 : .22) * (1 - .4 * distance / (range + 2));
        return new LeaperBehaviour.Evidence(player.getUUID(), clue, strength, false);
    }

    static double visionStrength(
            Vec3 eye, Vec3 forward, Vec3 target, int light, boolean crouching, boolean invisible, int visibleSamples) {
        if (invisible || visibleSamples <= 0) return 0;
        var offset = target.subtract(eye);
        double distance = offset.length();
        double illumination = Math.clamp(light, 0, 15) / 15.0;
        double range = (14 + 24 * illumination) * (crouching ? .65 : 1);
        if (distance > range) return 0;
        double facing = distance < 1e-6 ? 1 : forward.dot(offset) / distance;
        // Close peripheral awareness still requires unobstructed sight. No through-wall bubble.
        if (distance > 3 && facing < .35) return 0;
        if (distance <= 5 && (facing >= .35 || distance <= 3)) return 1;
        double distanceFactor = 1 - .82 * distance / range;
        double focus = .45 + .55 * Math.clamp((facing - .35) / .65, 0, 1);
        return Math.max(.06, distanceFactor * focus * (.6 + .4 * illumination) * (crouching ? .7 : 1));
    }

    static boolean makesMovementNoise(Vec3 previous, Vec3 current, int elapsed, boolean sneaking, boolean grounded) {
        if (sneaking || !grounded || elapsed <= 0 || elapsed > 20) return false;
        double horizontal = Math.hypot(current.x - previous.x, current.z - previous.z) / elapsed;
        // Network teleports and falling motion are not footsteps.
        return horizontal >= .025 && horizontal < 1.5 && Math.abs(current.y - previous.y) / elapsed < .75;
    }

    static boolean visible(ServerLevel level, Vec3 from, Vec3 to) {
        return visible(level, from, to, null);
    }

    private static boolean visible(ServerLevel level, Vec3 from, Vec3 to, MinecraftMachinesLeaper rig) {
        if (from.distanceToSqr(to) > (MAX_SIGHT + 2) * (MAX_SIGHT + 2)) return false;
        return rayClear(level, from, to, ClipContext.Block.VISUAL, rig);
    }

    private static boolean rayClear(
            ServerLevel level, Vec3 from, Vec3 to, ClipContext.Block shape, MinecraftMachinesLeaper rig) {
        // Level.clip may obtain chunks. Establish that the entire ray is loaded first.
        boolean loaded =
                BlockGetter.traverseBlocks(
                        from, to, level,
                        (world, pos) -> world.hasChunkAt(pos) ? null : Boolean.FALSE,
                        world -> Boolean.TRUE);
        if (!loaded) return false;
        var clip = new ClipContext(from, to, shape, ClipContext.Fluid.NONE, CollisionContext.empty());
        if (rig != null) ((ClipContextExtension) clip).sable$setSubLevelIgnoring(rig::ownsBody);
        return level.clip(clip).getType() == HitResult.Type.MISS;
    }

    /** Bounded acoustic connectivity; stone, closed doors, slabs and water seal the air cells. */
    static int soundPathDistance(ServerLevel level, Vec3 from, Vec3 to, int maxSteps) {
        return soundPathDistance(level, from, to, maxSteps, null);
    }

    private static int soundPathDistance(
            ServerLevel level, Vec3 from, Vec3 to, int maxSteps, MinecraftMachinesLeaper rig) {
        if (maxSteps <= 0 || maxSteps > 18 || Math.abs(from.y - to.y) > 3) return -1;
        var start = BlockPos.containing(from);
        var goal = BlockPos.containing(to);
        if (!airCell(level, start) || !airCell(level, goal)) return -1;
        // Open air carries sound directly. A six-axis grid otherwise makes diagonal
        // footsteps cost up to sqrt(2) more than equally distant cardinal footsteps.
        // Keep the same dry air-cell and native moving-collider checks as detours.
        if (from.distanceTo(to) <= maxSteps
                && MinecraftMachinesLeaperAcoustics.dryLine(level, from, to)
                && rayClear(level, from, to, ClipContext.Block.COLLIDER, rig))
            return (int) Math.ceil(from.distanceTo(to));
        if (start.distManhattan(goal) > maxSteps) return -1;
        var open = new PriorityQueue<SoundNode>(Comparator.comparingInt(SoundNode::estimate).thenComparingInt(SoundNode::remaining));
        var cost = new HashMap<BlockPos, Integer>();
        var previous = new HashMap<BlockPos, BlockPos>();
        open.add(new SoundNode(start, 0, start.distManhattan(goal)));
        cost.put(start, 0);
        for (int visited = 0; !open.isEmpty() && visited < MAX_SOUND_NODES; visited++) {
            var node = open.remove();
            if (node.cost != cost.getOrDefault(node.position, Integer.MAX_VALUE)) continue;
            if (node.position.equals(goal))
                return soundRouteClear(level, from, to, start, goal, previous, rig) ? node.cost : -1;
            for (var direction : SOUND_DIRECTIONS) {
                var next = node.position.relative(direction);
                int nextCost = node.cost + 1, remaining = next.distManhattan(goal);
                if (nextCost + remaining > maxSteps
                        || Math.abs(next.getY() - start.getY()) > 3
                        || nextCost >= cost.getOrDefault(next, Integer.MAX_VALUE)
                        || !airCell(level, next)) continue;
                cost.put(next, nextCost);
                previous.put(next, node.position);
                open.add(new SoundNode(next, nextCost, remaining));
            }
        }
        return -1;
    }

    private static boolean soundRouteClear(
            ServerLevel level, Vec3 from, Vec3 to, BlockPos start, BlockPos goal,
            Map<BlockPos, BlockPos> previous, MinecraftMachinesLeaper rig) {
        // World-cell search alone cannot see a wall carried by a Sable sublevel. Check its
        // chosen air route against native colliders too, conservatively rejecting a blocked
        // route rather than spending an unbounded second search around moving geometry.
        var points = new ArrayList<Vec3>();
        points.add(to);
        for (var at = goal; ; at = previous.get(at)) {
            points.add(Vec3.atCenterOf(at));
            if (at.equals(start)) break;
        }
        points.add(from);
        var segmentStart = points.getFirst();
        var last = segmentStart;
        var direction = Vec3.ZERO;
        for (int i = 1; i < points.size(); i++) {
            var next = points.get(i);
            var delta = next.subtract(last);
            if (delta.lengthSqr() < 1e-10) continue;
            // Collapse straight corridor cells into a single ray. At most 20 short rays
            // remain for an 18-step zigzag, independent of the acoustic search node count.
            if (direction.lengthSqr() > 0
                    && (direction.cross(delta).lengthSqr() > 1e-10 || direction.dot(delta) <= 0)) {
                if (!rayClear(level, segmentStart, last, ClipContext.Block.COLLIDER, rig)) return false;
                segmentStart = last;
            }
            direction = delta;
            last = next;
        }
        return rayClear(level, segmentStart, last, ClipContext.Block.COLLIDER, rig);
    }

    private static boolean airCell(ServerLevel level, BlockPos pos) {
        return MinecraftMachinesLeaperAcoustics.airCell(level, pos);
    }

    private static LeaperBehaviour.Point point(Vec3 position) {
        return new LeaperBehaviour.Point(position.x, position.y, position.z);
    }

    private record Motion(Vec3 position, int tick, int lastNoise) {}

    private record SoundNode(BlockPos position, int cost, int remaining) {
        int estimate() { return cost + remaining; }
    }
}
