package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("minecraft_machines_quadruped")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesQuadrupedGameTests {
    @GameTest(template = "empty", timeoutTicks = 100000, batch = "quadrupedTraining")
    public static void trainAndEvaluateQuadruped(GameTestHelper helper) {
        if (!Boolean.getBoolean("minecraft_machines.quadrupedTrain")) { helper.succeed(); return; }
        new MinecraftMachinesQuadrupedTrainer(helper).run();
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = "quadrupedPhysics")
    public static void quadrupedPhysicalStandingAndJointTracking(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = new BlockPos(0, 10, 0);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) level.setChunkForced((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, true);
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
            level.setBlockAndUpdate(center.offset(x, -4, z), Blocks.SMOOTH_STONE.defaultBlockState());
            for (int y = -3; y <= 6; y++) level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        var rig = MinecraftMachinesQuadruped.spawn(level, center);
        var start = rig.position();
        helper.startSequence()
                .thenExecuteAfter(100, () -> {
                    MinecraftMachines.LOGGER.info("QUADRUPED_STAND position={} delta={} upright={} angles={}", rig.position(), rig.position().sub(start), rig.upright(), rig.angles());
                    helper.assertTrue(rig.valid() && rig.bodyCount() == 9 && rig.jointCount() == 8, "Expected nine bodies and eight live joints");
                    helper.assertTrue(rig.upright() > 0.95 && Math.abs(rig.position().y - start.y) < 0.5, "Quadruped must stand without a world constraint");
                    rig.command(new double[]{-0.2, 0.4, -0.2, 0.4, -0.2, 0.4, -0.2, 0.4});
                })
                .thenExecuteAfter(100, () -> {
                    MinecraftMachines.LOGGER.info("QUADRUPED_TRACK position={} upright={} angles={}", rig.position(), rig.upright(), rig.angles());
                    double[] actual = rig.angles();
                    for (int i = 0; i < 8; i++) helper.assertTrue(Math.abs(actual[i] - (i % 2 == 0 ? -0.2 : 0.4)) < 0.15, "Physical joint " + i + " must track its target, actual=" + actual[i]);
                    helper.assertTrue(rig.upright() > 0.9, "Quadruped must remain upright while bending knees");
                    rig.close();
                }).thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 4400, batch = "quadrupedSavedReplay")
    public static void savedQuadrupedPolicyWalksStopsAndResumes(GameTestHelper helper) throws Exception {
        if (Boolean.getBoolean("minecraft_machines.quadrupedTrain")) { helper.succeed(); return; }
        double[] genome = MinecraftMachinesQuadrupedManager.loadValidatedGenome();
        var level = helper.getLevel();
        BlockPos center = new BlockPos(0, 10, 512);
        for (int cx = -1; cx <= 1; cx++) for (int cz = 4; cz <= 33; cz++) level.setChunkForced(cx, cz, true);
        for (int x = -7; x <= 7; x++) for (int z = -440; z <= 8; z++) {
            level.setBlockAndUpdate(center.offset(x, -4, z), Blocks.SMOOTH_STONE.defaultBlockState());
            for (int y = -3; y < 7; y++) level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        var rig = MinecraftMachinesQuadruped.spawn(level, center);
        double[] state = new double[]{0, 1, 0, 0, 0};
        var start = rig.position();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            try {
                int t = (int) ++state[0];
                if (t % 400 == 0) MinecraftMachines.LOGGER.info("QUADRUPED_REPLAY_PROGRESS tick={} pos={} velocity={} heading={} angles={}", t, rig.position(), rig.velocity(), rig.heading(), rig.angles());
                if (t == 20) { rig.captureResetPose(); rig.reset(); }
                boolean walking = t > 80 && (t < 1280 || t >= 1680);
                if (t == 1280) state[3] = rig.position().z;
                if (t == 1679) state[4] = Math.abs(rig.position().z - state[3]);
                if (t == 2000) rig.disturb(0.5, 0.2);
                if (t > 80) state[1] = Math.min(state[1], rig.upright());
                if (walking) state[2] += 0.05;
                if (t > 20 && t % 2 == 0) {
                    var up = rig.up();
                    double[] targets = dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedGaitPolicy.action(
                            genome, walking ? state[2] : 0, up.x, up.z, walking ? 1 : 0,
                            3 * rig.heading() + 0.15 * (rig.position().x - start.x));
                    if (t < 60) for (int j = 0; j < 8; j++) targets[j] *= (t - 20) / 40.0;
                    rig.command(targets);
                }
                helper.assertTrue(rig.valid() && rig.upright() > 0.8, "Saved policy must remain upright throughout stop, restart, and stronger disturbance");
                if (t < 4080) { helper.runAfterDelay(1, () -> step[0].run()); return; }
                var result = new com.google.gson.JsonObject();
                result.addProperty("forward_blocks", start.z - rig.position().z);
                result.addProperty("lateral_blocks", Math.abs(start.x - rig.position().x));
                result.addProperty("minimum_up_dot", state[1]);
                result.addProperty("stopping_drift_blocks", state[4]);
                result.addProperty("total_seconds", 204);
                result.addProperty("walking_seconds", 180);
                result.addProperty("disturbance_lateral_velocity", 0.5);
                result.addProperty("disturbance_roll_velocity", 0.2);
                java.nio.file.Files.createDirectories(java.nio.file.Path.of("quadruped_training"));
                java.nio.file.Files.writeString(java.nio.file.Path.of("quadruped_training/saved-replay.json"), result.toString());
                MinecraftMachines.LOGGER.info("QUADRUPED_SAVED_REPLAY {}", result);
                rig.close();
                helper.assertTrue(result.get("forward_blocks").getAsDouble() > 100 && result.get("lateral_blocks").getAsDouble() < 2,
                        "Saved quadruped must walk >100 blocks and stay within two blocks of its line");
                helper.assertTrue(state[4] < 1, "Stopping drift must be below one block");
                helper.succeed();
            } catch (Exception e) { rig.close(); helper.fail("Saved replay failed: " + e); }
        };
        helper.runAfterDelay(1, () -> step[0].run());
    }

}
