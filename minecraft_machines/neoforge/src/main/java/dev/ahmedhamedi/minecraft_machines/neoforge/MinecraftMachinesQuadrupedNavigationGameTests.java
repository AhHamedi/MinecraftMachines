package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedDirectionalGait;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@GameTestHolder("minecraft_machines_quadruped_navigation")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesQuadrupedNavigationGameTests {
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "navigationQualification")
    public static void heldOutPointArrivals(GameTestHelper helper) throws Exception {
        String mode = System.getProperty("minecraft_machines.quadrupedNavigation", "packaged");
        if (!mode.equals("evaluate")
                && !mode.equals("evaluate-baseline")
                && !mode.equals("packaged")) {
            helper.succeed();
            return;
        }
        new MinecraftMachinesQuadrupedNavigationEvaluator(
                        helper, mode.equals("packaged"), mode.equals("evaluate-baseline"))
                .run();
    }

    @GameTest(template = "empty", timeoutTicks = 150000, batch = "navigationTraining")
    public static void trainPointController(GameTestHelper helper) throws Exception {
        String mode = System.getProperty("minecraft_machines.quadrupedNavigation", "packaged");
        if (!mode.equals("train") && !mode.equals("baseline")) {
            helper.succeed();
            return;
        }
        new MinecraftMachinesQuadrupedNavigationTrainer(helper, mode.equals("baseline")).run();
    }

    static BlockPos arena(ServerLevel level, int slot, Block floor) {
        BlockPos center = new BlockPos((slot % 4) * 112, 10, (slot / 4) * 112);
        for (int cx = (center.getX() - 49) >> 4; cx <= (center.getX() + 49) >> 4; cx++)
            for (int cz = (center.getZ() - 49) >> 4; cz <= (center.getZ() + 49) >> 4; cz++)
                level.setChunkForced(cx, cz, true);
        for (int x = -49; x <= 49; x++)
            for (int z = -49; z <= 49; z++)
                level.setBlockAndUpdate(center.offset(x, -4, z), floor.defaultBlockState());
        return center;
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void directionalMotorPrimitives(GameTestHelper helper) throws Exception {
        if (!System.getProperty("minecraft_machines.quadrupedNavigation", "packaged")
                .equals("probe")) {
            helper.succeed();
            return;
        }
        double[] genome = MinecraftMachinesQuadrupedManager.loadValidatedGenome();
        double[][] commands = {
            {1, 0}, {-.7, 0}, {0, .65}, {0, -.65}, {.65, .4}, {.65, -.4}, {0, 0}
        };
        List<MinecraftMachinesQuadruped> rigs = new ArrayList<>();
        Vector3d[] starts = new Vector3d[commands.length];
        double[] accumulatedHeading = new double[commands.length],
                lastHeading = new double[commands.length],
                minUp = new double[commands.length];
        java.util.Arrays.fill(minUp, 1);
        for (int i = 0; i < commands.length; i++) {
            var rig =
                    MinecraftMachinesQuadruped.spawn(
                            helper.getLevel(), arena(helper.getLevel(), i, Blocks.SMOOTH_STONE));
            rigs.add(rig);
            starts[i] = rig.position();
        }
        int[] tick = {0};
        Runnable[] step = new Runnable[1];
        step[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        for (int i = 0; i < rigs.size(); i++) {
                            var rig = rigs.get(i);
                            if (t == 20) {
                                rig.captureResetPose();
                                rig.reset();
                            }
                            double h = rig.heading();
                            if (t > 80) {
                                accumulatedHeading[i] +=
                                        Math.atan2(
                                                Math.sin(h - lastHeading[i]),
                                                Math.cos(h - lastHeading[i]));
                                minUp[i] = Math.min(minUp[i], rig.upright());
                            }
                            lastHeading[i] = h;
                            if (t > 20 && t % 2 == 0) {
                                var up = rig.up();
                                double[] action =
                                        QuadrupedDirectionalGait.action(
                                                genome,
                                                Math.max(0, t - 80) * .05,
                                                h,
                                                up.x,
                                                up.z,
                                                t > 80 ? commands[i][0] : 0,
                                                t > 80 ? commands[i][1] : 0);
                                if (t < 60)
                                    for (int j = 0; j < 8; j++) action[j] *= (t - 20) / 40.0;
                                rig.command(action);
                            }
                        }
                        if (t < 480) {
                            helper.runAfterDelay(1, () -> step[0].run());
                            return;
                        }
                        JsonArray results = new JsonArray();
                        for (int i = 0; i < rigs.size(); i++) {
                            var rig = rigs.get(i);
                            JsonObject r = new JsonObject();
                            r.addProperty("forward_command", commands[i][0]);
                            r.addProperty("turn_command", commands[i][1]);
                            r.addProperty("dx", rig.position().x - starts[i].x);
                            r.addProperty("dz", rig.position().z - starts[i].z);
                            r.addProperty("yaw_degrees", Math.toDegrees(accumulatedHeading[i]));
                            r.addProperty("minimum_up", minUp[i]);
                            results.add(r);
                            MinecraftMachines.LOGGER.info("QUADRUPED_DIRECTION_PROBE {}", r);
                        }
                        Files.createDirectories(Path.of("quadruped_navigation"));
                        Files.writeString(
                                Path.of("quadruped_navigation/primitives.json"),
                                new GsonBuilder().setPrettyPrinting().create().toJson(results));
                        rigs.forEach(MinecraftMachinesQuadruped::close);
                        helper.assertTrue(
                                accumulatedHeading[2] > .5 && accumulatedHeading[3] < -.5,
                                "Signed motor command must physically turn both ways");
                        for (double u : minUp)
                            helper.assertTrue(
                                    u > .8, "All directional primitives must remain upright");
                        helper.succeed();
                    } catch (Exception e) {
                        rigs.forEach(MinecraftMachinesQuadruped::close);
                        helper.fail("Primitive test failed: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> step[0].run());
    }
}
