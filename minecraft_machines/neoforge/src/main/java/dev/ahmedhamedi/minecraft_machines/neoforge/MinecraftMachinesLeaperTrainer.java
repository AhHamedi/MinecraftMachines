package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

/** Seeded cross-entropy search evaluated entirely in the live Sable simulator. */
final class MinecraftMachinesLeaperTrainer {
    private static final long SEED = 2026090612L;
    private static final int GENERATIONS = 24, WARMUP = 100, WALK = 500, HOLD = 100;
    private static final double[][] COMMANDS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, .9}, {0, 1, 0}};
    private final GameTestHelper helper;
    private final int population = Integer.getInteger("minecraft_machines.leaperPopulation", 8);
    private final List<MinecraftMachinesLeaper> rigs = new ArrayList<>();
    private final boolean horrific = MinecraftMachinesLeaperLearning.horrific();
    private final boolean brisk = MinecraftMachinesLeaperLearning.brisk();
    private final int walk = horrific ? 240 : brisk ? 300 : WALK,
            generations = horrific ? 20 : brisk ? 16 : GENERATIONS;
    private final double cadence = MinecraftMachinesLeaperLearning.cadence(),
            slew = MinecraftMachinesLeaperLearning.slew(),
            power = MinecraftMachinesLeaperLearning.power();
    private final long seed = horrific ? 2026090722L : brisk ? 2026090712L : SEED;
    private final Random random = new Random(seed);
    private final double[]
            mean =
                    horrific
                            ? LeaperGait.sprintSeed(
                                    MinecraftMachinesLeaperModel.load("fast").gait())
                            : brisk
                                    ? MinecraftMachinesLeaperModel.load("careful").gait().clone()
                                    : LeaperGait.probeSeed(3),
            std = new double[10],
            scores = new double[population];
    private final double[][] genomes = new double[population][10];
    private final Trial[] trials = new Trial[population];
    private final List<Object> history = new ArrayList<>();
    private double[] best = mean.clone();
    private double bestScore = -Double.MAX_VALUE;
    private int generation, scenario, tick;
    private final long began = System.nanoTime();
    private boolean finished;

    private static class Trial {
        Vector3d start, previous, stop;
        final Vector3d[] toes = new Vector3d[4];
        final double[] clearance = new double[4];
        final int[] steps = new int[4];
        final boolean[] swing = new boolean[4];
        double yaw, lastHeading, path, minUp = 1, anchor, slip, tracking, stopDrift;
        boolean failed;
    }

    MinecraftMachinesLeaperTrainer(GameTestHelper helper) throws Exception {
        this.helper = helper;
        Arrays.fill(std, brisk ? .35 : .65);
        if (brisk) {
            std[6] = .12;
            std[7] = .12;
        }
        for (int i = 0; i < population; i++)
            rigs.add(
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            MinecraftMachinesLeaperLearning.arena(
                                    helper.getLevel(),
                                    i,
                                    Blocks.SMOOTH_STONE,
                                    horrific ? 128 : brisk ? 48 : 34)));
        sample();
        begin();
    }

    void run() {
        helper.runAfterDelay(
                1,
                () -> {
                    if (finished) return;
                    try {
                        step();
                        if (!finished) run();
                    } catch (Exception ex) {
                        close();
                        helper.fail("Leaper training: " + ex);
                    }
                });
    }

    private void begin() {
        tick = 0;
        for (int i = 0; i < population; i++) {
            rigs.get(i).reset(0);
            trials[i] = new Trial();
        }
    }

    private void sample() {
        for (int i = 0; i < population; i++)
            for (int j = 0; j < 10; j++)
                genomes[i][j] =
                        i == 0
                                ? mean[j]
                                : i == 1
                                        ? best[j]
                                        : Math.clamp(
                                                mean[j] + std[j] * random.nextGaussian(), -3, 3);
    }

    private void step() throws Exception {
        ++tick;
        boolean allDone = true;
        for (int i = 0; i < population; i++) {
            var r = rigs.get(i);
            var t = trials[i];
            if (t.failed) continue;
            var pos = r.position();
            var up = r.up();
            if (!r.valid()
                    || !Double.isFinite(pos.x + pos.y + pos.z)
                    || up.y < MinecraftMachinesLeaperLearning.minimumUp()
                    || r.anchorError() > .04) {
                t.failed = true;
                continue;
            }
            allDone = false;
            double forward = COMMANDS[scenario][0],
                    strafe = COMMANDS[scenario][1],
                    turn = COMMANDS[scenario][2];
            double ramp = Math.clamp((tick - WARMUP) / 60.0, 0, 1);
            if (tick > WARMUP + walk) {
                forward = 0;
                strafe = 0;
                turn = 0;
            } else if (scenario != 2)
                turn = Math.clamp(-2 * r.heading() + .4 * r.angularVelocity().y, -.5, .5);
            var action =
                    MinecraftMachinesLeaperLearning.action(
                            genomes[i],
                            Math.max(0, tick - WARMUP) * .05 * cadence,
                            r.heading(),
                            up.x,
                            up.z,
                            forward * ramp,
                            strafe * ramp,
                            turn * ramp);
            r.command(action, .05, slew, power);
            if (tick <= WARMUP) {
                t.start = new Vector3d(pos);
                t.previous = new Vector3d(pos);
                t.lastHeading = r.heading();
                continue;
            }
            if (Math.hypot(pos.x - t.start.x, pos.z - t.start.z)
                            > (horrific ? 115 : brisk ? 38 : 26)
                    || Math.abs(pos.y - t.start.y) > (horrific ? 1.5 : 1)) {
                t.failed = true;
                continue;
            }
            t.minUp = Math.min(t.minUp, up.y);
            t.anchor = Math.max(t.anchor, r.anchorError());
            if (tick <= WARMUP + walk) {
                t.yaw += LeaperGait.wrap(r.heading() - t.lastHeading);
                t.lastHeading = r.heading();
                t.path += Math.hypot(pos.x - t.previous.x, pos.z - t.previous.z);
                t.previous = new Vector3d(pos);
                var q = r.angles();
                for (int j = 0; j < 12; j++) t.tracking += Math.abs(q[j] - action[j]) / 12;
                for (int leg = 0; leg < 4; leg++) {
                    var foot = r.toe(leg);
                    t.clearance[leg] = Math.max(t.clearance[leg], foot.y - 7.18);
                    if (foot.y > 7.28) t.swing[leg] = true;
                    if (foot.y < 7.22 && t.swing[leg]) {
                        t.swing[leg] = false;
                        t.steps[leg]++;
                    }
                    if (t.toes[leg] != null && foot.y < 7.20 && t.toes[leg].y < 7.20)
                        t.slip += Math.hypot(foot.x - t.toes[leg].x, foot.z - t.toes[leg].z);
                    t.toes[leg] = new Vector3d(foot);
                }
                t.stop = new Vector3d(pos);
            } else
                t.stopDrift = Math.max(t.stopDrift, Math.hypot(pos.x - t.stop.x, pos.z - t.stop.z));
            allDone = false;
        }
        if (tick < WARMUP + walk + HOLD && !allDone) return;
        var details = new ArrayList<Object>();
        for (int i = 0; i < population; i++) {
            var t = trials[i];
            var p = rigs.get(i).position();
            double score = -150;
            if (!t.failed && t.stop != null) {
                double dx = t.stop.x - t.start.x, dz = t.stop.z - t.start.z;
                if (scenario != 2) {
                    double progress = -COMMANDS[scenario][0] * dz + COMMANDS[scenario][1] * dx;
                    double lateral = COMMANDS[scenario][0] * dx + COMMANDS[scenario][1] * dz;
                    score =
                            50
                                            * Math.min(
                                                    horrific ? 4 : brisk ? 2 : 1.2,
                                                    progress
                                                            / (walk
                                                                    * .05
                                                                    * (horrific
                                                                            ? 2
                                                                            : brisk ? 1.25 : .4)))
                                    - 3 * Math.abs(lateral)
                                    - 6 * Math.abs(t.yaw)
                                    + 8 * progress / Math.max(.1, t.path);
                } else
                    score =
                            50
                                            * Math.min(
                                                    1.2,
                                                    Math.signum(COMMANDS[scenario][2])
                                                            * t.yaw
                                                            / (walk * .05 * .07))
                                    - 5 * Math.hypot(dx, dz);
                score -=
                        (horrific ? 80 : 200) * (1 - t.minUp)
                                + (brisk ? 2 * t.slip / Math.max(1, t.path) : .6 * t.slip)
                                + 5 * t.tracking / walk
                                + (horrific ? 6 : 10) * t.stopDrift;
                double minClear = Arrays.stream(t.clearance).min().orElse(0);
                score += 10 * Math.clamp(minClear / .25, 0, 1);
                score -= 80 * (1 - Math.clamp(minClear / .12, 0, 1));
                if (brisk) {
                    // Transient clearance at startup must not hide persistent foot dragging.
                    double expected =
                            Math.max(
                                    1,
                                    (walk - 60)
                                            * .05
                                            * cadence
                                            / MinecraftMachinesLeaperLearning.period(genomes[i]));
                    double coverage = Arrays.stream(t.steps).min().orElse(0) / expected;
                    score -= 100 * (1 - Math.clamp(coverage / (horrific ? .5 : .65), 0, 1));
                }
            }
            scores[i] += score / COMMANDS.length;
            details.add(
                    Map.of(
                            "slot",
                            i,
                            "score",
                            score,
                            "failed",
                            t.failed,
                            "yaw",
                            t.yaw,
                            "min_up",
                            t.minUp,
                            "slip",
                            t.slip,
                            "clearance",
                            t.clearance,
                            "stop_drift",
                            t.stopDrift,
                            "steps",
                            t.steps));
        }
        history.add(Map.of("generation", generation, "scenario", scenario, "trials", details));
        if (++scenario < COMMANDS.length) {
            begin();
            return;
        }
        scenario = 0;
        Integer[] rank = new Integer[population];
        for (int i = 0; i < population; i++) rank[i] = i;
        Arrays.sort(rank, Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
        if (scores[rank[0]] > bestScore) {
            bestScore = scores[rank[0]];
            best = genomes[rank[0]].clone();
        }
        int elites = Math.max(2, population / 4);
        for (int j = 0; j < 10; j++) {
            double m = 0;
            for (int k = 0; k < elites; k++) m += genomes[rank[k]][j] / elites;
            double variance = 0;
            for (int k = 0; k < elites; k++)
                variance += Math.pow(genomes[rank[k]][j] - m, 2) / elites;
            mean[j] = .25 * mean[j] + .75 * m;
            std[j] = Math.max(.12, .25 * std[j] + .75 * Math.sqrt(variance));
        }
        MinecraftMachines.LOGGER.info(
                "LEAPER_TRAIN generation={} best={} current={} genome={} wall={}s",
                generation,
                bestScore,
                scores[rank[0]],
                best,
                (System.nanoTime() - began) / 1e9);
        MinecraftMachinesLeaperLearning.write(
                "gait-candidate",
                Map.of(
                        "validated",
                        false,
                        "contract",
                        MinecraftMachinesLeaper.CONTRACT,
                        "policy",
                        horrific ? LeaperGait.SPRINT_VERSION : LeaperGait.VERSION,
                        "seed",
                        seed,
                        "generation",
                        generation,
                        "population",
                        population,
                        "score",
                        bestScore,
                        "gait",
                        best,
                        "wall_seconds",
                        (System.nanoTime() - began) / 1e9,
                        "history",
                        history));
        if (++generation >= generations) {
            close();
            helper.succeed();
            return;
        }
        Arrays.fill(scores, 0);
        sample();
        begin();
    }

    private void close() {
        finished = true;
        rigs.forEach(MinecraftMachinesLeaper::close);
    }
}
