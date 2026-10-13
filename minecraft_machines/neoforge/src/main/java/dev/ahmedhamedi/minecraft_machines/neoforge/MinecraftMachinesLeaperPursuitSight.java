package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;
import java.util.UUID;

/** Retain visual attention on confirmed prey; never acquire an unknown distant player. */
final class MinecraftMachinesLeaperPursuitSight {
    static LeaperBehaviour.Evidence sample(ServerLevel level, MinecraftMachinesLeaper rig,
            UUID target, UUID allowedCreative) {
        if (target == null) return null;
        var player = level.getPlayerByUUID(target);
        if (player == null || !MinecraftMachinesLeaperPerception.eligible(player, allowedCreative)
                || player.isInvisible()) return null;
        var orientation = rig.coreOrientation();
        var optical = orientation.transform(new Vector3d(0, 0, -1.08 * rig.scale)).add(rig.corePivot());
        var front = orientation.transform(new Vector3d(0, 0, -1));
        var eye = new Vec3(optical.x, optical.y, optical.z);
        var offset = player.getEyePosition().subtract(eye);
        if (offset.lengthSqr() > 192 * 192) return null;
        // Track a launched target above the chassis without the narrow vertical
        // acquisition cone cutting its flight arc in half. Turning fully away still loses sight.
        double horizontal = Math.hypot(offset.x, offset.z), frontLength = Math.hypot(front.x, front.z);
        if (horizontal > 8 && frontLength > .1
                && (front.x * offset.x + front.z * offset.z) / (frontLength * horizontal) < -.5) return null;
        boolean seen = clear(level, rig, eye, player.getEyePosition())
                || clear(level, rig, eye, player.position().add(0, player.getBbHeight() * .52, 0));
        return seen ? new LeaperBehaviour.Evidence(target,
                new LeaperBehaviour.Point(player.getX(), player.getY(), player.getZ()), .8, true) : null;
    }

    private static boolean clear(ServerLevel level, MinecraftMachinesLeaper rig, Vec3 from, Vec3 to) {
        boolean loaded = BlockGetter.traverseBlocks(from, to, level,
                (world, pos) -> world.hasChunkAt(pos) ? null : Boolean.FALSE, world -> Boolean.TRUE);
        if (!loaded) return false;
        var clip = new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty());
        ((ClipContextExtension) clip).sable$setSubLevelIgnoring(rig::ownsBody);
        return level.clip(clip).getType() == HitResult.Type.MISS;
    }
}
