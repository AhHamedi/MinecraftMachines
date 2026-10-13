package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** Change both ways while different legs are swinging, preserving all physical bodies. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPaceTests {
    @GameTest(template = "empty", timeoutTicks = 1300, batch = "leaperPaceSwitch")
    public static void movingPaceTransitions(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("pace-switch")) {
            helper.succeed();
            return;
        }
        boolean horrific = MinecraftMachinesLeaperLearning.horrific();
        var careful = MinecraftMachinesLeaperModel.load(horrific ? "fast" : "careful");
        var fast = MinecraftMachinesLeaperModel.load(horrific ? "horrific" : "fast");
        var rigs = new ArrayList<MinecraftMachinesLeaper>();
        for (int i = 0; i < 4; i++)
            rigs.add(
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            MinecraftMachinesLeaperLearning.arena(
                                    helper.getLevel(),
                                    i,
                                    Blocks.SMOOTH_STONE,
                                    horrific ? 192 : 64)));
        double[] minUp = {1, 1, 1, 1},
                anchor = new double[4],
                drift = new double[4],
                limits = new double[4];
        Vector3d[] stop = new Vector3d[4];
        boolean[] failed = new boolean[4];
        int[] tick = {0};
        Runnable[] next = {null};
        next[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        for (int i = 0; i < 4; i++) {
                            var r = rigs.get(i);
                            var p = r.position();
                            var up = r.up();
                            int change = 307 + i * 23;
                            var before = i % 2 == 0 ? careful : fast;
                            var after = i % 2 == 0 ? fast : careful;
                            var model = t <= change ? before : after;
                            int gaitTick = t <= change ? t : t - change;
                            double forward = Math.clamp((gaitTick - 100) / 60., 0, 1);
                            if (gaitTick > 400) forward = 0;
                            r.command(
                                    model.action(
                                            Math.max(0, gaitTick - 100) * .05,
                                            r.heading(),
                                            up.x,
                                            up.z,
                                            forward,
                                            0,
                                            0),
                                    .05,
                                    model.slew(),
                                    model.power());
                            minUp[i] = Math.min(minUp[i], up.y);
                            anchor[i] = Math.max(anchor[i], r.anchorError());
                            if (!r.valid()
                                    || up.y < (horrific ? .90 : .96)
                                    || r.anchorError() > .04) failed[i] = true;
                            var q = r.angles();
                            for (int j = 0; j < 12; j++)
                                limits[i] =
                                        Math.max(
                                                limits[i],
                                                Math.max(
                                                        MinecraftMachinesLeaper.MIN[j % 3] - q[j],
                                                        q[j] - MinecraftMachinesLeaper.MAX[j % 3]));
                            if (t == change || gaitTick == 400) stop[i] = new Vector3d(p);
                            if (stop[i] != null && (gaitTick <= 100 || gaitTick > 400))
                                drift[i] =
                                        Math.max(
                                                drift[i],
                                                Math.hypot(p.x - stop[i].x, p.z - stop[i].z));
                        }
                        if (t < 1000) {
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        var rows = new ArrayList<Object>();
                        boolean all = true;
                        for (int i = 0; i < 4; i++) {
                            boolean passed =
                                    !failed[i] && limits[i] < .04 && drift[i] < (horrific ? 4 : .8);
                            all &= passed;
                            rows.add(
                                    Map.of(
                                            "from",
                                            i % 2 == 0
                                                    ? (horrific ? "standard" : "careful")
                                                    : (horrific ? "horrific" : "fast"),
                                            "to",
                                            i % 2 == 0
                                                    ? (horrific ? "horrific" : "fast")
                                                    : (horrific ? "standard" : "careful"),
                                            "switch_tick",
                                            307 + i * 23,
                                            "minimum_up",
                                            minUp[i],
                                            "anchor_error",
                                            anchor[i],
                                            "limit_violation",
                                            limits[i],
                                            "stop_drift",
                                            drift[i],
                                            "passed",
                                            passed));
                        }
                        MinecraftMachinesLeaperLearning.write(
                                "pace-switch", Map.of("validated", all, "trials", rows));
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.assertTrue(all, "Pace transition failed; see report");
                        helper.succeed();
                    } catch (Exception e) {
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.fail("Pace transition: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> next[0].run());
    }
}
