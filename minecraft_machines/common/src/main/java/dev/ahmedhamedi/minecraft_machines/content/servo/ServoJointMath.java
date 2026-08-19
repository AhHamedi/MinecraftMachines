package dev.ahmedhamedi.minecraft_machines.content.servo;

public final class ServoJointMath {
    private ServoJointMath() {
    }

    public static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    public static double requireNonNegative(final String name, final double value) {
        requireFinite(name, value);
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
        return value;
    }

    public static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static double moveTowards(final double current, final double target, final double maxChange) {
        if (maxChange <= 0.0) {
            return current;
        }

        final double delta = target - current;
        if (Math.abs(delta) <= maxChange) {
            return target;
        }

        return current + Math.copySign(maxChange, delta);
    }

    public static double normalizeSignedAngle(final double angle) {
        double normalized = Math.IEEEremainder(angle, Math.TAU);
        if (normalized <= -Math.PI) {
            normalized += Math.TAU;
        } else if (normalized > Math.PI) {
            normalized -= Math.TAU;
        }
        return normalized;
    }
}
