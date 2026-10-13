package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedGaitPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Opt-in accelerated training in the real Minecraft/Sable test server, with separate evaluation. */
final class MinecraftMachinesQuadrupedTrainer {
    private static final int POPULATION = 16;
    private static final int ELITES = 4;
    private static final int GENERATIONS = 40;
    private static final int WARMUP = 60;
    private static final int TRAIN_TICKS = 400;
    private static final int EVAL_TICKS = 1200;
    private static final long SEED = 20260906L;
    private final GameTestHelper helper;
    private final List<MinecraftMachinesQuadruped> rigs = new ArrayList<>();
    private final Random random = new Random(SEED);
    private final double[] mean = new double[QuadrupedGaitPolicy.PARAMETERS];
    private final double[] std = new double[QuadrupedGaitPolicy.PARAMETERS];
    private double[][] genomes = new double[POPULATION][QuadrupedGaitPolicy.PARAMETERS];
    private final Vector3d[] starts = new Vector3d[POPULATION];
    private final double[] scores = new double[POPULATION];
    private final Vector3d[] previousPositions = new Vector3d[POPULATION];
    private final double[] minimumUp = new double[POPULATION];
    private final boolean[] fallen = new boolean[POPULATION];
    private final JsonArray history = new JsonArray();
    private final JsonArray evaluations = new JsonArray();
    private int generation, tick, evaluation;
    private boolean evaluating, finished, initialized;
    private double[] best = new double[QuadrupedGaitPolicy.PARAMETERS];
    private double bestScore = -Double.MAX_VALUE;
    private final Path output = Path.of("quadruped_training");

    MinecraftMachinesQuadrupedTrainer(GameTestHelper helper) {
        this.helper = helper;
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(0, 10, 128);
        Arrays.fill(std, 0.65);
        for (int i = 0; i < POPULATION; i++) {
            BlockPos center = origin.offset(i * 16, 0, 0);
            for (int cx = (center.getX() - 7) >> 4; cx <= (center.getX() + 7) >> 4; cx++)
                for (int cz = (center.getZ() - 200) >> 4; cz <= (center.getZ() + 10) >> 4; cz++) level.setChunkForced(cx, cz, true);
            for (int x = -7; x <= 7; x++) for (int z = -200; z <= 10; z++) {
                level.setBlockAndUpdate(center.offset(x, -4, z), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = -3; y < 7; y++) level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            rigs.add(MinecraftMachinesQuadruped.spawn(level, center));
        }
        sample();
    }

    void run() {
        helper.runAfterDelay(1, () -> {
            if (finished) return;
            try {
                step();
                if (!finished) run();
            } catch (Exception e) {
                close();
                helper.fail("Quadruped training failed: " + e);
            }
        });
    }

    private void beginEpisode() {
        tick = 0;
        Arrays.fill(scores, 0);
        Arrays.fill(minimumUp, 1);
        Arrays.fill(fallen, false);
        for (int i = 0; i < rigs.size(); i++) {
            rigs.get(i).reset();
            starts[i] = rigs.get(i).position();
            previousPositions[i] = new Vector3d(starts[i]);
        }
    }

    private void step() throws Exception {
        tick++;
        if (!initialized) {
            if (tick < 20) return;
            rigs.forEach(MinecraftMachinesQuadruped::captureResetPose);
            initialized = true;
            beginEpisode();
            return;
        }
        int horizon = evaluating ? EVAL_TICKS : TRAIN_TICKS;
        for (int i = 0; i < rigs.size(); i++) {
            var rig = rigs.get(i);
            if (evaluating && evaluation >= 3 && tick == WARMUP + 300) rig.disturb(0.15, 0.08);
            if (tick <= WARMUP) {
                double[] rest = QuadrupedGaitPolicy.action(genomes[i], 0, 0, 0, 0);
                double ramp = Math.min(1, tick / 40.0);
                for (int j = 0; j < 8; j++) rest[j] *= ramp;
                rig.command(rest);
                if (tick == WARMUP) { starts[i] = rig.position(); previousPositions[i] = new Vector3d(starts[i]); }
                continue;
            }
            Vector3d position = rig.position();
            double up = rig.upright();
            minimumUp[i] = Math.min(minimumUp[i], up);
            double heightDrop = position.y - starts[i].y;
            boolean bad = !rig.valid() || !Double.isFinite(position.x + position.y + position.z + up)
                    || up < 0.8 || heightDrop < -0.65 || heightDrop > 1.0 || Math.abs(position.x - starts[i].x) > 4;
            if (bad) fallen[i] = true;
            if (fallen[i]) { rig.command(new double[8]); continue; }
            double forward = starts[i].z - position.z;
            double sideways = Math.abs(position.x - starts[i].x);
            scores[i] += Math.clamp(previousPositions[i].z - position.z, -0.1, 0.1)
                    - 0.02 * (1 - up) - 0.01 * sideways - 0.01 * Math.abs(rig.heading());
            previousPositions[i] = new Vector3d(position);
            if ((tick - WARMUP) % 2 == 0) {
                Vector3d bodyUp = rig.up();
                double scale = evaluating ? (i == 2 ? 0 : new double[]{0.65, 1, 1.3}[evaluation % 3]) : 1;
                rig.command(QuadrupedGaitPolicy.action(genomes[i], evaluating && i == 2 ? 0 : (tick - WARMUP) * 0.05, bodyUp.x, bodyUp.z, scale, 3 * rig.heading() + 0.15 * (position.x - starts[i].x)));
            }
            if (Math.abs(forward) > 190) fallen[i] = true;
        }
        if (tick < WARMUP + horizon) return;
        if (!evaluating) finishGeneration(); else finishEvaluation();
    }

    private void finishGeneration() throws Exception {
        Integer[] order = new Integer[POPULATION];
        for (int i = 0; i < POPULATION; i++) {
            order[i] = i;
            if (fallen[i]) scores[i] -= 30;
        }
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
        if (scores[order[0]] > bestScore) { bestScore = scores[order[0]]; best = genomes[order[0]].clone(); }
        JsonObject record = new JsonObject();
        record.addProperty("generation", generation);
        record.addProperty("best_score", scores[order[0]]);
        record.addProperty("best_forward_blocks", starts[order[0]].z - rigs.get(order[0]).position().z);
        record.addProperty("survivors", java.util.stream.IntStream.range(0, POPULATION).filter(i -> !fallen[i]).count());
        history.add(record);
        MinecraftMachines.LOGGER.info("QUADRUPED_TRAIN {}", record);
        for (int j = 0; j < mean.length; j++) {
            double eliteMean = 0;
            for (int k = 0; k < ELITES; k++) eliteMean += genomes[order[k]][j] / ELITES;
            double variance = 0;
            for (int k = 0; k < ELITES; k++) variance += Math.pow(genomes[order[k]][j] - eliteMean, 2) / ELITES;
            mean[j] = 0.2 * mean[j] + 0.8 * eliteMean;
            std[j] = Math.max(0.10, 0.2 * std[j] + 0.8 * Math.sqrt(variance));
        }
        generation++;
        write(false);
        if (generation == GENERATIONS) {
            evaluating = true;
            // Keep three independently assembled copies: trained, initial gait, stationary control.
            for (int i = POPULATION - 1; i >= 3; i--) rigs.remove(i).close();
            genomes[0] = best.clone(); genomes[1] = new double[mean.length]; genomes[2] = new double[mean.length];
        } else sample();
        beginEpisode();
    }

    private void sample() {
        for (int i = 0; i < POPULATION; i++) for (int j = 0; j < mean.length; j++)
            genomes[i][j] = Math.clamp(mean[j] + std[j] * random.nextGaussian(), -3, 3);
        genomes[0] = mean.clone();
        if (generation > 0) genomes[1] = best.clone();
    }

    private void finishEvaluation() throws Exception {
        for (int i = 0; i < rigs.size(); i++) {
            JsonObject result = new JsonObject();
            result.addProperty("trial", evaluation);
            result.addProperty("disturbance", evaluation >= 3 ? "lateral_velocity_0.15_roll_velocity_0.08_at_15s" : "none");
            result.addProperty("controller", i == 0 ? "trained" : i == 1 ? "initial_gait" : "stationary");
            result.addProperty("forward_blocks", starts[i].z - rigs.get(i).position().z);
            result.addProperty("lateral_blocks", Math.abs(starts[i].x - rigs.get(i).position().x));
            result.addProperty("minimum_up_dot", minimumUp[i]);
            result.addProperty("final_heading_radians", rigs.get(i).heading());
            result.addProperty("fell", fallen[i]);
            result.addProperty("seconds", EVAL_TICKS * 0.05);
            evaluations.add(result);
            MinecraftMachines.LOGGER.info("QUADRUPED_EVAL {}", result);
        }
        evaluation++;
        write(evaluation == 6);
        if (evaluation < 6) { beginEpisode(); return; }
        boolean accepted = true;
        for (var entry : evaluations) {
            var r = entry.getAsJsonObject();
            if (r.get("controller").getAsString().equals("trained"))
                accepted &= !r.get("fell").getAsBoolean() && r.get("forward_blocks").getAsDouble() >= 12;
        }
        close();
        helper.assertTrue(accepted, "Trained quadruped must walk >=12 blocks in every 60-second test without falling; see quadruped_training/result.json");
        helper.succeed();
    }

    private void write(boolean complete) throws Exception {
        Files.createDirectories(output);
        JsonObject result = new JsonObject();
        result.addProperty("morphology", MinecraftMachinesQuadruped.CONTRACT);
        result.addProperty("policy", QuadrupedGaitPolicy.VERSION);
        result.addProperty("seed", SEED);
        result.addProperty("complete", complete);
        result.addProperty("physics", "Minecraft 1.21.1 / Sable 2.0.0 / Rapier");
        result.addProperty("world_constraints", false);
        result.addProperty("generations", generation);
        result.addProperty("best_training_score", bestScore);
        result.add("genome", new com.google.gson.Gson().toJsonTree(best));
        result.add("training", history);
        result.add("evaluation", evaluations);
        Path temporary = output.resolve("result.json.tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(result));
        Files.move(temporary, output.resolve("result.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void close() { finished = true; for (var rig : rigs) rig.close(); }
}
