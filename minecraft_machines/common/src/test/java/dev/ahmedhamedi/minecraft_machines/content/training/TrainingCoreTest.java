package dev.ahmedhamedi.minecraft_machines.content.training;

import com.google.gson.JsonObject;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode;
import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ObservationSpec;
import dev.ahmedhamedi.minecraft_machines.content.training.api.RewardBreakdown;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoTelemetrySample;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoTelemetrySchemaBuilder;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.BridgeMessageType;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.BridgeSpecs;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.ProtocolEnvelope;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.StepRequest;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.StepResponse;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.TrainingBridgeProtocol;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CemExplorationDiagnostics;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CemFitnessProtocol;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CemScenarioSeeds;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.ContinuousCemDistribution;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.CurriculumScopedBestSelection;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.LinearTanhPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.ScoredGenome;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.CurriculumStage;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.PointTargetDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.StandingDisturbance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodActionDecoder;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodEvaluationManifests;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodObservation;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodObservationEncoder;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodObservationInput;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodPhaseGaitPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodPhaseGaitSeeds;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodPlanarMath;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodRewardCalculator;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodRewardConfig;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodRewardInput;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodSchemas;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodTrainingScenarios;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodWalkForwardFitness;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PointTargetCommandMath;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PointTargetCommandConfig;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.ServoAngleLimits;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PlanarBodyAxes;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PlanarLocalOffset;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PlanarWorldOffset;
import dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingCoreTest {
    @Test
    void duopodObservationSchemaHasDocumentedOrder() {
        final ObservationSpec spec = DuopodSchemas.observationSpec();

        assertEquals("minecraft_machines:duopod_locomotion", spec.schemaId());
        assertEquals(6, spec.schemaVersion());
        assertEquals(45, spec.size());
        final List<String> expected = new java.util.ArrayList<>(ServoTelemetrySchemaBuilder.BASE_OBSERVATION_FIELD_NAMES);
        for (final String servoName : List.of("left", "right")) {
            for (final String suffix : ServoTelemetrySchemaBuilder.SERVO_OBSERVATION_SUFFIXES) {
                expected.add(servoName + "_" + suffix);
            }
        }
        assertEquals(expected, spec.fields().stream().map(field -> field.name()).toList());
    }

    @Test
    void duopodActionSchemaHasTwoServoTargets() {
        assertEquals(3, DuopodSchemas.actionSpec().schemaVersion());
        assertEquals(2, DuopodSchemas.actionSpec().size());
        assertEquals("left_target", DuopodSchemas.actionSpec().fields().get(0).name());
        assertEquals("right_target", DuopodSchemas.actionSpec().fields().get(1).name());
        assertNotEquals(DuopodSchemas.observationSpec().compatibilityHash(), DuopodSchemas.actionSpec().compatibilityHash());
    }

    @Test
    void duopodPhaseGaitPolicyIsSmallBoundedAndPhaseDriven() {
        final DuopodPhaseGaitPolicy policy = new DuopodPhaseGaitPolicy();
        final double[] genome = new double[DuopodPhaseGaitPolicy.GENOME_SIZE];
        final double[] phaseZero = new double[DuopodSchemas.observationSpec().size()];
        phaseZero[0] = 0.0;
        phaseZero[1] = 1.0;
        phaseZero[2] = 0.25;
        final double[] phaseQuarter = Arrays.copyOf(phaseZero, phaseZero.length);
        phaseQuarter[0] = 1.0;
        phaseQuarter[1] = 0.0;

        assertEquals(7, policy.genomeSize());
        assertEquals(2, policy.actionSize());
        assertEquals(DuopodSchemas.observationSpec().size(), policy.observationSize());
        assertEquals("duopod_phase_gait_v2", DuopodPhaseGaitPolicy.POLICY_TYPE);
        assertArrayEquals(new double[]{0.0, 0.0}, policy.action(genome, phaseZero), 1.0e-10);

        final double[] moved = policy.action(genome, phaseQuarter);
        assertEquals(2, moved.length);
        assertTrue(moved[0] > 0.0);
        assertTrue(Math.abs(moved[1]) <= 1.0);
    }

    @Test
    void duopodPhaseGaitPolicyScalesAmplitudeAcrossEncodedTrainingSpeeds() {
        final DuopodPhaseGaitPolicy policy = new DuopodPhaseGaitPolicy();
        final double[] genome = new double[DuopodPhaseGaitPolicy.GENOME_SIZE];
        genome[6] = Double.MAX_VALUE;

        final double[] slowObservation = phaseGaitObservation(Math.PI * 0.5, 0.50);
        final double[] fastObservation = phaseGaitObservation(Math.PI * 0.5, 1.10);
        final double[] aboveTrainingRangeObservation = phaseGaitObservation(Math.PI * 0.5, 4.0);

        assertEquals(0.125, slowObservation[2], 1.0e-10);
        assertEquals(0.275, fastObservation[2], 1.0e-10);

        final double baseAmplitude = 0.475;
        final double[] slowAction = policy.action(genome, slowObservation);
        final double[] fastAction = policy.action(genome, fastObservation);
        assertArrayEquals(
                new double[]{baseAmplitude * (1.0 + 0.60 * 0.50 / 1.10), baseAmplitude * (1.0 + 0.60 * 0.50 / 1.10)},
                slowAction,
                1.0e-10);
        assertArrayEquals(new double[]{baseAmplitude * 1.60, baseAmplitude * 1.60}, fastAction, 1.0e-10);
        assertArrayEquals(fastAction, policy.action(genome, aboveTrainingRangeObservation), 1.0e-10);
        assertTrue(fastAction[0] > slowAction[0]);
    }

    @Test
    void duopodPhaseGaitPolicyKeepsWidenedSpeedScalingBounded() {
        final DuopodPhaseGaitPolicy policy = new DuopodPhaseGaitPolicy();
        final double[] genome = new double[DuopodPhaseGaitPolicy.GENOME_SIZE];
        genome[2] = Double.MAX_VALUE;
        genome[3] = Double.MAX_VALUE;
        genome[6] = Double.MAX_VALUE;

        for (final double commandSpeed : new double[]{-4.0, -1.10, 0.0, 0.50, 1.10, 4.0}) {
            for (int phaseStep = 0; phaseStep < 16; phaseStep++) {
                final double phase = phaseStep * Math.PI / 8.0;
                for (final double action : policy.action(genome, phaseGaitObservation(phase, commandSpeed))) {
                    assertTrue(Double.isFinite(action));
                    assertTrue(action >= -1.0 && action <= 1.0);
                }
            }
        }
    }

    @Test
    void duopodPhaseGaitStableBasinProbesAreDeterministicCopiesWithinBounds() {
        final List<double[]> first = DuopodPhaseGaitSeeds.stableBasinProbes();
        final List<double[]> second = DuopodPhaseGaitSeeds.stableBasinProbes();

        assertEquals(8, first.size());
        assertEquals(first.size(), second.size());
        for (int probe = 0; probe < first.size(); probe++) {
            assertTrue(first.get(probe) != second.get(probe));
            assertArrayEquals(first.get(probe), second.get(probe));
            assertEquals(DuopodPhaseGaitPolicy.GENOME_SIZE, first.get(probe).length);
            for (final double gene : first.get(probe)) {
                assertTrue(Double.isFinite(gene));
                assertTrue(Math.abs(gene) <= DuopodPhaseGaitSeeds.MAX_ABSOLUTE_GENE_VALUE);
            }
        }

        first.getFirst()[0] = DuopodPhaseGaitSeeds.MAX_ABSOLUTE_GENE_VALUE;
        assertArrayEquals(second.getFirst(), DuopodPhaseGaitSeeds.stableBasinProbes().getFirst());
    }

    @Test
    void duopodPhaseGaitStableBasinProbesCoverDiverseOppositePhaseDirections() {
        final List<double[]> probes = DuopodPhaseGaitSeeds.stableBasinProbes();

        assertEquals(probes.size(), probes.stream().map(Arrays::toString).distinct().count());
        assertTrue(probes.stream().anyMatch(probe -> probe[0] < 0.0));
        assertTrue(probes.stream().anyMatch(probe -> probe[0] > 0.0));
        assertTrue(probes.stream().allMatch(probe -> probe[2] >= -0.55 && probe[2] <= -0.20));
        assertTrue(probes.stream().anyMatch(probe -> probe[4] < 0.0));
        assertTrue(probes.stream().anyMatch(probe -> probe[4] > 0.0));
        assertTrue(probes.stream().allMatch(probe -> Math.abs(Math.tanh(probe[4])) >= 0.85));
        assertTrue(probes.stream().anyMatch(probe -> probe[5] < 0.0));
        assertTrue(probes.stream().anyMatch(probe -> probe[5] > 0.0));
        assertTrue(probes.stream().allMatch(probe -> probe[6] > 0.0 && probe[6] <= 0.40));
        assertTrue(probes.stream().noneMatch(probe -> Arrays.stream(probe).allMatch(gene -> gene == 0.0)));
    }

    @Test
    void observationNormalizationIsFiniteBoundedAndRepairsInvalidSensors() {
        final DuopodObservation observation = DuopodObservationEncoder.encode(new DuopodObservationInput(
                Double.NaN,
                new LocomotionCommand(100.0, -100.0, 100.0),
                100.0,
                -100.0,
                Double.POSITIVE_INFINITY,
                100.0,
                -100.0,
                100.0,
                Double.POSITIVE_INFINITY,
                -Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NaN,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                sample("left", Double.NaN, 25.0, 10.0, 100.0, 12.0, 500.0, 600.0, -85.0, 85.0, 1_000.0, true, true, true, 2.0),
                sample("right", -20.0, -25.0, 5.0, -100.0, -12.0, -500.0, 600.0, -85.0, 85.0, 1_000.0, true, true, true, -2.0)
        ));

        assertTrue(observation.repaired());
        assertEquals(45, observation.values().length);
        for (final double value : observation.values()) {
            assertTrue(Double.isFinite(value));
            assertTrue(value >= -1.0 && value <= 1.0);
        }
    }

    @Test
    void standingObservationDistinguishesTiltVelocityAndHeightError() {
        final DuopodObservation forwardTilt = standingObservation(0.35, 0.0, 0.0);
        final DuopodObservation backwardTilt = standingObservation(-0.35, 0.0, 0.0);
        final DuopodObservation fallingForward = standingObservation(0.0, 0.80, 0.0);
        final DuopodObservation recoveringBackward = standingObservation(0.0, -0.80, 0.0);
        final DuopodObservation highBody = standingObservation(0.0, 0.0, 0.50);
        final DuopodObservation lowBody = standingObservation(0.0, 0.0, -0.50);
        final DuopodObservation highCenterOfMass = standingObservation(0.0, 0.0, 0.0, 0.50, 0.20, -0.30, 0.60, -0.40);
        final int gravityForward = observationIndex("projected_gravity_forward");
        final int pitchRate = observationIndex("local_pitch_rate");
        final int heightError = observationIndex("standing_height_error");
        final int comHeight = observationIndex("center_of_mass_height_delta");
        final int comForwardDrift = observationIndex("center_of_mass_forward_drift");
        final int comLateralDrift = observationIndex("center_of_mass_lateral_drift");
        final int supportForward = observationIndex("support_com_forward_error");
        final int supportLateral = observationIndex("support_com_lateral_error");

        assertTrue(forwardTilt.values()[gravityForward] > 0.0);
        assertTrue(backwardTilt.values()[gravityForward] < 0.0);
        assertTrue(fallingForward.values()[pitchRate] > 0.0);
        assertTrue(recoveringBackward.values()[pitchRate] < 0.0);
        assertTrue(highBody.values()[heightError] > 0.0);
        assertTrue(lowBody.values()[heightError] < 0.0);
        assertTrue(highCenterOfMass.values()[comHeight] > 0.0);
        assertTrue(highCenterOfMass.values()[comForwardDrift] > 0.0);
        assertTrue(highCenterOfMass.values()[comLateralDrift] < 0.0);
        assertTrue(highCenterOfMass.values()[supportForward] > 0.0);
        assertTrue(highCenterOfMass.values()[supportLateral] < 0.0);
    }

    @Test
    void targetTransformUsesDuopodLocalFrame() {
        final PlanarLocalOffset ahead = DuopodPlanarMath.horizontalLocalOffset(5.0, 0.0, 1.0, 0.0, 0.0, 1.0);
        assertTrue(ahead.forward() > 0.0);
        assertEquals(0.0, ahead.right(), 1.0e-10);

        final PlanarLocalOffset right = DuopodPlanarMath.horizontalLocalOffset(0.0, 5.0, 1.0, 0.0, 0.0, 1.0);
        assertTrue(right.right() > 0.0);

        final PlanarLocalOffset rotatedAhead = DuopodPlanarMath.horizontalLocalOffset(0.0, 5.0, 0.0, 1.0, -1.0, 0.0);
        assertTrue(rotatedAhead.forward() > 0.0);
        assertEquals(0.0, rotatedAhead.right(), 1.0e-10);
    }

    @Test
    void pointTargetFrameStaysPlanarAndMetricWhenBodyForwardIsVertical() {
        final PlanarWorldOffset target = DuopodPlanarMath.horizontalWorldOffset(
                3.0,
                4.0,
                0.0,
                0.0,
                1.0,
                0.0);
        final PlanarLocalOffset recovered = DuopodPlanarMath.horizontalLocalOffset(
                target.x(), target.z(), 0.0, 0.0, 1.0, 0.0);
        final PlanarBodyAxes axes = DuopodPlanarMath.normalizedBodyAxes(0.0, 0.0, 1.0, 0.0);

        assertEquals(5.0, target.length(), 1.0e-10);
        assertEquals(3.0, recovered.forward(), 1.0e-10);
        assertEquals(4.0, recovered.right(), 1.0e-10);
        assertEquals(1.0, Math.hypot(axes.forwardX(), axes.forwardZ()), 1.0e-10);
        assertEquals(1.0, Math.hypot(axes.rightX(), axes.rightZ()), 1.0e-10);
        assertEquals(0.0,
                axes.forwardX() * axes.rightX() + axes.forwardZ() * axes.rightZ(),
                1.0e-10);
    }

    @Test
    void pointTargetResetDistanceMatchesRequestedDistanceUnderSevereCompoundTilt() {
        final PointTargetDefinition targetDefinition = new PointTargetDefinition(6.0, 8.0, 0.75);
        final double tiltedForwardX = 0.002;
        final double tiltedForwardZ = -0.015;
        final double tiltedRightX = 0.31;
        final double tiltedRightZ = 0.40;

        final PlanarWorldOffset target = DuopodPlanarMath.horizontalWorldOffset(
                targetDefinition.localForwardBlocks(),
                targetDefinition.localRightBlocks(),
                tiltedForwardX,
                tiltedForwardZ,
                tiltedRightX,
                tiltedRightZ);
        final PlanarLocalOffset recovered = DuopodPlanarMath.horizontalLocalOffset(
                target.x(),
                target.z(),
                tiltedForwardX,
                tiltedForwardZ,
                tiltedRightX,
                tiltedRightZ);

        assertEquals(targetDefinition.initialDistanceBlocks(), target.length(), 1.0e-10);
        assertEquals(targetDefinition.localForwardBlocks(), recovered.forward(), 1.0e-10);
        assertEquals(targetDefinition.localRightBlocks(), recovered.right(), 1.0e-10);
        assertEquals(targetDefinition.initialDistanceBlocks(), recovered.horizontalDistance(), 1.0e-10);
    }

    @Test
    void pointTargetCommandGeneratorProducesExpectedCommands() {
        final LocomotionCommand ahead = PointTargetCommandMath.commandForLocalOffset(PointTargetCommandConfig.DEFAULT, 6.0, 0.0);
        assertTrue(ahead.desiredForwardVelocity() > 0.0);
        assertEquals(0.0, ahead.desiredYawRate(), 1.0e-10);

        final LocomotionCommand left = PointTargetCommandMath.commandForLocalOffset(PointTargetCommandConfig.DEFAULT, 4.0, -4.0);
        final LocomotionCommand right = PointTargetCommandMath.commandForLocalOffset(PointTargetCommandConfig.DEFAULT, 4.0, 4.0);
        assertTrue(left.desiredYawRate() < 0.0);
        assertTrue(right.desiredYawRate() > 0.0);

        final LocomotionCommand reached = PointTargetCommandMath.commandForLocalOffset(PointTargetCommandConfig.DEFAULT, 0.1, 0.0);
        assertEquals(LocomotionCommand.ZERO, reached);
    }

    @Test
    void normalizedActionsMapToServoLimits() {
        final ServoAngleLimits limits = new ServoAngleLimits(-80.0, 100.0);

        assertEquals(-80.0, DuopodActionDecoder.targetAngleDegrees(-1.0, limits), 1.0e-10);
        assertEquals(10.0, DuopodActionDecoder.targetAngleDegrees(0.0, limits), 1.0e-10);
        assertEquals(100.0, DuopodActionDecoder.targetAngleDegrees(1.0, limits), 1.0e-10);
        assertEquals(100.0, DuopodActionDecoder.targetAngleDegrees(2.0, limits), 1.0e-10);
    }

    @Test
    void linearTanhPolicyIsBoundedAndUsesDynamicGenomeSize() {
        final LinearTanhPolicy policy = new LinearTanhPolicy(DuopodSchemas.observationSpec().size(), DuopodSchemas.actionSpec().size());
        assertEquals(92, policy.genomeSize());

        final double[] genome = new double[policy.genomeSize()];
        Arrays.fill(genome, 0.25);
        final double[] action = policy.action(genome, new double[DuopodSchemas.observationSpec().size()]);

        assertEquals(2, action.length);
        assertTrue(action[0] > -1.0 && action[0] < 1.0);
        assertTrue(action[1] > -1.0 && action[1] < 1.0);
    }

    @Test
    void linearPolicyFlatteningRoundTrips() {
        final LinearTanhPolicy policy = new LinearTanhPolicy(3, 2);
        final double[][] weights = {
                {1.0, 2.0, 3.0},
                {4.0, 5.0, 6.0}
        };
        final double[] biases = {7.0, 8.0};

        final double[] genome = policy.flatten(weights, biases);
        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 7.0, 4.0, 5.0, 6.0, 8.0}, genome);
        final LinearTanhPolicy.Unflattened unflattened = policy.unflatten(genome);
        assertArrayEquals(weights[0], unflattened.weights()[0]);
        assertArrayEquals(weights[1], unflattened.weights()[1]);
        assertArrayEquals(biases, unflattened.biases());
    }

    @Test
    void antitheticSamplingProducesPairedPerturbations() {
        final ContinuousCemDistribution distribution = ContinuousCemDistribution.initial(4, 0.5, 0.25, 1, -4.0, 4.0, 0.03, 123L);
        final List<double[]> samples = distribution.sampleAntithetic(2, RandomGeneratorFactory.of("L64X128MixRandom").create(123L));

        for (int i = 0; i < 4; i++) {
            assertEquals(0.0, samples.get(0)[i] + samples.get(1)[i], 1.0e-10);
        }
    }

    @Test
    void anchoredAntitheticSamplingPreservesTheKnownDistributionMean() {
        final ContinuousCemDistribution distribution = new ContinuousCemDistribution(
                new double[]{0.5, -0.25},
                new double[]{0.2, 0.2},
                new double[]{-1.0, -1.0},
                new double[]{1.0, 1.0},
                new double[]{0.02, 0.02},
                new double[]{1.0, 1.0},
                91L,
                0,
                new double[]{0.5, -0.25},
                1.0);

        final List<double[]> samples = distribution.sampleAntitheticWithMeanAnchor(
                6,
                RandomGeneratorFactory.of("L64X128MixRandom").create(91L));

        assertEquals(6, samples.size());
        assertArrayEquals(distribution.mean(), samples.getFirst());
        for (int parameter = 0; parameter < distribution.mean().length; parameter++) {
            assertEquals(
                    2.0 * distribution.mean()[parameter],
                    samples.get(1)[parameter] + samples.get(2)[parameter],
                    1.0e-10);
            assertEquals(
                    2.0 * distribution.mean()[parameter],
                    samples.get(3)[parameter] + samples.get(4)[parameter],
                    1.0e-10);
        }
    }

    @Test
    void cemDistributionMovesTowardElitesAndRespectsStdFloors() {
        final ContinuousCemDistribution distribution = ContinuousCemDistribution.initial(2, 1.0, 1.0, 1, -4.0, 4.0, 0.2, 42L);
        final ContinuousCemDistribution updated = distribution.update(List.of(
                new ScoredGenome(new double[]{2.0, 2.0}, 10.0, 8.0, 1.0),
                new ScoredGenome(new double[]{1.0, 1.0}, 9.0, 7.0, 1.0),
                new ScoredGenome(new double[]{-2.0, -2.0}, -1.0, -2.0, 0.0)
        ), 2, 0.35, 0.65);

        assertTrue(updated.mean()[0] > distribution.mean()[0]);
        assertTrue(updated.mean()[1] > distribution.mean()[1]);
        assertTrue(updated.standardDeviation()[0] >= 0.2);
        assertTrue(updated.standardDeviation()[1] >= 0.2);
        assertArrayEquals(new double[]{2.0, 2.0}, updated.bestGenome());
    }

    @Test
    void scoredGenomeMakesMachineFailureMoreCostlyThanShortTermReward() {
        final ScoredGenome stable = new ScoredGenome(new double[]{0.0}, 0.0, 0.0, 0.0, 0.0);
        final ScoredGenome fallsAfterProgress = new ScoredGenome(new double[]{1.0}, 10.0, 8.0, 0.0, 1.0);

        assertTrue(stable.aggregateFitness() > fallsAfterProgress.aggregateFitness());
        assertThrows(IllegalArgumentException.class,
                () -> new ScoredGenome(new double[]{0.0}, 0.0, 0.0, 0.0, 1.01));
    }

    @Test
    void walkForwardTerminalFitnessMatchesPublicationStabilityGate() {
        final DuopodWalkForwardFitness.EpisodeFitness stableForward =
                DuopodWalkForwardFitness.assessEpisode(1.0, 0.75, 0.80, 2.10, false, false);
        final DuopodWalkForwardFitness.EpisodeFitness unstableLaunch =
                DuopodWalkForwardFitness.assessEpisode(1.0, 8.0, 0.59, 2.10, false, false);
        final DuopodWalkForwardFitness.EpisodeFitness failedLaunch =
                DuopodWalkForwardFitness.assessEpisode(1.0, 8.0, 0.90, 7.00, true, false);
        final DuopodWalkForwardFitness.EpisodeFitness uprightBallisticLaunch =
                DuopodWalkForwardFitness.assessEpisode(30.0, 8.0, 0.90, 7.00, false, false);
        final DuopodWalkForwardFitness.EpisodeFitness arenaEscape =
                DuopodWalkForwardFitness.assessEpisode(1.0, 8.0, 0.90, 2.10, false, true);
        final DuopodWalkForwardFitness.EpisodeFitness stableBackward =
                DuopodWalkForwardFitness.assessEpisode(1.0, -0.25, 0.90, 2.10, false, false);

        assertTrue(stableForward.stable());
        assertTrue(stableForward.success());
        assertEquals(0.75, stableForward.stabilityAdjustedForwardDisplacement(), 1.0e-10);
        assertFalse(unstableLaunch.stable());
        assertFalse(unstableLaunch.success());
        assertEquals(0.0, unstableLaunch.stabilityAdjustedForwardDisplacement(), 1.0e-10);
        assertEquals(0.0, failedLaunch.stabilityAdjustedForwardDisplacement(), 1.0e-10);
        assertFalse(uprightBallisticLaunch.stable());
        assertFalse(uprightBallisticLaunch.success());
        assertEquals(4.0, uprightBallisticLaunch.verticalExcessBlocks(), 1.0e-10);
        assertEquals(0.0, uprightBallisticLaunch.stabilityAdjustedForwardDisplacement(), 1.0e-10);
        assertFalse(arenaEscape.stable());
        assertFalse(arenaEscape.success());
        assertEquals(0.0, arenaEscape.stabilityAdjustedForwardDisplacement(), 1.0e-10);
        assertFalse(stableBackward.success());
        assertEquals(-0.25, stableBackward.stabilityAdjustedForwardDisplacement(), 1.0e-10);
        assertTrue(stableForward.selectionReturn() > unstableLaunch.selectionReturn());
        assertTrue(stableForward.selectionReturn() > failedLaunch.selectionReturn());
        assertTrue(stableForward.selectionReturn() > uprightBallisticLaunch.selectionReturn());
        assertTrue(stableForward.selectionReturn() > arenaEscape.selectionReturn());
        assertTrue(stableForward.selectionReturn() > stableBackward.selectionReturn());
    }

    @Test
    void walkForwardTerminalFitnessRejectsMalformedMetrics() {
        assertThrows(IllegalArgumentException.class,
                () -> DuopodWalkForwardFitness.assessEpisode(Double.NaN, 0.0, 1.0, 0.0, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> DuopodWalkForwardFitness.assessEpisode(0.0, Double.POSITIVE_INFINITY, 1.0, 0.0, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> DuopodWalkForwardFitness.assessEpisode(0.0, 0.0, -1.01, 0.0, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> DuopodWalkForwardFitness.assessEpisode(0.0, 0.0, 1.0, -0.01, false, false));
    }

    @Test
    void cemMeanDirectionFollowsEpisodeScores() {
        final ContinuousCemDistribution distribution = new ContinuousCemDistribution(
                new double[]{0.0},
                new double[]{1.0},
                new double[]{-4.0},
                new double[]{4.0},
                new double[]{0.0},
                new double[]{4.0},
                7L,
                0,
                new double[]{0.0},
                Double.NEGATIVE_INFINITY);

        final ContinuousCemDistribution rightWins = distribution.update(List.of(
                new ScoredGenome(new double[]{-2.0}, 0.0, 0.0, 0.0),
                new ScoredGenome(new double[]{2.0}, 10.0, 8.0, 0.0)
        ), 1, 0.0, 1.0);
        final ContinuousCemDistribution leftWins = distribution.update(List.of(
                new ScoredGenome(new double[]{-2.0}, 10.0, 8.0, 0.0),
                new ScoredGenome(new double[]{2.0}, 0.0, 0.0, 0.0)
        ), 1, 0.0, 1.0);

        assertEquals(2.0, rightWins.mean()[0], 1.0e-10);
        assertEquals(-2.0, leftWins.mean()[0], 1.0e-10);
        assertArrayEquals(new double[]{2.0}, rightWins.bestGenome());
        assertArrayEquals(new double[]{-2.0}, leftWins.bestGenome());
    }

    @Test
    void cemExplorationCanExpandWhenStagnatedButNotSaturated() {
        final ContinuousCemDistribution distribution = ContinuousCemDistribution.initial(4, 0.04, 0.08, 1, -1.0, 1.0, 0.01, 0.10, 7L);
        final ContinuousCemDistribution expanded = distribution.expandStandardDeviation(2.0);

        assertTrue(expanded.standardDeviation()[0] > distribution.standardDeviation()[0]);
        assertEquals(0.10, expanded.standardDeviation()[1], 1.0e-10);
        assertArrayEquals(distribution.bestGenome(), expanded.bestGenome());
        assertTrue(CemExplorationDiagnostics.shouldExpandExploration(
                new double[]{10.0, 10.02, 10.03, 10.03, 10.04},
                0.10,
                0.01,
                0.035,
                0.20,
                0.65));
        assertFalse(CemExplorationDiagnostics.shouldExpandExploration(
                new double[]{10.0, 10.02, 10.03, 10.03, 10.04},
                0.10,
                0.01,
                0.035,
                0.90,
                0.65));
        assertTrue(CemExplorationDiagnostics.hasStagnated(
                new double[]{0.68, -20.0, -20.2, -21.5, -20.8},
                0.10));
        assertFalse(CemExplorationDiagnostics.hasStagnated(
                new double[]{0.68, 0.70, 0.79, 0.77, 0.81},
                0.10));
    }

    @Test
    void cemScenarioSeedsAreCommonWithinGeneration() {
        final CemScenarioSeeds first = CemScenarioSeeds.forGeneration(123L, 4, 3);
        final CemScenarioSeeds second = CemScenarioSeeds.forGeneration(123L, 4, 3);
        final CemScenarioSeeds different = CemScenarioSeeds.forGeneration(123L, 5, 3);

        assertArrayEquals(first.seeds(), second.seeds());
        assertFalse(Arrays.equals(first.seeds(), different.seeds()));
    }

    @Test
    void standingDisturbancesCoverSymmetricTiltVelocityAndDelayedImpulses() {
        final StandingDisturbance forward = DuopodTrainingScenarios.balanceStandingDisturbance(123L, 1, 1);
        final StandingDisturbance backward = DuopodTrainingScenarios.balanceStandingDisturbance(123L, 1, 2);
        final StandingDisturbance left = DuopodTrainingScenarios.balanceStandingDisturbance(123L, 1, 3);
        final StandingDisturbance right = DuopodTrainingScenarios.balanceStandingDisturbance(123L, 1, 4);
        final StandingDisturbance positivePitchVelocity = DuopodTrainingScenarios.balanceStandingDisturbance(123L, 1, 5);
        final StandingDisturbance negativePitchVelocity = DuopodTrainingScenarios.balanceStandingDisturbance(123L, 1, 6);
        final StandingDisturbance delayed = DuopodTrainingScenarios.balanceStandingDisturbance(123L, 1, 7);

        assertTrue(forward.pitchRadians() > 0.0);
        assertTrue(backward.pitchRadians() < 0.0);
        assertTrue(left.rollRadians() > 0.0);
        assertTrue(right.rollRadians() < 0.0);
        assertTrue(positivePitchVelocity.pitchAngularVelocityRadPerSecond() > 0.0);
        assertTrue(negativePitchVelocity.pitchAngularVelocityRadPerSecond() < 0.0);
        assertTrue(delayed.delayedImpulseControlStep() >= 0);
        assertTrue(Math.hypot(delayed.delayedLinearImpulseForward(), delayed.delayedLinearImpulseRight()) > 0.0);
    }

    @Test
    void cemBestSelectionIsScopedByCurriculumStage() {
        assertTrue(CurriculumScopedBestSelection.shouldReplace(
                null,
                0.0,
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE,
                1.0));
        assertTrue(CurriculumScopedBestSelection.shouldReplace(
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE,
                9.0,
                DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE,
                3.0));
        assertTrue(CurriculumScopedBestSelection.shouldReplace(
                DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE,
                3.0,
                DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE,
                3.1));
        assertFalse(CurriculumScopedBestSelection.shouldReplace(
                DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE,
                3.0,
                DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE,
                2.9));
        assertThrows(IllegalArgumentException.class, () -> CurriculumScopedBestSelection.shouldReplace(
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE,
                1.0,
                "",
                1.0));
        assertThrows(IllegalArgumentException.class, () -> CurriculumScopedBestSelection.shouldReplace(
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE,
                Double.NaN,
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE,
                1.0));
    }

    @Test
    void cemFitnessProtocolSeparatesIncompatibleObjectiveScales() {
        final CemFitnessProtocol protocol = new CemFitnessProtocol(160, 4, 3, 20, 6.0, 10.0, 12, "fitness_v6|arena_v2|layout_v1");

        assertTrue(protocol.isCompatibleWith(new CemFitnessProtocol(160, 4, 3, 20, 6.0, 10.0, 12, "fitness_v6|arena_v2|layout_v1")));
        assertFalse(protocol.isCompatibleWith(new CemFitnessProtocol(400, 4, 3, 20, 6.0, 10.0, 12, "fitness_v6|arena_v2|layout_v1")));
        assertFalse(protocol.isCompatibleWith(new CemFitnessProtocol(160, 5, 3, 20, 6.0, 10.0, 12, "fitness_v6|arena_v2|layout_v1")));
        assertFalse(protocol.isCompatibleWith(new CemFitnessProtocol(160, 4, 5, 20, 6.0, 10.0, 12, "fitness_v6|arena_v2|layout_v1")));
        assertFalse(protocol.isCompatibleWith(new CemFitnessProtocol(160, 4, 3, 0, 6.0, 10.0, 12, "fitness_v6|arena_v2|layout_v1")));
        assertFalse(protocol.isCompatibleWith(new CemFitnessProtocol(160, 4, 3, 20, 8.0, 12.0, 12, "fitness_v6|arena_v2|layout_v1")));
        assertFalse(protocol.isCompatibleWith(new CemFitnessProtocol(160, 4, 3, 20, 6.0, 10.0, 24, "fitness_v6|arena_v2|layout_v1")));
        assertFalse(protocol.isCompatibleWith(new CemFitnessProtocol(160, 4, 3, 20, 6.0, 10.0, 12, "fitness_v7|arena_v3|layout_v2")));
        assertThrows(IllegalArgumentException.class, () -> new CemFitnessProtocol(0, 4, 3, 20, 6.0, 10.0, 12, "fitness_v6"));
    }

    @Test
    void rewardProgressAndActionPenaltyBehaveAsExpected() {
        final DuopodRewardCalculator calculator = new DuopodRewardCalculator(DuopodRewardConfig.DEFAULT);
        final DuopodRewardInput smoothProgress = rewardInput(8.0, 6.0, new double[]{0.0, 0.0}, new double[]{0.0, 0.0});
        final DuopodRewardInput noProgress = rewardInput(8.0, 8.0, new double[]{0.0, 0.0}, new double[]{0.0, 0.0});
        final DuopodRewardInput abruptProgress = rewardInput(8.0, 6.0, new double[]{0.0, 0.0}, new double[]{1.0, -1.0});
        final DuopodRewardInput launchedProgress = rewardInput(8.0, 6.0, new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, false, 3.0, 3.0);
        final DuopodRewardInput reached = rewardInput(1.0, 0.5, new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, true);

        final RewardBreakdown progressReward = calculator.pointGoal(smoothProgress);
        assertTrue(progressReward.components().get("progress") > 0.0);
        assertTrue(progressReward.total() > calculator.pointGoal(noProgress).total());
        assertTrue(calculator.pointGoal(abruptProgress).components().get("action_rate") > progressReward.components().get("action_rate"));
        assertTrue(calculator.pointGoal(abruptProgress).total() < progressReward.total());
        assertTrue(calculator.pointGoal(launchedProgress).components().get("launch_penalty") < 0.0);
        assertTrue(calculator.pointGoal(launchedProgress).total() < progressReward.total());
        assertEquals(DuopodRewardConfig.DEFAULT.successBonus(), calculator.pointGoal(reached).components().get("success_bonus"), 1.0e-10);
    }

    @Test
    void commandRewardPenalizesLateralDriftForWalkForwardCommands() {
        final DuopodRewardCalculator calculator = new DuopodRewardCalculator(DuopodRewardConfig.DEFAULT);
        final LocomotionCommand walkForward = new LocomotionCommand(1.0, 0.0, 0.0);
        final DuopodRewardInput straight = commandRewardInput(1.0, 0.0, 0.0, walkForward);
        final DuopodRewardInput sideDrift = commandRewardInput(1.0, 1.25, 0.0, walkForward);

        final RewardBreakdown straightReward = calculator.commandTracking(straight);
        final RewardBreakdown sideDriftReward = calculator.commandTracking(sideDrift);

        assertEquals(straightReward.components().get("forward_tracking"), sideDriftReward.components().get("forward_tracking"), 1.0e-10);
        assertTrue(straightReward.components().get("lateral_tracking") > sideDriftReward.components().get("lateral_tracking"));
        assertTrue(straightReward.total() > sideDriftReward.total());
    }

    @Test
    void commandRewardMakesStationaryPolicyWorseThanRealForwardMotion() {
        final DuopodRewardCalculator calculator = new DuopodRewardCalculator(DuopodRewardConfig.DEFAULT);
        final LocomotionCommand walkForward = new LocomotionCommand(0.8, 0.0, 0.0);

        final RewardBreakdown stationary = calculator.commandTracking(commandRewardInput(0.0, 0.0, 0.0, walkForward));
        final RewardBreakdown following = calculator.commandTracking(commandRewardInput(0.8, 0.0, 0.0, walkForward));
        final RewardBreakdown movingBackward = calculator.commandTracking(commandRewardInput(-0.8, 0.0, 0.0, walkForward));

        assertTrue(stationary.total() < 0.0, "doing nothing must not collect most of the available locomotion reward");
        assertTrue(following.total() > stationary.total() + 0.05);
        assertTrue(movingBackward.total() < stationary.total());
        assertTrue(following.components().get("forward_progress") > 0.0);
        assertEquals(0.0, stationary.components().get("forward_progress"), 1.0e-10);
    }

    @Test
    void balanceStandRewardCreditsRecoveryAndPenalizesWorseningFalls() {
        final DuopodRewardCalculator calculator = new DuopodRewardCalculator(DuopodRewardConfig.DEFAULT);
        final DuopodRewardInput upright = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.02, 1.0, Double.NaN, new double[]{0.0, 0.0}, 1.0, 1.0);
        final DuopodRewardInput forwardTilt = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.02, 0.75, Double.NaN, new double[]{0.0, 0.0}, 1.0, 1.0);
        final double previousWorseError = DuopodRewardCalculator.standingBalanceError(forwardTilt);
        final double previousBetterError = DuopodRewardCalculator.standingBalanceError(upright);
        final DuopodRewardInput improving = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.02, 0.90, previousWorseError, new double[]{0.0, 0.0}, 1.0, 1.0);
        final DuopodRewardInput worsening = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.02, 0.65, previousBetterError, new double[]{0.0, 0.0}, 1.0, 1.0);

        assertTrue(DuopodRewardCalculator.standingBalanceError(upright) < DuopodRewardCalculator.standingBalanceError(forwardTilt));
        assertTrue(calculator.balanceStand(improving).components().get("recovery_progress") > 0.0);
        assertTrue(calculator.balanceStand(worsening).components().get("recovery_progress") < 0.0);
        assertTrue(calculator.balanceStand(improving).total() > calculator.balanceStand(worsening).total());
    }

    @Test
    void standingRewardUsesSupportContactUprightMotionActionAndLoad() {
        final DuopodRewardCalculator calculator = new DuopodRewardCalculator(DuopodRewardConfig.DEFAULT);
        final DuopodRewardInput stable = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.0, 1.0, Double.NaN, new double[]{0.0, 0.0}, 1.0, 1.0);
        final DuopodRewardInput highAngularVelocity = balanceRewardInput(0.0, 0.0, 0.0, 1.5, 0.0, 1.0, Double.NaN, new double[]{0.0, 0.0}, 1.0, 1.0);
        final DuopodRewardInput tilted = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.0, 0.1, Double.NaN, new double[]{0.0, 0.0}, 1.0, 1.0);
        final DuopodRewardInput unsupportedCom = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.0, 1.0, Double.NaN, new double[]{0.0, 0.0}, 1.0, 1.0, 0.80, 0.0, 0.0);
        final DuopodRewardInput airborneHoney = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.0, 1.0, Double.NaN, new double[]{0.0, 0.0}, 1.0, 1.0, 0.0, 0.45, 0.45);
        final DuopodRewardInput lowCenterOfMass = balanceRewardInput(
                0.0,
                0.0,
                0.0,
                0.05,
                0.0,
                1.0,
                Double.NaN,
                new double[]{0.0, 0.0},
                1.0,
                1.0,
                0.0,
                0.0,
                0.0,
                2.0,
                2.0,
                -0.75);
        final DuopodRewardInput abruptAction = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.0, 1.0, Double.NaN, new double[]{1.0, -1.0}, 1.0, 1.0);
        final DuopodRewardInput loaded = balanceRewardInput(0.0, 0.0, 0.0, 0.05, 0.0, 1.0, Double.NaN, new double[]{0.0, 0.0}, 90_000.0, 90_000.0);
        final DuopodRewardInput bearingNearFloor = balanceRewardInput(
                0.0,
                0.0,
                0.0,
                0.05,
                0.0,
                1.0,
                Double.NaN,
                new double[]{0.0, 0.0},
                1.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.02,
                2.0);

        final RewardBreakdown stableReward = calculator.balanceStand(stable);
        final RewardBreakdown fastReward = calculator.balanceStand(highAngularVelocity);
        final RewardBreakdown tiltedReward = calculator.balanceStand(tilted);
        final RewardBreakdown unsupportedReward = calculator.balanceStand(unsupportedCom);
        final RewardBreakdown airborneReward = calculator.balanceStand(airborneHoney);
        final RewardBreakdown lowCenterOfMassReward = calculator.balanceStand(lowCenterOfMass);
        final RewardBreakdown abruptReward = calculator.balanceStand(abruptAction);
        final RewardBreakdown loadedReward = calculator.balanceStand(loaded);
        final RewardBreakdown bearingContactReward = calculator.balanceStand(bearingNearFloor);

        assertTrue(stableReward.total() > fastReward.total());
        assertTrue(stableReward.components().get("settled_reward") > fastReward.components().get("settled_reward"));
        assertTrue(fastReward.components().get("angular_motion_penalty") < stableReward.components().get("angular_motion_penalty"));
        assertTrue(stableReward.total() > tiltedReward.total());
        assertTrue(stableReward.components().get("upright_reward") > tiltedReward.components().get("upright_reward"));
        assertTrue(stableReward.total() > unsupportedReward.total());
        assertTrue(stableReward.components().get("support_reward") > unsupportedReward.components().get("support_reward"));
        assertTrue(stableReward.total() > airborneReward.total());
        assertTrue(stableReward.components().get("honey_contact_reward") > airborneReward.components().get("honey_contact_reward"));
        assertTrue(stableReward.total() > lowCenterOfMassReward.total());
        assertTrue(stableReward.components().get("center_of_mass_height_reward") > lowCenterOfMassReward.components().get("center_of_mass_height_reward"));
        assertTrue(abruptReward.components().get("action_rate") > stableReward.components().get("action_rate"));
        assertTrue(abruptReward.components().get("action_rate_penalty") < stableReward.components().get("action_rate_penalty"));
        assertTrue(Double.isFinite(loadedReward.components().get("load_penalty")));
        assertTrue(loadedReward.components().get("load_penalty") < stableReward.components().get("load_penalty"));
        assertTrue(stableReward.total() > bearingContactReward.total());
        assertTrue(bearingContactReward.components().get("servo_ground_risk") > stableReward.components().get("servo_ground_risk"));
        assertTrue(bearingContactReward.components().get("servo_ground_risk_penalty") < stableReward.components().get("servo_ground_risk_penalty"));
        assertFalse(stableReward.components().containsKey("height_reward"));
        assertFalse(stableReward.components().containsKey("servo_down_pose"));
        assertFalse(stableReward.components().containsKey("foot_count_reward"));
        assertFalse(stableReward.components().containsKey("fall_penalty"));
    }

    @Test
    void centerOfMassBalanceRewardUsesHeightStillnessAndConservativeAction() {
        final DuopodRewardCalculator calculator = new DuopodRewardCalculator(DuopodRewardConfig.DEFAULT);
        final DuopodRewardInput highStill = centerOfMassRewardInput(0.60, 0.0, 0.0, 0.0, 0.0);
        final DuopodRewardInput lowStill = centerOfMassRewardInput(-0.40, 0.0, 0.0, 0.0, 0.0);
        final DuopodRewardInput highDrifting = centerOfMassRewardInput(0.60, 0.80, 0.0, 0.0, 0.0);
        final DuopodRewardInput highMoving = centerOfMassRewardInput(0.60, 0.0, 0.0, 1.50, 1.50);
        final DuopodRewardInput neutral = centerOfMassRewardInput(0.0, 0.0, 0.0, 0.0, 0.0);
        final DuopodRewardInput opposingServos = centerOfMassRewardInput(
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                new double[]{1.0, -1.0});
        final DuopodRewardInput invalidMachine = centerOfMassRewardInput(
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                new double[]{0.0, 0.0},
                true);

        final RewardBreakdown highStillReward = calculator.balanceCenterOfMass(highStill);
        final RewardBreakdown lowStillReward = calculator.balanceCenterOfMass(lowStill);
        final RewardBreakdown highDriftingReward = calculator.balanceCenterOfMass(highDrifting);
        final RewardBreakdown highMovingReward = calculator.balanceCenterOfMass(highMoving);
        final RewardBreakdown neutralReward = calculator.balanceCenterOfMass(neutral);
        final RewardBreakdown opposingServoReward = calculator.balanceCenterOfMass(opposingServos);
        final RewardBreakdown invalidMachineReward = calculator.balanceCenterOfMass(invalidMachine);

        assertTrue(highStillReward.total() > lowStillReward.total());
        assertTrue(highStillReward.total() > highDriftingReward.total());
        assertTrue(highStillReward.total() > highMovingReward.total());
        assertTrue(neutralReward.total() > opposingServoReward.total());
        assertTrue(highStillReward.components().get("center_of_mass_height_score") > lowStillReward.components().get("center_of_mass_height_score"));
        assertTrue(highStillReward.components().get("center_of_mass_stillness_reward") > highDriftingReward.components().get("center_of_mass_stillness_reward"));
        assertTrue(opposingServoReward.components().get("action_magnitude") > neutralReward.components().get("action_magnitude"));
        assertTrue(opposingServoReward.components().get("action_disagreement") > neutralReward.components().get("action_disagreement"));
        assertFalse(highStillReward.components().containsKey("support_reward"));
        assertFalse(highStillReward.components().containsKey("honey_contact_reward"));
        assertFalse(highStillReward.components().containsKey("honey_ground_error"));
        assertFalse(highStillReward.components().containsKey("upright_reward"));
        assertFalse(highStillReward.components().containsKey("fall_penalty"));
        assertFalse(invalidMachineReward.components().containsKey("fall_penalty"));
        assertTrue(invalidMachineReward.components().get("machine_failure_penalty") < 0.0);
    }

    @Test
    void protocolEnvelopeAndStepValidationRoundTrip() {
        final BridgeSpecs specs = new BridgeSpecs(
                TrainingBridgeProtocol.PROTOCOL_VERSION,
                "0.1.0",
                "minecraft_machines:duopod",
                DuopodSchemas.observationSpec(),
                DuopodSchemas.actionSpec(),
                2,
                1,
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE,
                99L);
        final StepRequest request = new StepRequest(
                "session",
                specs.observationSpec().compatibilityHash(),
                specs.actionSpec().compatibilityHash(),
                new double[][]{{0.0, 0.5}, {-0.5, 0.0}});
        request.validateAgainst(specs);

        final JsonObject payload = TrainingBridgeProtocol.toJsonTree(request);
        final ProtocolEnvelope envelope = new ProtocolEnvelope(BridgeMessageType.STEP, "r1", payload);
        final byte[] wire = TrainingBridgeProtocol.encodeLengthPrefixed(TrainingBridgeProtocol.encodeEnvelope(envelope), 4096);
        final ProtocolEnvelope decodedEnvelope = TrainingBridgeProtocol.decodeEnvelope(TrainingBridgeProtocol.decodeLengthPrefixed(wire, 4096));
        final StepRequest decoded = TrainingBridgeProtocol.fromJsonTree(decodedEnvelope.payload(), StepRequest.class);

        assertEquals(BridgeMessageType.STEP, decodedEnvelope.type());
        assertEquals("r1", decodedEnvelope.requestId());
        decoded.validateAgainst(specs);

        final StepRequest wrongShape = new StepRequest(
                "session",
                specs.observationSpec().compatibilityHash(),
                specs.actionSpec().compatibilityHash(),
                new double[][]{{0.0}});
        assertThrows(IllegalArgumentException.class, () -> wrongShape.validateAgainst(specs));

        final StepRequest wrongHash = new StepRequest(
                "session",
                "bad",
                specs.actionSpec().compatibilityHash(),
                new double[][]{{0.0, 0.0}, {0.0, 0.0}});
        assertThrows(IllegalArgumentException.class, () -> wrongHash.validateAgainst(specs));

        final StepRequest wrongActionHash = new StepRequest(
                "session",
                specs.observationSpec().compatibilityHash(),
                "bad",
                new double[][]{{0.0, 0.0}, {0.0, 0.0}});
        assertThrows(IllegalArgumentException.class, () -> wrongActionHash.validateAgainst(specs));

        final StepRequest nonFinite = new StepRequest(
                "session",
                specs.observationSpec().compatibilityHash(),
                specs.actionSpec().compatibilityHash(),
                new double[][]{{0.0, Double.NaN}, {0.0, 0.0}});
        assertThrows(IllegalArgumentException.class, () -> nonFinite.validateAgainst(specs));
        assertThrows(IllegalArgumentException.class, () -> new StepRequest(
                "session",
                specs.observationSpec().compatibilityHash(),
                specs.actionSpec().compatibilityHash(),
                null));
        assertThrows(IllegalArgumentException.class, () -> new StepRequest(
                "session",
                specs.observationSpec().compatibilityHash(),
                specs.actionSpec().compatibilityHash(),
                new double[][]{null, {0.0, 0.0}}));
    }

    @Test
    void protocolEnforcesMessageSizeLimitAndStepResponseShape() {
        final BridgeSpecs specs = new BridgeSpecs(
                TrainingBridgeProtocol.PROTOCOL_VERSION,
                "0.1.0",
                "minecraft_machines:duopod",
                DuopodSchemas.observationSpec(),
                DuopodSchemas.actionSpec(),
                2,
                1,
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE,
                99L);
        assertThrows(IllegalArgumentException.class, () -> TrainingBridgeProtocol.encodeLengthPrefixed("too-large", 4));
        final byte[] wire = TrainingBridgeProtocol.encodeLengthPrefixed("{}", 8);
        assertThrows(IllegalArgumentException.class, () -> TrainingBridgeProtocol.decodeLengthPrefixed(wire, 1));

        final StepResponse response = new StepResponse(
                new double[][]{
                        new double[DuopodSchemas.observationSpec().size()],
                        new double[DuopodSchemas.observationSpec().size()]
                },
                new double[]{1.0, -1.0},
                new boolean[]{false, true},
                new boolean[]{false, false},
                List.of(new JsonObject(), new JsonObject()),
                new double[][]{
                        new double[0],
                        new double[DuopodSchemas.observationSpec().size()]
                });
        response.validateAgainst(specs);

        final StepResponse wrongObservationSize = new StepResponse(
                new double[][]{
                        new double[1],
                        new double[DuopodSchemas.observationSpec().size()]
                },
                new double[]{0.0, 0.0},
                new boolean[]{false, false},
                new boolean[]{false, false},
                List.of(new JsonObject(), new JsonObject()),
                new double[][]{new double[0], new double[0]});
        assertThrows(IllegalArgumentException.class, () -> wrongObservationSize.validateAgainst(specs));
        final double[] outOfRangeObservation = new double[DuopodSchemas.observationSpec().size()];
        outOfRangeObservation[0] = DuopodSchemas.observationSpec().fields().get(0).maximum() + 0.25;
        final StepResponse outOfRange = new StepResponse(
                new double[][]{
                        outOfRangeObservation,
                        new double[DuopodSchemas.observationSpec().size()]
                },
                new double[]{0.0, 0.0},
                new boolean[]{false, false},
                new boolean[]{false, false},
                List.of(new JsonObject(), new JsonObject()),
                new double[][]{new double[0], new double[0]});
        assertThrows(IllegalArgumentException.class, () -> outOfRange.validateAgainst(specs));
        final double[] outOfRangeResetObservation = new double[DuopodSchemas.observationSpec().size()];
        outOfRangeResetObservation[1] = DuopodSchemas.observationSpec().fields().get(1).minimum() - 0.25;
        final StepResponse outOfRangeReset = new StepResponse(
                new double[][]{
                        new double[DuopodSchemas.observationSpec().size()],
                        new double[DuopodSchemas.observationSpec().size()]
                },
                new double[]{0.0, 0.0},
                new boolean[]{true, false},
                new boolean[]{false, false},
                List.of(new JsonObject(), new JsonObject()),
                new double[][]{outOfRangeResetObservation, new double[0]});
        assertThrows(IllegalArgumentException.class, () -> outOfRangeReset.validateAgainst(specs));
        assertThrows(IllegalArgumentException.class, () -> new StepResponse(
                null,
                new double[]{0.0, 0.0},
                new boolean[]{false, false},
                new boolean[]{false, false},
                List.of(new JsonObject(), new JsonObject()),
                new double[][]{new double[0], new double[0]}));
        assertThrows(IllegalArgumentException.class, () -> new StepResponse(
                new double[][]{new double[DuopodSchemas.observationSpec().size()], null},
                new double[]{0.0, 0.0},
                new boolean[]{false, false},
                new boolean[]{false, false},
                List.of(new JsonObject(), new JsonObject()),
                new double[][]{new double[0], new double[0]}));
        assertThrows(IllegalArgumentException.class, () -> new StepResponse(
                new double[][]{new double[]{Double.NaN}, new double[]{0.0}},
                new double[]{0.0, 0.0},
                new boolean[]{false, false},
                new boolean[]{false, false},
                List.of(new JsonObject(), new JsonObject()),
                new double[][]{new double[0], new double[0]}));
        assertThrows(IllegalArgumentException.class, () -> new StepResponse(
                new double[][]{new double[DuopodSchemas.observationSpec().size()], new double[DuopodSchemas.observationSpec().size()]},
                new double[]{Double.NaN, 0.0},
                new boolean[]{false, false},
                new boolean[]{false, false},
                List.of(new JsonObject(), new JsonObject()),
                new double[][]{new double[0], new double[0]}));
    }

    @Test
    void protocolEnvelopeRoundTripsEveryMessageType() {
        for (final BridgeMessageType type : BridgeMessageType.values()) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("message", type.name());
            final ProtocolEnvelope envelope = new ProtocolEnvelope(type, "req-" + type.name(), payload);
            final byte[] wire = TrainingBridgeProtocol.encodeLengthPrefixed(
                    TrainingBridgeProtocol.encodeEnvelope(envelope),
                    TrainingBridgeProtocol.DEFAULT_MAX_MESSAGE_BYTES);
            final ProtocolEnvelope decoded = TrainingBridgeProtocol.decodeEnvelope(
                    TrainingBridgeProtocol.decodeLengthPrefixed(wire, TrainingBridgeProtocol.DEFAULT_MAX_MESSAGE_BYTES));

            assertEquals(type, decoded.type());
            assertEquals(envelope.requestId(), decoded.requestId());
            assertEquals(type.name(), decoded.payload().get("message").getAsString());
        }

        assertThrows(IllegalArgumentException.class, () -> new ProtocolEnvelope(BridgeMessageType.PING, "", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolEnvelope(BridgeMessageType.PING, " ", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolEnvelope(null, "req", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> TrainingBridgeProtocol.decodeEnvelope("[]"));
        assertThrows(IllegalArgumentException.class, () -> TrainingBridgeProtocol.decodeEnvelope("{\"requestId\":\"req\",\"payload\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> TrainingBridgeProtocol.decodeEnvelope("{\"type\":\"NOPE\",\"requestId\":\"req\",\"payload\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> TrainingBridgeProtocol.decodeEnvelope("{\"type\":\"PING\",\"payload\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> TrainingBridgeProtocol.decodeEnvelope("{\"type\":\"PING\",\"requestId\":\"req\",\"payload\":[]}"));
    }

    @Test
    void sharedDuopodFlatCommandScenariosCoverForwardAndYaw() {
        final LocomotionCommand straight = DuopodTrainingScenarios.flatCommand(0);
        final LocomotionCommand right = DuopodTrainingScenarios.flatCommand(1);
        final LocomotionCommand left = DuopodTrainingScenarios.flatCommand(2);
        final LocomotionCommand rotateRight = DuopodTrainingScenarios.flatCommand(3);
        final LocomotionCommand rotateLeft = DuopodTrainingScenarios.flatCommand(4);

        assertTrue(straight.desiredForwardVelocity() > 0.0);
        assertEquals(0.0, straight.desiredYawRate(), 1.0e-10);
        assertTrue(right.desiredYawRate() > 0.0);
        assertTrue(left.desiredYawRate() < 0.0);
        assertTrue(rotateRight.desiredYawRate() > 0.0);
        assertTrue(rotateLeft.desiredYawRate() < 0.0);
        assertEquals(DuopodTrainingScenarios.flatCommand(0), DuopodTrainingScenarios.flatCommand(DuopodTrainingScenarios.FLAT_COMMAND_SCENARIO_COUNT));
    }

    @Test
    void duopodFlatCommandEpisodesUseNormalMinecraftTerrain() {
        final var episode = DuopodTrainingScenarios.flatCommandEpisode(1L, 2L, 0, 10, 0);
        final TerrainProfile profile = episode.terrainProfile();

        assertEquals(DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE, episode.curriculumStage().id());
        assertFalse(profile.enabled());
        assertEquals(TerrainProfile.NONE_ID, profile.generatorId());
        assertEquals(1, profile.widthBlocks());
        assertEquals(1, profile.lengthBlocks());
    }

    @Test
    void legacyDuopodCommandStagesNormalizeToMinecraftTerrainCommands() {
        final var episode = DuopodTrainingScenarios.commandEpisode(
                DuopodTrainingScenarios.LOW_BUMPS_COMMANDS_STAGE,
                1L,
                2L,
                0,
                10,
                0);
        final TerrainProfile profile = episode.terrainProfile();

        assertEquals(DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE, episode.curriculumStage().id());
        assertFalse(profile.enabled());
        assertEquals(TerrainProfile.NONE_ID, profile.generatorId());
        assertTrue(DuopodTrainingScenarios.isCommandCurriculumStage(DuopodTrainingScenarios.LOW_BUMPS_COMMANDS_STAGE));
        assertEquals(
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE,
                DuopodTrainingScenarios.normalizeCurriculumStage(DuopodTrainingScenarios.LOW_BUMPS_COMMANDS_STAGE));
        assertFalse(DuopodTrainingScenarios.isTrainingCurriculumStage(""));
        assertTrue(DuopodTrainingScenarios.isSupportedBridgeCurriculumStage(DuopodTrainingScenarios.MANUAL_COMMANDS_STAGE));
        assertThrows(IllegalArgumentException.class, () -> DuopodTrainingScenarios.commandEpisode("unknown", 1L, 2L, 0, 10, 0));
    }

    @Test
    void allCurrentDuopodStageLabelsUseNoGeneratedTrainingTerrain() {
        for (final String stage : List.of(
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE,
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE,
                DuopodTrainingScenarios.LOW_BUMPS_COMMANDS_STAGE,
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE,
                DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE,
                DuopodTrainingScenarios.WALK_FORWARD_STAGE,
                DuopodTrainingScenarios.BALANCE_STAND_STAGE,
                DuopodTrainingScenarios.BALANCE_CENTER_OF_MASS_STAGE,
                DuopodTrainingScenarios.MANUAL_COMMANDS_STAGE,
                DuopodTrainingScenarios.MANUAL_POINT_GOALS_STAGE
        )) {
            final TerrainProfile profile = DuopodTrainingScenarios.terrainProfileForStage(stage);

            assertFalse(profile.enabled(), stage);
            assertEquals(TerrainProfile.NONE_ID, profile.generatorId(), stage);
        }
    }

    @Test
    void duopodPointGoalEpisodesOwnLocalTargetAndUseNormalMinecraftTerrain() {
        final var episode = DuopodTrainingScenarios.flatPointGoalEpisode(1L, 2L, 0, 10, 2);
        final PointTargetDefinition target = episode.pointTarget();

        assertEquals(DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE, episode.curriculumStage().id());
        assertEquals(EnvironmentTaskMode.POINT_GOAL, episode.taskMode());
        assertTrue(target.enabled());
        assertEquals(target.initialDistanceBlocks(), episode.initialDistanceToTarget(), 1.0e-10);
        assertEquals(PointTargetCommandConfig.DEFAULT.successRadius(), target.successRadiusBlocks(), 1.0e-10);
        assertFalse(episode.terrainProfile().enabled());
        assertEquals(TerrainProfile.NONE_ID, episode.terrainProfile().generatorId());
        assertTrue(DuopodTrainingScenarios.isTrainingCurriculumStage(DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE));
        assertTrue(DuopodTrainingScenarios.isTrainingCurriculumStage(DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE));
        assertFalse(DuopodTrainingScenarios.isCommandCurriculumStage(DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeDefinition(
                1L,
                2L,
                EnvironmentTaskMode.POINT_GOAL,
                new CurriculumStage("bad", 0, "missing target"),
                LocomotionCommand.ZERO,
                10,
                0.0,
                1.0));
    }

    @Test
    void duopodBalanceStandEpisodeUsesBalanceTaskAndNormalMinecraftTerrain() {
        final var episode = DuopodTrainingScenarios.episodeForStage(
                DuopodTrainingScenarios.BALANCE_STAND_STAGE,
                1L,
                2L,
                0,
                10,
                0);

        assertEquals(EnvironmentTaskMode.BALANCE, episode.taskMode());
        assertEquals(LocomotionCommand.ZERO, episode.command());
        assertFalse(episode.pointTarget().enabled());
        assertEquals(0.0, episode.initialDistanceToTarget(), 1.0e-10);
        assertFalse(episode.terrainProfile().enabled());
        assertEquals(TerrainProfile.NONE_ID, episode.terrainProfile().generatorId());
        assertTrue(DuopodTrainingScenarios.isTrainingCurriculumStage(DuopodTrainingScenarios.BALANCE_STAND_STAGE));
        assertTrue(DuopodTrainingScenarios.isBalanceCurriculumStage(DuopodTrainingScenarios.BALANCE_STAND_STAGE));
        assertFalse(DuopodTrainingScenarios.isCommandCurriculumStage(DuopodTrainingScenarios.BALANCE_STAND_STAGE));
        assertFalse(DuopodTrainingScenarios.isPointGoalCurriculumStage(DuopodTrainingScenarios.BALANCE_STAND_STAGE));
        assertEquals("near_neutral", episode.standingDisturbance().scenarioId());
    }

    @Test
    void duopodCenterOfMassBalanceEpisodeUsesBalanceTaskAndAliases() {
        final var episode = DuopodTrainingScenarios.episodeForStage(
                "balance_centreofmass",
                1L,
                2L,
                0,
                10,
                0);

        assertEquals(DuopodTrainingScenarios.BALANCE_CENTER_OF_MASS_STAGE, episode.curriculumStage().id());
        assertEquals(EnvironmentTaskMode.BALANCE, episode.taskMode());
        assertEquals(LocomotionCommand.ZERO, episode.command());
        assertFalse(episode.pointTarget().enabled());
        assertFalse(episode.terrainProfile().enabled());
        assertTrue(DuopodTrainingScenarios.isTrainingCurriculumStage(DuopodTrainingScenarios.BALANCE_CENTER_OF_MASS_STAGE));
        assertTrue(DuopodTrainingScenarios.isBalanceCurriculumStage(DuopodTrainingScenarios.BALANCE_CENTER_OF_MASS_STAGE));
        assertTrue(episode.curriculumStage().description().contains("center-of-mass height/stillness"));
    }

    @Test
    void duopodWalkForwardEpisodeIsSimpleCommandTrackingSanityCheck() {
        final var slow = DuopodTrainingScenarios.episodeForStage(
                DuopodTrainingScenarios.WALK_FORWARD_STAGE,
                1L,
                2L,
                0,
                10,
                0);
        final var medium = DuopodTrainingScenarios.episodeForStage(
                DuopodTrainingScenarios.WALK_FORWARD_STAGE,
                2L,
                3L,
                0,
                10,
                1);
        final var fast = DuopodTrainingScenarios.episodeForStage(
                DuopodTrainingScenarios.WALK_FORWARD_STAGE,
                3L,
                4L,
                0,
                10,
                2);

        assertEquals(EnvironmentTaskMode.COMMAND_TRACKING, slow.taskMode());
        assertEquals(DuopodTrainingScenarios.WALK_FORWARD_STAGE, slow.curriculumStage().id());
        assertFalse(slow.pointTarget().enabled());
        assertFalse(slow.terrainProfile().enabled());
        assertTrue(DuopodTrainingScenarios.isTrainingCurriculumStage(DuopodTrainingScenarios.WALK_FORWARD_STAGE));
        assertTrue(DuopodTrainingScenarios.isCommandCurriculumStage(DuopodTrainingScenarios.WALK_FORWARD_STAGE));
        assertFalse(DuopodTrainingScenarios.isBalanceCurriculumStage(DuopodTrainingScenarios.WALK_FORWARD_STAGE));
        assertEquals(0.0, slow.command().desiredYawRate(), 1.0e-10);
        assertEquals(0.0, medium.command().desiredYawRate(), 1.0e-10);
        assertEquals(0.0, fast.command().desiredYawRate(), 1.0e-10);
        assertTrue(slow.command().desiredForwardVelocity() > 0.0);
        assertTrue(slow.command().desiredForwardVelocity() < medium.command().desiredForwardVelocity());
        assertTrue(medium.command().desiredForwardVelocity() < fast.command().desiredForwardVelocity());
    }

    @Test
    void duopodPointGoalTrainingScenariosCoverHeldOutBearingsAndRollAcrossGenerations() {
        assertEquals(14, DuopodTrainingScenarios.POINT_GOAL_SCENARIO_COUNT);

        final PointTargetDefinition first = DuopodTrainingScenarios.pointGoalTarget(0);
        final PointTargetDefinition straight = DuopodTrainingScenarios.pointGoalTarget(3);
        final PointTargetDefinition lastNear = DuopodTrainingScenarios.pointGoalTarget(6);
        final PointTargetDefinition firstFar = DuopodTrainingScenarios.pointGoalTarget(7);

        assertEquals(4.0, first.initialDistanceBlocks(), 1.0e-10);
        assertEquals(0.0, first.localForwardBlocks(), 1.0e-10);
        assertEquals(-4.0, first.localRightBlocks(), 1.0e-10);
        assertEquals(4.0, straight.localForwardBlocks(), 1.0e-10);
        assertEquals(0.0, straight.localRightBlocks(), 1.0e-10);
        assertEquals(0.0, lastNear.localForwardBlocks(), 1.0e-10);
        assertEquals(4.0, lastNear.localRightBlocks(), 1.0e-10);
        assertEquals(8.0, firstFar.initialDistanceBlocks(), 1.0e-10);

        assertEquals(0, DuopodTrainingScenarios.rollingPointGoalScenario(0, 0, 6));
        assertEquals(5, DuopodTrainingScenarios.rollingPointGoalScenario(0, 5, 6));
        assertEquals(6, DuopodTrainingScenarios.rollingPointGoalScenario(1, 0, 6));
        assertEquals(11, DuopodTrainingScenarios.rollingPointGoalScenario(1, 5, 6));
        assertEquals(12, DuopodTrainingScenarios.rollingPointGoalScenario(2, 0, 6));
        assertEquals(4, DuopodTrainingScenarios.rollingPointGoalScenario(3, 0, 6));
    }

    @Test
    void undersampledCommandAndBalanceCurriculaRotateAcrossAllScenarios() {
        assertEquals(5, DuopodTrainingScenarios.scenarioCount(DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE));
        assertEquals(8, DuopodTrainingScenarios.scenarioCount(DuopodTrainingScenarios.BALANCE_STAND_STAGE));
        assertEquals(0, DuopodTrainingScenarios.rollingTrainingScenario(
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE, 0, 0, 3));
        assertEquals(3, DuopodTrainingScenarios.rollingTrainingScenario(
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE, 1, 0, 3));
        assertEquals(1, DuopodTrainingScenarios.rollingTrainingScenario(
                DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE, 2, 0, 3));
        assertEquals(6, DuopodTrainingScenarios.rollingTrainingScenario(
                DuopodTrainingScenarios.BALANCE_STAND_STAGE, 2, 0, 3));
    }

    @Test
    void duopodHeldOutEvaluationManifestIsDeterministic() {
        final var manifest = DuopodEvaluationManifests.heldOutPointGoals();

        assertEquals("duopod_held_out_point_goals_v3", manifest.id());
        assertEquals(21, manifest.scenarios().size());
        assertEquals("bearing_m075_distance_06", manifest.scenarios().getFirst().id());
        assertEquals("bearing_p075_distance_12", manifest.scenarios().getLast().id());
        assertEquals(6.0, manifest.scenarios().getFirst().pointTarget().initialDistanceBlocks(), 1.0e-10);
        assertEquals(DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE, manifest.scenarios().getFirst().terrainStage());
        assertEquals(DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE, manifest.scenarios().getLast().terrainStage());
        assertEquals("b3d9ccc73651bfe943aada11d30e9116b80cead6fc3270006c275520f06bd09f", manifest.compatibilityHash());
        assertEquals("duopod_planar_evaluation_v1", DuopodEvaluationManifests.EVALUATION_CONTRACT_ID);
        assertEquals(1, DuopodEvaluationManifests.EVALUATION_CONTRACT_VERSION);
    }

    private static DuopodRewardInput rewardInput(
            final double previousDistance,
            final double currentDistance,
            final double[] previousAction,
            final double[] currentAction
    ) {
        return rewardInput(previousDistance, currentDistance, previousAction, currentAction, false);
    }

    private static DuopodRewardInput rewardInput(
            final double previousDistance,
            final double currentDistance,
            final double[] previousAction,
            final double[] currentAction,
            final boolean targetReached
    ) {
        return rewardInput(previousDistance, currentDistance, previousAction, currentAction, targetReached, 0.0, 0.0);
    }

    private static DuopodRewardInput rewardInput(
            final double previousDistance,
            final double currentDistance,
            final double[] previousAction,
            final double[] currentAction,
            final boolean targetReached,
            final double verticalVelocity,
            final double heightAboveSpawn
    ) {
        return new DuopodRewardInput(
                new LocomotionCommand(1.0, 0.0, 0.0),
                1.0,
                0.0,
                verticalVelocity,
                0.0,
                0.0,
                0.0,
                heightAboveSpawn,
                0.0,
                0.0,
                0.0,
                0.0,
                2.0,
                2.0,
                1.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                DuopodRewardCalculator.STANDING.targetStandingHeightAboveSpawnBlocks(),
                Double.NaN,
                0.05,
                previousDistance,
                currentDistance,
                previousAction,
                currentAction,
                targetReached,
                false);
    }

    private static DuopodRewardInput commandRewardInput(
            final double forwardVelocity,
            final double lateralVelocity,
            final double yawRate,
            final LocomotionCommand command
    ) {
        return new DuopodRewardInput(
                command,
                forwardVelocity,
                lateralVelocity,
                0.0,
                0.0,
                0.0,
                yawRate,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                2.0,
                2.0,
                1.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                DuopodRewardCalculator.STANDING.targetStandingHeightAboveSpawnBlocks(),
                Double.NaN,
                0.05,
                0.0,
                0.0,
                new double[]{0.0, 0.0},
                new double[]{0.0, 0.0},
                false,
                false);
    }

    private static DuopodRewardInput balanceRewardInput(
            final double forwardVelocity,
            final double lateralVelocity,
            final double verticalVelocity,
            final double rollRate,
            final double heightAboveSpawn,
            final double bodyUpDot,
            final double previousBalanceError,
            final double[] currentAction,
            final double leftServoLoad,
            final double rightServoLoad
    ) {
        return balanceRewardInput(
                forwardVelocity,
                lateralVelocity,
                verticalVelocity,
                rollRate,
                heightAboveSpawn,
                bodyUpDot,
                previousBalanceError,
                currentAction,
                leftServoLoad,
                rightServoLoad,
                0.0,
                0.0,
                0.0);
    }

    private static DuopodRewardInput balanceRewardInput(
            final double forwardVelocity,
            final double lateralVelocity,
            final double verticalVelocity,
            final double rollRate,
            final double heightAboveSpawn,
            final double bodyUpDot,
            final double previousBalanceError,
            final double[] currentAction,
            final double leftServoLoad,
            final double rightServoLoad,
            final double supportDistanceBlocks,
            final double leftHoneyGroundClearance,
            final double rightHoneyGroundClearance
    ) {
        return balanceRewardInput(
                forwardVelocity,
                lateralVelocity,
                verticalVelocity,
                rollRate,
                heightAboveSpawn,
                bodyUpDot,
                previousBalanceError,
                currentAction,
                leftServoLoad,
                rightServoLoad,
                supportDistanceBlocks,
                leftHoneyGroundClearance,
                rightHoneyGroundClearance,
                2.0,
                2.0,
                0.0);
    }

    private static DuopodRewardInput balanceRewardInput(
            final double forwardVelocity,
            final double lateralVelocity,
            final double verticalVelocity,
            final double rollRate,
            final double heightAboveSpawn,
            final double bodyUpDot,
            final double previousBalanceError,
            final double[] currentAction,
            final double leftServoLoad,
            final double rightServoLoad,
            final double supportDistanceBlocks,
            final double leftHoneyGroundClearance,
            final double rightHoneyGroundClearance,
            final double leftServoGroundClearance,
            final double rightServoGroundClearance
    ) {
        return balanceRewardInput(
                forwardVelocity,
                lateralVelocity,
                verticalVelocity,
                rollRate,
                heightAboveSpawn,
                bodyUpDot,
                previousBalanceError,
                currentAction,
                leftServoLoad,
                rightServoLoad,
                supportDistanceBlocks,
                leftHoneyGroundClearance,
                rightHoneyGroundClearance,
                leftServoGroundClearance,
                rightServoGroundClearance,
                0.0);
    }

    private static DuopodRewardInput balanceRewardInput(
            final double forwardVelocity,
            final double lateralVelocity,
            final double verticalVelocity,
            final double rollRate,
            final double heightAboveSpawn,
            final double bodyUpDot,
            final double previousBalanceError,
            final double[] currentAction,
            final double leftServoLoad,
            final double rightServoLoad,
            final double supportDistanceBlocks,
            final double leftHoneyGroundClearance,
            final double rightHoneyGroundClearance,
            final double leftServoGroundClearance,
            final double rightServoGroundClearance,
            final double centerOfMassHeightDeltaBlocks
    ) {
        return new DuopodRewardInput(
                LocomotionCommand.ZERO,
                forwardVelocity,
                lateralVelocity,
                verticalVelocity,
                rollRate,
                rollRate,
                0.0,
                heightAboveSpawn,
                0.0,
                0.0,
                leftHoneyGroundClearance,
                rightHoneyGroundClearance,
                leftServoGroundClearance,
                rightServoGroundClearance,
                leftServoLoad,
                rightServoLoad,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                bodyUpDot,
                supportDistanceBlocks,
                supportDistanceBlocks,
                0.0,
                centerOfMassHeightDeltaBlocks,
                0.0,
                0.0,
                DuopodRewardCalculator.STANDING.targetStandingHeightAboveSpawnBlocks(),
                previousBalanceError,
                0.05,
                0.0,
                0.0,
                new double[]{0.0, 0.0},
                currentAction,
                false,
                false);
    }

    private static DuopodRewardInput centerOfMassRewardInput(
            final double centerOfMassHeightDeltaBlocks,
            final double centerOfMassForwardDriftBlocks,
            final double centerOfMassLateralDriftBlocks,
            final double linearVelocity,
            final double rollPitchRate
    ) {
        return centerOfMassRewardInput(
                centerOfMassHeightDeltaBlocks,
                centerOfMassForwardDriftBlocks,
                centerOfMassLateralDriftBlocks,
                linearVelocity,
                rollPitchRate,
                new double[]{0.0, 0.0});
    }

    private static DuopodRewardInput centerOfMassRewardInput(
            final double centerOfMassHeightDeltaBlocks,
            final double centerOfMassForwardDriftBlocks,
            final double centerOfMassLateralDriftBlocks,
            final double linearVelocity,
            final double rollPitchRate,
            final double[] currentAction
    ) {
        return centerOfMassRewardInput(
                centerOfMassHeightDeltaBlocks,
                centerOfMassForwardDriftBlocks,
                centerOfMassLateralDriftBlocks,
                linearVelocity,
                rollPitchRate,
                currentAction,
                false);
    }

    private static DuopodRewardInput centerOfMassRewardInput(
            final double centerOfMassHeightDeltaBlocks,
            final double centerOfMassForwardDriftBlocks,
            final double centerOfMassLateralDriftBlocks,
            final double linearVelocity,
            final double rollPitchRate,
            final double[] currentAction,
            final boolean machineFailure
    ) {
        return new DuopodRewardInput(
                LocomotionCommand.ZERO,
                linearVelocity,
                linearVelocity,
                linearVelocity,
                rollPitchRate,
                rollPitchRate,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                1.0,
                2.0,
                2.0,
                1.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                1.0,
                1.0,
                0.0,
                centerOfMassHeightDeltaBlocks,
                centerOfMassForwardDriftBlocks,
                centerOfMassLateralDriftBlocks,
                DuopodRewardCalculator.STANDING.targetStandingHeightAboveSpawnBlocks(),
                Double.NaN,
                0.05,
                0.0,
                0.0,
                new double[]{0.0, 0.0},
                currentAction,
                false,
                machineFailure);
    }

    private static ServoTelemetrySample sample(
            final String name,
            final double targetAngleDegrees,
            final double actualAngleDegrees,
            final double angleErrorDegrees,
            final double angularVelocityRadPerSecond,
            final double generatedSpeedRpm,
            final double estimatedTorque,
            final double jointLoad,
            final double minimumAngleDegrees,
            final double maximumAngleDegrees,
            final double maximumTorque,
            final boolean enabled,
            final boolean attached,
            final boolean validConstraint,
            final double previousAction
    ) {
        return new ServoTelemetrySample(
                name,
                targetAngleDegrees,
                actualAngleDegrees,
                angleErrorDegrees,
                angularVelocityRadPerSecond,
                generatedSpeedRpm,
                estimatedTorque,
                jointLoad,
                minimumAngleDegrees,
                maximumAngleDegrees,
                maximumTorque,
                enabled,
                attached,
                validConstraint,
                previousAction);
    }

    private static DuopodObservation standingObservation(
            final double projectedGravityForward,
            final double localPitchRate,
            final double standingHeightError
    ) {
        return standingObservation(projectedGravityForward, localPitchRate, standingHeightError, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    private static DuopodObservation standingObservation(
            final double projectedGravityForward,
            final double localPitchRate,
            final double standingHeightError,
            final double centerOfMassHeightDelta,
            final double centerOfMassForwardDrift,
            final double centerOfMassLateralDrift,
            final double supportComForwardError,
            final double supportComLateralError
    ) {
        return DuopodObservationEncoder.encode(new DuopodObservationInput(
                0.0,
                LocomotionCommand.ZERO,
                0.0,
                0.0,
                0.0,
                0.0,
                localPitchRate,
                0.0,
                projectedGravityForward,
                0.0,
                standingHeightError,
                centerOfMassHeightDelta,
                centerOfMassForwardDrift,
                centerOfMassLateralDrift,
                supportComForwardError,
                supportComLateralError,
                sample("left", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -85.0, 85.0, 1_000.0, true, true, true, 0.0),
                sample("right", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -85.0, 85.0, 1_000.0, true, true, true, 0.0)
        ));
    }

    private static double[] phaseGaitObservation(final double phaseRad, final double desiredForwardVelocity) {
        return DuopodObservationEncoder.encode(new DuopodObservationInput(
                phaseRad,
                new LocomotionCommand(desiredForwardVelocity, 0.0, 0.0),
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                sample("left", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -85.0, 85.0, 1_000.0, true, true, true, 0.0),
                sample("right", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -85.0, 85.0, 1_000.0, true, true, true, 0.0)
        )).values();
    }

    private static int observationIndex(final String name) {
        final List<String> names = DuopodSchemas.observationSpec().fields().stream()
                .map(field -> field.name())
                .toList();
        return names.indexOf(name);
    }
}
