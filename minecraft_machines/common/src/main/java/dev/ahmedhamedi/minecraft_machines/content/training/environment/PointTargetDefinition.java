package dev.ahmedhamedi.minecraft_machines.content.training.environment;

public record PointTargetDefinition(
        double localForwardBlocks,
        double localRightBlocks,
        double successRadiusBlocks
) {
    public static final PointTargetDefinition NONE = new PointTargetDefinition(0.0, 0.0, 0.0);

    public PointTargetDefinition {
        requireFinite("localForwardBlocks", localForwardBlocks);
        requireFinite("localRightBlocks", localRightBlocks);
        if (!Double.isFinite(successRadiusBlocks) || successRadiusBlocks < 0.0) {
            throw new IllegalArgumentException("successRadiusBlocks must be finite and non-negative");
        }
    }

    public boolean enabled() {
        return this.initialDistanceBlocks() > 0.0 || this.successRadiusBlocks > 0.0;
    }

    public double initialDistanceBlocks() {
        return Math.hypot(this.localForwardBlocks, this.localRightBlocks);
    }

    public boolean reached(final double currentDistanceBlocks) {
        return this.enabled()
                && Double.isFinite(currentDistanceBlocks)
                && currentDistanceBlocks <= this.successRadiusBlocks;
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
