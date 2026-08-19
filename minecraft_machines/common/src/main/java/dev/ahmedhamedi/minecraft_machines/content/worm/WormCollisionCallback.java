package dev.ahmedhamedi.minecraft_machines.content.worm;

import dev.ahmedhamedi.minecraft_machines.content.training.environment.TrainingMachineCollisionRegistry;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class WormCollisionCallback implements BlockSubLevelCollisionCallback {
    private static final Vector3dc ZERO_TANGENT = new Vector3d();
    private static final CollisionResult REMOVE_COLLISION = new CollisionResult(ZERO_TANGENT, true);

    private final BlockSubLevelCollisionCallback delegate;

    private WormCollisionCallback(final BlockSubLevelCollisionCallback delegate) {
        this.delegate = delegate;
    }

    public static BlockSubLevelCollisionCallback wrap(final BlockSubLevelCollisionCallback delegate) {
        if (delegate instanceof WormCollisionCallback) {
            return delegate;
        }
        return new WormCollisionCallback(delegate);
    }

    @Override
    public CollisionResult sable$onCollision(final BlockPos blockPos,
                                             final BlockPos otherBlockPos,
                                             final Vector3d tangentMotion,
                                             final double normalSpeed) {
        if (shouldSuppressWormCollision(blockPos, otherBlockPos)) {
            return REMOVE_COLLISION;
        }

        if (this.delegate != null) {
            return this.delegate.sable$onCollision(blockPos, otherBlockPos, tangentMotion, normalSpeed);
        }
        return CollisionResult.NONE;
    }

    private static boolean shouldSuppressWormCollision(final BlockPos blockPos, final BlockPos otherBlockPos) {
        if (blockPos == null || otherBlockPos == null) {
            return false;
        }

        final SubLevelPhysicsSystem physicsSystem;
        try {
            physicsSystem = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        } catch (final IllegalStateException ignored) {
            return false;
        }

        if (TrainingMachineCollisionRegistry.shouldSuppressCollision(physicsSystem.getLevel(), blockPos, otherBlockPos)) {
            return true;
        }

        final ServerSubLevel first = WormCollisionTags.findWormSubLevelContaining(physicsSystem.getLevel(), blockPos);
        if (first == null) {
            return false;
        }

        final ServerSubLevel second = WormCollisionTags.findWormSubLevelContaining(physicsSystem.getLevel(), otherBlockPos);
        return second != null;
    }
}
