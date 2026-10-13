package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedDirectionalGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedPointController;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Robust CEM over shared physical goal trials. Evaluation goals are defined separately. */
final class MinecraftMachinesQuadrupedNavigationTrainer {
    private static final int POPULATION = 16,
            ELITES = 4,
            GENERATIONS = 28,
            WARMUP = 60,
            HORIZON = 1000;
    private static final long SEED = 2026090602L;
    private static final double[][] GOALS = {{0, -24}, {20, 12}, {-17, 4}, {2.5, -1.5}, {0, 3}};
    private final GameTestHelper helper;
    private final boolean baseline;
    private final List<MinecraftMachinesQuadruped> rigs = new ArrayList<>();
    private final double[] gait;
    private final Random random = new Random(SEED);
    private final double[] mean = new double[10],
            std = new double[10],
            totalScore = new double[POPULATION];
    private final double[][] genomes = new double[POPULATION][10];
    private final Vector3d[] starts = new Vector3d[POPULATION], previous = new Vector3d[POPULATION];
    private final QuadrupedPointController[] controllers = new QuadrupedPointController[POPULATION];
    private final double[] path = new double[POPULATION],
            minimumUp = new double[POPULATION],
            arrivalError = new double[POPULATION];
    private final int[] arrivalTick = new int[POPULATION], doneTick = new int[POPULATION];
    private final boolean[] failed = new boolean[POPULATION];
    private final JsonArray history = new JsonArray(), baselineResults = new JsonArray();
    private double[] best = new double[10];
    private double bestScore = -Double.MAX_VALUE;
    private int generation, scenario, tick;
    private boolean initialized, finished;

    MinecraftMachinesQuadrupedNavigationTrainer(GameTestHelper helper, boolean baseline)
            throws Exception {
        this.helper = helper;
        this.baseline = baseline;
        gait = MinecraftMachinesQuadrupedManager.loadValidatedGenome();
        Arrays.fill(std, .65);
        for (int i = 0; i < (baseline ? GOALS.length : POPULATION); i++)
            rigs.add(
                    MinecraftMachinesQuadruped.spawn(
                            helper.getLevel(),
                            MinecraftMachinesQuadrupedNavigationGameTests.arena(
                                    helper.getLevel(), i, Blocks.SMOOTH_STONE)));
        sample();
        if (baseline) for (double[] genome : genomes) Arrays.fill(genome, 0);
    }

    void run() {
        helper.runAfterDelay(
                1,
                () -> {
                    if (finished) return;
                    try {
                        step();
                        if (!finished) run();
                    } catch (Exception e) {
                        close();
                        helper.fail("Navigation training failed: " + e);
                    }
                });
    }

    private void begin() {
        tick = 0;
        Arrays.fill(path, 0);
        Arrays.fill(minimumUp, 1);
        Arrays.fill(arrivalTick, 0);
        Arrays.fill(doneTick, 0);
        Arrays.fill(failed, false);
        for (int i = 0; i < rigs.size(); i++) {
            rigs.get(i).reset();
            starts[i] = rigs.get(i).position();
            previous[i] = new Vector3d(starts[i]);
            controllers[i] = null;
        }
    }

    private void step() throws Exception {
        tick++;
        if (!initialized) {
            if (tick < 20) return;
            rigs.forEach(MinecraftMachinesQuadruped::captureResetPose);
            initialized = true;
            begin();
            return;
        }
        for (int i = 0; i < rigs.size(); i++) {
            var rig = rigs.get(i);
            if (tick <= WARMUP) {
                double[] rest = QuadrupedDirectionalGait.action(gait, 0, rig.heading(), 0, 0, 0, 0);
                for (int j = 0; j < 8; j++) rest[j] *= Math.min(1, tick / 40.0);
                rig.command(rest);
                if (tick == WARMUP) {
                    starts[i] = rig.position();
                    previous[i] = new Vector3d(starts[i]);
                    double[] goal = GOALS[baseline ? i : scenario];
                    controllers[i] =
                            new QuadrupedPointController(
                                    genomes[i], starts[i].x + goal[0], starts[i].z + goal[1]);
                }
                continue;
            }
            if (doneTick[i] > 0) continue;
            var pos = rig.position();
            double up = rig.upright();
            minimumUp[i] = Math.min(minimumUp[i], up);
            path[i] += Math.hypot(pos.x - previous[i].x, pos.z - previous[i].z);
            previous[i] = pos;
            if (!rig.valid()
                    || !Double.isFinite(pos.x + pos.y + pos.z + up)
                    || up < .9
                    || Math.abs(pos.y - starts[i].y) > 1
                    || Math.abs(pos.x - starts[i].x) > 44
                    || Math.abs(pos.z - starts[i].z) > 44) {
                failed[i] = true;
                doneTick[i] = tick;
                rig.command(new double[8]);
                continue;
            }
            if (tick % 2 != 0) continue;
            var command = controllers[i].update(pos.x, pos.z, rig.heading(), .1);
            var bodyUp = rig.up();
            rig.command(
                    QuadrupedDirectionalGait.action(
                            gait,
                            (tick - WARMUP) * .05,
                            rig.heading(),
                            bodyUp.x,
                            bodyUp.z,
                            command.forward(),
                            command.turn()));
            if (command.arrived() && arrivalTick[i] == 0) {
                arrivalTick[i] = tick;
                arrivalError[i] = command.distance();
            }
            if (arrivalTick[i] > 0) {
                if (command.distance() > .75) {
                    arrivalTick[i] = 0;
                    continue;
                }
                if (tick - arrivalTick[i] >= 100) doneTick[i] = tick;
            }
        }
        boolean done =
                java.util.stream.IntStream.range(0, rigs.size()).allMatch(i -> doneTick[i] > 0);
        if (!done && tick < WARMUP + HORIZON) return;
        int successes = 0;
        for (int i = 0; i < rigs.size(); i++) {
            var pos = rigs.get(i).position();
            var controller = controllers[i];
            double distance = Math.hypot(controller.goalX() - pos.x, controller.goalZ() - pos.z);
            double[] goal = GOALS[baseline ? i : scenario];
            double initialDistance = Math.hypot(goal[0], goal[1]);
            boolean success =
                    !failed[i] && doneTick[i] > 0 && arrivalTick[i] > 0 && distance <= .75;
            if (success) successes++;
            double score =
                    50 * (1 - Math.min(2, distance / initialDistance))
                            + (success ? 50 : 0)
                            - .02 * (success ? arrivalTick[i] - WARMUP : HORIZON)
                            - .3 * path[i] / initialDistance
                            - (failed[i] ? 100 : 0);
            totalScore[i] += score / GOALS.length;
            if (baseline) {
                var result = new JsonObject();
                result.add("goal_offset", new Gson().toJsonTree(goal));
                result.addProperty("success", success);
                result.addProperty("final_distance", distance);
                if (arrivalTick[i] > 0)
                    result.addProperty("arrival_seconds", (arrivalTick[i] - WARMUP) * .05);
                else result.add("arrival_seconds", com.google.gson.JsonNull.INSTANCE);
                result.addProperty("minimum_up", minimumUp[i]);
                result.addProperty("path_blocks", path[i]);
                baselineResults.add(result);
                MinecraftMachines.LOGGER.info("QUADRUPED_NAV_BASELINE {}", result);
            }
        }
        MinecraftMachines.LOGGER.info(
                "QUADRUPED_NAV_EPISODE generation={} scenario={} successes={}/{} ticks={}",
                generation,
                scenario,
                successes,
                rigs.size(),
                tick);
        if (baseline) {
            write();
            close();
            helper.succeed();
            return;
        }
        if (++scenario < GOALS.length) {
            begin();
            return;
        }
        Integer[] order =
                java.util.stream.IntStream.range(0, POPULATION).boxed().toArray(Integer[]::new);
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> totalScore[i]).reversed());
        if (totalScore[order[0]] > bestScore) {
            bestScore = totalScore[order[0]];
            best = genomes[order[0]].clone();
        }
        var record = new JsonObject();
        record.addProperty("generation", generation);
        record.addProperty("generation_best", totalScore[order[0]]);
        record.addProperty("best_score", bestScore);
        record.addProperty("mean_score", Arrays.stream(totalScore).average().orElseThrow());
        history.add(record);
        MinecraftMachines.LOGGER.info("QUADRUPED_NAV_TRAIN {}", record);
        for (int j = 0; j < 10; j++) {
            double em = 0, var = 0;
            for (int k = 0; k < ELITES; k++) em += genomes[order[k]][j] / ELITES;
            for (int k = 0; k < ELITES; k++) var += Math.pow(genomes[order[k]][j] - em, 2) / ELITES;
            mean[j] = .2 * mean[j] + .8 * em;
            std[j] = Math.max(.10, .2 * std[j] + .8 * Math.sqrt(var));
        }
        generation++;
        write();
        if (generation == GENERATIONS) {
            close();
            helper.succeed();
            return;
        }
        scenario = 0;
        Arrays.fill(totalScore, 0);
        sample();
        begin();
    }

    private void sample() {
        for (int i = 0; i < POPULATION; i++)
            for (int j = 0; j < 10; j++)
                genomes[i][j] = Math.clamp(mean[j] + std[j] * random.nextGaussian(), -3, 3);
        genomes[0] = mean.clone();
        if (generation > 0) genomes[1] = best.clone();
    }

    private void write() throws Exception {
        var result = new JsonObject();
        result.addProperty("morphology", MinecraftMachinesQuadruped.CONTRACT);
        result.addProperty("gait_policy", QuadrupedDirectionalGait.VERSION);
        result.addProperty("navigation_policy", QuadrupedPointController.VERSION);
        result.addProperty("seed", SEED);
        result.addProperty("training_complete", generation == GENERATIONS);
        result.addProperty("validated", false);
        result.addProperty("generations", generation);
        result.addProperty("population", POPULATION);
        result.addProperty("best_training_score", bestScore);
        result.add("gait_genome", new Gson().toJsonTree(gait));
        result.add("navigation_genome", new Gson().toJsonTree(best));
        result.add("training_goals", new Gson().toJsonTree(GOALS));
        result.add("history", history);
        result.add("baseline", baselineResults);
        Files.createDirectories(Path.of("quadruped_navigation"));
        Path file =
                Path.of("quadruped_navigation/" + (baseline ? "baseline" : "candidate") + ".json");
        Path temp = Path.of(file + ".tmp");
        Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(result));
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private void close() {
        finished = true;
        rigs.forEach(MinecraftMachinesQuadruped::close);
    }
}
