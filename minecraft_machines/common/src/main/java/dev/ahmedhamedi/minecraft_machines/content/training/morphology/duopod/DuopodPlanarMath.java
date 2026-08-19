package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public final class DuopodPlanarMath {
    private static final double MINIMUM_PLANAR_AXIS_LENGTH = 1.0e-8;

    private DuopodPlanarMath() {
    }

    public static PlanarBodyAxes normalizedBodyAxes(
            final double worldForwardX,
            final double worldForwardZ,
            final double worldRightX,
            final double worldRightZ
    ) {
        final double forwardLength = Math.hypot(worldForwardX, worldForwardZ);
        final double forwardX;
        final double forwardZ;
        if (Double.isFinite(forwardLength) && forwardLength > MINIMUM_PLANAR_AXIS_LENGTH) {
            forwardX = worldForwardX / forwardLength;
            forwardZ = worldForwardZ / forwardLength;
        } else {
            final double rightLength = Math.hypot(worldRightX, worldRightZ);
            if (!Double.isFinite(rightLength) || rightLength <= MINIMUM_PLANAR_AXIS_LENGTH) {
                throw new IllegalArgumentException("Duopod orientation has no usable horizontal heading");
            }
            final double rightX = worldRightX / rightLength;
            final double rightZ = worldRightZ / rightLength;
            forwardX = rightZ;
            forwardZ = -rightX;
        }
        return new PlanarBodyAxes(forwardX, forwardZ, -forwardZ, forwardX);
    }

    public static PlanarWorldOffset horizontalWorldOffset(
            final double localForward,
            final double localRight,
            final double worldForwardX,
            final double worldForwardZ,
            final double worldRightX,
            final double worldRightZ
    ) {
        final PlanarBodyAxes axes = normalizedBodyAxes(
                worldForwardX,
                worldForwardZ,
                worldRightX,
                worldRightZ);
        return new PlanarWorldOffset(
                axes.forwardX() * localForward + axes.rightX() * localRight,
                axes.forwardZ() * localForward + axes.rightZ() * localRight);
    }

    public static PlanarLocalOffset horizontalLocalOffset(
            final double worldOffsetX,
            final double worldOffsetZ,
            final double worldForwardX,
            final double worldForwardZ,
            final double worldRightX,
            final double worldRightZ
    ) {
        final PlanarBodyAxes axes = normalizedBodyAxes(
                worldForwardX,
                worldForwardZ,
                worldRightX,
                worldRightZ);
        return new PlanarLocalOffset(
                worldOffsetX * axes.forwardX() + worldOffsetZ * axes.forwardZ(),
                worldOffsetX * axes.rightX() + worldOffsetZ * axes.rightZ());
    }

    public static double normalizeSignedAngle(final double angleRad) {
        double value = angleRad % (Math.PI * 2.0);
        if (value <= -Math.PI) {
            value += Math.PI * 2.0;
        } else if (value > Math.PI) {
            value -= Math.PI * 2.0;
        }
        return value;
    }
}
