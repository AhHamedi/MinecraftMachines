package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public record DuopodRewardConfig(
        double forwardTrackingScale,
        double lateralTrackingScale,
        double yawTrackingScale,
        double uprightScale,
        double commandActionRatePenalty,
        double pointActionRatePenalty,
        double commandAlivePenalty,
        double pointAlivePenalty,
        double successBonus,
        double machineFailurePenalty,
        double launchVelocityPenalty,
        double launchHeightPenalty,
        double allowedUpwardVelocityBlocksPerSecond,
        double allowedHeightAboveSpawnBlocks
) {
    public static final DuopodRewardConfig DEFAULT = new DuopodRewardConfig(
            1.0,
            1.0,
            1.0,
            4.0,
            0.002,
            0.002,
            0.01,
            0.01,
            25.0,
            15.0,
            0.35,
            0.60,
            0.75,
            1.25
    );
}
