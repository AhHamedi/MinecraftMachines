package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonParser;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

/**
 * Native return-driven policy optimization; course geometry never enters the policy observation.
 */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPredatorExperiments {
    @GameTest(template = "empty", timeoutTicks = 240000, batch = "leaperPredator")
    public static void predatorTraining(GameTestHelper helper) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!Set.of(
                        "predator-train",
                        "predator-probe",
                        "predator-evaluate",
                        "predator-slope-train",
                        "predator-slope-evaluate")
                .contains(mode)) {
            helper.succeed();
            return;
        }
        new Run(helper, mode).begin();
    }

    static MinecraftMachinesLeaperPredatorPolicy candidate() throws Exception {
        Path p =
                Path.of(
                        "leaper_learning",
                        MinecraftMachinesLeaperLearning.reportName("predator-candidate") + ".json");
        if (!Files.exists(p)) return MinecraftMachinesLeaperPredatorPolicy.seed();
        var a =
                JsonParser.parseString(Files.readString(p))
                        .getAsJsonObject()
                        .getAsJsonArray("genome");
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsDouble();
        return new MinecraftMachinesLeaperPredatorPolicy(v);
    }

    static MinecraftMachinesLeaperTerrainCourse course(int i) {
        boolean steep = MinecraftMachinesLeaperLearning.mode().startsWith("predator-slope");
        var base =
                switch (i % 4) {
                    case 0 ->
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "full-block-stairs-up",
                                    0,
                                    1,
                                    steep ? 1 : 2,
                                    0,
                                    0,
                                    "horrific",
                                    36,
                                    811);
                    case 1 ->
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "full-block-stairs-down",
                                    1,
                                    1,
                                    steep ? 1 : 2,
                                    0,
                                    0,
                                    "horrific",
                                    36,
                                    812);
                    case 2 ->
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "full-block-broken-ground", 3, 1, 3, 0, 0, "horrific", 36, 813);
                    default ->
                            new MinecraftMachinesLeaperTerrainCourse(
                                    "flat-regression", 9, 0, 4, 0, 0, "horrific", 44, 814);
                };
        if (i < 4) return base;
        int kind = new int[] {0, 1, 10, 11}[i - 4];
        return new MinecraftMachinesLeaperTerrainCourse(
                new String[] {
                            "double-step-up", "double-step-down", "uneven-hills", "two-block-ridges"
                        }
                        [i - 4],
                kind,
                2,
                kind == 11 ? 3 : steep && (kind == 0 || kind == 1) ? 2 : 4,
                .55,
                -.25,
                base.pace(),
                40,
                base.seed() + 37);
    }

    static class Run {
        final GameTestHelper helper;
        final String mode;
        final boolean train;
        final List<Episode> episodes = new ArrayList<>();
        final List<Object> records = new ArrayList<>(), generations = new ArrayList<>();
        final Map<Integer, Vector3d> starts = new HashMap<>();
        final Random random = new Random(20260907911L);
        final double[][] genomes = new double[8][MinecraftMachinesLeaperPredatorPolicy.COUNT];
        final double[] scores = new double[8],
                worst = new double[8],
                mean,
                sd = new double[MinecraftMachinesLeaperPredatorPolicy.COUNT];
        double[] best;
        double bestScore = -1e9;
        int generation, scenario, batch, tick;
        long began = System.nanoTime();

        Run(GameTestHelper h, String m) throws Exception {
            helper = h;
            mode = m;
            train = m.equals("predator-train") || m.equals("predator-slope-train");
            var seed = candidate();
            best = seed.genome.clone();
            var faster = seed.value.clone();
            faster[1] = 1.15;
            faster[0] = 4.2;
            faster[2] = .72;
            if (m.equals("predator-slope-train")) {
                faster[0] = 3.6;
                faster[1] = 1.05;
                faster[3] = 5.0;
                faster[4] = 2.7;
                faster[5] = 1.6;
            }
            mean = MinecraftMachinesLeaperPredatorPolicy.physical(faster).genome;
            Arrays.fill(sd, .18);
        }

        void sample() {
            Arrays.fill(scores, 0);
            Arrays.fill(worst, 1e9);
            for (int i = 0; i < 8; i++)
                for (int j = 0; j < mean.length; j++)
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
            batch();
            loop();
        }

        void batch() throws Exception {
            episodes.forEach(e -> e.rig.close());
            episodes.clear();
            tick = 0;
            for (int i = 0; i < 8; i++) {
                int ci = train ? scenario : (batch * 8 + i) % 4,
                        slot = train ? ci * 8 + i : batch * 8 + i;
                var course = course(ci);
                var start = starts.computeIfAbsent(slot, k -> course.build(helper.getLevel(), k));
                var rig = MinecraftMachinesLeaper.spawn(helper.getLevel(), start);
                var policy = new MinecraftMachinesLeaperPredatorPolicy(train ? genomes[i] : best);
                if (mode.equals("predator-probe")) {
                    int profile = slot / 4;
                    var p = policy.value.clone();
                    if (profile == 1) {
                        p[16] = 6;
                        p[17] = 6;
                    }
                    if (profile == 2) {
                        p[2] = .75;
                        p[1] = 1.3;
                        p[16] = 6;
                        p[17] = 6;
                    }
                    if (profile == 3) {
                        p[5] = 1.8;
                        p[4] = 3;
                        p[16] = 6;
                        p[17] = 6;
                    }
                    policy = MinecraftMachinesLeaperPredatorPolicy.physical(p);
                }
                episodes.add(
                        new Episode(
                                rig,
                                new MinecraftMachinesLeaperTerrainMap(helper.getLevel()),
                                course,
                                start,
                                policy,
                                train ? 1000 : 1600,
                                !train));
            }
            MinecraftMachines.LOGGER.info(
                    "PREDATOR_BATCH mode={} gen={} scenario={} batch={}",
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
                            episodes.forEach(ep -> ep.rig.close());
                            helper.fail("Predator: " + e);
                        }
                    });
        }

        void step() throws Exception {
            ++tick;
            episodes.forEach(Episode::step);
            episodes.stream().filter(e -> e.done).forEach(e -> e.rig.close());
            if (tick % 300 == 0)
                MinecraftMachines.LOGGER.info(
                        "PREDATOR_PROGRESS tick={} error={}",
                        tick,
                        episodes.stream()
                                .map(
                                        e ->
                                                String.format(
                                                        Locale.ROOT,
                                                        "%.1f%s",
                                                        e.error,
                                                        e.done
                                                                ? e.success ? " pass" : " fail"
                                                                : ""))
                                .toList());
            if (episodes.stream().anyMatch(e -> !e.done)) {
                loop();
                return;
            }
            for (int i = 0; i < episodes.size(); i++) {
                var e = episodes.get(i);
                var r = e.result();
                r.put("generation", generation);
                r.put("candidate", train ? i : batch * 8 + i);
                records.add(r);
                if (train) {
                    scores[i] += e.score() / 8;
                    worst[i] = Math.min(worst[i], e.score());
                }
            }
            if (train)
                MinecraftMachinesLeaperLearning.write(
                        "predator-training",
                        Map.of(
                                "episodes",
                                records,
                                "completed",
                                records.size(),
                                "wall_seconds",
                                (System.nanoTime() - began) / 1e9));
            if (train) {
                if (++scenario < 8) {
                    batch();
                    loop();
                    return;
                }
                scenario = 0;
                for (int i = 0; i < 8; i++) scores[i] = .7 * scores[i] + .3 * worst[i];
                Integer[] rank = {0, 1, 2, 3, 4, 5, 6, 7};
                Arrays.sort(rank, Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
                // Incumbent is reevaluated on the same eight tasks each generation.
                // A lucky historical score cannot freeze selection indefinitely.
                bestScore = scores[rank[0]];
                best = genomes[rank[0]].clone();
                for (int j = 0; j < mean.length; j++) {
                    double m = 0, v = 0;
                    for (int k = 0; k < 3; k++) m += genomes[rank[k]][j] / 3.;
                    for (int k = 0; k < 3; k++) v += Math.pow(genomes[rank[k]][j] - m, 2) / 3.;
                    mean[j] = .25 * mean[j] + .75 * m;
                    sd[j] = Math.max(.08, .3 * sd[j] + .7 * Math.sqrt(v));
                }
                generations.add(
                        Map.of(
                                "generation",
                                generation,
                                "scores",
                                scores.clone(),
                                "best_score",
                                bestScore,
                                "genome",
                                best.clone()));
                MinecraftMachinesLeaperLearning.write(
                        "predator-candidate",
                        Map.of(
                                "policy",
                                MinecraftMachinesLeaperPredatorPolicy.VERSION,
                                "genome",
                                best,
                                "parameters",
                                new MinecraftMachinesLeaperPredatorPolicy(best).named(),
                                "seed",
                                20260907911L,
                                "generations",
                                generations));
                MinecraftMachinesLeaperLearning.write(
                        "predator-training",
                        Map.of(
                                "episodes",
                                records,
                                "completed",
                                records.size(),
                                "wall_seconds",
                                (System.nanoTime() - began) / 1e9));
                MinecraftMachines.LOGGER.info(
                        "PREDATOR_GENERATION gen={} score={} parameters={}",
                        generation,
                        bestScore,
                        new MinecraftMachinesLeaperPredatorPolicy(best).named());
                if (++generation < Integer.getInteger("minecraft_machines.leaperGenerations", 12)) {
                    sample();
                    batch();
                    loop();
                    return;
                }
            } else if (++batch < (mode.equals("predator-probe") ? 2 : 1)) {
                batch();
                loop();
                return;
            }
            MinecraftMachinesLeaperLearning.write(
                    mode,
                    Map.of(
                            "policy",
                            MinecraftMachinesLeaperPredatorPolicy.VERSION,
                            "trials",
                            records,
                            "wall_seconds",
                            (System.nanoTime() - began) / 1e9));
            episodes.forEach(e -> e.rig.close());
            helper.succeed();
        }
    }

    static class Episode {
        final MinecraftMachinesLeaper rig;
        final MinecraftMachinesLeaperPredatorControl control;
        final MinecraftMachinesLeaperTerrainMap map;
        final MinecraftMachinesLeaperTerrainCourse course;
        final Vector3d start, goal;
        final LeaperPointController nav;
        final int horizon;
        final boolean record;
        LeaperPointController.Command command;
        int tick, settled, arrival, fallen, stalledTicks;
        double error, minUp = 1, anchor, limits, peakSpeed, path, previousError;
        boolean done, success, failed;
        String reason = "";
        Vector3d previous;
        final List<Object> history = new ArrayList<>();
        boolean holdPosition;
        final dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperMotionWindow
                motion =
                        new dev.ahmedhamedi.minecraft_machines.content.training.morphology
                                .LeaperMotionWindow();

        Episode(
                MinecraftMachinesLeaper r,
                MinecraftMachinesLeaperTerrainMap m,
                MinecraftMachinesLeaperTerrainCourse c,
                Vector3d s,
                MinecraftMachinesLeaperPredatorPolicy p,
                int horizon,
                boolean record) {
            rig = r;
            map = m;
            course = c;
            start = s;
            goal = c.goal(s);
            goal.y = m.height(goal.x, goal.z, s.y + 12);
            this.horizon = horizon;
            this.record = record;
            r.reset(c.heading());
            control = new MinecraftMachinesLeaperPredatorControl(r, m, p);
            nav =
                    new LeaperPointController(
                            MinecraftMachinesLeaperModel.load("horrific").navigation(),
                            goal.x,
                            goal.z);
            previous = r.position();
            error = previousError = c.distance();
        }

        void step() {
            if (done) return;
            ++tick;
            var p = rig.position();
            var v = rig.velocity();
            motion.update(p.x, p.z);
            double speed = Math.hypot(v.x, v.z);
            minUp = Math.min(minUp, rig.upright());
            anchor = Math.max(anchor, rig.anchorError());
            limits = Math.max(limits, rig.limitViolation());
            if (rig.upright() < .5) fallen++;
            else fallen = 0;
            if (!rig.valid()
                    || !Double.isFinite(p.x + p.y + p.z)
                    || fallen > 30
                    || anchor > .10
                    || limits > .12
                    || p.y < 0
                    || Math.hypot(p.x - start.x, p.z - start.z) > 62) {
                done = failed = true;
                reason = "fall, joint bounds or left course";
                return;
            }
            if (tick > 80 && (command == null || tick % 2 == 1))
                command = nav.update(p.x, p.z, rig.heading(), .1);
            if (error < .55 && speed < 2) holdPosition = true;
            if (error > .95) holdPosition = false;
            double scale = holdPosition ? 0 : Math.min(1, error / 2.5);
            control.step(
                    command == null ? 0 : command.forward() * scale,
                    command == null ? 0 : command.strafe() * scale,
                    command == null ? 0 : command.turn() * scale,
                    1);
            error = Math.hypot(p.x - goal.x, p.z - goal.z);
            if (tick > 80) {
                path += Math.hypot(p.x - previous.x, p.z - previous.z);
                peakSpeed = Math.max(peakSpeed, speed);
                if (speed < .2 && error > 2) stalledTicks++;
            }
            previous = new Vector3d(p);
            if (error < 1
                    && (speed < .4 || motion.stationary())
                    && Math.abs(map.height(p.x, p.z, p.y + 5) - goal.y) < .6
                    && rig.upright() > .75) {
                if (arrival == 0) arrival = tick;
                if (++settled >= 40) done = success = true;
            } else {
                settled = 0;
                arrival = 0;
            }
            if (tick >= horizon + 80) {
                done = true;
                reason = "timeout";
            }
            if (record && tick % 10 == 0)
                history.add(
                        Map.of(
                                "tick",
                                tick,
                                "position",
                                List.of(p.x, p.y, p.z),
                                "speed",
                                speed,
                                "error",
                                error,
                                "up",
                                rig.upright(),
                                "roughness",
                                control.roughness));
        }

        double score() {
            return 120 * (1 - Math.min(2, error / course.distance()))
                    + (success ? 150 : 0)
                    - (failed ? 250 : 0)
                    - 2.5 * (arrival > 0 ? (arrival - 80) * .05 : horizon * .05)
                    - 25 * (1 - minUp)
                    - .2 * stalledTicks
                    - 2 * path / course.distance();
        }

        Map<String, Object> result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("course", course);
            r.put("success", success);
            r.put("failed", failed);
            r.put("reason", reason);
            r.put("genome", control.policy.genome);
            r.put("parameters", control.policy.named());
            r.put("error", error);
            r.put("arrival_seconds", arrival > 0 ? (arrival - 80) * .05 : null);
            r.put(
                    "mean_progress_speed",
                    arrival > 0 ? course.distance() / ((arrival - 80) * .05) : 0);
            r.put("peak_speed", peakSpeed);
            r.put("minimum_up", minUp);
            r.put("anchor_error", anchor);
            r.put("limit_violation", limits);
            r.put("stalled_seconds", stalledTicks * .05);
            r.put("path", path);
            r.put("score", score());
            if (record) r.put("history", history);
            return r;
        }
    }
}
