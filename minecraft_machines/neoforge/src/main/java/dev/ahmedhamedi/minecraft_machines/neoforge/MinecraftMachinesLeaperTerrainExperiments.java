package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonParser;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperTerrainExperiments {
    @GameTest(template = "empty", timeoutTicks = 160000, batch = "leaperTerrainExperiments")
    public static void terrainSearchAndQualification(GameTestHelper helper) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!Set.of(
                        "terrain-courses",
                        "terrain-search",
                        "terrain-qualify",
                        "terrain-routes",
                        "terrain-fresh",
                        "terrain-holdout")
                .contains(mode)) {
            helper.succeed();
            return;
        }
        new Run(helper, mode).begin();
    }

    static MinecraftMachinesLeaperTerrainParameters candidate() throws Exception {
        var file =
                Path.of(
                        "leaper_learning",
                        MinecraftMachinesLeaperLearning.reportName("terrain-candidate") + ".json");
        if (!Files.exists(file)) return MinecraftMachinesLeaperTerrainParameters.seed();
        var a =
                JsonParser.parseString(Files.readString(file))
                        .getAsJsonObject()
                        .getAsJsonArray("genome");
        double[] p = new double[a.size()];
        for (int i = 0; i < p.length; i++) p[i] = a.get(i).getAsDouble();
        return new MinecraftMachinesLeaperTerrainParameters(p);
    }

    static List<MinecraftMachinesLeaperTerrainCourse> courses(boolean training) {
        var cases = new ArrayList<MinecraftMachinesLeaperTerrainCourse>();
        if (training) {
            cases.add(
                    new MinecraftMachinesLeaperTerrainCourse(
                            "train-stairs-up", 0, 1, 4, 0, 0, "horrific", 34, 31));
            cases.add(
                    new MinecraftMachinesLeaperTerrainCourse(
                            "train-stairs-down", 1, 1, 4, 0, .2, "horrific", 34, 32));
            cases.add(
                    new MinecraftMachinesLeaperTerrainCourse(
                            "train-broken-ground", 3, .5, 4, 0, 0, "horrific", 28, 33));
            cases.add(
                    new MinecraftMachinesLeaperTerrainCourse(
                            "train-half-stairs", 0, .5, 3, Math.PI / 2, 1.3, "careful", 30, 34));
        } else {
            for (int pace = 0; pace < 2; pace++) {
                String p = pace == 0 ? "careful" : "horrific";
                cases.add(
                        new MinecraftMachinesLeaperTerrainCourse(
                                "full-stairs-up-" + p, 0, 1, 4, 0, .1, p, 42, 81));
                cases.add(
                        new MinecraftMachinesLeaperTerrainCourse(
                                "full-stairs-down-" + p, 1, 1, 4, 0, -.2, p, 42, 82));
                cases.add(
                        new MinecraftMachinesLeaperTerrainCourse(
                                "half-stairs-up-" + p, 0, .5, 2, Math.PI / 2, 1.4, p, 38, 83));
                cases.add(
                        new MinecraftMachinesLeaperTerrainCourse(
                                "half-stairs-down-" + p, 1, .5, 2, -Math.PI / 2, -1.3, p, 38, 84));
                cases.add(
                        new MinecraftMachinesLeaperTerrainCourse(
                                "broken-ground-" + p, 3, .5, 3, 0, .3, p, 36, 85));
                cases.add(
                        new MinecraftMachinesLeaperTerrainCourse(
                                "diagonal-ascent-" + p, 0, 1, 5, Math.PI / 4, .4, p, 44, 86));
                cases.add(
                        new MinecraftMachinesLeaperTerrainCourse(
                                "cross-slope-" + p, 4, .5, 4, 0, 0, p, 38, 87));
                cases.add(
                        new MinecraftMachinesLeaperTerrainCourse(
                                "climb-and-descend-" + p, 2, .5, 3, 0, 0, p, 48, 88));
            }
        }
        return cases;
    }

    static class Run {
        final GameTestHelper helper;
        final String mode;
        final boolean train;
        final List<MinecraftMachinesLeaperTerrainCourse> courses;
        final List<Object> records = new ArrayList<>(), generations = new ArrayList<>();
        final Map<Integer, Vector3d> starts = new HashMap<>();
        final Random random = new Random(2026090731L);
        final double[][] genomes = new double[8][MinecraftMachinesLeaperTerrainParameters.COUNT];
        final double[] scores = new double[8],
                mean,
                sd = new double[MinecraftMachinesLeaperTerrainParameters.COUNT];
        final List<MinecraftMachinesLeaperTerrainEpisode> episodes = new ArrayList<>();
        double[] best;
        double bestScore = -1e9;
        int generation, scenario, batch, tick;
        long began = System.nanoTime();

        Run(GameTestHelper helper, String mode) throws Exception {
            this.helper = helper;
            this.mode = mode;
            train = mode.equals("terrain-search");
            courses = courses(train);
            if (mode.equals("terrain-routes")) {
                courses.clear();
                for (String pace : List.of("careful", "horrific")) {
                    courses.add(
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "wall-detour-" + pace, 5, 1, 4, 0, 0, pace, 40, 93));
                    courses.add(
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "pit-detour-" + pace, 6, 1, 4, 0, 0, pace, 40, 94));
                    courses.add(
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "up-and-down-route-" + pace, 2, 1, 4, 0, .2, pace, 52, 95));
                    courses.add(
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "sideways-ascent-route-" + pace,
                                    0,
                                    1,
                                    4,
                                    Math.PI / 2,
                                    1.0,
                                    pace,
                                    42,
                                    96));
                }
            }
            if (mode.equals("terrain-fresh")) {
                courses.clear();
                for (String pace : List.of("careful", "horrific"))
                    for (int k = 0; k < 12; k++) {
                        int kind = new int[] {0, 1, 3, 4, 2, 0, 7, 8, 3, 0, 1, 2}[k];
                        double rise = new double[] {1, 1, .5, .5, .5, 1, 1, 1, .5, .5, .5, 1}[k];
                        double tread = new double[] {5, 3, 3, 5, 3, 4, 5, 5, 4, 2.5, 2.5, 5}[k];
                        double angle =
                                new double[] {.35, 1.2, -.8, 2.5, .5, -1.2, 0, 0, 1.6, 2.1, -2.1, 0}
                                        [k];
                        double heading = angle + new double[] {-.6, .6, 1.4, 0}[k % 4];
                        double distance = kind == 7 || kind == 8 ? 55 : kind == 2 ? 54 : 44;
                        courses.add(
                                new MinecraftMachinesLeaperTerrainCourse(
                                        "fresh-" + k + "-" + pace,
                                        kind,
                                        rise,
                                        tread,
                                        angle,
                                        heading,
                                        pace,
                                        distance,
                                        101 + k));
                    }
            }
            if (mode.equals("terrain-holdout")) {
                courses.clear();
                var holdoutRandom = new Random(202609075179L);
                for (String pace : List.of("careful", "fast", "horrific"))
                    for (int k = 0; k < 8; k++) {
                        int kind = new int[] {0, 1, 2, 3, 4, 7, 8, 9}[k];
                        double angle = holdoutRandom.nextDouble() * Math.PI * 2 - Math.PI;
                        double heading = angle + (holdoutRandom.nextDouble() - .5) * 1.6;
                        double tread =
                                kind == 7 || kind == 8
                                        ? 4.5 + holdoutRandom.nextDouble()
                                        : 3.5 + 2 * holdoutRandom.nextDouble();
                        double rise = kind == 3 || kind == 4 ? .5 : 1;
                        double distance = kind == 7 || kind == 8 ? 56 : kind == 2 ? 54 : 44;
                        courses.add(
                                new MinecraftMachinesLeaperTerrainCourse(
                                        "holdout-" + k + "-" + pace,
                                        kind,
                                        rise,
                                        tread,
                                        angle,
                                        heading,
                                        pace,
                                        distance,
                                        1000 + holdoutRandom.nextInt(10000)));
                    }
            }
            mean =
                    (mode.equals("terrain-courses")
                                    ? MinecraftMachinesLeaperTerrainParameters.seed()
                                    : candidate())
                            .genome.clone();
            best = mean.clone();
            Arrays.fill(sd, .35);
        }

        void sample() {
            Arrays.fill(scores, 0);
            for (int i = 0; i < 8; i++)
                for (int j = 0; j < MinecraftMachinesLeaperTerrainParameters.COUNT; j++)
                    genomes[i][j] =
                            i == 0
                                    ? best[j]
                                    : i == 1
                                            ? mean[j]
                                            : Math.clamp(
                                                    mean[j] + sd[j] * random.nextGaussian(), -3, 3);
        }

        void begin() throws Exception {
            if (train) sample();
            startBatch();
            loop();
        }

        void startBatch() throws Exception {
            episodes.forEach(e -> e.rig.close());
            episodes.clear();
            tick = 0;
            for (int i = 0; i < 8; i++) {
                int ci = train ? scenario : batch * 8 + i;
                if (ci >= courses.size()) break;
                var course = courses.get(ci);
                int slot = train ? ci * 8 + i : ci;
                var start = starts.computeIfAbsent(slot, k -> course.build(helper.getLevel(), k));
                var rig = MinecraftMachinesLeaper.spawn(helper.getLevel(), start);
                double[] effective = (train ? genomes[i] : best).clone();
                episodes.add(
                        new MinecraftMachinesLeaperTerrainEpisode(
                                rig,
                                new MinecraftMachinesLeaperTerrainMap(helper.getLevel()),
                                course,
                                start,
                                new MinecraftMachinesLeaperTerrainParameters(effective),
                                train ? 2100 : 4400,
                                !train,
                                !train && !mode.equals("terrain-courses")));
            }
            MinecraftMachines.LOGGER.info(
                    "TERRAIN_EXPERIMENT mode={} generation={} scenario={} batch={}",
                    mode,
                    generation,
                    scenario,
                    batch);
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                        } catch (Exception e) {
                            episodes.forEach(t -> t.rig.close());
                            helper.fail("Terrain experiments: " + e);
                        }
                    });
        }

        void step() throws Exception {
            ++tick;
            episodes.forEach(MinecraftMachinesLeaperTerrainEpisode::step);
            if (tick % 400 == 0)
                MinecraftMachines.LOGGER.info(
                        "TERRAIN_PROGRESS gen={} course={} tick={} errors={}",
                        generation,
                        scenario,
                        tick,
                        episodes.stream()
                                .map(
                                        e ->
                                                Math.round(e.error * 10) / 10.0
                                                        + (e.done
                                                                ? e.success ? " pass" : " fail"
                                                                : ""))
                                .toList());
            if (episodes.stream().anyMatch(e -> !e.done)) {
                loop();
                return;
            }
            for (int i = 0; i < episodes.size(); i++) {
                var e = episodes.get(i);
                var r = new LinkedHashMap<String, Object>(e.result());
                r.put("generation", generation);
                r.put("candidate", i);
                records.add(r);
                if (train) scores[i] += e.score() / courses.size();
            }
            if (train) {
                if (++scenario < courses.size()) {
                    startBatch();
                    loop();
                    return;
                }
                scenario = 0;
                Integer[] rank = {0, 1, 2, 3, 4, 5, 6, 7};
                Arrays.sort(rank, Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
                if (scores[rank[0]] > bestScore) {
                    bestScore = scores[rank[0]];
                    best = genomes[rank[0]].clone();
                }
                for (int j = 0; j < MinecraftMachinesLeaperTerrainParameters.COUNT; j++) {
                    double m =
                            (genomes[rank[0]][j] + genomes[rank[1]][j] + genomes[rank[2]][j]) / 3;
                    double variance = 0;
                    for (int k = 0; k < 3; k++)
                        variance += Math.pow(genomes[rank[k]][j] - m, 2) / 3;
                    mean[j] = .3 * mean[j] + .7 * m;
                    sd[j] = Math.max(.10, .35 * sd[j] + .65 * Math.sqrt(variance));
                }
                generations.add(
                        Map.of(
                                "generation",
                                generation,
                                "scores",
                                scores.clone(),
                                "best_score",
                                bestScore,
                                "best",
                                best.clone()));
                MinecraftMachinesLeaperLearning.write(
                        "terrain-candidate",
                        Map.of(
                                "policy",
                                MinecraftMachinesLeaperTerrainControl.VERSION,
                                "seed",
                                2026090731L,
                                "generation",
                                generation,
                                "population",
                                8,
                                "scenarios",
                                courses.size(),
                                "genome",
                                best,
                                "parameters",
                                new MinecraftMachinesLeaperTerrainParameters(best).named(),
                                "score",
                                bestScore,
                                "generations",
                                generations));
                MinecraftMachinesLeaperLearning.write(
                        "terrain-training",
                        Map.of(
                                "episodes",
                                records,
                                "completed",
                                records.size(),
                                "wall_seconds",
                                (System.nanoTime() - began) / 1e9));
                MinecraftMachines.LOGGER.info(
                        "TERRAIN_GENERATION {} score={} params={}",
                        generation,
                        bestScore,
                        new MinecraftMachinesLeaperTerrainParameters(best).named());
                if (++generation
                        < Math.clamp(
                                Integer.getInteger("minecraft_machines.leaperGenerations", 6),
                                1,
                                20)) {
                    sample();
                    startBatch();
                    loop();
                    return;
                }
            } else if (++batch * 8 < courses.size()) {
                startBatch();
                loop();
                return;
            }
            MinecraftMachinesLeaperLearning.write(
                    mode,
                    Map.of(
                            "policy",
                            MinecraftMachinesLeaperTerrainControl.VERSION,
                            "genome",
                            best,
                            "parameters",
                            new MinecraftMachinesLeaperTerrainParameters(best).named(),
                            "trials",
                            records,
                            "wall_seconds",
                            (System.nanoTime() - began) / 1e9));
            episodes.forEach(e -> e.rig.close());
            if (mode.equals("terrain-qualify")
                    || mode.equals("terrain-routes")
                    || mode.equals("terrain-fresh")
                    || mode.equals("terrain-holdout"))
                helper.assertTrue(
                        records.stream()
                                .allMatch(r -> Boolean.TRUE.equals(((Map<?, ?>) r).get("success"))),
                        "Terrain qualification did not pass all trials");
            helper.succeed();
        }
    }
}
