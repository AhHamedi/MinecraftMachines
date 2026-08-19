package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class DuopodKinematics {
    private static final Vector3dc WORLD_DOWN = new Vector3d(0.0, -1.0, 0.0);
    private static final Vector3dc WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    private DuopodKinematics() {
    }

    public static Direction modelRightDirection(final Direction modelForwardDirection) {
        if (modelForwardDirection.getAxis().isVertical()) {
            throw new IllegalArgumentException("Duopod forward direction must be horizontal");
        }
        return modelForwardDirection.getClockWise();
    }

    public static Axes worldAxes(final Direction modelForwardDirection, final Quaterniondc baseOrientation) {
        final Vector3d forward = directionVector(modelForwardDirection);
        final Vector3d right = directionVector(modelRightDirection(modelForwardDirection));
        final Vector3d up = new Vector3d(0.0, 1.0, 0.0);
        baseOrientation.transform(forward);
        baseOrientation.transform(right);
        baseOrientation.transform(up);
        return new Axes(forward.normalize(), right.normalize(), up.normalize());
    }

    public static LocalOffset horizontalLocalOffset(
            final Vec3 basePosition,
            final Quaterniondc baseOrientation,
            final Direction modelForwardDirection,
            final Vec3 targetPosition
    ) {
        final Axes axes = worldAxes(modelForwardDirection, baseOrientation);
        final PlanarLocalOffset offset = DuopodPlanarMath.horizontalLocalOffset(
                targetPosition.x - basePosition.x,
                targetPosition.z - basePosition.z,
                axes.forward().x(),
                axes.forward().z(),
                axes.right().x(),
                axes.right().z());
        return new LocalOffset(offset.forward(), offset.right());
    }

    /**
     * Returns a unit-length, orthogonal frame on the world X/Z plane. Pitch and roll therefore
     * cannot shorten a requested horizontal offset or skew its bearing. When the transformed
     * forward axis is nearly vertical, the transformed right axis supplies the equivalent heading.
     */
    public static PlanarBodyAxes horizontalAxes(
            final Direction modelForwardDirection,
            final Quaterniondc baseOrientation
    ) {
        final Axes axes = worldAxes(modelForwardDirection, baseOrientation);
        final Vector3dc forward = axes.forward();
        final Vector3dc right = axes.right();
        return DuopodPlanarMath.normalizedBodyAxes(
                forward.x(),
                forward.z(),
                right.x(),
                right.z());
    }

    public static Vec3 horizontalTargetPosition(
            final Vec3 basePosition,
            final Quaterniondc baseOrientation,
            final Direction modelForwardDirection,
            final double localForward,
            final double localRight
    ) {
        final Axes axes = worldAxes(modelForwardDirection, baseOrientation);
        final PlanarWorldOffset offset = DuopodPlanarMath.horizontalWorldOffset(
                localForward,
                localRight,
                axes.forward().x(),
                axes.forward().z(),
                axes.right().x(),
                axes.right().z());
        return new Vec3(
                basePosition.x + offset.x(),
                basePosition.y,
                basePosition.z + offset.z());
    }

    public static LocalOffset horizontalLocalOffset(
            final double worldOffsetX,
            final double worldOffsetZ,
            final double worldForwardX,
            final double worldForwardZ,
            final double worldRightX,
            final double worldRightZ
    ) {
        final PlanarLocalOffset offset = DuopodPlanarMath.horizontalLocalOffset(
                worldOffsetX,
                worldOffsetZ,
                worldForwardX,
                worldForwardZ,
                worldRightX,
                worldRightZ);
        return new LocalOffset(offset.forward(), offset.right());
    }

    public static LocalBodyMotion localMotion(
            final Vector3dc worldLinearVelocity,
            final Vector3dc worldAngularVelocity,
            final Quaterniondc baseOrientation,
            final Direction modelForwardDirection
    ) {
        final PlanarBodyAxes axes = horizontalAxes(modelForwardDirection, baseOrientation);
        return new LocalBodyMotion(
                worldLinearVelocity.x() * axes.forwardX() + worldLinearVelocity.z() * axes.forwardZ(),
                worldLinearVelocity.x() * axes.rightX() + worldLinearVelocity.z() * axes.rightZ(),
                worldLinearVelocity.y(),
                worldAngularVelocity.x() * axes.forwardX() + worldAngularVelocity.z() * axes.forwardZ(),
                worldAngularVelocity.x() * axes.rightX() + worldAngularVelocity.z() * axes.rightZ(),
                worldAngularVelocity.y()
        );
    }

    public static ProjectedGravity projectedGravity(final Quaterniondc baseOrientation, final Direction modelForwardDirection) {
        final Axes axes = worldAxes(modelForwardDirection, baseOrientation);
        return new ProjectedGravity(WORLD_DOWN.dot(axes.forward()), WORLD_DOWN.dot(axes.right()));
    }

    public static double bodyUpDotWorldUp(final Quaterniondc baseOrientation, final Direction modelForwardDirection) {
        return worldAxes(modelForwardDirection, baseOrientation).up().dot(WORLD_UP);
    }

    public static double normalizeSignedAngle(final double angleRad) {
        return DuopodPlanarMath.normalizeSignedAngle(angleRad);
    }

    private static Vector3d directionVector(final Direction direction) {
        final Vec3i normal = direction.getNormal();
        return new Vector3d(normal.getX(), normal.getY(), normal.getZ());
    }

    public record Axes(Vector3dc forward, Vector3dc right, Vector3dc up) {
    }

    public record LocalOffset(double forward, double right) {
        public double horizontalDistance() {
            return Math.hypot(this.forward, this.right);
        }
    }

    public record LocalBodyMotion(
            double forwardVelocity,
            double lateralVelocity,
            double verticalVelocity,
            double rollRate,
            double pitchRate,
            double yawRate
    ) {
    }

    public record ProjectedGravity(double forward, double right) {
    }
}
