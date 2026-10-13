package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonParser;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Native CEM timing search. Writes a separate candidate; never promotes the active policy. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperClearanceTuning {
    private static final int POPULATION = 8, GENERATIONS = 3, TASKS = 4;
    private static final int[] MUTATED = {
        0, 1, 2
    }; // Stride, period, duty; all others remain exact.
    private static final long SEED = 2026090759001L;

    @GameTest(template = "empty", timeoutTicks = 100000, batch = "leaperClearanceTune")
    public static void tune(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-clearance-tune")) {
            helper.succeed();
            return;
        }
        var run = new Run(helper);
        try {
            run.begin();
        } catch (Exception e) {
            run.fail(e);
        }
    }

    private static MinecraftMachinesLeaperTerrainCourse course(int task) {
        return switch (task) {
            case 0 ->
                    new MinecraftMachinesLeaperTerrainCourse(
                            "three-block-up-head-on", 0, 3, 3, 0, 0, "horrific", 48, 59001);
            case 1 ->
                    new MinecraftMachinesLeaperTerrainCourse(
                            "three-block-up-tilted", 0, 3, 3, .8, -.4, "horrific", 48, 59002);
            case 2 ->
                    new MinecraftMachinesLeaperTerrainCourse(
                            "two-block-up-head-on", 0, 2, 2, 0, 0, "horrific", 48, 59003);
            case 3 ->
                    new MinecraftMachinesLeaperTerrainCourse(
                            "dense-rubble-careful", 3, 1.5, 2, -.6, .9, "careful", 48, 59004);
            default -> throw new IllegalArgumentException("Expected four training tasks");
        };
    }

    private static MinecraftMachinesLeaperPredatorPolicy original() throws Exception {
        var seedFile = Path.of("leaper_learning", "horrific-predator-clearance-seed.json");
        try (var stream =
                Files.exists(seedFile)
                        ? Files.newInputStream(seedFile)
                        : MinecraftMachinesLeaperClearanceTuning.class.getResourceAsStream(
                                "/data/minecraft_machines/leaper/predator.json")) {
            if (stream == null) throw new IllegalStateException("Missing qualified v9 seed");
            var model =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!model.get("validated").getAsBoolean()
                    || !model.get("policy").getAsString().equals("leaper_predator_precise_v9"))
                throw new IllegalStateException("Timing search requires a qualified v9 seed");
            var values = model.getAsJsonArray("genome");
            double[] genome = new double[values.size()];
            for (int i = 0; i < genome.length; i++) genome[i] = values.get(i).getAsDouble();
            return new MinecraftMachinesLeaperPredatorPolicy(genome);
        }
    }

    private static final class Run {
        final GameTestHelper helper;
        final MinecraftMachinesLeaperPredatorPolicy seed = original();
        final Random random = new Random(SEED);
        final Map<Integer, Vector3d> starts = new HashMap<>();
        final List<MinecraftMachinesLeaperPredatorRouteTests.Trial> trials = new ArrayList<>();
        final List<Map<String, Object>> records = new ArrayList<>(),
                generations = new ArrayList<>();
        final double[][] genomes = new double[POPULATION][];
        final double[] totals = new double[POPULATION], worst = new double[POPULATION];
        final int[] completed = new int[POPULATION], passed = new int[POPULATION];
        final boolean[] recorded = new boolean[POPULATION];
        final double[] mean, sd = {.15, .30, .35};
        double[] incumbent;
        Double bestScore;
        int generation, task, tick, selectedGeneration = -1, selectedCandidate = -1;
        int previousSubsteps;
        boolean physicsSaved;
        String failure;
        final long began = System.nanoTime();

        Run(GameTestHelper helper) throws Exception {
            this.helper = helper;
            incumbent = seed.genome.clone();
            mean = seed.genome.clone();
            double[] physical = seed.value.clone();
            physical[0] = 3.45;
            physical[1] = 1.12;
            physical[2] = .54;
            var timing = MinecraftMachinesLeaperPredatorPolicy.physical(physical);
            // Do not re-encode the other 17 parameters, even by floating-point roundoff.
            for (int index : MUTATED) mean[index] = timing.genome[index];
        }

        void begin() throws Exception {
            if (!MinecraftMachinesLeaperLearning.horrific()
                    || Integer.getInteger("minecraft_machines.leaperSubsteps", 4) != 4
                    || !Boolean.parseBoolean(
                            System.getProperty("minecraft_machines.leaperGrip", "true"))
                    || Double.parseDouble(
                                    System.getProperty("minecraft_machines.leaperBalance", "1"))
                            != 1)
                throw new IllegalStateException(
                        "Use horrific profile, four substeps, grip and balance=1");
            var physics = SubLevelPhysicsSystem.get(helper.getLevel());
            previousSubsteps = physics.getConfig().substepsPerTick;
            physicsSaved = true;
            physics.getConfig().substepsPerTick = 4;
            physics.onConfigUpdated();
            sample();
            batch();
            persist("running");
            loop();
        }

        void sample() {
            Arrays.fill(totals, 0);
            Arrays.fill(worst, Double.POSITIVE_INFINITY);
            Arrays.fill(completed, 0);
            Arrays.fill(passed, 0);
            for (int candidate = 0; candidate < POPULATION; candidate++) {
                genomes[candidate] = seed.genome.clone();
                for (int k = 0; k < MUTATED.length; k++) {
                    int index = MUTATED[k];
                    genomes[candidate][index] =
                            candidate == 0
                                    ? incumbent[index]
                                    : candidate == 1
                                            ? mean[index]
                                            : mean[index] + sd[k] * random.nextGaussian();
                }
            }
        }

        void batch() {
            closeTrials();
            trials.clear();
            Arrays.fill(recorded, false);
            tick = 0;
            var course = course(task);
            for (int candidate = 0; candidate < POPULATION; candidate++) {
                int slot = task * POPULATION + candidate;
                var start =
                        starts.computeIfAbsent(slot, key -> course.build(helper.getLevel(), key));
                var trial =
                        new MinecraftMachinesLeaperPredatorRouteTests.Trial(
                                helper,
                                slot,
                                new MinecraftMachinesLeaperPredatorPolicy(genomes[candidate]),
                                course,
                                start);
                trials.add(trial);
                trial.horizon = 2080;
                trial.nav.control.earlyClearance = trial.nav.control.coordinatedSwing = true;
            }
            MinecraftMachines.LOGGER.info(
                    "CLEARANCE_TUNE_BATCH generation={} task={} course={}",
                    generation,
                    task,
                    course.name());
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            step();
                        } catch (Exception e) {
                            fail(e);
                        }
                    });
        }

        void step() throws Exception {
            tick++;
            boolean changed = false;
            for (int candidate = 0; candidate < trials.size(); candidate++) {
                var trial = trials.get(candidate);
                trial.step();
                if (!trial.done || recorded[candidate]) continue;
                double score =
                        (trial.success ? 1000 : -1000) - .2 * trial.tick - 3 * trial.backwardTravel;
                if (!Double.isFinite(score))
                    throw new IllegalStateException("Nonfinite completed trial score");
                var row = trial.result();
                row.put("generation", generation);
                row.put("candidate", candidate);
                row.put("task", task);
                row.put("arena_slot", task * POPULATION + candidate);
                row.put("genome", genomes[candidate].clone());
                row.put("parameters", trial.nav.control.policy.named());
                row.put("score", score);
                records.add(row);
                totals[candidate] += score;
                worst[candidate] = Math.min(worst[candidate], score);
                completed[candidate]++;
                if (trial.success) passed[candidate]++;
                recorded[candidate] = changed = true;
                trial.rig.close();
                MinecraftMachines.LOGGER.info(
                        "CLEARANCE_TUNE_TRIAL generation={} candidate={} task={} success={}"
                            + " score={}",
                        generation,
                        candidate,
                        task,
                        trial.success,
                        score);
            }
            if (changed) persist("running");
            if (tick % 400 == 0)
                MinecraftMachines.LOGGER.info(
                        "CLEARANCE_TUNE_PROGRESS generation={} task={} tick={} errors={}",
                        generation,
                        task,
                        tick,
                        trials.stream().map(t -> t.error).toList());
            if (trials.stream().anyMatch(t -> !t.done)) {
                loop();
                return;
            }
            if (++task < TASKS) {
                batch();
                loop();
                return;
            }
            select();
            persist(generation + 1 == GENERATIONS ? "complete" : "running");
            if (++generation < GENERATIONS) {
                task = 0;
                sample();
                batch();
                loop();
                return;
            }
            if (records.size() != POPULATION * GENERATIONS * TASKS)
                throw new IllegalStateException("Incomplete 96-episode search");
            exportCandidate();
            close();
            helper.succeed();
        }

        void select() {
            for (int count : completed)
                if (count != TASKS)
                    throw new IllegalStateException(
                            "Selection requires four completed tasks per candidate");
            double[] scores = new double[POPULATION];
            Integer[] rank = new Integer[POPULATION];
            for (int i = 0; i < POPULATION; i++) {
                scores[i] = totals[i] + worst[i];
                rank[i] = i;
            }
            Arrays.sort(rank, Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
            // Reevaluate the incumbent each generation instead of keeping a lucky historical score.
            selectedGeneration = generation;
            selectedCandidate = rank[0];
            bestScore = scores[rank[0]];
            incumbent = genomes[rank[0]].clone();
            var stats = new LinkedHashMap<String, Object>();
            stats.put("generation", generation);
            stats.put("sampled_genomes", Arrays.stream(genomes).map(double[]::clone).toList());
            stats.put("mean_before", mean.clone());
            stats.put("sd_before", sd.clone());
            stats.put("task_score_totals", totals.clone());
            stats.put("worst_task_scores", worst.clone());
            stats.put("scores", scores);
            stats.put("tasks_passed", passed.clone());
            stats.put("elite_candidates", List.of(rank[0], rank[1]));
            stats.put("selected_candidate", selectedCandidate);
            stats.put("selected_genome", incumbent.clone());
            for (int k = 0; k < MUTATED.length; k++) {
                int index = MUTATED[k];
                double a = genomes[rank[0]][index], b = genomes[rank[1]][index];
                mean[index] = (a + b) * .5;
                sd[k] =
                        Math.max(
                                .06,
                                Math.sqrt(
                                        (Math.pow(a - mean[index], 2)
                                                        + Math.pow(b - mean[index], 2))
                                                * .5));
            }
            stats.put("mean_after", mean.clone());
            stats.put("sd_after", sd.clone());
            generations.add(stats);
            MinecraftMachines.LOGGER.info(
                    "CLEARANCE_TUNE_GENERATION generation={} score={} passed={} parameters={}",
                    generation,
                    bestScore,
                    passed[selectedCandidate],
                    new MinecraftMachinesLeaperPredatorPolicy(incumbent).named());
        }

        void persist(String status) throws Exception {
            var report = new LinkedHashMap<String, Object>();
            report.put("policy", MinecraftMachinesLeaperPredatorPolicy.VERSION);
            report.put("status", status);
            report.put("seed", SEED);
            report.put("baseline_genome", seed.genome);
            report.put("genome", incumbent.clone());
            report.put("mutated_indices", MUTATED);
            report.put("population", POPULATION);
            report.put("planned_generations", GENERATIONS);
            report.put("planned_episodes", POPULATION * GENERATIONS * TASKS);
            report.put("early_clearance", true);
            report.put("coordinated_swing", true);
            report.put("shoulder_maximum", 1.05);
            report.put("completed", records.size());
            report.put("episodes", records);
            report.put("generations", generations);
            report.put("selected_generation", selectedGeneration);
            report.put("selected_candidate", selectedCandidate);
            report.put("best_score", bestScore);
            report.put(
                    "score_definition",
                    "Trial: (success ? 1000 : -1000) - .2*ticks - 3*backward_travel; candidate: sum"
                        + " of four trials plus worst trial");
            report.put(
                    "motion_metric",
                    "Opposite-goal proxy excluding navigator back-outs; not measured collision"
                        + " impulses");
            report.put("wall_seconds", (System.nanoTime() - began) / 1e9);
            if (failure != null) report.put("failure", failure);
            MinecraftMachinesLeaperLearning.write("predator-clearance-tune", report);
        }

        void exportCandidate() throws Exception {
            var candidate = new LinkedHashMap<String, Object>();
            candidate.put("validated", false);
            candidate.put("policy", MinecraftMachinesLeaperPredatorPolicy.VERSION);
            candidate.put("genome", incumbent.clone());
            candidate.put(
                    "parameters", new MinecraftMachinesLeaperPredatorPolicy(incumbent).named());
            candidate.put("baseline_genome", seed.genome);
            candidate.put("mutated_indices", MUTATED);
            candidate.put("early_clearance", true);
            candidate.put("coordinated_swing", true);
            candidate.put("shoulder_maximum", 1.05);
            candidate.put("selected_generation", selectedGeneration);
            candidate.put("selected_candidate", selectedCandidate);
            candidate.put("completed_tasks", TASKS);
            candidate.put("tasks_passed", passed[selectedCandidate]);
            candidate.put("score", bestScore);
            candidate.put("seed", SEED);
            candidate.put("completed_episodes", records.size());
            candidate.put("generations", generations);
            candidate.put(
                    "qualification_note",
                    "Training selection only; requires full route/interaction/field qualification"
                        + " before promotion");
            MinecraftMachinesLeaperLearning.write("predator-clearance-tuned-candidate", candidate);
        }

        void closeTrials() {
            for (var trial : trials) trial.rig.close();
        }

        void close() {
            closeTrials();
            if (physicsSaved) {
                var physics = SubLevelPhysicsSystem.get(helper.getLevel());
                physics.getConfig().substepsPerTick = previousSubsteps;
                physics.onConfigUpdated();
                physicsSaved = false;
            }
        }

        void fail(Exception exception) {
            failure = exception.toString();
            try {
                persist("failed");
            } catch (Exception writeFailure) {
                exception.addSuppressed(writeFailure);
            } finally {
                close();
            }
            helper.fail("Clearance tuning: " + exception);
        }
    }
}
