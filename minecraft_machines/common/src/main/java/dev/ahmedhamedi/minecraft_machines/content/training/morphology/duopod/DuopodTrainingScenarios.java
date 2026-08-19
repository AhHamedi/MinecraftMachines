package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode;
import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.CurriculumStage;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.PointTargetDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.StandingDisturbance;
import dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile;

import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public final class DuopodTrainingScenarios {
    public static final String MINECRAFT_TERRAIN_COMMANDS_STAGE = "minecraft_terrain_commands";
    public static final String MINECRAFT_TERRAIN_POINT_GOALS_STAGE = "minecraft_terrain_point_goals";
    public static final String FLAT_COMMANDS_STAGE = "flat_commands";
    public static final String LOW_BUMPS_COMMANDS_STAGE = "low_bumps_commands";
    public static final String FLAT_POINT_GOALS_STAGE = "flat_point_goals";
    public static final String WALK_FORWARD_STAGE = "walk_forward";
    public static final String BALANCE_STAND_STAGE = "balance_stand";
    public static final String BALANCE_CENTER_OF_MASS_STAGE = "balance_center_of_mass";
    public static final String MANUAL_COMMANDS_STAGE = "manual_commands";
    public static final String MANUAL_POINT_GOALS_STAGE = "manual_point_goals";
    public static final int FLAT_COMMAND_SCENARIO_COUNT = 5;
    public static final int WALK_FORWARD_SCENARIO_COUNT = 3;
    public static final double DEFAULT_GAIT_FREQUENCY_HZ = 0.35;
    private static final double[] POINT_GOAL_BEARINGS_DEGREES = {-90.0, -60.0, -30.0, 0.0, 30.0, 60.0, 90.0};
    public static final double DEFAULT_POINT_GOAL_NEAR_DISTANCE_BLOCKS = 4.0;
    public static final double DEFAULT_POINT_GOAL_FAR_DISTANCE_BLOCKS = 8.0;
    public static final int POINT_GOAL_SCENARIO_COUNT = POINT_GOAL_BEARINGS_DEGREES.length * 2;
    public static final int BALANCE_STAND_SCENARIO_COUNT = 8;
    public static final int BALANCE_DISTURBANCE_STAGE_COUNT = DuopodStandingBalanceConfig.DEFAULT.disturbanceStages().size();

    private DuopodTrainingScenarios() {
    }

    public static EpisodeDefinition flatCommandEpisode(
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario
    ) {
        return commandEpisode(MINECRAFT_TERRAIN_COMMANDS_STAGE, episodeId, seed, stageIndex, maximumControlSteps, scenario);
    }

    public static EpisodeDefinition commandEpisode(
            final String curriculumStage,
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario
    ) {
        final String stage = normalizeCurriculumStage(curriculumStage);
        if (!isCommandCurriculumStage(stage)) {
            throw new IllegalArgumentException("unsupported Duopod command curriculum stage " + curriculumStage);
        }
        return new EpisodeDefinition(
                episodeId,
                seed,
                EnvironmentTaskMode.COMMAND_TRACKING,
                new CurriculumStage(stage, stageIndex, commandScenarioDescription(stage, scenario)),
                commandForStage(stage, scenario),
                maximumControlSteps,
                0.0,
                DEFAULT_GAIT_FREQUENCY_HZ,
                terrainProfileForStage(stage));
    }

    public static EpisodeDefinition flatPointGoalEpisode(
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario
    ) {
        return flatPointGoalEpisode(
                episodeId,
                seed,
                stageIndex,
                maximumControlSteps,
                scenario,
                DEFAULT_POINT_GOAL_NEAR_DISTANCE_BLOCKS,
                DEFAULT_POINT_GOAL_FAR_DISTANCE_BLOCKS);
    }

    public static EpisodeDefinition flatPointGoalEpisode(
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario,
            final double nearDistanceBlocks,
            final double farDistanceBlocks
    ) {
        return pointGoalEpisode(
                MINECRAFT_TERRAIN_POINT_GOALS_STAGE,
                episodeId,
                seed,
                stageIndex,
                maximumControlSteps,
                pointGoalTarget(scenario, nearDistanceBlocks, farDistanceBlocks),
                pointGoalScenarioDescription(scenario, nearDistanceBlocks, farDistanceBlocks));
    }

    public static EpisodeDefinition manualPointGoalEpisode(
            final long episodeId,
            final long seed,
            final int maximumControlSteps,
            final PointTargetDefinition target
    ) {
        return pointGoalEpisode(
                MANUAL_POINT_GOALS_STAGE,
                episodeId,
                seed,
                0,
                maximumControlSteps,
                target,
                "manual bridge point goal");
    }

    public static EpisodeDefinition episodeForStage(
            final String curriculumStage,
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario
    ) {
        return episodeForStage(
                curriculumStage,
                episodeId,
                seed,
                stageIndex,
                maximumControlSteps,
                scenario,
                DEFAULT_POINT_GOAL_NEAR_DISTANCE_BLOCKS,
                DEFAULT_POINT_GOAL_FAR_DISTANCE_BLOCKS);
    }

    public static EpisodeDefinition episodeForStage(
            final String curriculumStage,
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario,
            final double pointGoalNearDistanceBlocks,
            final double pointGoalFarDistanceBlocks
    ) {
        final String stage = normalizeCurriculumStage(curriculumStage);
        if (isCommandCurriculumStage(stage)) {
            return commandEpisode(stage, episodeId, seed, stageIndex, maximumControlSteps, scenario);
        }
        if (MINECRAFT_TERRAIN_POINT_GOALS_STAGE.equals(stage)) {
            return pointGoalEpisode(
                    stage,
                    episodeId,
                    seed,
                    stageIndex,
                    maximumControlSteps,
                    pointGoalTarget(scenario, pointGoalNearDistanceBlocks, pointGoalFarDistanceBlocks),
                    pointGoalScenarioDescription(scenario, pointGoalNearDistanceBlocks, pointGoalFarDistanceBlocks));
        }
        if (isBalanceCurriculumStage(stage)) {
            return balanceStandEpisode(stage, episodeId, seed, stageIndex, maximumControlSteps, scenario);
        }
        throw new IllegalArgumentException("unsupported Duopod curriculum stage " + curriculumStage);
    }

    public static boolean isCommandCurriculumStage(final String curriculumStage) {
        final String stage = normalizeCurriculumStage(curriculumStage);
        return MINECRAFT_TERRAIN_COMMANDS_STAGE.equals(stage) || WALK_FORWARD_STAGE.equals(stage);
    }

    public static boolean isPointGoalCurriculumStage(final String curriculumStage) {
        return MINECRAFT_TERRAIN_POINT_GOALS_STAGE.equals(normalizeCurriculumStage(curriculumStage));
    }

    public static boolean isBalanceCurriculumStage(final String curriculumStage) {
        final String stage = normalizeCurriculumStage(curriculumStage);
        return BALANCE_STAND_STAGE.equals(stage) || BALANCE_CENTER_OF_MASS_STAGE.equals(stage);
    }

    public static boolean isTrainingCurriculumStage(final String curriculumStage) {
        return isCommandCurriculumStage(curriculumStage)
                || isPointGoalCurriculumStage(curriculumStage)
                || isBalanceCurriculumStage(curriculumStage);
    }

    public static boolean isSupportedBridgeCurriculumStage(final String curriculumStage) {
        final String stage = normalizeCurriculumStage(curriculumStage);
        return isTrainingCurriculumStage(stage) || MANUAL_COMMANDS_STAGE.equals(stage);
    }

    public static TerrainProfile terrainProfileForStage(final String curriculumStage) {
        final String stage = normalizeCurriculumStage(curriculumStage);
        if (!isTrainingCurriculumStage(stage)
                && !MANUAL_COMMANDS_STAGE.equals(stage)
                && !MANUAL_POINT_GOALS_STAGE.equals(stage)) {
            throw new IllegalArgumentException("unsupported Duopod curriculum stage " + curriculumStage);
        }
        return TerrainProfile.none();
    }

    public static String normalizeCurriculumStage(final String curriculumStage) {
        if (curriculumStage == null || curriculumStage.isBlank()) {
            return "";
        }
        return switch (curriculumStage) {
            case FLAT_COMMANDS_STAGE, LOW_BUMPS_COMMANDS_STAGE -> MINECRAFT_TERRAIN_COMMANDS_STAGE;
            case FLAT_POINT_GOALS_STAGE -> MINECRAFT_TERRAIN_POINT_GOALS_STAGE;
            case "balance_com", "balance_centerofmass", "balance_centreofmass", "balance_centre_of_mass" -> BALANCE_CENTER_OF_MASS_STAGE;
            default -> curriculumStage;
        };
    }

    public static String defaultTrainingCurriculumStage() {
        return MINECRAFT_TERRAIN_COMMANDS_STAGE;
    }

    public static boolean isLegacyCompatibilityStage(final String curriculumStage) {
        return FLAT_COMMANDS_STAGE.equals(curriculumStage)
                || LOW_BUMPS_COMMANDS_STAGE.equals(curriculumStage)
                || FLAT_POINT_GOALS_STAGE.equals(curriculumStage);
    }

    public static LocomotionCommand flatCommand(final int scenario) {
        return switch (Math.floorMod(scenario, FLAT_COMMAND_SCENARIO_COUNT)) {
            case 1 -> new LocomotionCommand(0.9, 0.0, 0.75);
            case 2 -> new LocomotionCommand(0.9, 0.0, -0.75);
            case 3 -> new LocomotionCommand(0.25, 0.0, 1.10);
            case 4 -> new LocomotionCommand(0.25, 0.0, -1.10);
            default -> new LocomotionCommand(1.20, 0.0, 0.0);
        };
    }

    public static LocomotionCommand walkForwardCommand(final int scenario) {
        return switch (Math.floorMod(scenario, WALK_FORWARD_SCENARIO_COUNT)) {
            case 1 -> new LocomotionCommand(0.80, 0.0, 0.0);
            case 2 -> new LocomotionCommand(1.10, 0.0, 0.0);
            default -> new LocomotionCommand(0.50, 0.0, 0.0);
        };
    }

    private static LocomotionCommand commandForStage(final String curriculumStage, final int scenario) {
        return WALK_FORWARD_STAGE.equals(curriculumStage)
                ? walkForwardCommand(scenario)
                : flatCommand(scenario);
    }

    public static String flatScenarioDescription(final int scenario) {
        return switch (Math.floorMod(scenario, FLAT_COMMAND_SCENARIO_COUNT)) {
            case 1 -> "forward while turning right";
            case 2 -> "forward while turning left";
            case 3 -> "mostly rotate right";
            case 4 -> "mostly rotate left";
            default -> "straight forward";
        };
    }

    public static String commandScenarioDescription(final String curriculumStage, final int scenario) {
        if (WALK_FORWARD_STAGE.equals(normalizeCurriculumStage(curriculumStage))) {
            final LocomotionCommand command = walkForwardCommand(scenario);
            return String.format(
                    Locale.ROOT,
                    "walk-forward sanity %.2f blocks/s",
                    command.desiredForwardVelocity());
        }
        return "minecraft terrain " + flatScenarioDescription(scenario);
    }

    public static PointTargetDefinition pointGoalTarget(final int scenario) {
        return pointGoalTarget(
                scenario,
                DEFAULT_POINT_GOAL_NEAR_DISTANCE_BLOCKS,
                DEFAULT_POINT_GOAL_FAR_DISTANCE_BLOCKS);
    }

    public static PointTargetDefinition pointGoalTarget(
            final int scenario,
            final double nearDistanceBlocks,
            final double farDistanceBlocks
    ) {
        final int wrapped = Math.floorMod(scenario, POINT_GOAL_SCENARIO_COUNT);
        final double bearingDegrees = POINT_GOAL_BEARINGS_DEGREES[wrapped % POINT_GOAL_BEARINGS_DEGREES.length];
        final double distanceBlocks = wrapped / POINT_GOAL_BEARINGS_DEGREES.length == 0
                ? nearDistanceBlocks
                : farDistanceBlocks;
        final double bearingRad = Math.toRadians(bearingDegrees);
        return new PointTargetDefinition(
                Math.cos(bearingRad) * distanceBlocks,
                Math.sin(bearingRad) * distanceBlocks,
                PointTargetCommandConfig.DEFAULT.successRadius());
    }

    public static int rollingPointGoalScenario(
            final int stageIndex,
            final int localScenario,
            final int scenariosPerCandidate
    ) {
        return rollingTrainingScenario(
                MINECRAFT_TERRAIN_POINT_GOALS_STAGE,
                stageIndex,
                localScenario,
                scenariosPerCandidate);
    }

    public static int rollingTrainingScenario(
            final String curriculumStage,
            final int stageIndex,
            final int localScenario,
            final int scenariosPerCandidate
    ) {
        if (scenariosPerCandidate < 1) {
            throw new IllegalArgumentException("scenariosPerCandidate must be positive");
        }
        return Math.floorMod(
                stageIndex * scenariosPerCandidate + localScenario,
                scenarioCount(curriculumStage));
    }

    public static int scenarioCount(final String curriculumStage) {
        return switch (normalizeCurriculumStage(curriculumStage)) {
            case WALK_FORWARD_STAGE -> WALK_FORWARD_SCENARIO_COUNT;
            case MINECRAFT_TERRAIN_COMMANDS_STAGE -> FLAT_COMMAND_SCENARIO_COUNT;
            case MINECRAFT_TERRAIN_POINT_GOALS_STAGE -> POINT_GOAL_SCENARIO_COUNT;
            case BALANCE_STAND_STAGE, BALANCE_CENTER_OF_MASS_STAGE -> BALANCE_STAND_SCENARIO_COUNT;
            default -> throw new IllegalArgumentException("unsupported Duopod training curriculum stage " + curriculumStage);
        };
    }

    public static String pointGoalScenarioDescription(final int scenario) {
        return pointGoalScenarioDescription(
                scenario,
                DEFAULT_POINT_GOAL_NEAR_DISTANCE_BLOCKS,
                DEFAULT_POINT_GOAL_FAR_DISTANCE_BLOCKS);
    }

    public static String pointGoalScenarioDescription(
            final int scenario,
            final double nearDistanceBlocks,
            final double farDistanceBlocks
    ) {
        final PointTargetDefinition target = pointGoalTarget(scenario, nearDistanceBlocks, farDistanceBlocks);
        final double bearingDegrees = Math.toDegrees(Math.atan2(target.localRightBlocks(), target.localForwardBlocks()));
        return String.format(
                Locale.ROOT,
                "minecraft terrain point goal %.1f blocks at %.0f degrees",
                target.initialDistanceBlocks(),
                bearingDegrees);
    }

    public static EpisodeDefinition balanceStandEpisode(
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario
    ) {
        return balanceStandEpisode(BALANCE_STAND_STAGE, episodeId, seed, stageIndex, maximumControlSteps, scenario);
    }

    public static EpisodeDefinition balanceCenterOfMassEpisode(
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario
    ) {
        return balanceStandEpisode(BALANCE_CENTER_OF_MASS_STAGE, episodeId, seed, stageIndex, maximumControlSteps, scenario);
    }

    private static EpisodeDefinition balanceStandEpisode(
            final String curriculumStage,
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final int scenario
    ) {
        final StandingDisturbance disturbance = balanceStandingDisturbance(seed, stageIndex, scenario);
        final String stage = normalizeCurriculumStage(curriculumStage);
        return new EpisodeDefinition(
                episodeId,
                seed,
                EnvironmentTaskMode.BALANCE,
                new CurriculumStage(
                        stage,
                        stageIndex,
                        balanceScenarioDescription(stage, disturbance)),
                LocomotionCommand.ZERO,
                maximumControlSteps,
                0.0,
                DEFAULT_GAIT_FREQUENCY_HZ,
                terrainProfileForStage(stage),
                PointTargetDefinition.NONE,
                disturbance);
    }

    public static StandingDisturbance balanceStandingDisturbance(
            final long seed,
            final int stageIndex,
            final int scenario
    ) {
        final DuopodStandingBalanceConfig config = DuopodStandingBalanceConfig.DEFAULT;
        final DuopodStandingBalanceConfig.DisturbanceStage stage = config.disturbanceStage(stageIndex);
        final int wrapped = Math.floorMod(scenario, BALANCE_STAND_SCENARIO_COUNT);
        final RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed ^ (0x9E3779B97F4A7C15L * (wrapped + 1L)));
        final double jointNoise = stage.maxJointActionOffset();
        final double[] jointOffsets = {
                symmetric(random, jointNoise),
                symmetric(random, jointNoise)
        };
        return switch (wrapped) {
            case 1 -> disturbance(
                    "small_forward_pitch",
                    stage,
                    stage.maxTiltRadians(),
                    0.0,
                    stage.maxAngularVelocityRadPerSecond() * 0.25,
                    0.0,
                    0.0,
                    stage.maxAngularImpulse() * 0.75,
                    jointOffsets,
                    -1);
            case 2 -> disturbance(
                    "small_backward_pitch",
                    stage,
                    -stage.maxTiltRadians(),
                    0.0,
                    -stage.maxAngularVelocityRadPerSecond() * 0.25,
                    0.0,
                    0.0,
                    -stage.maxAngularImpulse() * 0.75,
                    jointOffsets,
                    -1);
            case 3 -> disturbance(
                    "small_left_roll",
                    stage,
                    0.0,
                    stage.maxTiltRadians(),
                    0.0,
                    stage.maxAngularVelocityRadPerSecond() * 0.25,
                    stage.maxAngularImpulse() * 0.75,
                    0.0,
                    jointOffsets,
                    -1);
            case 4 -> disturbance(
                    "small_right_roll",
                    stage,
                    0.0,
                    -stage.maxTiltRadians(),
                    0.0,
                    -stage.maxAngularVelocityRadPerSecond() * 0.25,
                    -stage.maxAngularImpulse() * 0.75,
                    0.0,
                    jointOffsets,
                    -1);
            case 5 -> disturbance(
                    "positive_pitch_velocity",
                    stage,
                    0.0,
                    0.0,
                    stage.maxAngularVelocityRadPerSecond(),
                    0.0,
                    0.0,
                    stage.maxAngularImpulse(),
                    jointOffsets,
                    -1);
            case 6 -> disturbance(
                    "negative_pitch_velocity",
                    stage,
                    0.0,
                    0.0,
                    -stage.maxAngularVelocityRadPerSecond(),
                    0.0,
                    0.0,
                    -stage.maxAngularImpulse(),
                    jointOffsets,
                    -1);
            case 7 -> {
                final double angle = random.nextDouble(0.0, Math.PI * 2.0);
                final double delayedForward = Math.cos(angle) * stage.maxLinearImpulse();
                final double delayedRight = Math.sin(angle) * stage.maxLinearImpulse();
                yield new StandingDisturbance(
                        "delayed_random_horizontal_impulse",
                        stage.index(),
                        0.0,
                        0.0,
                        symmetric(random, stage.maxAngularVelocityRadPerSecond() * 0.15),
                        symmetric(random, stage.maxAngularVelocityRadPerSecond() * 0.15),
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        -1,
                        stage.delayedImpulseControlStep(),
                        delayedForward,
                        delayedRight,
                        symmetric(random, stage.maxAngularImpulse() * 0.35),
                        symmetric(random, stage.maxAngularImpulse() * 0.35),
                        jointOffsets);
            }
            default -> new StandingDisturbance(
                    "near_neutral",
                    stage.index(),
                    symmetric(random, stage.maxTiltRadians() * 0.15),
                    symmetric(random, stage.maxTiltRadians() * 0.15),
                    symmetric(random, stage.maxAngularVelocityRadPerSecond() * 0.15),
                    symmetric(random, stage.maxAngularVelocityRadPerSecond() * 0.15),
                    0.0,
                    0.0,
                    0.0,
                    symmetric(random, stage.maxAngularImpulse() * 0.10),
                    symmetric(random, stage.maxAngularImpulse() * 0.10),
                    stage.maxAngularImpulse() > 0.0 ? 0 : -1,
                    -1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    jointOffsets);
        };
    }

    public static String balanceScenarioDescription(final StandingDisturbance disturbance) {
        return balanceScenarioDescription(BALANCE_STAND_STAGE, disturbance);
    }

    private static String balanceScenarioDescription(final String curriculumStage, final StandingDisturbance disturbance) {
        final String objective = BALANCE_CENTER_OF_MASS_STAGE.equals(normalizeCurriculumStage(curriculumStage))
                ? "center-of-mass height/stillness"
                : "support/upright standing";
        return "%s disturbance stage %d: %s".formatted(objective, disturbance.standingStage(), disturbance.scenarioId());
    }

    private static StandingDisturbance disturbance(
            final String scenarioId,
            final DuopodStandingBalanceConfig.DisturbanceStage stage,
            final double pitchRadians,
            final double rollRadians,
            final double pitchAngularVelocity,
            final double rollAngularVelocity,
            final double angularImpulseRoll,
            final double angularImpulsePitch,
            final double[] jointOffsets,
            final int delayedImpulseControlStep
    ) {
        return new StandingDisturbance(
                scenarioId,
                stage.index(),
                pitchRadians,
                rollRadians,
                pitchAngularVelocity,
                rollAngularVelocity,
                0.0,
                0.0,
                0.0,
                angularImpulseRoll,
                angularImpulsePitch,
                0,
                delayedImpulseControlStep,
                0.0,
                0.0,
                0.0,
                0.0,
                jointOffsets);
    }

    private static double symmetric(final RandomGenerator random, final double magnitude) {
        if (magnitude <= 0.0) {
            return 0.0;
        }
        return (random.nextDouble() * 2.0 - 1.0) * magnitude;
    }

    private static EpisodeDefinition pointGoalEpisode(
            final String curriculumStage,
            final long episodeId,
            final long seed,
            final int stageIndex,
            final int maximumControlSteps,
            final PointTargetDefinition target,
            final String description
    ) {
        final LocomotionCommand initialCommand = PointTargetCommandMath.commandForLocalOffset(
                PointTargetCommandConfig.DEFAULT,
                target.localForwardBlocks(),
                target.localRightBlocks());
        return new EpisodeDefinition(
                episodeId,
                seed,
                EnvironmentTaskMode.POINT_GOAL,
                new CurriculumStage(curriculumStage, stageIndex, description),
                initialCommand,
                maximumControlSteps,
                target.initialDistanceBlocks(),
                DEFAULT_GAIT_FREQUENCY_HZ,
                terrainProfileForStage(curriculumStage),
                target);
    }
}
