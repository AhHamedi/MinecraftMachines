package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonParser;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;

import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import java.nio.file.*;
import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperNavigationTests {
    @GameTest(template = "empty", timeoutTicks = 160000, batch = "leaperNavigationTraining")
    public static void trainNavigation(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("train-navigation")) {
            helper.succeed();
            return;
        }
        new MinecraftMachinesLeaperNavigationTrainer(helper).run();
    }

    static double[] readGait() throws Exception {
        return readArray("gait-candidate", "gait");
    }

    static double[] readArray(String filename, String key) throws Exception {
        var array =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "leaper_learning/"
                                                        + MinecraftMachinesLeaperLearning
                                                                .reportName(filename)
                                                        + ".json")))
                        .getAsJsonObject()
                        .getAsJsonArray(key);
        double[] result = new double[array.size()];
        for (int i = 0; i < result.length; i++) result[i] = array.get(i).getAsDouble();
        return result;
    }

    @GameTest(template = "empty", timeoutTicks = 30000, batch = "leaperNavigationEvaluation")
    public static void pointArrivals(GameTestHelper helper) throws Exception {
        String mode = MinecraftMachinesLeaperLearning.mode();
        if (!mode.startsWith("points-")) {
            helper.succeed();
            return;
        }
        final boolean baseline = mode.equals("points-seed");
        final boolean fresh = mode.equals("points-fresh");
        final var packaged =
                (mode.equals("points-packaged") || fresh)
                        ? MinecraftMachinesLeaperModel.load(
                                MinecraftMachinesLeaperLearning.packagedPace())
                        : null;
        final double[] gait =
                packaged != null
                        ? packaged.gait()
                        : baseline ? LeaperGait.probeSeed(3) : readGait();
        final double[] nav =
                packaged != null
                        ? packaged.navigation()
                        : mode.equals("points-trained")
                                ? readArray("navigation-candidate", "navigation")
                                : new double[10];
        final int count = baseline ? 8 : 48, population = 8;
        final long evaluationSeed = fresh ? 2026090927L : 2026090721L;
        var random = new Random(evaluationSeed);
        double[][] goals = new double[count][3];
        for (int i = 0; i < count; i++) {
            double angle = i < 8 && !fresh ? i * Math.PI / 4 : random.nextDouble() * 2 * Math.PI;
            double d = i < 8 ? 12 : i < 16 ? .6 + (i - 8) * .45 : 6 + random.nextDouble() * 22;
            goals[i] =
                    new double[] {
                        d * Math.sin(angle),
                        -d * Math.cos(angle),
                        i < 8 && !fresh ? 0 : random.nextDouble() * 2 * Math.PI - Math.PI
                    };
        }
        var rigs = new ArrayList<MinecraftMachinesLeaper>();
        Block[] floors = {
            Blocks.SMOOTH_STONE, Blocks.GRASS_BLOCK, Blocks.OAK_PLANKS, Blocks.COBBLESTONE
        };
        for (int i = 0; i < population; i++)
            rigs.add(
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            MinecraftMachinesLeaperLearning.arena(
                                    helper.getLevel(), i, floors[i % 4], 48)));
        var episodes = new MinecraftMachinesLeaperEpisode[population];
        var results = new ArrayList<Map<String, Object>>();
        int[] batch = {0};
        long began = System.nanoTime();
        Runnable begin =
                () -> {
                    for (int i = 0; i < population; i++) {
                        int n = batch[0] * population + i;
                        var g = goals[n];
                        episodes[i] =
                                new MinecraftMachinesLeaperEpisode(
                                        rigs.get(i),
                                        packaged != null
                                                ? packaged
                                                : new MinecraftMachinesLeaperModel(
                                                        gait,
                                                        nav,
                                                        MinecraftMachinesLeaperLearning.cadence(),
                                                        MinecraftMachinesLeaperLearning.slew(),
                                                        MinecraftMachinesLeaperLearning.power(),
                                                        MinecraftMachinesLeaperLearning.horrific()),
                                        g[0],
                                        g[1],
                                        g[2],
                                        !baseline && n >= 16 && n % 3 == 0,
                                        3200,
                                        true,
                                        42);
                    }
                };
        begin.run();
        Runnable[] next = {null};
        next[0] =
                () -> {
                    try {
                        boolean done = true;
                        for (var episode : episodes) {
                            episode.step();
                            done &= episode.done();
                        }
                        if (!done) {
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        for (int i = 0; i < population; i++) {
                            var row = episodes[i].result();
                            row.put("index", batch[0] * population + i);
                            row.put("floor", floors[i % 4].getDescriptionId());
                            results.add(row);
                        }
                        long successes =
                                results.stream().filter(r -> (boolean) r.get("success")).count();
                        MinecraftMachines.LOGGER.info(
                                "LEAPER_POINTS batch={} successes={}/{}",
                                batch[0],
                                successes,
                                results.size());
                        MinecraftMachinesLeaperLearning.write(
                                mode,
                                Map.of(
                                        "validated",
                                        results.size() == count && successes == count,
                                        "policy",
                                        LeaperPointController.VERSION,
                                        "gait",
                                        gait,
                                        "navigation",
                                        nav,
                                        "seed",
                                        evaluationSeed,
                                        "trials",
                                        results,
                                        "successes",
                                        successes,
                                        "count",
                                        results.size(),
                                        "wall_seconds",
                                        (System.nanoTime() - began) / 1e9,
                                        "envelope",
                                        Map.of(
                                                "gait_policy",
                                                        MinecraftMachinesLeaperLearning.horrific()
                                                                ? LeaperGait.SPRINT_VERSION
                                                                : LeaperGait.VERSION,
                                                "cadence",
                                                        packaged == null
                                                                ? MinecraftMachinesLeaperLearning
                                                                        .cadence()
                                                                : packaged.cadence(),
                                                "slew",
                                                        packaged == null
                                                                ? MinecraftMachinesLeaperLearning
                                                                        .slew()
                                                                : packaged.slew(),
                                                "power",
                                                        packaged == null
                                                                ? MinecraftMachinesLeaperLearning
                                                                        .power()
                                                                : packaged.power())));
                        if (++batch[0] * population < count) {
                            begin.run();
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        if (!baseline)
                            helper.assertTrue(
                                    successes == count,
                                    "Leaper destinations: " + successes + "/" + count);
                        helper.succeed();
                    } catch (Exception e) {
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.fail("Leaper point evaluation: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> next[0].run());
    }
}
