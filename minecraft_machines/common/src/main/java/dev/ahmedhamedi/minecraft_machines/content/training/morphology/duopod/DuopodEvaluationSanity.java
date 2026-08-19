package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public final class DuopodEvaluationSanity {
    private DuopodEvaluationSanity() {
    }

    public static double requireUnitInterval(final String name, final double value) {
        requireFinite(name, value);
        if (value < 0.0 || value > 1.0) {
            throw new IllegalStateException(name + " must be within [0, 1], got " + value);
        }
        return value;
    }

    public static double requireMatchingInitialDistance(
            final double requestedDistance,
            final double measuredDistance,
            final double tolerance
    ) {
        requireNonNegativeFinite("requested initial distance", requestedDistance);
        requireNonNegativeFinite("measured initial distance", measuredDistance);
        requireTolerance(tolerance);
        if (Math.abs(requestedDistance - measuredDistance) > tolerance) {
            throw new IllegalStateException(
                    "measured initial target distance " + measuredDistance
                            + " differs from requested distance " + requestedDistance
                            + " by more than " + tolerance);
        }
        return measuredDistance;
    }

    public static double requireReachableProgress(
            final double initialDistance,
            final double finalDistance,
            final double pathLength,
            final double tolerance
    ) {
        requireNonNegativeFinite("initial target distance", initialDistance);
        requireNonNegativeFinite("final target distance", finalDistance);
        requireNonNegativeFinite("travelled path length", pathLength);
        requireTolerance(tolerance);
        final double progress = initialDistance - finalDistance;
        if (progress > pathLength + tolerance) {
            throw new IllegalStateException(
                    "target progress " + progress
                            + " exceeds travelled path length " + pathLength
                            + " by more than " + tolerance);
        }
        return progress;
    }

    private static void requireNonNegativeFinite(final String name, final double value) {
        requireFinite(name, value);
        if (value < 0.0) {
            throw new IllegalStateException(name + " must be non-negative, got " + value);
        }
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException(name + " must be finite, got " + value);
        }
    }

    private static void requireTolerance(final double tolerance) {
        if (!Double.isFinite(tolerance) || tolerance < 0.0) {
            throw new IllegalArgumentException("tolerance must be finite and non-negative");
        }
    }
}
