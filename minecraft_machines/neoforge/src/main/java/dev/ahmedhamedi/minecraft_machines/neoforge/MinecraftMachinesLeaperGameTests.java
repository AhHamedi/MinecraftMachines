package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.GsonBuilder;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperGameTests {
    @GameTest(template = "empty", timeoutTicks = 1800, batch = "leaper")
    public static void physicalStandingCrouchAndArticulation(GameTestHelper helper) {
        var level = helper.getLevel();
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) level.setChunkForced(x, z, true);
        for (int x = -12; x <= 12; x++)
            for (int z = -12; z <= 12; z++)
                level.setBlockAndUpdate(
                        new BlockPos(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
        var rig =
                MinecraftMachinesLeaper.spawn(
                        level, new Vector3d(.5, 1 + MinecraftMachinesLeaper.HEIGHT, .5));
        var history = new ArrayList<Map<String, Object>>();
        var start = rig.position();
        int[] tick = {0};
        double[] stats = {1, 0, 0};
        Runnable[] step = {null};
        step[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        String mode =
                                t < 200
                                        ? "stand"
                                        : t < 400 ? "crouch" : t < 1400 ? "articulate" : "stand";
                        double[] targets =
                                MinecraftMachinesLeaperManager.pose(
                                        mode, Math.max(0, t - 400) * .05);
                        rig.command(targets, .05);
                        stats[0] = Math.min(stats[0], rig.upright());
                        stats[1] = Math.max(stats[1], rig.anchorError());
                        if (t % 20 == 0) {
                            var p = rig.position();
                            history.add(
                                    Map.of(
                                            "tick",
                                            t,
                                            "mode",
                                            mode,
                                            "position",
                                            List.of(p.x, p.y, p.z),
                                            "up",
                                            rig.upright(),
                                            "anchor_error",
                                            rig.anchorError(),
                                            "angles",
                                            rig.angles(),
                                            "targets",
                                            targets));
                        }
                        if (t % 100 == 0)
                            MinecraftMachines.LOGGER.info(
                                    "LEAPER_PHYSICS t={} mode={} pos={} up={} anchor={} angles={}",
                                    t,
                                    mode,
                                    rig.position(),
                                    rig.upright(),
                                    rig.anchorError(),
                                    rig.angles());
                        if (t == 190 || t == 390 || t == 1590) {
                            var a = rig.angles();
                            for (int i = 0; i < 12; i++)
                                stats[2] = Math.max(stats[2], Math.abs(a[i] - targets[i]));
                        }
                        if (t < 1600) {
                            helper.runAfterDelay(1, () -> step[0].run());
                            return;
                        }
                        var result =
                                Map.of(
                                        "contract",
                                        MinecraftMachinesLeaper.CONTRACT,
                                        "body_count",
                                        rig.bodyCount(),
                                        "joints",
                                        12,
                                        "seconds",
                                        80,
                                        "minimum_up",
                                        stats[0],
                                        "max_anchor_error",
                                        stats[1],
                                        "settled_tracking_error",
                                        stats[2],
                                        "drift",
                                        rig.position().distance(start),
                                        "history",
                                        history);
                        Files.createDirectories(Path.of("leaper"));
                        Files.writeString(
                                Path.of("leaper/physics.json"),
                                new GsonBuilder().setPrettyPrinting().create().toJson(result));
                        rig.close();
                        helper.assertTrue(
                                stats[0] > .97,
                                "Leaper must stay upright through poses; up=" + stats[0]);
                        helper.assertTrue(
                                stats[1] < .08,
                                "Joint anchors must remain connected, max=" + stats[1]);
                        helper.assertTrue(
                                stats[2] < .12,
                                "Settled joints must track targets, error=" + stats[2]);
                        helper.succeed();
                    } catch (Exception ex) {
                        rig.close();
                        helper.fail("Leaper: " + ex);
                    }
                };
        helper.runAfterDelay(1, () -> step[0].run());
    }
}
