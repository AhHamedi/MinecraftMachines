package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/** Destination-controller search on fixed training tasks, separate from held-out tests. */
final class MinecraftMachinesLeaperNavigationTrainer {
    private static final int POP = 8, GENERATIONS = 16;
    private static final long SEED = 2026090613L;
    private static final double[][] GOALS = {
        {0, -14, 0}, {11, 9, 1.2}, {-7, 4, -2}, {.9, -1.2, 2.7}
    };
    private final GameTestHelper helper;
    private final double[] gait,
            mean = new double[10],
            std = new double[10],
            score = new double[POP];
    private final double[][] genomes = new double[POP][10];
    private final boolean brisk = MinecraftMachinesLeaperLearning.brisk();
    private final Random random =
            new Random(
                    MinecraftMachinesLeaperLearning.horrific()
                            ? 2026090723L
                            : brisk ? 2026090713L : SEED);
    private final List<MinecraftMachinesLeaper> rigs = new ArrayList<>();
    private final MinecraftMachinesLeaperEpisode[] episodes =
            new MinecraftMachinesLeaperEpisode[POP];
    private final List<Object> history = new ArrayList<>();
    private final long began = System.nanoTime();
    private double[] best = new double[10];
    private double bestScore = -Double.MAX_VALUE;
    private int generation, scenario;
    private boolean finished;

    MinecraftMachinesLeaperNavigationTrainer(GameTestHelper helper) throws Exception {
        this.helper = helper;
        gait = MinecraftMachinesLeaperNavigationTests.readGait();
        if (brisk) {
            System.arraycopy(
                    MinecraftMachinesLeaperModel.load(
                                    MinecraftMachinesLeaperLearning.horrific() ? "fast" : "careful")
                            .navigation(),
                    0,
                    mean,
                    0,
                    10);
            best = mean.clone();
        }
        Arrays.fill(std, .65);
        for (int i = 0; i < POP; i++)
            rigs.add(
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            MinecraftMachinesLeaperLearning.arena(
                                    helper.getLevel(), i, Blocks.SMOOTH_STONE, 40)));
        sample();
        begin();
    }

    private void sample() {
        for (int i = 0; i < POP; i++)
            for (int j = 0; j < 10; j++)
                genomes[i][j] =
                        i == 0
                                ? mean[j]
                                : i == 1
                                        ? best[j]
                                        : Math.clamp(
                                                mean[j] + std[j] * random.nextGaussian(), -3, 3);
    }

    private void begin() {
        var g = GOALS[scenario];
        for (int i = 0; i < POP; i++)
            episodes[i] =
                    new MinecraftMachinesLeaperEpisode(
                            rigs.get(i),
                            new MinecraftMachinesLeaperModel(
                                    gait,
                                    genomes[i],
                                    MinecraftMachinesLeaperLearning.cadence(),
                                    MinecraftMachinesLeaperLearning.slew(),
                                    MinecraftMachinesLeaperLearning.power(),
                                    MinecraftMachinesLeaperLearning.horrific()),
                            g[0],
                            g[1],
                            g[2],
                            false,
                            brisk ? 1400 : 2200,
                            false,
                            42);
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
                        helper.fail("Leaper navigation training: " + ex);
                    }
                });
    }

    private void step() throws Exception {
        boolean done = true;
        for (var e : episodes) {
            e.step();
            done &= e.done();
        }
        if (!done) return;
        var rows = new ArrayList<Object>();
        for (int i = 0; i < POP; i++) {
            score[i] += episodes[i].score() / GOALS.length;
            var row = episodes[i].result();
            row.put("slot", i);
            row.put("score", episodes[i].score());
            rows.add(row);
        }
        history.add(Map.of("generation", generation, "scenario", scenario, "trials", rows));
        if (++scenario < GOALS.length) {
            begin();
            return;
        }
        scenario = 0;
        Integer[] rank = new Integer[POP];
        for (int i = 0; i < POP; i++) rank[i] = i;
        Arrays.sort(rank, Comparator.comparingDouble((Integer i) -> score[i]).reversed());
        if (score[rank[0]] > bestScore) {
            bestScore = score[rank[0]];
            best = genomes[rank[0]].clone();
        }
        for (int j = 0; j < 10; j++) {
            double m = 0;
            for (int k = 0; k < 3; k++) m += genomes[rank[k]][j] / 3;
            double variance = 0;
            for (int k = 0; k < 3; k++) variance += Math.pow(genomes[rank[k]][j] - m, 2) / 3;
            mean[j] = .25 * mean[j] + .75 * m;
            std[j] = Math.max(.12, .25 * std[j] + .75 * Math.sqrt(variance));
        }
        MinecraftMachines.LOGGER.info(
                "LEAPER_NAV_TRAIN generation={} best={} current={} genome={}",
                generation,
                bestScore,
                score[rank[0]],
                best);
        MinecraftMachinesLeaperLearning.write(
                "navigation-candidate",
                Map.of(
                        "validated",
                        false,
                        "contract",
                        MinecraftMachinesLeaper.CONTRACT,
                        "policy",
                        LeaperPointController.VERSION,
                        "seed",
                        MinecraftMachinesLeaperLearning.horrific()
                                ? 2026090723L
                                : brisk ? 2026090713L : SEED,
                        "generation",
                        generation,
                        "score",
                        bestScore,
                        "gait",
                        gait,
                        "navigation",
                        best,
                        "history",
                        history,
                        "wall_seconds",
                        (System.nanoTime() - began) / 1e9));
        if (++generation >= GENERATIONS) {
            close();
            helper.succeed();
            return;
        }
        Arrays.fill(score, 0);
        sample();
        begin();
    }

    private void close() {
        finished = true;
        rigs.forEach(MinecraftMachinesLeaper::close);
    }
}
