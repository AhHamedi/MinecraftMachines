package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlock;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ResetStrategy;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoTelemetrySample;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.BridgeMessageType;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.ProtocolEnvelope;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.StepRequest;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.StepResponse;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.TrainingBridgeProtocol;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.ContinuousCemDistribution;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.LinearTanhPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.ScoredGenome;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchReset;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchStep;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.LocomotionVectorEnvironment;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.TrainingMachineCollisionRegistry;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodEvaluationManifest;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodEvaluationScenario;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodKinematics;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodPhaseGaitPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodSchemas;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodTrainingScenarios;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodWalkForwardBenchmarkAcceptance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodWalkForwardFitness;
import dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile;
import dev.ahmedhamedi.minecraft_machines.content.worm.WormCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGeneratorFactory;

@GameTestHolder(MinecraftMachines.MOD_ID)
@PrefixGameTestTemplate(false)
public class MinecraftMachinesDuopodGameTests {
    private static final BlockPos DUOPOD_CENTER = new BlockPos(5, 4, 5);
    private static final Direction DUOPOD_FORWARD = Direction.NORTH;
    private static final double EXPECTED_STANDING_SPAWN_CLEARANCE_BLOCKS = 0.08;

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void walkForwardBenchmarkPairsEveryControllerByLaneEpisodeAndSeed(final GameTestHelper helper) {
        final Set<Integer> artifactSlots = new LinkedHashSet<>();
        for (int lane = 0; lane < DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.size(); lane++) {
            final long episodeId = MinecraftMachinesDuopodWalkForwardBenchmark.pairedEpisodeId(lane, 0L);
            final long seed = MinecraftMachinesDuopodWalkForwardBenchmark.pairedEpisodeSeed(lane);
            for (int controller = 0; controller < DuopodWalkForwardBenchmarkAcceptance.CONTROLLERS.size(); controller++) {
                final int artifactSlot = MinecraftMachinesDuopodWalkForwardBenchmark.pairedEpisodeSlot(controller, lane);
                if (!artifactSlots.add(artifactSlot)) {
                    throw new GameTestAssertException("Expected a unique artifact slot for controller="
                            + controller + " lane=" + lane);
                }
                if (artifactSlot % DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.size() != lane) {
                    throw new GameTestAssertException("Expected artifact slot " + artifactSlot
                            + " to preserve physical lane " + lane);
                }
                if (MinecraftMachinesDuopodWalkForwardBenchmark.pairedEpisodeId(lane, 0L) != episodeId
                        || MinecraftMachinesDuopodWalkForwardBenchmark.pairedEpisodeSeed(lane) != seed) {
                    throw new GameTestAssertException("Expected all controller batches to reuse the exact episode identity and seed for lane "
                            + lane);
                }
            }
        }
        final int expectedEpisodes = DuopodWalkForwardBenchmarkAcceptance.CONTROLLERS.size()
                * DuopodWalkForwardBenchmarkAcceptance.FORWARD_SPEEDS.size();
        if (artifactSlots.size() != expectedEpisodes) {
            throw new GameTestAssertException("Expected " + expectedEpisodes
                    + " paired benchmark artifact slots, got " + artifactSlots.size());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void walkForwardBenchmarkCanonicalHashAndActivationDeadlineAreStable(final GameTestHelper helper) {
        final String zeroGenomeHash = MinecraftMachinesDuopodWalkForwardBenchmark.canonicalGenomeSha256(
                new double[]{0.0});
        if (!"af5570f5a1810b7af78caf4bc70a660f0df51e42baf91d4de5b2328de0e83dfc"
                .equals(zeroGenomeHash)) {
            throw new GameTestAssertException("Expected canonical big-endian binary64 SHA-256, got "
                    + zeroGenomeHash);
        }
        if (MinecraftMachinesDuopodWalkForwardBenchmark.chunkActivationTimedOut(100L, 299L)) {
            throw new GameTestAssertException("Expected chunk activation to remain live before its 200-tick deadline");
        }
        if (!MinecraftMachinesDuopodWalkForwardBenchmark.chunkActivationTimedOut(100L, 300L)) {
            throw new GameTestAssertException("Expected chunk activation to time out at its 200-tick deadline");
        }
        if (MinecraftMachinesDuopodWalkForwardBenchmark.chunkActivationTimedOut(100L, 99L)) {
            throw new GameTestAssertException("Expected a rewound game clock not to trigger activation timeout");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void walkForwardCheckpointMetadataMatchesPhaseGaitDistribution(final GameTestHelper helper) {
        final long seed = 2026080301L;
        final JsonObject config = MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                .withPopulationSize(32)
                .withMaximumGenerations(12)
                .withEpisodeTicks(800)
                .withControlTicks(4)
                .withSpacingBlocks(12)
                .withMaxConcurrentSlots(24)
                .withCurriculumStage(DuopodTrainingScenarios.WALK_FORWARD_STAGE)
                .toJson();
        final JsonObject cem = config.getAsJsonObject("cemSettings");
        if (!"phase_gait_genome".equals(cem.get("parameterization").getAsString())
                || Math.abs(cem.get("initialParameterStd").getAsDouble() - 0.85) > 1.0e-12
                || !"all_phase_gait_parameters".equals(cem.get("initialParameterStdScope").getAsString())
                || Math.abs(cem.get("minimumParameterStd").getAsDouble() - 0.12) > 1.0e-12
                || Math.abs(cem.get("initialWeightStd").getAsDouble() - 0.85) > 1.0e-12
                || Math.abs(cem.get("minimumStd").getAsDouble() - 0.12) > 1.0e-12
                || cem.get("biasParametersPresent").getAsBoolean()
                || cem.get("legacyWeightAndBiasFieldsApply").getAsBoolean()) {
            throw new GameTestAssertException(
                    "Expected checkpoint metadata to report the phase-gait distribution actually sampled: " + cem);
        }
        final ContinuousCemDistribution distribution = MinecraftMachinesDuopodCemTrainer.initialDistribution(
                new DuopodPhaseGaitPolicy(),
                seed,
                MinecraftMachinesDuopodCemTrainer.Config.fromJsonOrDefault(config),
                true);
        if (distribution.seed() != seed) {
            throw new GameTestAssertException("Expected the explicit seed to reach the fresh CEM distribution");
        }
        for (int i = 0; i < distribution.standardDeviation().length; i++) {
            if (Math.abs(distribution.standardDeviation()[i] - cem.get("initialParameterStd").getAsDouble()) > 1.0e-12
                    || Math.abs(distribution.minimumStandardDeviation()[i]
                    - cem.get("minimumParameterStd").getAsDouble()) > 1.0e-12) {
                throw new GameTestAssertException(
                        "Expected recorded phase-gait optimizer metadata to match distribution element " + i);
            }
        }
        final JsonObject linearCem = MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                .withCurriculumStage(DuopodTrainingScenarios.FLAT_COMMANDS_STAGE)
                .toJson()
                .getAsJsonObject("cemSettings");
        if (!"linear_tanh_weights_and_biases".equals(linearCem.get("parameterization").getAsString())
                || Math.abs(linearCem.get("initialWeightStd").getAsDouble() - 0.18) > 1.0e-12
                || Math.abs(linearCem.get("initialBiasStd").getAsDouble() - 0.45) > 1.0e-12
                || Math.abs(linearCem.get("minimumParameterStd").getAsDouble() - 0.02) > 1.0e-12
                || !"weight_parameters_only_bias_uses_initialBiasStd".equals(
                linearCem.get("initialParameterStdScope").getAsString())
                || !linearCem.get("biasParametersPresent").getAsBoolean()
                || !linearCem.get("legacyWeightAndBiasFieldsApply").getAsBoolean()) {
            throw new GameTestAssertException("Expected linear CEM metadata to distinguish weight and bias scales");
        }
        final JsonObject balanceCem = MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                .withCurriculumStage(DuopodTrainingScenarios.BALANCE_STAND_STAGE)
                .toJson()
                .getAsJsonObject("cemSettings");
        if (Math.abs(balanceCem.get("initialWeightStd").getAsDouble() - 0.18) > 1.0e-12
                || Math.abs(balanceCem.get("initialBiasStd").getAsDouble() - 0.45) > 1.0e-12
                || Math.abs(balanceCem.get("minimumParameterStd").getAsDouble() - 0.06) > 1.0e-12) {
            throw new GameTestAssertException("Expected balance CEM metadata to report its wider minimum scale");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void walkForwardBenchmarkPhysicsTickPoseSamplesRetainIntermediateExtrema(final GameTestHelper helper) {
        final double postWarmupBaseY = 10.0;
        double minimumBodyUp = Double.POSITIVE_INFINITY;
        double peakVerticalExcursion = 0.0;
        boolean arenaEscape = false;

        minimumBodyUp = MinecraftMachinesDuopodWalkForwardBenchmark.accumulateMinimumBodyUp(
                minimumBodyUp,
                0.98);
        peakVerticalExcursion = MinecraftMachinesDuopodWalkForwardBenchmark.accumulatePeakVerticalExcursion(
                peakVerticalExcursion,
                postWarmupBaseY,
                10.2);
        arenaEscape |= MinecraftMachinesDuopodWalkForwardBenchmark.outsideArena(
                2.0,
                0.2,
                -8.0,
                57.0,
                4.0);

        // This unsafe pose exists only between two nominal 5 Hz control
        // boundaries. Per-physics-tick sampling must preserve it even though
        // the following sample returns to an apparently safe pose.
        minimumBodyUp = MinecraftMachinesDuopodWalkForwardBenchmark.accumulateMinimumBodyUp(
                minimumBodyUp,
                0.15);
        peakVerticalExcursion = MinecraftMachinesDuopodWalkForwardBenchmark.accumulatePeakVerticalExcursion(
                peakVerticalExcursion,
                postWarmupBaseY,
                17.25);
        arenaEscape |= MinecraftMachinesDuopodWalkForwardBenchmark.outsideArena(
                2.5,
                4.25,
                -8.0,
                57.0,
                4.0);

        minimumBodyUp = MinecraftMachinesDuopodWalkForwardBenchmark.accumulateMinimumBodyUp(
                minimumBodyUp,
                0.99);
        peakVerticalExcursion = MinecraftMachinesDuopodWalkForwardBenchmark.accumulatePeakVerticalExcursion(
                peakVerticalExcursion,
                postWarmupBaseY,
                10.1);
        arenaEscape |= MinecraftMachinesDuopodWalkForwardBenchmark.outsideArena(
                3.0,
                0.1,
                -8.0,
                57.0,
                4.0);

        if (Math.abs(minimumBodyUp - 0.15) > 1.0e-12
                || Math.abs(peakVerticalExcursion - 7.25) > 1.0e-12
                || !arenaEscape) {
            throw new GameTestAssertException(
                    "Expected per-physics-tick extrema and arena escape to survive a safe control-boundary pose");
        }
        if (!MinecraftMachinesDuopodWalkForwardBenchmark.completePhysicsTickPoseSampling(12L, 3, 4)
                || MinecraftMachinesDuopodWalkForwardBenchmark.completePhysicsTickPoseSampling(11L, 3, 4)
                || MinecraftMachinesDuopodWalkForwardBenchmark.completePhysicsTickPoseSampling(13L, 3, 4)) {
            throw new GameTestAssertException(
                    "Expected benchmark evidence to require exactly control_steps * control_ticks pose samples");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void walkForwardBenchmarkPublicationCommitsImmutableHashPairedArtifacts(final GameTestHelper helper) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("minecraft-machines-benchmark-publication-");
            final UUID firstId = UUID.randomUUID();
            final String firstGeneratedAt = "2026-08-03T00:00:00Z";
            final JsonObject firstPayload = benchmarkPublicationFixturePayload();
            final String firstCsv = "slot,benchmark_id\n0," + firstId + "\n";
            final MinecraftMachinesDuopodWalkForwardBenchmark.PublishedBenchmarkArtifacts first =
                    MinecraftMachinesDuopodWalkForwardBenchmark.publishArtifactBundle(
                            directory,
                            firstId,
                            firstGeneratedAt,
                            firstPayload,
                            firstCsv);

            assertPublishedBenchmarkBundle(first, firstId);
            try {
                MinecraftMachinesDuopodWalkForwardBenchmark.publishArtifactBundle(
                        directory,
                        firstId,
                        firstGeneratedAt,
                        firstPayload,
                        firstCsv);
                throw new GameTestAssertException("Expected immutable benchmark-ID artifacts to reject replacement");
            } catch (final java.nio.file.FileAlreadyExistsException expected) {
                // Expected: an immutable benchmark ID can only be published once.
            }

            final UUID secondId = UUID.randomUUID();
            final String secondCsv = "slot,benchmark_id\n0," + secondId + "\n";
            final MinecraftMachinesDuopodWalkForwardBenchmark.PublishedBenchmarkArtifacts second =
                    MinecraftMachinesDuopodWalkForwardBenchmark.publishArtifactBundle(
                            directory,
                            secondId,
                            "2026-08-03T00:01:00Z",
                            benchmarkPublicationFixturePayload(),
                            secondCsv);
            assertPublishedBenchmarkBundle(second, secondId);
            if (!Files.exists(first.immutableJsonPath()) || !Files.exists(first.immutableCsvPath())) {
                throw new GameTestAssertException("Expected a newer latest manifest to preserve older immutable evidence");
            }
            final JsonObject latestJson = JsonParser.parseString(
                    Files.readString(second.latestJsonPath())).getAsJsonObject();
            final JsonObject latestManifest = JsonParser.parseString(
                    Files.readString(second.latestManifestPath())).getAsJsonObject();
            if (!secondId.toString().equals(latestJson.get("benchmark_id").getAsString())
                    || !secondId.toString().equals(latestManifest.get("benchmark_id").getAsString())
                    || !Files.readString(second.latestCsvPath()).contains("," + secondId + "\n")) {
                throw new GameTestAssertException("Expected latest JSON, CSV, and manifest to share the newest benchmark ID");
            }
        } catch (final IOException | IllegalStateException e) {
            throw new GameTestAssertException("Could not verify immutable benchmark publication: " + e.getMessage());
        } finally {
            if (directory != null) {
                try {
                    try (var files = Files.list(directory)) {
                        for (final Path path : files.toList()) {
                            Files.deleteIfExists(path);
                        }
                    }
                    Files.deleteIfExists(directory);
                } catch (final IOException e) {
                    throw new GameTestAssertException("Could not clean benchmark publication fixture: " + e.getMessage());
                }
            }
        }
        helper.succeed();
    }

    private static JsonObject benchmarkPublicationFixturePayload() {
        final JsonObject payload = new JsonObject();
        payload.addProperty("format", "minecraft_machines_duopod_walk_forward_benchmark_v3");
        payload.addProperty("format_version", 3);
        final JsonObject protocol = new JsonObject();
        protocol.addProperty("acceptance_rule", "post_warmup_stability_adjusted_anti_ballistic_v2");
        protocol.addProperty("acceptance_rule_version", 2);
        protocol.addProperty("arena_id", MinecraftMachinesDuopodFlatArena.ARENA_ID);
        final JsonObject world = new JsonObject();
        world.addProperty("level_name", "gametest");
        world.addProperty("seed", 1L);
        world.addProperty("dimension", "minecraft:overworld");
        protocol.add("world", world);
        payload.add("protocol", protocol);
        final JsonObject policy = new JsonObject();
        policy.addProperty("genome_sha256", "fixture-genome-sha256");
        policy.addProperty("checkpoint_run_id", UUID.nameUUIDFromBytes(
                "benchmark-publication-fixture".getBytes(StandardCharsets.UTF_8)).toString());
        policy.addProperty("checkpoint_generation", 1);
        policy.addProperty("checkpoint_fitness_contract", "minecraft_machines:duopod_cem_fitness_v6");
        payload.add("policy", policy);
        return payload;
    }

    private static void assertPublishedBenchmarkBundle(
            final MinecraftMachinesDuopodWalkForwardBenchmark.PublishedBenchmarkArtifacts published,
            final UUID expectedId
    ) throws IOException {
        final String immutableJsonContents = Files.readString(published.immutableJsonPath());
        final String immutableCsvContents = Files.readString(published.immutableCsvPath());
        final String manifestContents = Files.readString(published.latestManifestPath());
        final JsonObject immutableJson = JsonParser.parseString(immutableJsonContents).getAsJsonObject();
        final JsonObject manifest = JsonParser.parseString(manifestContents).getAsJsonObject();
        final JsonObject immutableDescriptors = manifest.getAsJsonObject("immutable_artifacts");
        final String manifestJsonHash = immutableDescriptors.getAsJsonObject("json").get("sha256").getAsString();
        final String manifestCsvHash = immutableDescriptors.getAsJsonObject("csv").get("sha256").getAsString();
        if (!expectedId.equals(published.benchmarkId())
                || !expectedId.toString().equals(immutableJson.get("benchmark_id").getAsString())
                || !expectedId.toString().equals(manifest.get("benchmark_id").getAsString())
                || !published.jsonSha256().equals(manifestJsonHash)
                || !published.csvSha256().equals(manifestCsvHash)
                || !manifestJsonHash.equals(MinecraftMachinesDuopodWalkForwardBenchmark.sha256Utf8(immutableJsonContents))
                || !manifestCsvHash.equals(MinecraftMachinesDuopodWalkForwardBenchmark.sha256Utf8(immutableCsvContents))
                || !immutableJsonContents.equals(Files.readString(published.latestJsonPath()))
                || !immutableCsvContents.equals(Files.readString(published.latestCsvPath()))
                || !immutableJsonContents.endsWith("\n")
                || !manifestContents.endsWith("\n")
                || !manifest.get("commit_complete").getAsBoolean()) {
            throw new GameTestAssertException("Expected immutable and latest benchmark evidence to match the committed manifest hashes");
        }
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void walkForwardBenchmarkArenaProvidesAndRestoresThreeFixedLanes(final GameTestHelper helper) {
        if (MinecraftMachinesDuopodFlatArena.rightOffsetForSlot(0, 12) != 0
                || MinecraftMachinesDuopodFlatArena.rightOffsetForSlot(2, 12) != 24
                || MinecraftMachinesDuopodFlatArena.rightOffsetForSlot(3, 12) != 48
                || MinecraftMachinesDuopodFlatArena.rightOffsetForSlot(5, 12) != 72
                || MinecraftMachinesDuopodTrainingMorphology.requestedRightOffsetForSlot(3, 12, true) != 48
                || MinecraftMachinesDuopodTrainingMorphology.requestedRightOffsetForSlot(5, 12, true) != 72
                || MinecraftMachinesDuopodTrainingMorphology.requestedRightOffsetForSlot(3, 12, false) != 36) {
            throw new GameTestAssertException(
                    "Expected each three-speed candidate group to be isolated by one unused lane spacing");
        }
        final ServerLevel level = helper.getLevel();
        final BlockPos morphologyOrigin = helper.absolutePos(new BlockPos(5, 12, 5));
        final Direction forward = Direction.NORTH;
        final Direction right = forward.getClockWise();
        final BlockPos[] sentinels = new BlockPos[]{
                morphologyOrigin.below(),
                morphologyOrigin.relative(right, 12).below(),
                morphologyOrigin.relative(right, 24).below(),
                morphologyOrigin.above(2)
        };
        final BlockState[] originals = new BlockState[]{
                Blocks.DIRT.defaultBlockState(),
                Blocks.GOLD_BLOCK.defaultBlockState(),
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                Blocks.COBBLESTONE.defaultBlockState()
        };
        for (int index = 0; index < sentinels.length; index++) {
            level.setBlockAndUpdate(sentinels[index], originals[index]);
        }

        final MinecraftMachinesDuopodFlatArena arena = MinecraftMachinesDuopodFlatArena.prepare(
                level,
                morphologyOrigin,
                forward,
                3,
                12,
                1,
                1);
        final Path recoveryJournal = arena.recoveryJournalPath();
        if (recoveryJournal == null || !Files.isRegularFile(recoveryJournal)) {
            throw new GameTestAssertException(
                    "Expected a durable recovery journal before controlled arena mutation");
        }
        try {
            for (int lane = 0; lane < 3; lane++) {
                final BlockPos requested = morphologyOrigin.relative(right, lane * 12);
                if (!arena.requestedOriginForSlot(lane).equals(requested)) {
                    throw new GameTestAssertException("Expected fixed requested origin " + requested
                            + " for lane " + lane + ", got " + arena.requestedOriginForSlot(lane));
                }
                if (!arena.expectedSpawnCenterForSlot(lane).equals(requested.above(3))) {
                    throw new GameTestAssertException("Expected deterministic spawn center for lane " + lane);
                }
                if (!level.getBlockState(requested.below()).is(Blocks.SMOOTH_STONE)) {
                    throw new GameTestAssertException("Expected smooth-stone support for lane " + lane);
                }
                if (!level.getBlockState(requested.above(2)).isAir()) {
                    throw new GameTestAssertException("Expected controlled clear space above lane " + lane);
                }
            }
        } finally {
            arena.close();
        }
        for (int index = 0; index < sentinels.length; index++) {
            if (!level.getBlockState(sentinels[index]).equals(originals[index])) {
                throw new GameTestAssertException("Expected controlled arena cleanup to restore sentinel "
                        + sentinels[index] + " to " + originals[index]
                        + ", got " + level.getBlockState(sentinels[index]));
            }
        }
        if (Files.exists(recoveryJournal)) {
            throw new GameTestAssertException(
                    "Expected complete arena restoration to remove its recovery journal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void duopodKinematicsDefineUprightAndAngularRatesFromBodyFrame(final GameTestHelper helper) {
        final Quaterniond level = new Quaterniond();
        final double pitchRadians = Math.toRadians(30.0);
        final Quaterniond pitched = new Quaterniond().rotateX(pitchRadians);

        assertNear(1.0, DuopodKinematics.bodyUpDotWorldUp(level, DUOPOD_FORWARD), 1.0e-10, "level up dot");
        assertNear(Math.cos(pitchRadians), DuopodKinematics.bodyUpDotWorldUp(pitched, DUOPOD_FORWARD), 1.0e-10, "pitched up dot");

        final DuopodKinematics.ProjectedGravity levelGravity =
                DuopodKinematics.projectedGravity(level, DUOPOD_FORWARD);
        final DuopodKinematics.ProjectedGravity pitchedGravity =
                DuopodKinematics.projectedGravity(pitched, DUOPOD_FORWARD);
        assertNear(0.0, levelGravity.forward(), 1.0e-10, "level gravity forward projection");
        assertNear(0.0, levelGravity.right(), 1.0e-10, "level gravity right projection");
        assertNear(
                Math.sin(pitchRadians),
                Math.hypot(pitchedGravity.forward(), pitchedGravity.right()),
                1.0e-10,
                "tilted gravity projection magnitude");

        final DuopodKinematics.Axes pitchedAxes = DuopodKinematics.worldAxes(DUOPOD_FORWARD, pitched);
        final DuopodKinematics.LocalBodyMotion localPitchRate = DuopodKinematics.localMotion(
                new Vector3d(),
                new Vector3d(pitchedAxes.right()).mul(1.25),
                pitched,
                DUOPOD_FORWARD);
        assertNear(0.0, localPitchRate.rollRate(), 1.0e-10, "local roll rate");
        assertNear(1.25, localPitchRate.pitchRate(), 1.0e-10, "local pitch rate");
        assertNear(0.0, localPitchRate.yawRate(), 1.0e-10, "local yaw rate");

        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void duopodSpawnProducesBaseTwoChildrenAndDiscoverableServos(final GameTestHelper helper) {
        final SpawnedDuopod spawned = spawnDuopod(helper);
        final ServerLevel level = helper.getLevel();
        final DuopodInstance duopod = spawned.duopod();

        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    assertSubLevelExists(level, duopod.baseSubLevelId(), "base");
                    assertSubLevelExists(level, duopod.leftChildSubLevelId(), "left child");
                    assertSubLevelExists(level, duopod.rightChildSubLevelId(), "right child");
                    if (duopod.baseSubLevelId().equals(duopod.leftChildSubLevelId())
                            || duopod.baseSubLevelId().equals(duopod.rightChildSubLevelId())
                            || duopod.leftChildSubLevelId().equals(duopod.rightChildSubLevelId())) {
                        throw new GameTestAssertException("Expected Duopod base, left child, and right child to be separate physics bodies");
                    }

                    if (resolveLeftServo(level, duopod) == null) {
                        throw new GameTestAssertException("Expected left Duopod servo to be discoverable after assembly");
                    }
                    if (resolveRightServo(level, duopod) == null) {
                        throw new GameTestAssertException("Expected right Duopod servo to be discoverable after assembly");
                    }

                    spawned.control().destroy();
                })
                .thenExecuteAfter(5, () -> assertDuopodRemoved(level, duopod))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 160, batch = "duopodSpawnRollback")
    public static void duopodUnexpectedSpawnFailureRollsBackEveryWorldMutation(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos center = helper.absolutePos(DUOPOD_CENTER);
        clearSpawnLayer(level, center);
        // A replaceable, non-air target proves rollback restores the original state rather than
        // merely clearing construction blocks.
        level.setBlockAndUpdate(center.below(), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(center, Blocks.SHORT_GRASS.defaultBlockState());

        final Map<BlockPos, BlockState> originalBlocks = snapshotBlockStates(level, center);
        final Set<UUID> originalSubLevelIds = snapshotSubLevelIds(level);
        final Map<UUID, TrainingMachineCollisionRegistry.Entry> originalTrainingEntries =
                snapshotTrainingEntries(level);
        final AABB spawnBounds = new AABB(center).inflate(8.0);
        final int originalGlueCount = level.getEntitiesOfClass(SuperGlueEntity.class, spawnBounds).size();

        // Collision ownership registration rejects a null batch ID. This happens only after all
        // three physics bodies have assembled, deterministically exercising the unexpected-runtime
        // rollback path without a production-only fault-injection hook.
        final MinecraftMachinesDuopodSpawner.SpawnResult result = MinecraftMachinesDuopodSpawner.spawn(
                level,
                center,
                DUOPOD_FORWARD,
                null);
        if (result.success() || result.duopod() != null
                || !result.message().startsWith("Unexpected Duopod spawn failure:")) {
            throw new GameTestAssertException("Expected injected ownership failure, got " + result);
        }

        final Set<UUID> remainingSubLevelIds = snapshotSubLevelIds(level);
        if (!remainingSubLevelIds.equals(originalSubLevelIds)) {
            throw new GameTestAssertException("Expected failed spawn to restore the exact sublevel UUID set; before="
                    + originalSubLevelIds + " after=" + remainingSubLevelIds);
        }
        final Map<UUID, TrainingMachineCollisionRegistry.Entry> remainingTrainingEntries =
                snapshotTrainingEntries(level);
        if (!remainingTrainingEntries.equals(originalTrainingEntries)) {
            throw new GameTestAssertException("Expected failed spawn to preserve collision ownership entries; before="
                    + originalTrainingEntries + " after=" + remainingTrainingEntries);
        }
        for (final Map.Entry<BlockPos, BlockState> originalBlock : originalBlocks.entrySet()) {
            final BlockState remaining = level.getBlockState(originalBlock.getKey());
            if (!remaining.equals(originalBlock.getValue())) {
                throw new GameTestAssertException("Expected failed spawn to restore block " + originalBlock.getKey()
                        + " to " + originalBlock.getValue() + ", got " + remaining);
            }
        }
        final int remainingGlueCount = level.getEntitiesOfClass(SuperGlueEntity.class, spawnBounds).size();
        if (remainingGlueCount != originalGlueCount) {
            throw new GameTestAssertException("Expected failed spawn to restore glue count from "
                    + originalGlueCount + " to the same value, got " + remainingGlueCount);
        }

        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void duopodSpawnStartsWithOutwardServosAndLoweredHoneyTips(final GameTestHelper helper) {
        final SpawnedDuopod spawned = spawnDuopod(helper);
        final ServerLevel level = helper.getLevel();
        final DuopodInstance duopod = spawned.duopod();
        final MinecraftMachinesDuopodControl control = spawned.control();

        helper.startSequence()
                .thenExecute(() -> {
                    final Direction right = duopod.modelForwardDirection().getClockWise();
                    final Direction left = right.getOpposite();
                    final RoboticServoJointBlockEntity leftServo = resolveLeftServo(level, duopod);
                    final RoboticServoJointBlockEntity rightServo = resolveRightServo(level, duopod);
                    if (leftServo == null || rightServo == null) {
                        throw new GameTestAssertException("Expected both Duopod servos to resolve after assembly");
                    }
                    if (leftServo.getBlockState().getValue(RoboticServoJointBlock.FACING) != left) {
                        throw new GameTestAssertException("Expected left Duopod servo to face " + left
                                + ", got " + leftServo.getBlockState().getValue(RoboticServoJointBlock.FACING));
                    }
                    if (rightServo.getBlockState().getValue(RoboticServoJointBlock.FACING) != right) {
                        throw new GameTestAssertException("Expected right Duopod servo to face " + right
                                + ", got " + rightServo.getBlockState().getValue(RoboticServoJointBlock.FACING));
                    }

                    final Vec3 base = control.getBasePosition();
                    final Vec3 leftTip = control.getLeftHoneyTipPosition();
                    final Vec3 rightTip = control.getRightHoneyTipPosition();
                    assertTipLoweredAndOnSide(base, leftTip, left, "left");
                    assertTipLoweredAndOnSide(base, rightTip, right, "right");

                    control.destroy();
                })
                .thenExecuteAfter(5, () -> assertDuopodRemoved(level, duopod))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void duopodRightServoReflectsOppositeFacingMount(final GameTestHelper helper) {
        final SpawnedDuopod spawned = spawnDuopod(helper);
        final ServerLevel level = helper.getLevel();
        final DuopodInstance duopod = spawned.duopod();
        final MinecraftMachinesDuopodControl control = spawned.control();

        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    final RoboticServoJointBlockEntity leftServo = resolveLeftServo(level, duopod);
                    final RoboticServoJointBlockEntity rightServo = resolveRightServo(level, duopod);
                    if (leftServo == null || rightServo == null) {
                        throw new GameTestAssertException("Expected both Duopod servos to resolve after assembly");
                    }

                    control.setNormalizedAction(1.0, 1.0);
                    final ServoTelemetrySample left = control.getLeftTelemetrySample(1.0);
                    final ServoTelemetrySample right = control.getRightTelemetrySample(1.0);

                    if (Math.abs(leftServo.getTargetAngleDegrees() - 60.0) > 1.0e-6) {
                        throw new GameTestAssertException("Expected full positive left action to target the safe +60 degree limit, got %.2f"
                                .formatted(leftServo.getTargetAngleDegrees()));
                    }
                    if (Math.abs(rightServo.getTargetAngleDegrees() + 60.0) > 1.0e-6) {
                        throw new GameTestAssertException("Expected full positive right action to target the mirrored -60 degree limit, got %.2f"
                                .formatted(rightServo.getTargetAngleDegrees()));
                    }
                    if (Math.abs(left.targetAngleDegrees() - right.targetAngleDegrees()) > 1.0e-6) {
                        throw new GameTestAssertException("Expected semantic left/right targets to match after telemetry reflection, got %.2f and %.2f"
                                .formatted(left.targetAngleDegrees(), right.targetAngleDegrees()));
                    }
                })
                .thenExecuteAfter(10, () -> {
                    final RoboticServoJointBlockEntity leftServo = resolveLeftServo(level, duopod);
                    final RoboticServoJointBlockEntity rightServo = resolveRightServo(level, duopod);
                    if (leftServo == null || rightServo == null) {
                        throw new GameTestAssertException("Expected both Duopod servos to resolve while checking reflected motion");
                    }

                    final ServoTelemetrySample left = control.getLeftTelemetrySample(1.0);
                    final ServoTelemetrySample right = control.getRightTelemetrySample(1.0);
                    if (leftServo.getActualAngleDegrees() <= 0.5) {
                        throw new GameTestAssertException("Expected left raw servo angle to move positive, got %.2f"
                                .formatted(leftServo.getActualAngleDegrees()));
                    }
                    if (rightServo.getActualAngleDegrees() >= -0.5) {
                        throw new GameTestAssertException("Expected right raw servo angle to move negative, got %.2f"
                                .formatted(rightServo.getActualAngleDegrees()));
                    }
                    if (left.actualAngleDegrees() <= 0.5 || right.actualAngleDegrees() <= 0.5) {
                        throw new GameTestAssertException("Expected reflected semantic actual angles to both be positive, got %.2f and %.2f"
                                .formatted(left.actualAngleDegrees(), right.actualAngleDegrees()));
                    }
                    assertNear(
                            leftServo.getAngularVelocityRadPerSecond(),
                            left.angularVelocityRadPerSecond(),
                            1.0e-6,
                            "left semantic angular velocity");
                    assertNear(
                            -rightServo.getAngularVelocityRadPerSecond(),
                            right.angularVelocityRadPerSecond(),
                            1.0e-6,
                            "reflected right semantic angular velocity");

                    control.destroy();
                })
                .thenExecuteAfter(5, () -> assertDuopodRemoved(level, duopod))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void trainRewardVisualizationRootCommandToggles(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final CommandSourceStack source = level.getServer().createCommandSourceStack().withPermission(4);

        helper.startSequence()
                .thenExecute(() -> {
                    executeCommand(level, source, "mm train reward_viz off");
                    if (MinecraftMachinesDuopodCemTrainer.rewardVisualizationEnabledForGameTest()) {
                        throw new GameTestAssertException("Expected root reward_viz off command to disable visualization");
                    }

                    executeCommand(level, source, "mm train reward_viz on");
                    if (!MinecraftMachinesDuopodCemTrainer.rewardVisualizationEnabledForGameTest()) {
                        throw new GameTestAssertException("Expected root reward_viz on command to enable visualization");
                    }

                    executeCommand(level, source, "mm train cem reward_viz off");
                    if (MinecraftMachinesDuopodCemTrainer.rewardVisualizationEnabledForGameTest()) {
                        throw new GameTestAssertException("Expected generic CEM reward_viz off command to disable visualization");
                    }

                    executeCommand(level, source, "mm train duopod cem reward_viz on");
                    if (!MinecraftMachinesDuopodCemTrainer.rewardVisualizationEnabledForGameTest()) {
                        throw new GameTestAssertException("Expected explicit Duopod CEM reward_viz on command to enable visualization");
                    }

                    executeCommand(level, source, "mm train reward_viz off");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void duopodCleanupRemovesOwnedSublevelsAndCollisionEntries(final GameTestHelper helper) {
        final SpawnedDuopod spawned = spawnDuopod(helper);
        final ServerLevel level = helper.getLevel();
        final DuopodInstance duopod = spawned.duopod();

        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    final ServerSubLevel base = assertSubLevelExists(level, duopod.baseSubLevelId(), "base");
                    final ServerSubLevel left = assertSubLevelExists(level, duopod.leftChildSubLevelId(), "left child");
                    final ServerSubLevel right = assertSubLevelExists(level, duopod.rightChildSubLevelId(), "right child");
                    assertTrainingEntry(base, duopod, "base");
                    assertTrainingEntry(left, duopod, "left child");
                    assertTrainingEntry(right, duopod, "right child");

                    spawned.control().destroy();

                    if (TrainingMachineCollisionRegistry.entry(base) != null
                            || TrainingMachineCollisionRegistry.entry(left) != null
                            || TrainingMachineCollisionRegistry.entry(right) != null) {
                        throw new GameTestAssertException("Expected removed Duopod bodies to clear training collision entries");
                    }
                })
                .thenExecuteAfter(5, () -> assertDuopodRemoved(level, duopod))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 180, batch = "duopodTrainingCollision")
    public static void sameBatchDuopodsSuppressEachOtherButKeepWorldContacts(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final UUID batchId = UUID.randomUUID();
        final AtomicReference<LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod>> environment = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> {
                    final BlockPos origin = helper.absolutePos(new BlockPos(6, 3, 6));
                    prepareVectorEnvironmentSpawnArea(level, origin, 2, 14, DUOPOD_FORWARD);
                    final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                            level,
                            origin,
                            DUOPOD_FORWARD,
                            batchId,
                            14);
                    environment.set(new LocomotionVectorEnvironment<>(
                            morphology,
                            2,
                            1,
                            ResetStrategy.RESPAWN,
                            (slot, episodeIndex) -> DuopodTrainingScenarios.flatCommandEpisode(
                                    slot * 1_000L + episodeIndex,
                                    5_000L + slot * 17L + episodeIndex,
                                    0,
                                    2,
                                    slot)));
                    environment.get().resetAll();
                    if (countTrainingEntries(level, batchId) != 6) {
                        throw new GameTestAssertException("Expected vector environment reset to register two same-batch Duopods");
                    }
                    final BlockSubLevelCollisionCallback callback = WormCollisionCallback.wrap(null);
                    try {
                        final BlockPos[] plotPositions = plotBlocksInsideTwoBatchMachines(level, batchId);
                        final BlockPos firstPlotPos = plotPositions[0];
                        final BlockPos secondPlotPos = plotPositions[1];
                        final SubLevelPhysicsSystem previous = SubLevelPhysicsSystem.currentlySteppingSystem;
                        SubLevelPhysicsSystem.currentlySteppingSystem = SubLevelContainer.getContainer(level).physicsSystem();
                        try {
                            if (!TrainingMachineCollisionRegistry.shouldSuppressCollision(level, firstPlotPos, secondPlotPos)) {
                                throw new GameTestAssertException("Expected same-batch Duopod contacts to be suppressed by the training registry; "
                                        + describeCollisionLookup(level, firstPlotPos, secondPlotPos));
                            }
                            if (!callback.sable$onCollision(firstPlotPos, secondPlotPos, new Vector3d(), 1.0).removeCollision()) {
                                throw new GameTestAssertException("Expected Sable collision callback to remove same-batch Duopod contacts");
                            }
                            if (callback.sable$onCollision(firstPlotPos, null, new Vector3d(), 1.0).removeCollision()) {
                                throw new GameTestAssertException("Expected Duopod-vs-world contacts to keep normal collision");
                            }
                        } finally {
                            SubLevelPhysicsSystem.currentlySteppingSystem = previous;
                        }
                    } finally {
                        environment.get().close();
                    }
                })
                .thenExecuteAfter(5, () -> {
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected same-batch vector environment close to clear collision entries");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 260, batch = "duopodCemEvaluation")
    public static void duopodCemEvaluationWritesHeldOutJsonAndCleansUp(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final UUID batchId = UUID.randomUUID();
        final LinearTanhPolicy policy = new LinearTanhPolicy(DuopodSchemas.observationSpec().size(), DuopodSchemas.actionSpec().size());
        final double[] genome = new double[policy.genomeSize()];
        final BlockPos origin = helper.absolutePos(new BlockPos(6, 3, 6));
        final DuopodEvaluationManifest manifest = new DuopodEvaluationManifest(
                "duopod_gametest_point_goal",
                1,
                List.of(new DuopodEvaluationScenario(
                        "ahead_4",
                        0.0,
                        4.0,
                        0.0,
                        DuopodTrainingScenarios.FLAT_POINT_GOALS_STAGE)));
        final MinecraftMachinesDuopodCemTrainer.EvaluationRun evaluation = new MinecraftMachinesDuopodCemTrainer.EvaluationRun(
                UUID.randomUUID(),
                UUID.randomUUID(),
                level.dimension(),
                origin,
                DUOPOD_FORWARD,
                batchId,
                genome,
                manifest,
                1,
                0,
                1);
        final Path output = level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("minecraft_machines")
                .resolve("duopod_cem_evaluation_latest.json");

        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        Files.deleteIfExists(output);
                    } catch (final IOException e) {
                        throw new GameTestAssertException("Could not clear previous evaluation output: " + e.getMessage());
                    }
                    prepareVectorEnvironmentSpawnArea(level, origin, 1, 14, DUOPOD_FORWARD);
                    if (!evaluation.start(level)) {
                        throw new GameTestAssertException("Expected Duopod CEM evaluation to start: " + evaluation.lastFailure());
                    }
                    if (countTrainingEntries(level, batchId) != 3) {
                        throw new GameTestAssertException("Expected CEM evaluation to spawn one Duopod");
                    }
                })
                .thenExecuteAfter(1, () -> {
                    boolean active = true;
                    int ticks = 0;
                    while (active && ticks < 20) {
                        active = evaluation.tick(level.getServer());
                        ticks++;
                    }
                    if (active) {
                        evaluation.close(level);
                        throw new GameTestAssertException("Expected tiny Duopod CEM evaluation to finish within 20 server-tick loop iterations");
                    }
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected CEM evaluation to clean up all Duopod collision entries");
                    }
                    final JsonObject json;
                    try {
                        json = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
                    } catch (final IOException | IllegalStateException e) {
                        throw new GameTestAssertException("Expected CEM evaluation JSON output: " + e.getMessage());
                    }
                    if (!json.has("scenario_manifest") || !json.has("summary") || !json.has("episodes")) {
                        throw new GameTestAssertException("Expected CEM evaluation JSON to include scenario_manifest, summary, and episodes");
                    }
                    if (!"duopod_gametest_point_goal".equals(json.getAsJsonObject("scenario_manifest").get("id").getAsString())) {
                        throw new GameTestAssertException("Expected CEM evaluation JSON to preserve manifest id");
                    }
                    if (json.getAsJsonArray("episodes").size() != 1) {
                        throw new GameTestAssertException("Expected CEM evaluation JSON to contain one episode row");
                    }
                    final JsonObject summary = json.getAsJsonObject("summary");
                    if (!summary.has("mean_distance_travelled_blocks")
                            || !summary.has("mean_path_directness")
                            || !summary.has("mean_servo_load")
                            || !summary.has("mean_time_to_target_steps")
                            || !summary.has("mean_forward_command_error")
                            || !summary.has("mean_yaw_command_error")) {
                        throw new GameTestAssertException("Expected CEM evaluation summary to include richer held-out metrics");
                    }
                    final JsonObject row = json.getAsJsonArray("episodes").get(0).getAsJsonObject();
                    if (!row.has("distance_travelled_blocks")
                            || !row.has("path_directness")
                            || !row.has("time_to_target_steps")
                            || !row.has("mean_servo_load")
                            || !row.has("peak_servo_load")
                            || !row.has("mean_forward_command_error")
                            || !row.has("mean_yaw_command_error")) {
                        throw new GameTestAssertException("Expected CEM evaluation row to include richer held-out metrics");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 260, batch = "duopodTargetChangeValidation")
    public static void duopodCemTargetChangeValidationWritesJsonWithoutRespawn(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final UUID batchId = UUID.randomUUID();
        final LinearTanhPolicy policy = new LinearTanhPolicy(DuopodSchemas.observationSpec().size(), DuopodSchemas.actionSpec().size());
        final double[] genome = new double[policy.genomeSize()];
        final BlockPos origin = helper.absolutePos(new BlockPos(6, 3, 6));
        final MinecraftMachinesDuopodCemTrainer.TargetChangeValidationRun validation = new MinecraftMachinesDuopodCemTrainer.TargetChangeValidationRun(
                UUID.randomUUID(),
                UUID.randomUUID(),
                level.dimension(),
                origin,
                DUOPOD_FORWARD,
                batchId,
                genome,
                1,
                1,
                3);
        final Path output = level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("minecraft_machines")
                .resolve("duopod_cem_target_change_latest.json");

        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        Files.deleteIfExists(output);
                    } catch (final IOException e) {
                        throw new GameTestAssertException("Could not clear previous target-change output: " + e.getMessage());
                    }
                    prepareVectorEnvironmentSpawnArea(level, origin, 1, 14, DUOPOD_FORWARD);
                    if (!validation.start(level)) {
                        throw new GameTestAssertException("Expected target-change validation to start: " + validation.lastFailure());
                    }
                    if (countTrainingEntries(level, batchId) != 3) {
                        throw new GameTestAssertException("Expected target-change validation to spawn one Duopod");
                    }
                })
                .thenExecuteAfter(1, () -> {
                    boolean active = true;
                    int ticks = 0;
                    while (active && ticks < 40) {
                        active = validation.tick(level.getServer());
                        ticks++;
                    }
                    if (active) {
                        validation.close(level);
                        throw new GameTestAssertException("Expected tiny target-change validation to finish within 40 server-tick loop iterations");
                    }
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected target-change validation to clean up all Duopod collision entries");
                    }
                    final JsonObject json;
                    try {
                        json = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
                    } catch (final IOException | IllegalStateException e) {
                        throw new GameTestAssertException("Expected target-change validation JSON output: " + e.getMessage());
                    }
                    if (!"minecraft_machines_duopod_cem_target_change_v1".equals(json.get("format").getAsString())) {
                        throw new GameTestAssertException("Expected target-change validation JSON format v1");
                    }
                    if (!json.has("before_switch") || !json.has("after_switch") || !json.has("post_switch_response") || !json.has("summary")) {
                        throw new GameTestAssertException("Expected target-change validation JSON to include switch snapshots and summary");
                    }
                    final JsonObject summary = json.getAsJsonObject("summary");
                    if (!summary.get("same_machine").getAsBoolean()) {
                        throw new GameTestAssertException("Expected target-change validation to keep the same Duopod machine id");
                    }
                    if (!summary.get("phase_continued").getAsBoolean()) {
                        throw new GameTestAssertException("Expected target-change validation to continue the same phase clock");
                    }
                    if (summary.get("old_target_bearing_degrees").getAsDouble() >= 0.0
                            || summary.get("new_target_bearing_degrees").getAsDouble() <= 0.0) {
                        throw new GameTestAssertException("Expected target-change validation to switch from ahead-left to ahead-right");
                    }
                    if (summary.get("desired_yaw_before").getAsDouble() >= 0.0
                            || summary.get("desired_yaw_after").getAsDouble() <= 0.0) {
                        throw new GameTestAssertException("Expected target-change validation to invert desired yaw after target switch");
                    }
                    if (!summary.has("left_action_before")
                            || !summary.has("right_action_before")
                            || !summary.has("left_action_after")
                            || !summary.has("right_action_after")
                            || !summary.has("observed_yaw_response_delta")) {
                        throw new GameTestAssertException("Expected target-change validation summary to include before/after action and yaw response fields");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 220, batch = "duopodVectorLifecycle")
    public static void duopodVectorEnvironmentStepsAutoResetsAndClosesCleanly(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final UUID batchId = UUID.randomUUID();
        final int slotCount = 2;
        final int controlTicks = 4;
        // Keep this two-slot lifecycle smoke test inside its compact GameTest allocation.
        // Production CEM runs separately enforce the 12-block physics-isolation contract.
        final int spacingBlocks = 6;
        final AtomicReference<LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod>> environment = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> {
                    final BlockPos origin = helper.absolutePos(new BlockPos(6, 3, 6));
                    prepareVectorEnvironmentSpawnArea(level, origin, slotCount, spacingBlocks, DUOPOD_FORWARD);
                    final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                            level,
                            origin,
                            DUOPOD_FORWARD,
                            batchId,
                            spacingBlocks);
                    environment.set(new LocomotionVectorEnvironment<>(
                            morphology,
                            slotCount,
                            controlTicks,
                            ResetStrategy.RESPAWN,
                            (slot, episodeIndex) -> DuopodTrainingScenarios.flatCommandEpisode(
                                    slot * 1_000L + episodeIndex,
                                    10_000L + slot * 31L + episodeIndex,
                                    0,
                                    1,
                                    slot)));
                    final EnvironmentBatchReset reset = environment.get().resetAll();
                    assertObservationBatch(reset.observations(), slotCount, "reset");
                    for (int slot = 0; slot < slotCount; slot++) {
                        assertImmediateContactReset(reset.infos().get(slot), "initial reset slot " + slot);
                    }
                    if (countTrainingEntries(level, batchId) != slotCount * 3) {
                        throw new GameTestAssertException("Expected vector environment reset to spawn three Duopod bodies per slot");
                    }
                    environment.get().beginStep(new double[][]{
                            {0.0, 0.0},
                            {0.0, 0.0}
                    });
                })
                .thenExecuteAfter(controlTicks, () -> {
                    final EnvironmentBatchStep step = environment.get().finishStep();
                    assertNeutralAutoResetStep(step, slotCount, "first neutral interval");
                    if (countTrainingEntries(level, batchId) != slotCount * 3) {
                        throw new GameTestAssertException("Expected auto-reset to leave exactly one live Duopod per vector slot");
                    }

                    final EnvironmentBatchReset refreshed = environment.get().updateEpisodes((slot, episode) -> episode);
                    assertObservationBatch(refreshed.observations(), slotCount, "auto-reset refresh");
                    for (int slot = 0; slot < slotCount; slot++) {
                        assertImmediateContactReset(refreshed.infos().get(slot), "auto-reset slot " + slot);
                    }
                    environment.get().beginStep(new double[][]{
                            {0.0, 0.0},
                            {0.0, 0.0}
                    });
                })
                .thenExecuteAfter(controlTicks, () -> {
                    try {
                        final EnvironmentBatchStep step = environment.get().finishStep();
                        assertNeutralAutoResetStep(step, slotCount, "post-respawn neutral interval");
                        if (countTrainingEntries(level, batchId) != slotCount * 3) {
                            throw new GameTestAssertException("Expected repeated auto-reset to leave exactly one live Duopod per vector slot");
                        }
                    } finally {
                        environment.get().close();
                    }
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected vector environment close to clear all owned Duopod collision entries");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 220, batch = "duopodVectorExistingTerrain")
    public static void duopodVectorEnvironmentUsesExistingWorldTerrainAndCleansUp(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final UUID batchId = UUID.randomUUID();
        final int slotCount = 2;
        final BlockPos origin = helper.absolutePos(new BlockPos(6, 3, 6));
        prepareVectorEnvironmentSpawnArea(level, origin, slotCount, 0, DUOPOD_FORWARD);
        final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                level,
                origin,
                DUOPOD_FORWARD,
                batchId,
                0);
        final LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod> environment = new LocomotionVectorEnvironment<>(
                morphology,
                slotCount,
                1,
                ResetStrategy.RESPAWN,
                (slot, episodeIndex) -> DuopodTrainingScenarios.flatCommandEpisode(
                        episodeIndex,
                        99L + episodeIndex,
                        0,
                        2,
                        slot));

        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        final EnvironmentBatchReset reset = environment.resetAll(List.of(
                                DuopodTrainingScenarios.flatCommandEpisode(1L, 99L, 0, 2, 0),
                                DuopodTrainingScenarios.flatCommandEpisode(2L, 100L, 0, 2, 1)));
                        assertObservationBatch(reset.observations(), slotCount, "world-terrain reset");
                        if (!TerrainProfile.NONE_ID.equals(reset.infos().getFirst().get("terrain_profile"))) {
                            throw new GameTestAssertException("Expected reset info to expose no generated training terrain");
                        }
                        if (!DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE.equals(reset.infos().getFirst().get("curriculum_stage"))) {
                            throw new GameTestAssertException("Expected command reset info to expose canonical Minecraft-terrain command curriculum");
                        }
                        if (!Boolean.FALSE.equals(reset.infos().getFirst().get("terrain_enabled"))) {
                            throw new GameTestAssertException("Expected reset info to mark generated terrain disabled");
                        }
                        assertHoneySpawnGroundClearance(reset.infos().getFirst(), "slot 0");
                        if (morphology.activeTerrainLeaseCount() != 0) {
                            throw new GameTestAssertException("Expected existing-world training to acquire no terrain leases");
                        }
                        if (countTrainingEntries(level, batchId) != slotCount * 3) {
                            throw new GameTestAssertException("Expected world-terrain vector environment reset to spawn one Duopod per slot");
                        }
                    } finally {
                        environment.close();
                    }
                    if (morphology.activeTerrainLeaseCount() != 0) {
                        throw new GameTestAssertException("Expected vector environment close to keep terrain leases at zero");
                    }
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected world-terrain vector environment close to clear collision entries");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 220, batch = "duopodVectorLowBumps")
    public static void duopodVectorEnvironmentAcceptsLowBumpsStageWithoutGeneratingTerrain(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final UUID batchId = UUID.randomUUID();
        final BlockPos origin = helper.absolutePos(new BlockPos(6, 3, 6));
        prepareVectorEnvironmentSpawnArea(level, origin, 1, 14, DUOPOD_FORWARD);
        final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                level,
                origin,
                DUOPOD_FORWARD,
                batchId,
                14);
        final LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod> environment = new LocomotionVectorEnvironment<>(
                morphology,
                1,
                1,
                ResetStrategy.RESPAWN,
                (slot, episodeIndex) -> DuopodTrainingScenarios.commandEpisode(
                        DuopodTrainingScenarios.LOW_BUMPS_COMMANDS_STAGE,
                        episodeIndex,
                        1234L + episodeIndex,
                        0,
                        2,
                        slot));

        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        final EnvironmentBatchReset reset = environment.resetAll();
                        assertObservationBatch(reset.observations(), 1, "low-bump alias reset");
                        if (!TerrainProfile.NONE_ID.equals(reset.infos().getFirst().get("terrain_profile"))) {
                            throw new GameTestAssertException("Expected low-bump alias to use no generated training terrain");
                        }
                        if (!DuopodTrainingScenarios.MINECRAFT_TERRAIN_COMMANDS_STAGE.equals(reset.infos().getFirst().get("curriculum_stage"))) {
                            throw new GameTestAssertException("Expected low-bump alias reset info to normalize to canonical Minecraft-terrain command curriculum");
                        }
                        if (!Boolean.FALSE.equals(reset.infos().getFirst().get("terrain_enabled"))) {
                            throw new GameTestAssertException("Expected low-bump alias to mark generated terrain disabled");
                        }
                    } finally {
                        environment.close();
                    }
                    if (morphology.activeTerrainLeaseCount() != 0) {
                        throw new GameTestAssertException("Expected low-bump alias to acquire no terrain leases");
                    }
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected low-bump alias vector environment close to clear collision entries");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 220, batch = "duopodVectorPointGoal")
    public static void duopodVectorEnvironmentCanUseFlatPointGoalStage(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final UUID batchId = UUID.randomUUID();
        final BlockPos origin = helper.absolutePos(new BlockPos(6, 3, 6));
        prepareVectorEnvironmentSpawnArea(level, origin, 1, 14, DUOPOD_FORWARD);
        final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                level,
                origin,
                DUOPOD_FORWARD,
                batchId,
                14);
        final LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod> environment = new LocomotionVectorEnvironment<>(
                morphology,
                1,
                1,
                ResetStrategy.RESPAWN,
                (slot, episodeIndex) -> DuopodTrainingScenarios.flatPointGoalEpisode(
                        episodeIndex,
                        1234L + episodeIndex,
                        0,
                        2,
                        slot));

        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        final EnvironmentBatchReset reset = environment.resetAll();
                        assertObservationBatch(reset.observations(), 1, "point-goal world-terrain reset");
                        if (!TerrainProfile.NONE_ID.equals(reset.infos().getFirst().get("terrain_profile"))) {
                            throw new GameTestAssertException("Expected point-goal reset info to expose no generated terrain");
                        }
                        if (!DuopodTrainingScenarios.MINECRAFT_TERRAIN_POINT_GOALS_STAGE.equals(reset.infos().getFirst().get("curriculum_stage"))) {
                            throw new GameTestAssertException("Expected point-goal reset info to expose canonical Minecraft-terrain point-goal curriculum");
                        }
                        if (!Boolean.FALSE.equals(reset.infos().getFirst().get("terrain_enabled"))) {
                            throw new GameTestAssertException("Expected point-goal reset info to mark generated terrain disabled");
                        }
                        final Object distance = reset.infos().getFirst().get("distance_to_target");
                        if (!(distance instanceof Number number) || number.doubleValue() <= 0.0) {
                            throw new GameTestAssertException("Expected point-goal reset info to expose a positive distance_to_target");
                        }
                    } finally {
                        environment.close();
                    }
                    if (morphology.activeTerrainLeaseCount() != 0) {
                        throw new GameTestAssertException("Expected point-goal world-terrain close to acquire no terrain leases");
                    }
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected point-goal vector environment close to clear collision entries");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 360, batch = "duopodTrainingBridge")
    public static void trainingBridgeAcceptsLoopbackSessionResetStepAndClose(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos origin = helper.absolutePos(new BlockPos(6, 3, 6));
        final AtomicReference<CompletableFuture<BridgeSmokeResult>> clientFuture = new AtomicReference<>();
        final CountDownLatch sessionCreated = new CountDownLatch(1);
        final CountDownLatch allowStep = new CountDownLatch(1);

        helper.startSequence()
                .thenExecute(() -> {
                    final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
                    if (physicsSystem == null || physicsSystem.getPaused()) {
                        throw new GameTestAssertException("Expected unpaused Sable physics before bridge session");
                    }
                    prepareVectorEnvironmentSpawnArea(level, origin, 1, 0, DUOPOD_FORWARD);
                    try {
                        final MinecraftMachinesTrainingBridge.BridgeTestHandle handle = MinecraftMachinesTrainingBridge.startDuopodForGameTest(
                                level,
                                origin,
                                DUOPOD_FORWARD,
                                1,
                                0,
                                12_345L);
                        clientFuture.set(CompletableFuture.supplyAsync(
                                () -> runBridgeSmokeClient(handle, sessionCreated, allowStep)));
                    } catch (final IOException e) {
                        throw new GameTestAssertException("Expected training bridge to start: " + e.getMessage());
                    }
                })
                .thenExecuteAfter(80, () -> {
                    if (sessionCreated.getCount() != 0L) {
                        allowStep.countDown();
                        MinecraftMachinesTrainingBridge.stopGameTestBridge();
                        throw new GameTestAssertException("Expected bridge CREATE_SESSION response before idle lockstep check");
                    }
                    final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
                    if (physicsSystem == null || !physicsSystem.getPaused()) {
                        allowStep.countDown();
                        MinecraftMachinesTrainingBridge.stopGameTestBridge();
                        throw new GameTestAssertException("Expected bridge to pause Sable physics while awaiting STEP");
                    }
                    allowStep.countDown();
                })
                .thenExecuteAfter(180, () -> {
                    final CompletableFuture<BridgeSmokeResult> future = clientFuture.get();
                    try {
                        if (future == null) {
                            throw new GameTestAssertException("Expected bridge smoke client to start");
                        }
                        if (!future.isDone()) {
                            throw new GameTestAssertException("Expected bridge smoke client to complete HELLO/session/reset/step/close");
                        }
                        final BridgeSmokeResult result = future.join();
                        if (result.sessionId().isBlank()
                                || result.observationSize() != DuopodSchemas.observationSpec().size()
                                || result.actualActionTicks() != 4
                                || !result.lockstep()) {
                            throw new GameTestAssertException("Expected bridge smoke result to expose lockstep action ticks and valid dimensions");
                        }
                    } catch (final CompletionException e) {
                        final Throwable cause = e.getCause() == null ? e : e.getCause();
                        throw new GameTestAssertException("Training bridge smoke client failed: " + cause.getMessage());
                    } finally {
                        MinecraftMachinesTrainingBridge.stopGameTestBridge();
                    }
                    final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
                    if (physicsSystem == null || physicsSystem.getPaused()) {
                        throw new GameTestAssertException("Expected bridge close to restore the prior unpaused Sable physics state");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 260, batch = "duopodCemTraining")
    public static void duopodCemTrainingRunTicksOneGenerationAndCleansUp(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final UUID batchId = UUID.randomUUID();
        final int population = 3;
        final int scenarios = 1;
        final int spacingBlocks = 0;
        final int controlTicks = 2;
        final MinecraftMachinesDuopodCemTrainer.Config config = new MinecraftMachinesDuopodCemTrainer.Config(
                population,
                1,
                1,
                4,
                controlTicks,
                scenarios,
                spacingBlocks,
                2,
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE);
        final MinecraftMachinesDuopodCemTrainer.Config automaticSpacing = new MinecraftMachinesDuopodCemTrainer.Config(
                population,
                1,
                1,
                4,
                controlTicks,
                scenarios,
                0,
                2,
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE);
        if (automaticSpacing.spacingBlocks() != MinecraftMachinesDuopodCemTrainer.Config.MINIMUM_SAFE_SPACING_BLOCKS) {
            throw new GameTestAssertException("Expected zero CEM spacing to normalize to the safe 12-block default");
        }
        final JsonObject walkTrainingMetadata = MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                .withCurriculumStage(DuopodTrainingScenarios.WALK_FORWARD_STAGE)
                .toJson();
        final JsonObject walkFitnessMetadata = walkTrainingMetadata.getAsJsonObject("walkForwardTerminalFitness");
        if (!"minecraft_machines:duopod_cem_fitness_v6".equals(
                walkFitnessMetadata.get("fitnessContract").getAsString())
                || !MinecraftMachinesDuopodFlatArena.ARENA_ID.equals(
                walkTrainingMetadata.get("trainingArena").getAsString())
                || !MinecraftMachinesDuopodFlatArena.SLOT_LAYOUT_ID.equals(
                walkTrainingMetadata.get("trainingSlotLayout").getAsString())
                || walkFitnessMetadata.get("maximumPeakVerticalExcursionBlocks").getAsDouble()
                != DuopodWalkForwardFitness.MAXIMUM_PEAK_VERTICAL_EXCURSION_BLOCKS
                || !walkFitnessMetadata.has("terminalArenaEscapePenalty")) {
            throw new GameTestAssertException("Expected walk-forward checkpoint config to declare the v6 anti-ballistic, arena-escape, and grouped-layout gate");
        }
        if (MinecraftMachinesDuopodCemTrainer.Config.DEFAULT.withPopulationSize(8).eliteCount() != 2) {
            throw new GameTestAssertException("Expected command-sized CEM populations to retain at least two elites");
        }
        if (MinecraftMachinesDuopodCemTrainer.Config.DEFAULT.withPopulationSize(32).eliteCount() != 8) {
            throw new GameTestAssertException("Expected a 32-candidate publication run to retain eight elites");
        }
        final long commonWalkSeedA = MinecraftMachinesDuopodCemTrainer.TrainingRun.trainingEpisodeSeed(
                DuopodTrainingScenarios.WALK_FORWARD_STAGE, 99L, 3, 3, 1, 1, 0L);
        final long commonWalkSeedB = MinecraftMachinesDuopodCemTrainer.TrainingRun.trainingEpisodeSeed(
                DuopodTrainingScenarios.WALK_FORWARD_STAGE, 99L, 3, 3, 1, 91, 0L);
        final long independentTerrainSeedA = MinecraftMachinesDuopodCemTrainer.TrainingRun.trainingEpisodeSeed(
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE, 99L, 3, 3, 1, 1, 0L);
        final long independentTerrainSeedB = MinecraftMachinesDuopodCemTrainer.TrainingRun.trainingEpisodeSeed(
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE, 99L, 3, 3, 1, 91, 0L);
        if (commonWalkSeedA != commonWalkSeedB || independentTerrainSeedA == independentTerrainSeedB) {
            throw new GameTestAssertException("Expected walk-forward candidates to share scenario seeds without changing other curricula");
        }
        final double expectedReplayPhaseAdvance = Math.PI * 2.0
                * DuopodTrainingScenarios.DEFAULT_GAIT_FREQUENCY_HZ
                * 4.0 / 20.0;
        if (Math.abs(MinecraftMachinesDuopodCemTrainer.replayPhaseAdvanceRadians(
                4,
                DuopodTrainingScenarios.DEFAULT_GAIT_FREQUENCY_HZ) - expectedReplayPhaseAdvance) > 1.0e-12
                || MinecraftMachinesDuopodCemTrainer.replayControlHoldCountdown(4) != 3) {
            throw new GameTestAssertException("Expected replay phase and action cadence to match four-tick 0.35 Hz training control");
        }
        if (Math.abs(MinecraftMachinesDuopodCemTrainer.TrainingRun.postWarmupDisplacement(7.5, 2.0) - 5.5) > 1.0e-12) {
            throw new GameTestAssertException("Expected walk-forward terminal displacement to exclude passive spawn warmup motion");
        }
        if (!MinecraftMachinesDuopodCemTrainer.TrainingRun.shouldInjectStructuredGaitSeeds(
                DuopodTrainingScenarios.WALK_FORWARD_STAGE, 0, true)
                || MinecraftMachinesDuopodCemTrainer.TrainingRun.shouldInjectStructuredGaitSeeds(
                DuopodTrainingScenarios.WALK_FORWARD_STAGE, 1, true)
                || MinecraftMachinesDuopodCemTrainer.TrainingRun.shouldInjectStructuredGaitSeeds(
                DuopodTrainingScenarios.WALK_FORWARD_STAGE, 0, false)
                || MinecraftMachinesDuopodCemTrainer.TrainingRun.shouldInjectStructuredGaitSeeds(
                DuopodTrainingScenarios.FLAT_COMMANDS_STAGE, 0, true)) {
            throw new GameTestAssertException("Expected structured gait probes only in generation zero of a fresh walk-forward optimizer");
        }
        final DuopodPhaseGaitPolicy phasePolicy = new DuopodPhaseGaitPolicy();
        final ContinuousCemDistribution collapsedPhaseDistribution = ContinuousCemDistribution.initial(
                phasePolicy.genomeSize(),
                0.02,
                0.02,
                phasePolicy.observationSize(),
                -3.0,
                3.0,
                0.02,
                1.25,
                7L);
        final ContinuousCemDistribution widenedPhaseRestart =
                MinecraftMachinesDuopodCemTrainer.protocolRestartDistribution(
                        phasePolicy,
                        8L,
                        new double[phasePolicy.genomeSize()],
                        collapsedPhaseDistribution);
        for (int i = 0; i < phasePolicy.genomeSize(); i++) {
            if (widenedPhaseRestart.standardDeviation()[i] < 0.60
                    || widenedPhaseRestart.minimumStandardDeviation()[i] < 0.12) {
                throw new GameTestAssertException("Expected incompatible phase-gait resume to restore a wide search distribution");
            }
        }
        if (widenedPhaseRestart.generation() != 0
                || widenedPhaseRestart.bestFitness() != Double.NEGATIVE_INFINITY) {
            throw new GameTestAssertException("Expected incompatible phase-gait resume to reset generation and score scale");
        }
        try {
            new MinecraftMachinesDuopodCemTrainer.Config(
                    population,
                    1,
                    1,
                    4,
                    controlTicks,
                    scenarios,
                    MinecraftMachinesDuopodCemTrainer.Config.MINIMUM_SAFE_SPACING_BLOCKS - 1,
                    2,
                    DuopodTrainingScenarios.FLAT_COMMANDS_STAGE);
            throw new GameTestAssertException("Expected overlapping nonzero CEM spacing to be rejected");
        } catch (final IllegalArgumentException expected) {
            // Expected: unsafe spacing must never create overlapping physics rigs.
        }
        final int slotCount = config.slotCount();
        final LinearTanhPolicy policy = new LinearTanhPolicy(DuopodSchemas.observationSpec().size(), DuopodSchemas.actionSpec().size());
        final long seed = 42_4242L;
        final ContinuousCemDistribution distribution = ContinuousCemDistribution.initial(
                policy.genomeSize(),
                0.18,
                0.35,
                policy.observationSize(),
                -3.0,
                3.0,
                0.02,
                seed);
        final ContinuousCemDistribution resumedDistribution = new ContinuousCemDistribution(
                distribution.mean(),
                distribution.standardDeviation(),
                distribution.lowerBounds(),
                distribution.upperBounds(),
                distribution.minimumStandardDeviation(),
                distribution.maximumStandardDeviation(),
                distribution.seed(),
                7,
                distribution.bestGenome(),
                distribution.bestFitness());
        final MinecraftMachinesDuopodCemTrainer.TrainingRun resumedRun =
                new MinecraftMachinesDuopodCemTrainer.TrainingRun(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        level.dimension(),
                        helper.absolutePos(new BlockPos(6, 3, 6)),
                        DUOPOD_FORWARD,
                        UUID.randomUUID(),
                        config.withMaximumGenerations(8),
                        policy,
                        resumedDistribution,
                        RandomGeneratorFactory.of("L64X128MixRandom").create(seed),
                        seed);
        if (resumedRun.generation() != resumedDistribution.generation()) {
            throw new GameTestAssertException("Expected a resumed CEM run to retain its optimizer generation");
        }
        final MinecraftMachinesDuopodCemTrainer.TrainingRun run = new MinecraftMachinesDuopodCemTrainer.TrainingRun(
                UUID.randomUUID(),
                UUID.randomUUID(),
                level.dimension(),
                helper.absolutePos(new BlockPos(6, 3, 6)),
                DUOPOD_FORWARD,
                batchId,
                config,
                policy,
                distribution,
                RandomGeneratorFactory.of("L64X128MixRandom").create(seed),
                seed);
        final Path checkpoint = level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("minecraft_machines")
                .resolve("duopod_cem_best.json");
        final Path namedCheckpoint = level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("minecraft_machines")
                .resolve("duopod_cem_gametest_named.json");
        final Path checkpointBackup = checkpoint.resolveSibling(checkpoint.getFileName() + ".bak");
        final Path namedCheckpointBackup = namedCheckpoint.resolveSibling(namedCheckpoint.getFileName() + ".bak");
        final boolean[] runActive = {true};
        final boolean[] spawnedBatchObserved = {false};

        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        Files.deleteIfExists(checkpoint);
                        Files.deleteIfExists(checkpointBackup);
                        Files.deleteIfExists(namedCheckpoint);
                        Files.deleteIfExists(namedCheckpointBackup);
                    } catch (final IOException e) {
                        throw new GameTestAssertException("Could not clear previous CEM checkpoint: " + e.getMessage());
                    }
                    prepareVectorEnvironmentSpawnArea(level, run.origin(), slotCount, config.spacingBlocks(), DUOPOD_FORWARD);
                    if (!run.startGeneration(level)) {
                        throw new GameTestAssertException("Expected Duopod CEM smoke generation to start: " + run.lastFailure());
                    }
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected CEM start to defer spawning until its chunk lease activates");
                    }
                })
                .thenExecuteFor(160, () -> {
                    if (runActive[0]) {
                        runActive[0] = run.tick(level.getServer());
                        if (countTrainingEntries(level, batchId) > 0) {
                            spawnedBatchObserved[0] = true;
                        }
                    }
                })
                .thenExecute(() -> {
                    if (runActive[0]) {
                        run.close(level);
                        throw new GameTestAssertException("Expected tiny Duopod CEM run to finish within 160 server ticks");
                    }
                    if (!spawnedBatchObserved[0]) {
                        throw new GameTestAssertException("Expected the activated chunk lease to spawn a CEM batch on a later server tick");
                    }
                    if (run.generation() != config.maximumGenerations()) {
                        throw new GameTestAssertException("Expected CEM smoke run to complete exactly one generation");
                    }
                    if (run.finishedControlSteps() != config.maximumControlSteps()) {
                        throw new GameTestAssertException("Expected CEM smoke run to finish all configured control steps");
                    }
                    if (countTrainingEntries(level, batchId) != 0) {
                        throw new GameTestAssertException("Expected completed CEM smoke run to clean up all Duopod collision entries");
                    }
                    if (MinecraftMachinesDuopodCemTrainer.saveCheckpoint(level.getServer().createCommandSourceStack()) != 1) {
                        throw new GameTestAssertException("Expected completed CEM smoke run to save a best-genome checkpoint");
                    }
                    if (MinecraftMachinesDuopodCemTrainer.saveCheckpoint(level.getServer().createCommandSourceStack()) != 1
                            || !Files.exists(checkpointBackup)) {
                        throw new GameTestAssertException("Expected a second atomic checkpoint save to preserve a recovery backup");
                    }
                    if (MinecraftMachinesDuopodCemTrainer.saveCheckpoint(level.getServer().createCommandSourceStack(), "gametest_named") != 1
                            || !Files.exists(namedCheckpoint)) {
                        throw new GameTestAssertException("Expected completed CEM smoke run to save a named best-genome checkpoint");
                    }
                    if (MinecraftMachinesDuopodCemTrainer.loadCheckpoint(level.getServer().createCommandSourceStack(), "gametest_named") != 1) {
                        throw new GameTestAssertException("Expected named Duopod CEM checkpoint to load");
                    }
                    final JsonObject json;
                    try {
                        json = JsonParser.parseString(Files.readString(checkpoint)).getAsJsonObject();
                    } catch (final IOException | IllegalStateException e) {
                        throw new GameTestAssertException("Expected readable Duopod CEM checkpoint JSON: " + e.getMessage());
                    }
                    if (!"minecraft_machines_duopod_cem_checkpoint_v4".equals(json.get("format").getAsString())) {
                        throw new GameTestAssertException("Expected Duopod CEM checkpoint to use v4 immutable-provenance format");
                    }
                    if (!json.has("trainingConfig") || !json.has("fitnessContract")
                            || !json.has("distribution") || !json.has("evaluationMetadata")
                            || !json.has("failureRate") || !json.has("runSeed")
                            || !json.has("discoveryRunSeed") || !json.has("optimizerSeed")) {
                        throw new GameTestAssertException("Expected Duopod CEM checkpoint to include its fitness contract and complete training metadata");
                    }
                    if (json.get("runSeed").getAsLong() != seed
                            || json.get("discoveryRunSeed").getAsLong() != seed
                            || json.get("optimizerSeed").getAsLong() != seed) {
                        throw new GameTestAssertException(
                                "Expected checkpoint discovery and optimizer seed provenance to remain explicit");
                    }
                    if (!"minecraft_machines:duopod_cem_fitness_v6".equals(json.get("fitnessContract").getAsString())) {
                        throw new GameTestAssertException("Expected checkpoint scores to declare the v6 exact-layout fitness contract");
                    }
                    if (json.getAsJsonObject("trainingConfig").get("chunkActivationTimeoutTicks").getAsInt()
                            != MinecraftMachinesDuopodCemTrainer.TRAINING_CHUNK_ACTIVATION_TIMEOUT_TICKS) {
                        throw new GameTestAssertException("Expected checkpoint metadata to declare the bounded chunk-activation wait");
                    }
                    if (!"not_applicable".equals(json.getAsJsonObject("trainingConfig").get("trainingArena").getAsString())) {
                        throw new GameTestAssertException("Expected non-walk checkpoint metadata to avoid claiming the controlled flat arena");
                    }
                    if (!"minecraft_machines:duopod".equals(json.get("morphologyId").getAsString())) {
                        throw new GameTestAssertException("Expected Duopod CEM checkpoint to include morphology id");
                    }
                    if (json.getAsJsonObject("distribution").get("generation").getAsInt() != run.generation()) {
                        throw new GameTestAssertException("Expected saved optimizer metadata to match the latest completed generation");
                    }
                    final ContinuousCemDistribution sameProtocol = MinecraftMachinesDuopodCemTrainer.initialDistribution(
                            policy,
                            seed + 1L,
                            config);
                    if (sameProtocol.generation() < 1 || !Double.isFinite(sameProtocol.bestFitness())) {
                        throw new GameTestAssertException("Expected an identical fitness protocol to resume saved CEM state");
                    }
                    final ContinuousCemDistribution explicitFresh = MinecraftMachinesDuopodCemTrainer.initialDistribution(
                            policy,
                            seed + 10L,
                            config,
                            true);
                    if (explicitFresh.generation() != 0
                            || explicitFresh.bestFitness() != Double.NEGATIVE_INFINITY
                            || Arrays.stream(explicitFresh.mean()).anyMatch(value -> Math.abs(value) > 1.0e-12)) {
                        throw new GameTestAssertException("Expected explicit fresh initialization to bypass loaded checkpoint state safely");
                    }

                    final MinecraftMachinesDuopodCemTrainer.Config longerHorizon = config.withEpisodeTicks(8);
                    final ContinuousCemDistribution restarted = MinecraftMachinesDuopodCemTrainer.initialDistribution(
                            policy,
                            seed + 2L,
                            longerHorizon);
                    final JsonArray checkpointGenome = json.getAsJsonArray("genome");
                    if (restarted.generation() != 0 || restarted.bestFitness() != Double.NEGATIVE_INFINITY) {
                        throw new GameTestAssertException("Expected a changed fitness protocol to reset generation and best-fitness scale");
                    }
                    final JsonObject savedDistribution = json.getAsJsonObject("distribution");
                    final JsonArray savedStandardDeviation = savedDistribution.getAsJsonArray("standardDeviation");
                    final JsonArray savedMinimumStandardDeviation = savedDistribution.getAsJsonArray("minimumStandardDeviation");
                    final JsonArray savedMaximumStandardDeviation = savedDistribution.getAsJsonArray("maximumStandardDeviation");
                    for (int i = 0; i < restarted.mean().length; i++) {
                        final double expectedRestartStd = Math.max(
                                savedMinimumStandardDeviation.get(i).getAsDouble(),
                                Math.min(
                                        savedMaximumStandardDeviation.get(i).getAsDouble(),
                                        savedStandardDeviation.get(i).getAsDouble() * 1.5));
                        if (Math.abs(restarted.mean()[i] - checkpointGenome.get(i).getAsDouble()) > 1.0e-12
                                || Math.abs(restarted.standardDeviation()[i] - expectedRestartStd) > 1.0e-12) {
                            throw new GameTestAssertException("Expected protocol restart to widen around the checkpoint genome");
                        }
                    }
                    final double[] lowGenome = new double[policy.genomeSize()];
                    final ScoredGenome deliberatelyWorse = new ScoredGenome(lowGenome, -1_000.0, -1_000.0, 0.0, 0.0);
                    if (MinecraftMachinesDuopodCemTrainer.shouldReplaceBestGenome(
                            deliberatelyWorse,
                            config.curriculumStage(),
                            "linear_tanh",
                            config)) {
                        throw new GameTestAssertException("Expected a worse candidate under the same protocol to preserve the checkpoint");
                    }
                    if (!MinecraftMachinesDuopodCemTrainer.shouldReplaceBestGenome(
                            deliberatelyWorse,
                            longerHorizon.curriculumStage(),
                            "linear_tanh",
                            longerHorizon)) {
                        throw new GameTestAssertException("Expected the first candidate under a new protocol to establish its own score scale");
                    }
                    final JsonObject v2FitnessContract = json.deepCopy();
                    v2FitnessContract.addProperty("fitnessContract", "minecraft_machines:duopod_cem_fitness_v2");
                    try {
                        Files.writeString(namedCheckpoint, v2FitnessContract.toString());
                    } catch (final IOException e) {
                        throw new GameTestAssertException("Could not create v2 fitness-contract checkpoint fixture: " + e.getMessage());
                    }
                    if (MinecraftMachinesDuopodCemTrainer.loadCheckpoint(
                            level.getServer().createCommandSourceStack(),
                            "gametest_named") != 1) {
                        throw new GameTestAssertException("Expected the v2 fitness-contract checkpoint to remain loadable as a seed");
                    }
                    final ContinuousCemDistribution v2ContractRestart = MinecraftMachinesDuopodCemTrainer.initialDistribution(
                            policy,
                            seed + 30L,
                            config);
                    if (v2ContractRestart.generation() != 0
                            || v2ContractRestart.bestFitness() != Double.NEGATIVE_INFINITY) {
                        throw new GameTestAssertException("Expected v2 fitness scores to restart instead of exact-resuming under the v6 contract");
                    }
                    final JsonObject legacyWithoutFitnessContract = json.deepCopy();
                    legacyWithoutFitnessContract.remove("fitnessContract");
                    try {
                        Files.writeString(namedCheckpoint, legacyWithoutFitnessContract.toString());
                    } catch (final IOException e) {
                        throw new GameTestAssertException("Could not create legacy checkpoint fixture: " + e.getMessage());
                    }
                    if (MinecraftMachinesDuopodCemTrainer.loadCheckpoint(
                            level.getServer().createCommandSourceStack(),
                            "gametest_named") != 1) {
                        throw new GameTestAssertException("Expected legacy checkpoint fixture to remain loadable as a seed");
                    }
                    final ContinuousCemDistribution legacyRestart = MinecraftMachinesDuopodCemTrainer.initialDistribution(
                            policy,
                            seed + 3L,
                            config);
                    if (legacyRestart.generation() != 0 || legacyRestart.bestFitness() != Double.NEGATIVE_INFINITY) {
                        throw new GameTestAssertException("Expected a checkpoint without a fitness contract to restart without reusing its score");
                    }
                    if (MinecraftMachinesDuopodCemTrainer.loadCheckpoint(level.getServer().createCommandSourceStack()) != 1) {
                        throw new GameTestAssertException("Expected current checkpoint to reload after the legacy compatibility assertion");
                    }
                    try {
                        Files.writeString(checkpoint, "{corrupt primary checkpoint");
                    } catch (final IOException e) {
                        throw new GameTestAssertException("Could not create corrupt primary checkpoint fixture: " + e.getMessage());
                    }
                    if (MinecraftMachinesDuopodCemTrainer.loadCheckpoint(level.getServer().createCommandSourceStack()) != 1) {
                        throw new GameTestAssertException("Expected checkpoint load to recover from the validated backup");
                    }
                    if (MinecraftMachinesDuopodCemTrainer.saveCheckpoint(level.getServer().createCommandSourceStack()) != 1) {
                        throw new GameTestAssertException("Expected a recovered checkpoint to heal the corrupt primary atomically");
                    }
                    try {
                        JsonParser.parseString(Files.readString(checkpoint)).getAsJsonObject();
                        JsonParser.parseString(Files.readString(checkpointBackup)).getAsJsonObject();
                    } catch (final IOException | IllegalStateException e) {
                        throw new GameTestAssertException("Expected valid primary and recovery checkpoint JSON after healing: " + e.getMessage());
                    }
                })
                .thenSucceed();
    }

    private static SpawnedDuopod spawnDuopod(final GameTestHelper helper) {
        return spawnDuopod(helper, DUOPOD_CENTER, UUID.randomUUID());
    }

    private static int executeCommand(final ServerLevel level, final CommandSourceStack source, final String command) {
        try {
            final int result = level.getServer().getCommands().getDispatcher().execute(command, source);
            if (result != 1) {
                throw new GameTestAssertException("Command returned " + result + ": " + command);
            }
            return result;
        } catch (final CommandSyntaxException e) {
            throw new GameTestAssertException("Command failed: " + command + " - " + e.getMessage());
        }
    }

    private static SpawnedDuopod spawnDuopod(final GameTestHelper helper, final BlockPos centerLocalPos, final UUID batchId) {
        final ServerLevel level = helper.getLevel();
        final BlockPos center = helper.absolutePos(centerLocalPos);
        clearSpawnLayer(level, center);
        final MinecraftMachinesDuopodSpawner.SpawnResult result = MinecraftMachinesDuopodSpawner.spawn(
                level,
                center,
                DUOPOD_FORWARD,
                batchId);
        if (!result.success() || result.duopod() == null) {
            throw new GameTestAssertException("Expected Duopod spawn to succeed: " + result.message());
        }
        final MinecraftMachinesDuopodControl control = new MinecraftMachinesDuopodControl(level, result.duopod());
        if (!control.isValid()) {
            throw new GameTestAssertException("Expected newly spawned Duopod control object to be valid");
        }
        return new SpawnedDuopod(result.duopod(), control);
    }

    private static void prepareVectorEnvironmentSpawnArea(
            final ServerLevel level,
            final BlockPos origin,
            final int slotCount,
            final int spacingBlocks,
            final Direction forwardDirection
    ) {
        final Direction right = forwardDirection.getClockWise();
        for (int slot = 0; slot < slotCount; slot++) {
            final BlockPos requested = origin.relative(right, slot * spacingBlocks);
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    level.setBlockAndUpdate(requested.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                    for (int y = 1; y <= 6; y++) {
                        level.setBlockAndUpdate(requested.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private static Map<BlockPos, BlockState> snapshotBlockStates(final ServerLevel level, final BlockPos center) {
        final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = -4; y <= 1; y++) {
                    final BlockPos pos = center.offset(x, y, z);
                    states.put(pos, level.getBlockState(pos));
                }
            }
        }
        return states;
    }

    private static Set<UUID> snapshotSubLevelIds(final ServerLevel level) {
        final Set<UUID> subLevelIds = new LinkedHashSet<>();
        for (final ServerSubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            subLevelIds.add(subLevel.getUniqueId());
        }
        return subLevelIds;
    }

    private static Map<UUID, TrainingMachineCollisionRegistry.Entry> snapshotTrainingEntries(
            final ServerLevel level
    ) {
        final Map<UUID, TrainingMachineCollisionRegistry.Entry> entries = new LinkedHashMap<>();
        for (final ServerSubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            final TrainingMachineCollisionRegistry.Entry entry = TrainingMachineCollisionRegistry.entry(subLevel);
            if (entry != null) {
                entries.put(subLevel.getUniqueId(), entry);
            }
        }
        return entries;
    }

    private static void clearSpawnLayer(final ServerLevel level, final BlockPos center) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = -4; y <= 1; y++) {
                    level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void assertTipLoweredAndOnSide(final Vec3 base, final Vec3 tip, final Direction side, final String label) {
        final double verticalDrop = base.y - tip.y;
        if (verticalDrop < 2.0) {
            throw new GameTestAssertException("Expected " + label + " Duopod honey tip to start below the base, drop="
                    + verticalDrop + " base=" + base + " tip=" + tip);
        }
        final double sideOffset = (tip.x - base.x) * side.getStepX() + (tip.z - base.z) * side.getStepZ();
        if (sideOffset < 2.0) {
            throw new GameTestAssertException("Expected " + label + " Duopod honey tip to stay on the "
                    + side + " side of the base, sideOffset=" + sideOffset + " base=" + base + " tip=" + tip);
        }
    }

    private static void assertHoneySpawnGroundClearance(final Map<String, Object> info, final String label) {
        final double leftClearance = numberInfo(info, "left_honey_ground_clearance_blocks");
        final double rightClearance = numberInfo(info, "right_honey_ground_clearance_blocks");
        assertSpawnGroundClearance(
                leftClearance,
                label + " left honey",
                numberInfo(info, "left_honey_tip_position_x"),
                numberInfo(info, "left_honey_tip_position_y"),
                numberInfo(info, "left_honey_tip_position_z"),
                numberInfo(info, "base_position_y"));
        assertSpawnGroundClearance(
                rightClearance,
                label + " right honey",
                numberInfo(info, "right_honey_tip_position_x"),
                numberInfo(info, "right_honey_tip_position_y"),
                numberInfo(info, "right_honey_tip_position_z"),
                numberInfo(info, "base_position_y"));
    }

    private static void assertSpawnGroundClearance(
            final double clearance,
            final String label,
            final double honeyX,
            final double honeyY,
            final double honeyZ,
            final double baseY
    ) {
        if (Math.abs(clearance - EXPECTED_STANDING_SPAWN_CLEARANCE_BLOCKS) > 0.01) {
            throw new GameTestAssertException("Expected " + label + " to spawn at the solver-safe ground clearance "
                    + EXPECTED_STANDING_SPAWN_CLEARANCE_BLOCKS + ", clearance="
                    + clearance + " honey=(" + honeyX + ", " + honeyY + ", " + honeyZ + ") baseY=" + baseY);
        }
    }

    private static void assertImmediateContactReset(final Map<String, Object> info, final String label) {
        assertHoneySpawnGroundClearance(info, label);
        final double bodyUp = numberInfo(info, "body_up_dot_world_up");
        if (bodyUp < 0.999) {
            throw new GameTestAssertException("Expected " + label + " to start upright, bodyUp=" + bodyUp);
        }
        assertNear(0.0, numberInfo(info, "height_above_spawn_blocks"), 1.0e-6, label + " spawn height");
        assertNear(0.0, numberInfo(info, "local_forward_velocity"), 1.0e-6, label + " forward velocity");
        assertNear(0.0, numberInfo(info, "local_lateral_velocity"), 1.0e-6, label + " lateral velocity");
        assertNear(0.0, numberInfo(info, "local_vertical_velocity"), 1.0e-6, label + " vertical velocity");
        assertNear(0.0, numberInfo(info, "local_roll_rate"), 1.0e-6, label + " roll rate");
        assertNear(0.0, numberInfo(info, "local_pitch_rate"), 1.0e-6, label + " pitch rate");
        assertNear(0.0, numberInfo(info, "local_yaw_rate"), 1.0e-6, label + " yaw rate");
    }

    private static void assertNeutralAutoResetStep(
            final EnvironmentBatchStep step,
            final int slotCount,
            final String label
    ) {
        assertObservationBatch(step.observations(), slotCount, label + " step");
        assertObservationBatch(step.resetObservations(), slotCount, label + " auto-reset");
        for (int slot = 0; slot < slotCount; slot++) {
            final Map<String, Object> info = step.infos().get(slot);
            if (!step.truncated()[slot] || step.terminated()[slot]) {
                throw new GameTestAssertException("Expected one-step vector environment episodes to truncate without termination, slot="
                        + slot + " terminated=" + step.terminated()[slot] + " truncated=" + step.truncated()[slot]
                        + " info=" + summarizeStepInfo(info));
            }
            if (!Boolean.TRUE.equals(info.get("auto_reset_respawned"))) {
                throw new GameTestAssertException("Expected vector environment time-limit auto-reset to respawn slot " + slot);
            }
            if (Boolean.TRUE.equals(info.get("task_failure_condition"))) {
                throw new GameTestAssertException("Expected " + label + " slot " + slot + " to avoid a spawn-induced task failure");
            }
            final double bodyUp = numberInfo(info, "body_up_dot_world_up");
            final double heightDelta = numberInfo(info, "height_above_spawn_blocks");
            final double verticalVelocity = numberInfo(info, "local_vertical_velocity");
            // A zero-action Duopod is intentionally not a standing controller and can begin
            // toppling during a four-tick interval. This test owns vector stepping and
            // auto-reset semantics, so require valid terminal diagnostics without imposing a
            // locomotion outcome that belongs in the held-out controller benchmark.
            if (!Double.isFinite(bodyUp) || !Double.isFinite(heightDelta) || !Double.isFinite(verticalVelocity)) {
                throw new GameTestAssertException("Expected " + label + " slot " + slot
                        + " to report finite terminal dynamics, bodyUp=" + bodyUp
                        + " heightDelta=" + heightDelta + " verticalVelocity=" + verticalVelocity);
            }
        }
    }

    private static double numberInfo(final Map<String, Object> info, final String key) {
        final Object value = info.get(key);
        if (value instanceof final Number number) {
            return number.doubleValue();
        }
        throw new GameTestAssertException("Expected reset info to include numeric " + key + ", got " + value);
    }

    private static ServerSubLevel assertSubLevelExists(final ServerLevel level, final UUID subLevelId, final String label) {
        final ServerSubLevel subLevel = MinecraftMachinesDuopodSpawner.getServerSubLevel(level, subLevelId);
        if (subLevel == null) {
            throw new GameTestAssertException("Expected Duopod " + label + " body to exist");
        }
        return subLevel;
    }

    private static void assertTrainingEntry(final ServerSubLevel subLevel, final DuopodInstance duopod, final String label) {
        final TrainingMachineCollisionRegistry.Entry entry = TrainingMachineCollisionRegistry.entry(subLevel);
        if (entry == null) {
            throw new GameTestAssertException("Expected Duopod " + label + " body to have a training collision entry");
        }
        if (!entry.machineId().equals(duopod.machineId()) || !entry.batchId().equals(duopod.batchId())) {
            throw new GameTestAssertException("Expected Duopod " + label + " collision entry to match its machine and batch");
        }
        if (!DuopodInstance.MORPHOLOGY_TYPE.equals(entry.morphologyType())) {
            throw new GameTestAssertException("Expected Duopod " + label + " collision entry morphology to be duopod");
        }
    }

    private static void assertDuopodRemoved(final ServerLevel level, final DuopodInstance duopod) {
        if (MinecraftMachinesDuopodSpawner.getServerSubLevel(level, duopod.baseSubLevelId()) != null
                || MinecraftMachinesDuopodSpawner.getServerSubLevel(level, duopod.leftChildSubLevelId()) != null
                || MinecraftMachinesDuopodSpawner.getServerSubLevel(level, duopod.rightChildSubLevelId()) != null) {
            throw new GameTestAssertException("Expected Duopod cleanup to remove all owned physics bodies");
        }
    }

    private static BlockPos[] plotBlocksInsideTwoBatchMachines(
            final ServerLevel level,
            final UUID batchId,
            final DuopodInstance first,
            final DuopodInstance second
    ) {
        return new BlockPos[]{
                plotBlockInsideRegisteredMachine(level, batchId, first.machineId(), "first same-batch Duopod"),
                plotBlockInsideRegisteredMachine(level, batchId, second.machineId(), "second same-batch Duopod")
        };
    }

    private static BlockPos[] plotBlocksInsideTwoBatchMachines(final ServerLevel level, final UUID batchId) {
        UUID firstMachineId = null;
        BlockPos firstPosition = null;
        for (final ServerSubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            final TrainingMachineCollisionRegistry.Entry entry = TrainingMachineCollisionRegistry.entry(subLevel);
            if (entry == null || !entry.batchId().equals(batchId)) {
                continue;
            }
            final BlockPos position = plotBlockInside(subLevel, "registered training body");
            if (firstMachineId == null) {
                firstMachineId = entry.machineId();
                firstPosition = position;
            } else if (!firstMachineId.equals(entry.machineId())) {
                return new BlockPos[]{firstPosition, position};
            }
        }
        throw new GameTestAssertException("Expected two same-batch Duopods with registered collision sublevels; entries="
                + describeTrainingEntries(level, batchId));
    }

    private static BlockPos plotBlockInsideRegisteredMachine(
            final ServerLevel level,
            final UUID batchId,
            final UUID machineId,
            final String label
    ) {
        for (final ServerSubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            final TrainingMachineCollisionRegistry.Entry entry = TrainingMachineCollisionRegistry.entry(subLevel);
            if (entry == null || !entry.batchId().equals(batchId) || !entry.machineId().equals(machineId)) {
                continue;
            }
            return plotBlockInside(subLevel, label);
        }
        throw new GameTestAssertException("Expected registered collision sublevel for " + label
                + " machine=" + machineId + " batch=" + batchId
                + " entries=" + describeTrainingEntries(level, batchId));
    }

    private static BlockPos plotBlockInside(final ServerSubLevel subLevel, final String label) {
        final var bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) {
            throw new GameTestAssertException("Expected " + label + " plot to have bounds");
        }
        return new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
    }

    private static void assertObservationBatch(final double[][] observations, final int expectedRows, final String label) {
        if (observations.length != expectedRows) {
            throw new GameTestAssertException("Expected " + label + " observation batch to contain " + expectedRows + " rows");
        }
        for (int row = 0; row < observations.length; row++) {
            if (observations[row].length != DuopodSchemas.observationSpec().size()) {
                throw new GameTestAssertException("Expected " + label + " observation row " + row + " to match Duopod observation size");
            }
            for (final double value : observations[row]) {
                if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
                    throw new GameTestAssertException("Expected " + label + " observations to be finite and normalized");
                }
            }
        }
    }

    private static int countTrainingEntries(final ServerLevel level, final UUID batchId) {
        int count = 0;
        for (final ServerSubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            final TrainingMachineCollisionRegistry.Entry entry = TrainingMachineCollisionRegistry.entry(subLevel);
            if (entry != null && entry.batchId().equals(batchId)) {
                count++;
            }
        }
        return count;
    }

    private static String describeTrainingEntries(final ServerLevel level, final UUID batchId) {
        final StringBuilder builder = new StringBuilder();
        int count = 0;
        for (final ServerSubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            final TrainingMachineCollisionRegistry.Entry entry = TrainingMachineCollisionRegistry.entry(subLevel);
            if (entry == null || !entry.batchId().equals(batchId)) {
                continue;
            }
            if (count > 0) {
                builder.append(", ");
            }
            builder.append(entry.machineId()).append('/').append(entry.subLevelId());
            count++;
            if (count >= 6) {
                builder.append(", ...");
                break;
            }
        }
        return count == 0 ? "none" : builder.toString();
    }

    private static String describeCollisionLookup(final ServerLevel level, final BlockPos firstPos, final BlockPos secondPos) {
        final StringBuilder builder = new StringBuilder("first=")
                .append(firstPos.toShortString())
                .append(" second=")
                .append(secondPos.toShortString())
                .append(" entries=");
        int count = 0;
        for (final ServerSubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            final TrainingMachineCollisionRegistry.Entry entry = TrainingMachineCollisionRegistry.entry(subLevel);
            if (entry == null) {
                continue;
            }
            count++;
            final var bounds = subLevel.getPlot().getBoundingBox();
            builder.append('[')
                    .append(entry.subLevelId())
                    .append(" batch=").append(entry.batchId())
                    .append(" first=").append(bounds != null && bounds.contains(firstPos.getX(), firstPos.getY(), firstPos.getZ()))
                    .append(" second=").append(bounds != null && bounds.contains(secondPos.getX(), secondPos.getY(), secondPos.getZ()))
                    .append(']');
            if (count >= 4) {
                builder.append("...");
                break;
            }
        }
        return builder.toString();
    }

    private static RoboticServoJointBlockEntity resolveLeftServo(final ServerLevel level, final DuopodInstance duopod) {
        return MinecraftMachinesServoLocator.resolve(
                level,
                duopod.leftServoPosition(),
                duopod.leftServoInstanceId(),
                duopod.leftChildSubLevelId());
    }

    private static RoboticServoJointBlockEntity resolveRightServo(final ServerLevel level, final DuopodInstance duopod) {
        return MinecraftMachinesServoLocator.resolve(
                level,
                duopod.rightServoPosition(),
                duopod.rightServoInstanceId(),
                duopod.rightChildSubLevelId());
    }

    private static BridgeSmokeResult runBridgeSmokeClient(
            final MinecraftMachinesTrainingBridge.BridgeTestHandle handle,
            final CountDownLatch sessionCreated,
            final CountDownLatch allowStep
    ) {
        try (Socket socket = new Socket(handle.host(), handle.port())) {
            socket.setSoTimeout(30_000);
            try (DataInputStream input = new DataInputStream(socket.getInputStream());
                 DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                final ProtocolEnvelope hello = roundTrip(input, output, new ProtocolEnvelope(
                        BridgeMessageType.HELLO,
                        "hello",
                        new JsonObject()));
                assertBridgeEnvelope(hello, BridgeMessageType.HELLO, handle);

                final JsonObject createPayload = new JsonObject();
                createPayload.addProperty("token", handle.token());
                createPayload.addProperty("morphologyId", handle.morphologyId());
                final ProtocolEnvelope created = roundTrip(input, output, new ProtocolEnvelope(
                        BridgeMessageType.CREATE_SESSION,
                        "create",
                        createPayload));
                assertBridgeEnvelope(created, BridgeMessageType.CREATE_SESSION, handle);
                final JsonObject createdPayload = created.payload();
                final String sessionId = createdPayload.get("sessionId").getAsString();
                final JsonObject createdReset = createdPayload.getAsJsonObject("reset");
                assertBridgeObservationPayload(createdReset, handle, "create reset");
                final String initialScenario = createdReset.getAsJsonArray("infos")
                        .get(0).getAsJsonObject().get("curriculum_stage_description").getAsString();
                if (!createdPayload.get("lockstep").getAsBoolean()
                        || !createdPayload.get("physicsPaused").getAsBoolean()) {
                    throw new IllegalStateException("CREATE_SESSION did not advertise paused lockstep physics");
                }
                sessionCreated.countDown();
                if (!allowStep.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for idle lockstep assertion");
                }

                final StepRequest stepRequest = new StepRequest(
                        sessionId,
                        handle.observationSchemaHash(),
                        handle.actionSchemaHash(),
                        new double[][]{{0.0, 0.0}});
                writeEnvelope(output, new ProtocolEnvelope(
                        BridgeMessageType.STEP,
                        "step",
                        TrainingBridgeProtocol.toJsonTree(stepRequest)));
                awaitPendingBridgeStep();
                try (Socket updateSocket = new Socket(handle.host(), handle.port())) {
                    updateSocket.setSoTimeout(30_000);
                    try (DataInputStream updateInput = new DataInputStream(updateSocket.getInputStream());
                         DataOutputStream updateOutput = new DataOutputStream(updateSocket.getOutputStream())) {
                        final JsonObject updatePayload = new JsonObject();
                        updatePayload.addProperty("sessionId", sessionId);
                        updatePayload.add("targets", targetMatrix(handle.slotCount(), 4.0, 0.0));
                        final ProtocolEnvelope rejected = roundTrip(updateInput, updateOutput, new ProtocolEnvelope(
                                BridgeMessageType.UPDATE_TARGETS,
                                "update_pending",
                                updatePayload));
                        assertBridgeError(rejected, "while STEP is pending");
                        final ProtocolEnvelope stepped = readEnvelope(input);
                        assertBridgeEnvelope(stepped, BridgeMessageType.STEP, handle);
                        final StepResponse step = TrainingBridgeProtocol.fromJsonTree(stepped.payload(), StepResponse.class);
                        if (step.observations().length != handle.slotCount()
                                || step.observations()[0].length != handle.observationSize()
                                || step.rewards().length != handle.slotCount()
                                || step.infos().size() != handle.slotCount()) {
                            throw new IllegalStateException("STEP response dimensions did not match bridge specs");
                        }
                        final JsonObject firstInfo = step.infos().getFirst();
                        final int actualActionTicks = firstInfo.get("actual_action_ticks").getAsInt();
                        final boolean lockstep = firstInfo.get("bridge_lockstep").getAsBoolean();
                        if (actualActionTicks != handle.controlTicks() || !lockstep) {
                            throw new IllegalStateException("STEP response did not report exact lockstep action ticks");
                        }
                        final JsonObject metricsPayload = new JsonObject();
                        metricsPayload.addProperty("sessionId", sessionId);
                        final ProtocolEnvelope metrics = roundTrip(input, output, new ProtocolEnvelope(
                                BridgeMessageType.GET_METRICS,
                                "metrics",
                                metricsPayload));
                        assertBridgeEnvelope(metrics, BridgeMessageType.GET_METRICS, handle);
                        if (metrics.payload().get("actual_action_ticks").getAsInt() != handle.controlTicks()
                                || !metrics.payload().get("bridge_lockstep").getAsBoolean()
                                || !metrics.payload().get("physics_paused").getAsBoolean()) {
                            throw new IllegalStateException("GET_METRICS did not report paused lockstep state");
                        }
                        final JsonObject resetPayload = new JsonObject();
                        resetPayload.addProperty("sessionId", sessionId);
                        final ProtocolEnvelope reset = roundTrip(input, output, new ProtocolEnvelope(
                                BridgeMessageType.RESET_ALL,
                                "reset_all",
                                resetPayload));
                        assertBridgeEnvelope(reset, BridgeMessageType.RESET_ALL, handle);
                        assertBridgeObservationPayload(reset.payload(), handle, "explicit reset");
                        final JsonObject resetInfo = reset.payload().getAsJsonArray("infos").get(0).getAsJsonObject();
                        if (resetInfo.get("curriculum_stage_index").getAsInt() != 1
                                || initialScenario.equals(resetInfo.get("curriculum_stage_description").getAsString())) {
                            throw new IllegalStateException("one-slot bridge did not rotate to the next curriculum scenario");
                        }
                        final JsonObject closePayload = new JsonObject();
                        closePayload.addProperty("sessionId", sessionId);
                        final ProtocolEnvelope closed = roundTrip(input, output, new ProtocolEnvelope(
                                BridgeMessageType.CLOSE_SESSION,
                                "close",
                                closePayload));
                        assertBridgeEnvelope(closed, BridgeMessageType.CLOSE_SESSION, handle);
                        if (!closed.payload().get("closed").getAsBoolean()) {
                            throw new IllegalStateException("CLOSE_SESSION did not report closed=true");
                        }
                    }
                }
                return new BridgeSmokeResult(sessionId, handle.observationSize(), handle.controlTicks(), true);
            }
        } catch (final IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CompletionException(e);
        }
    }

    private static ProtocolEnvelope roundTrip(
            final DataInputStream input,
            final DataOutputStream output,
            final ProtocolEnvelope request
    ) throws IOException {
        writeEnvelope(output, request);
        return readEnvelope(input);
    }

    private static void writeEnvelope(
            final DataOutputStream output,
            final ProtocolEnvelope request
    ) throws IOException {
        final byte[] encoded = TrainingBridgeProtocol.encodeLengthPrefixed(
                TrainingBridgeProtocol.encodeEnvelope(request),
                TrainingBridgeProtocol.DEFAULT_MAX_MESSAGE_BYTES);
        output.write(encoded);
        output.flush();
    }

    private static ProtocolEnvelope readEnvelope(final DataInputStream input) throws IOException {
        final int length = input.readInt();
        if (length < 0 || length > TrainingBridgeProtocol.DEFAULT_MAX_MESSAGE_BYTES) {
            throw new IOException("invalid bridge response length " + length);
        }
        final byte[] payload = new byte[length];
        input.readFully(payload);
        return TrainingBridgeProtocol.decodeEnvelope(new String(payload, StandardCharsets.UTF_8));
    }

    private static void awaitPendingBridgeStep() {
        final long deadline = System.nanoTime() + 250_000_000L;
        while (System.nanoTime() < deadline) {
            if (MinecraftMachinesTrainingBridge.hasPendingStepForGameTest()) {
                return;
            }
            try {
                Thread.sleep(1L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for pending bridge STEP", e);
            }
        }
        throw new IllegalStateException("bridge STEP did not become pending");
    }

    private static JsonArray targetMatrix(final int slotCount, final double forwardBlocks, final double rightBlocks) {
        final JsonArray matrix = new JsonArray();
        for (int slot = 0; slot < slotCount; slot++) {
            final JsonArray row = new JsonArray();
            row.add(forwardBlocks);
            row.add(rightBlocks);
            matrix.add(row);
        }
        return matrix;
    }

    private static void assertBridgeEnvelope(
            final ProtocolEnvelope envelope,
            final BridgeMessageType expectedType,
            final MinecraftMachinesTrainingBridge.BridgeTestHandle handle
    ) {
        if (envelope.type() == BridgeMessageType.ERROR) {
            throw new IllegalStateException("bridge returned error: " + envelope.payload().get("message").getAsString());
        }
        if (envelope.type() != expectedType) {
            throw new IllegalStateException("expected bridge response " + expectedType + " but got " + envelope.type());
        }
        if (expectedType == BridgeMessageType.HELLO || expectedType == BridgeMessageType.CREATE_SESSION) {
            final JsonObject payload = envelope.payload();
            if (!handle.morphologyId().equals(payload.get("morphologyId").getAsString())
                    || payload.get("slotCount").getAsInt() != handle.slotCount()
                    || !handle.observationSchemaHash().equals(payload.get("observationSchemaHash").getAsString())
                    || !handle.actionSchemaHash().equals(payload.get("actionSchemaHash").getAsString())) {
                throw new IllegalStateException("bridge specs payload did not match started bridge handle");
            }
        }
    }

    private static void assertBridgeError(final ProtocolEnvelope envelope, final String expectedMessageFragment) {
        if (envelope.type() != BridgeMessageType.ERROR) {
            throw new IllegalStateException("expected bridge error but got " + envelope.type());
        }
        final String message = envelope.payload().get("message").getAsString();
        if (!message.contains(expectedMessageFragment)) {
            throw new IllegalStateException("expected bridge error containing " + expectedMessageFragment + " but got " + message);
        }
    }

    private static void assertBridgeObservationPayload(
            final JsonObject payload,
            final MinecraftMachinesTrainingBridge.BridgeTestHandle handle,
            final String label
    ) {
        if (payload == null) {
            throw new IllegalStateException(label + " payload was missing");
        }
        final JsonArray observations = payload.getAsJsonArray("observations");
        if (observations == null || observations.size() != handle.slotCount()) {
            throw new IllegalStateException(label + " observation slot count mismatch");
        }
        final JsonArray firstObservation = observations.get(0).getAsJsonArray();
        if (firstObservation.size() != handle.observationSize()) {
            throw new IllegalStateException(label + " observation size mismatch");
        }
    }

    private static String summarizeStepInfo(final Map<String, Object> info) {
        return "reason=%s health=%s episodeLength=%s baseY=%s heightAboveSpawn=%s resetRespawned=%s"
                .formatted(
                        info.get("termination_reason"),
                        info.get("health_message"),
                        info.get("episode_length"),
                        info.get("base_position_y"),
                        info.get("height_above_spawn_blocks"),
                        info.get("reset_respawned"));
    }

    private static void assertNear(final double expected, final double actual, final double tolerance, final String label) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new GameTestAssertException("%s expected %.6f but got %.6f".formatted(label, expected, actual));
        }
    }

    private record BridgeSmokeResult(
            String sessionId,
            int observationSize,
            int actualActionTicks,
            boolean lockstep
    ) {
    }

    private record SpawnedDuopod(DuopodInstance duopod, MinecraftMachinesDuopodControl control) {
    }
}
