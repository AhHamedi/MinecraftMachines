package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** CEM scores actual navigation completion, including placement, routing and terminal control. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperTerrainOptimization {
    @GameTest(template = "empty", timeoutTicks = 200000, batch = "leaperTerrainOptimization")
    public static void optimize(GameTestHelper helper) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!Set.of("predator-terrain-train", "predator-terrain-probe", "predator-riser-probe")
                .contains(mode)) {
            helper.succeed();
            return;
        }
        new Search(helper, mode.endsWith("probe")).begin();
    }

    static MinecraftMachinesLeaperTerrainCourse course(int scenario) {
        return new MinecraftMachinesLeaperTerrainCourse(
                new String[] {
                            "train-three-up",
                            "train-three-down",
                            "train-rubble",
                            "train-flat",
                            "train-two-up",
                            "train-two-down",
                            "train-ridges",
                            "train-hills"
                        }
                        [scenario],
                new int[] {0, 1, 3, 9, 0, 1, 11, 10}[scenario],
                new double[] {3, 3, 1.5, 0, 2, 2, 3, 1}[scenario],
                new double[] {4, 4, 3, 4, 2, 2, 4, 3}[scenario],
                new double[] {.2, -.2, .35, 0, -.25, .4, -.35, .55}[scenario],
                new double[] {.1, 0, -.2, 0, .2, -.1, .2, -.2}[scenario],
                "horrific",
                40,
                37000 + scenario);
    }

    static class Search {
        final GameTestHelper helper;
        final boolean probe;
        final Random random = new Random(202609071337L);
        final Map<Integer, Vector3d> starts = new HashMap<>();
        final List<MinecraftMachinesLeaperPredatorRouteTests.Trial> trials = new ArrayList<>();
        final List<Object> episodes = new ArrayList<>(), generations = new ArrayList<>();
        final double[][] population = new double[8][];
        final double[] total = new double[8], worst = new double[8], sd = new double[20];
        double[] best, mean;
        int generation, scenario, tick;
        final long began = System.nanoTime();

        Search(GameTestHelper h, boolean probe) throws Exception {
            helper = h;
            this.probe = probe;
            best = MinecraftMachinesLeaperPredatorExperiments.candidate().genome.clone();
            mean = best.clone();
            Arrays.fill(sd, .24);
        }

        void sample() {
            Arrays.fill(total, 0);
            Arrays.fill(worst, 1e9);
            for (int i = 0; i < 8; i++) {
                population[i] = best.clone();
                for (int j = 0; j < 20; j++)
                    if (i > 1)
                        population[i][j] =
                                Math.clamp(mean[j] + sd[j] * random.nextGaussian(), -3, 3);
                if (i == 1) {
                    var values = new MinecraftMachinesLeaperPredatorPolicy(best).value.clone();
                    values[1] *= .90;
                    values[2] = .62;
                    population[i] = MinecraftMachinesLeaperPredatorPolicy.physical(values).genome;
                }
                if (probe) {
                    var p = new MinecraftMachinesLeaperPredatorPolicy(best).value.clone();
                    if (i == 1) {
                        p[3] = 4.6;
                        p[4] = 3.3;
                    }
                    if (i == 2) {
                        p[2] = .58;
                        p[16] = 7.9;
                        p[17] = 7.4;
                    }
                    if (i == 3) {
                        p[10] = .9;
                        p[11] = .9;
                        p[4] = 3.2;
                    }
                    if (i == 4) {
                        p[0] = 4.7;
                        p[2] = .60;
                        p[16] = 7.9;
                        p[17] = 7.4;
                    }
                    if (i == 5) {
                        p[3] = 4.6;
                        p[4] = 3.3;
                        p[2] = .6;
                        p[10] = .85;
                        p[16] = 7.9;
                        p[17] = 7.4;
                    }
                    if (i == 6) {
                        p[5] = 1.1;
                        p[8] = .35;
                        p[2] = .6;
                    }
                    if (i == 7) {
                        p[3] = 4.7;
                        p[4] = 3.3;
                        p[5] = 1.2;
                        p[8] = .4;
                        p[10] = .9;
                        p[16] = 7.9;
                        p[17] = 7.4;
                    }
                    if (MinecraftMachinesLeaperLearning.mode().equals("predator-riser-probe")) {
                        p = new MinecraftMachinesLeaperPredatorPolicy(best).value.clone();
                        if (i == 1) p[10] = .7;
                        if (i == 2) p[10] = .85;
                        if (i == 3) p[10] = .999;
                        if (i == 4) {
                            p[10] = .85;
                            p[11] = .85;
                        }
                        if (i == 5) {
                            p[3] = 4.9;
                            p[4] = 3.45;
                            p[10] = .9;
                        }
                        if (i == 6) {
                            p[5] = 1.2;
                            p[8] = .5;
                            p[10] = .85;
                        }
                        if (i == 7) {
                            p[2] = .70;
                            p[1] = 1.2;
                            p[10] = .85;
                        }
                    }
                    population[i] = MinecraftMachinesLeaperPredatorPolicy.physical(p).genome;
                }
            }
        }

        void begin() {
            sample();
            batch();
            loop();
        }

        void batch() {
            trials.forEach(t -> t.rig.close());
            trials.clear();
            tick = 0;
            for (int i = 0; i < 8; i++) {
                int slot = scenario * 8 + i;
                var course =
                        MinecraftMachinesLeaperLearning.mode().equals("predator-riser-probe")
                                ? new MinecraftMachinesLeaperTerrainCourse(
                                        "head-on-three-block-ascent",
                                        0,
                                        3,
                                        3,
                                        0,
                                        0,
                                        "horrific",
                                        48,
                                        29071)
                                : course(scenario);
                var start = starts.computeIfAbsent(slot, k -> course.build(helper.getLevel(), k));
                var trial =
                        new MinecraftMachinesLeaperPredatorRouteTests.Trial(
                                helper,
                                slot,
                                new MinecraftMachinesLeaperPredatorPolicy(population[i]),
                                course,
                                start);
                trial.horizon = 1280;
                trials.add(trial);
            }
            MinecraftMachines.LOGGER.info(
                    "TERRAIN_OPT_BATCH generation={} scenario={}", generation, scenario);
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                        } catch (Exception e) {
                            trials.forEach(t -> t.rig.close());
                            helper.fail("Terrain optimization: " + e);
                        }
                    });
        }

        void step() throws Exception {
            tick++;
            trials.forEach(
                    t -> {
                        t.step();
                        if (t.done) t.rig.close();
                    });
            if (tick % 300 == 0)
                MinecraftMachines.LOGGER.info(
                        "TERRAIN_OPT_PROGRESS gen={} case={} tick={} error={}",
                        generation,
                        scenario,
                        tick,
                        trials.stream()
                                .map(
                                        t ->
                                                String.format(
                                                        Locale.ROOT,
                                                        "%.1f%s",
                                                        t.error,
                                                        t.done
                                                                ? t.success ? " PASS" : " FAIL"
                                                                : ""))
                                .toList());
            if (trials.stream().anyMatch(t -> !t.done)) {
                loop();
                return;
            }
            for (int i = 0; i < 8; i++) {
                var t = trials.get(i);
                double score =
                        120 * (1 - Math.min(2, t.error / t.course.distance()))
                                + (t.success ? 180 : 0)
                                - 2.0 * Math.max(0, t.tick - 80) * .05
                                - 12 * t.nav.recoveries
                                - 20 * (1 - t.minUp)
                                - (t.reason.equals("physical failure") ? 250 : 0);
                total[i] += score / 8;
                worst[i] = Math.min(worst[i], score);
                var row = t.result();
                if (!probe) row.remove("history");
                row.put("generation", generation);
                row.put("candidate", i);
                row.put("genome", population[i]);
                row.put("score", score);
                episodes.add(row);
            }
            MinecraftMachinesLeaperLearning.write(
                    probe ? "predator-terrain-probe" : "predator-terrain-training",
                    Map.of(
                            "episodes",
                            episodes,
                            "completed",
                            episodes.size(),
                            "seed",
                            202609071337L,
                            "wall_seconds",
                            (System.nanoTime() - began) / 1e9));
            if (probe) {
                trials.forEach(t -> t.rig.close());
                helper.succeed();
                return;
            }
            if (++scenario < 8) {
                batch();
                loop();
                return;
            }
            scenario = 0;
            Integer[] rank = {0, 1, 2, 3, 4, 5, 6, 7};
            double[] score = new double[8];
            for (int i = 0; i < 8; i++) score[i] = .7 * total[i] + .3 * worst[i];
            Arrays.sort(rank, Comparator.comparingDouble((Integer i) -> score[i]).reversed());
            best = population[rank[0]].clone();
            for (int j = 0; j < 20; j++) {
                double m = 0, v = 0;
                for (int i = 0; i < 3; i++) m += population[rank[i]][j] / 3;
                for (int i = 0; i < 3; i++) v += Math.pow(population[rank[i]][j] - m, 2) / 3;
                mean[j] = .2 * mean[j] + .8 * m;
                sd[j] = Math.max(.07, .25 * sd[j] + .75 * Math.sqrt(v));
            }
            generations.add(
                    Map.of(
                            "generation",
                            generation,
                            "scores",
                            score,
                            "selected",
                            rank[0],
                            "genome",
                            best.clone()));
            MinecraftMachinesLeaperLearning.write(
                    "predator-candidate",
                    Map.of(
                            "genome",
                            best,
                            "parameters",
                            new MinecraftMachinesLeaperPredatorPolicy(best).named(),
                            "generations",
                            generations));
            MinecraftMachines.LOGGER.info(
                    "TERRAIN_OPT_GENERATION gen={} scores={} selected={} parameters={}",
                    generation,
                    score,
                    rank[0],
                    new MinecraftMachinesLeaperPredatorPolicy(best).named());
            if (++generation < Integer.getInteger("minecraft_machines.leaperGenerations", 4)) {
                sample();
                batch();
                loop();
                return;
            }
            trials.forEach(t -> t.rig.close());
            helper.succeed();
        }
    }
}
