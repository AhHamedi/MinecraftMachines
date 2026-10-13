package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedGaitPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Held-out qualification. The packaged parameters are never optimized here. */
@GameTestHolder("minecraft_machines_quadruped_robustness")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesQuadrupedRobustnessGameTests {
    private record Scenario(String name, Block floor, double yawDegrees, double push, double roll, int obstacle) {}
    private static final Scenario[] SCENARIOS = {
            new Scenario("smooth_stone", Blocks.SMOOTH_STONE, 0, 0, 0, 0),
            new Scenario("grass", Blocks.GRASS_BLOCK, 0, 0, 0, 0),
            new Scenario("oak_planks", Blocks.OAK_PLANKS, 0, 0, 0, 0),
            new Scenario("cobblestone", Blocks.COBBLESTONE, 0, 0, 0, 0),
            new Scenario("blue_ice", Blocks.BLUE_ICE, 0, 0, 0, 0),
            new Scenario("initial_yaw_plus_15", Blocks.SMOOTH_STONE, 15, 0, 0, 0),
            new Scenario("initial_yaw_minus_15", Blocks.SMOOTH_STONE, -15, 0, 0, 0),
            new Scenario("stronger_push", Blocks.SMOOTH_STONE, 0, 1, 0.4, 0),
            new Scenario("half_block_ridges", Blocks.SMOOTH_STONE, 0, 0, 0, 1),
            new Scenario("half_block_platform", Blocks.SMOOTH_STONE, 0, 0, 0, 2)
    };

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void savedPolicyHeldOutQualification(GameTestHelper helper) throws Exception {
        double[] genome = MinecraftMachinesQuadrupedManager.loadValidatedGenome();
        var level = helper.getLevel();
        List<MinecraftMachinesQuadruped> rigs = new ArrayList<>();
        Vector3d[] starts = new Vector3d[SCENARIOS.length];
        double[] minimumUp = new double[SCENARIOS.length];
        double[] maxLateral = new double[SCENARIOS.length];
        double[] initialHeading = new double[SCENARIOS.length];
        boolean[] failed = new boolean[SCENARIOS.length];
        java.util.Arrays.fill(minimumUp, 1);
        try {
            for (int i = 0; i < SCENARIOS.length; i++) {
                Scenario scenario = SCENARIOS[i];
                BlockPos center = new BlockPos(i * 20, 10, 256);
                for (int cx = (center.getX() - 8) >> 4; cx <= (center.getX() + 8) >> 4; cx++)
                    for (int cz = 5; cz <= 17; cz++) level.setChunkForced(cx, cz, true);
                for (int x = -8; x <= 8; x++) for (int z = -170; z <= 8; z++) {
                    level.setBlockAndUpdate(center.offset(x, -4, z), scenario.floor.defaultBlockState());
                    for (int y = -3; y <= 6; y++) level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    boolean obstacle = scenario.obstacle == 1 && z < -15 && Math.floorMod(z, 16) < 2
                            || scenario.obstacle == 2 && z < -20;
                    if (obstacle) level.setBlockAndUpdate(center.offset(x, -3, z), Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                }
                var rig = MinecraftMachinesQuadruped.spawn(level, center);
                rigs.add(rig);
                starts[i] = rig.position();
            }
        } catch (Exception e) { rigs.forEach(MinecraftMachinesQuadruped::close); throw e; }
        int[] tick = {0};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            try {
                int t = ++tick[0];
                for (int i = 0; i < rigs.size(); i++) {
                    if (failed[i]) continue;
                    var rig = rigs.get(i);
                    var scenario = SCENARIOS[i];
                    if (t == 20) {
                        rig.captureResetPose(); rig.reset();
                        if (scenario.yawDegrees != 0) rig.rotateInitialHeading(Math.toRadians(scenario.yawDegrees));
                    }
                    if (t == 21) initialHeading[i] = Math.toDegrees(rig.heading());
                    if (t == 380 && scenario.push != 0) rig.disturb(scenario.push, scenario.roll);
                    if (t > 80) {
                        minimumUp[i] = Math.min(minimumUp[i], rig.upright());
                        maxLateral[i] = Math.max(maxLateral[i], Math.abs(rig.position().x - starts[i].x));
                    }
                    if (!rig.valid() || rig.upright() < 0.8 || rig.position().y < starts[i].y - 1 || maxLateral[i] > 6) {
                        failed[i] = true;
                        continue;
                    }
                    if (t > 20 && t % 2 == 0) {
                        var up = rig.up();
                        double[] targets = QuadrupedGaitPolicy.action(genome, Math.max(0, t - 80) * 0.05,
                                up.x, up.z, t > 80 ? 1 : 0, 3 * rig.heading() + 0.15 * (rig.position().x - starts[i].x));
                        if (t < 60) for (int j = 0; j < 8; j++) targets[j] *= (t - 20) / 40.0;
                        rig.command(targets);
                    }
                }
                if (t < 1280) { helper.runAfterDelay(1, () -> step[0].run()); return; }
                JsonObject report = new JsonObject();
                report.addProperty("policy", QuadrupedGaitPolicy.VERSION);
                report.addProperty("walking_seconds_per_trial", 60);
                report.addProperty("minimum_accepted_forward_blocks", 40);
                report.addProperty("maximum_accepted_final_lateral_blocks", 2);
                report.addProperty("minimum_accepted_up_dot", 0.8);
                report.addProperty("parameters_changed", false);
                JsonArray results = new JsonArray();
                int accepted = 0;
                for (int i = 0; i < rigs.size(); i++) {
                    var rig = rigs.get(i);
                    var scenario = SCENARIOS[i];
                    double forward = starts[i].z - rig.position().z;
                    double lateral = Math.abs(starts[i].x - rig.position().x);
                    boolean passed = !failed[i] && forward >= 40 && lateral <= 2;
                    if (passed) accepted++;
                    JsonObject result = new JsonObject();
                    result.addProperty("scenario", scenario.name);
                    result.addProperty("initial_heading_degrees", initialHeading[i]);
                    result.addProperty("disturbance_lateral_velocity", scenario.push);
                    result.addProperty("disturbance_roll_velocity", scenario.roll);
                    result.addProperty("forward_blocks", forward);
                    result.addProperty("final_lateral_blocks", lateral);
                    result.addProperty("maximum_lateral_blocks", maxLateral[i]);
                    result.addProperty("minimum_up_dot", minimumUp[i]);
                    result.addProperty("fell_or_left_lane", failed[i]);
                    result.addProperty("passed", passed);
                    results.add(result);
                    MinecraftMachines.LOGGER.info("QUADRUPED_HELD_OUT {}", result);
                }
                report.addProperty("accepted", accepted);
                report.addProperty("total", rigs.size());
                report.add("trials", results);
                Files.createDirectories(Path.of("quadruped_training"));
                Files.writeString(Path.of("quadruped_training/held-out-qualification.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
                rigs.forEach(MinecraftMachinesQuadruped::close);
                helper.assertTrue(accepted == rigs.size(), "Held-out qualification: " + accepted + "/" + rigs.size() + " passed; see complete report");
                helper.succeed();
            } catch (Exception e) { rigs.forEach(MinecraftMachinesQuadruped::close); helper.fail("Held-out qualification failed: " + e); }
        };
        helper.runAfterDelay(1, () -> step[0].run());
    }
}
