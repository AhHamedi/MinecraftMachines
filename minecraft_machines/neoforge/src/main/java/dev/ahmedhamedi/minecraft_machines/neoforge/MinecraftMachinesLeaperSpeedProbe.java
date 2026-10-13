package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;

import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

/** Matched physical cadence/actuator sweeps; no world tick-rate changes. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperSpeedProbe {
    private static final double[][] SETTINGS = {
        {1, .6}, {2, .6}, {2, 1.2}, {2.5, 1.5}, {3, 1.8}, {3.5, 2.1}, {4, 2.4}, {5, 3}
    };
    private static final double[][] COMMANDS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, .8}};

    private static class Trial {
        Vector3d start, previous, stop;
        Vector3d[] toes = new Vector3d[4];
        double[] clearance = new double[4];
        int[] steps = new int[4];
        boolean[] swing = new boolean[4];
        double minUp = 1,
                anchor,
                slip,
                path,
                yaw,
                heading,
                drift,
                tracking,
                minY = 100,
                maxY = -100;
        int airborne;
        boolean failed;
    }

    @GameTest(template = "empty", timeoutTicks = 4000, batch = "leaperSpeedProbe")
    public static void speedEnvelope(GameTestHelper helper) throws Exception {
        if (!Set.of("speed-grid", "sprint-grid", "sprint-diagonal-grid")
                .contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed();
            return;
        }
        boolean horrific = MinecraftMachinesLeaperLearning.horrific();
        double[][] settings =
                horrific
                        ? new double[][] {
                            {3, 1.8, 1}, {5, 3, 1}, {5, 3, 2}, {7, 4.5, 1},
                            {7, 4.5, 2}, {7, 4.5, 4}, {9, 6, 2}, {9, 6, 4}
                        }
                        : SETTINGS;
        boolean diagonal = MinecraftMachinesLeaperLearning.mode().equals("sprint-diagonal-grid");
        boolean sprint = diagonal || MinecraftMachinesLeaperLearning.mode().equals("sprint-grid");
        double[][] commands = diagonal ? new double[][] {{.7, .7, 0}, {.7, -.7, 0}} : COMMANDS;
        double[] gains = {.60, .65, .70, .75, .80, .85, .90, 1.0};
        if (sprint)
            settings =
                    new double[][] {
                        {1, 4.5, 1}, {1, 4.5, 2}, {1, 4.5, 4}, {1.3, 6, 2},
                        {1.3, 6, 4}, {.8, 3.5, 1}, {.8, 3.5, 2}, {.8, 3.5, 4}
                    };
        if (diagonal)
            settings =
                    new double[][] {
                        {.8, 3.5, 2},
                        {.8, 3.5, 2},
                        {.8, 3.5, 2},
                        {.8, 3.5, 2},
                        {.8, 3.5, 2},
                        {.8, 3.5, 2},
                        {.8, 3.5, 2},
                        {.8, 3.5, 2}
                    };
        final double[][] envelope = settings;
        int walkEnd = horrific ? 360 : 500;
        int end = walkEnd + 100;
        double seconds = (walkEnd - 100) * .05;
        double[] original = MinecraftMachinesLeaperModel.load(horrific ? "fast" : "careful").gait();
        double[] gait =
                diagonal
                        ? MinecraftMachinesLeaperNavigationTests.readGait()
                        : sprint ? LeaperGait.sprintSeed(original) : original;
        var rigs = new ArrayList<MinecraftMachinesLeaper>();
        for (int i = 0; i < 8; i++)
            rigs.add(
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            MinecraftMachinesLeaperLearning.arena(
                                    helper.getLevel(),
                                    i,
                                    Blocks.SMOOTH_STONE,
                                    horrific ? 144 : 64)));
        var trials = new Trial[8];
        var results = new ArrayList<Object>();
        int[] tick = {0}, scenario = {0};
        long began = System.nanoTime();
        Runnable begin =
                () -> {
                    tick[0] = 0;
                    for (int i = 0; i < 8; i++) {
                        rigs.get(i).reset(0);
                        trials[i] = new Trial();
                    }
                };
        begin.run();
        Runnable[] next = {null};
        next[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        double[] cmd = commands[scenario[0]];
                        for (int i = 0; i < 8; i++) {
                            var r = rigs.get(i);
                            var m = trials[i];
                            var pos = r.position();
                            var up = r.up();
                            if (m.failed) {
                                r.command(
                                        LeaperGait.action(
                                                gait, 0, r.heading(), up.x, up.z, 0, 0, 0, sprint),
                                        .05,
                                        envelope[i][1],
                                        horrific ? envelope[i][2] : 1);
                                continue;
                            }
                            double ramp = t <= walkEnd ? Math.clamp((t - 100) / 60., 0, 1) : 0;
                            if (diagonal) ramp *= gains[i];
                            double turn =
                                    diagonal || scenario[0] == 3
                                            ? cmd[2]
                                            : Math.clamp(
                                                    -2 * r.heading() + .4 * r.angularVelocity().y,
                                                    -.5,
                                                    .5);
                            var action =
                                    LeaperGait.action(
                                            gait,
                                            Math.max(0, t - 100) * .05 * envelope[i][0],
                                            r.heading(),
                                            up.x,
                                            up.z,
                                            cmd[0] * ramp,
                                            cmd[1] * ramp,
                                            turn * ramp,
                                            sprint);
                            r.command(action, .05, envelope[i][1], horrific ? envelope[i][2] : 1);
                            if (t <= 100) {
                                m.start = new Vector3d(pos);
                                m.previous = new Vector3d(pos);
                                m.heading = r.heading();
                                continue;
                            }
                            m.minUp = Math.min(m.minUp, up.y);
                            m.anchor = Math.max(m.anchor, r.anchorError());
                            m.minY = Math.min(m.minY, pos.y);
                            m.maxY = Math.max(m.maxY, pos.y);
                            if (!r.valid()
                                    || !Double.isFinite(pos.x + pos.y + pos.z)
                                    || up.y < (horrific ? .90 : .94)
                                    || m.anchor > .04
                                    || Math.hypot(pos.x - m.start.x, pos.z - m.start.z)
                                            > (horrific ? 130 : 55)
                                    || Math.abs(pos.y - m.start.y) > (horrific ? 1.5 : 1)) {
                                m.failed = true;
                                continue;
                            }
                            if (t <= walkEnd) {
                                m.yaw += LeaperGait.wrap(r.heading() - m.heading);
                                m.heading = r.heading();
                                m.path += Math.hypot(pos.x - m.previous.x, pos.z - m.previous.z);
                                m.previous = new Vector3d(pos);
                                m.stop = new Vector3d(pos);
                                var q = r.angles();
                                for (int j = 0; j < 12; j++)
                                    m.tracking += Math.abs(q[j] - action[j]) / 12;
                                int support = 0;
                                for (int leg = 0; leg < 4; leg++) {
                                    var f = r.toe(leg);
                                    m.clearance[leg] = Math.max(m.clearance[leg], f.y - 7.18);
                                    if (f.y < 7.25) support++;
                                    if (f.y > 7.28) m.swing[leg] = true;
                                    if (f.y < 7.22 && m.swing[leg]) {
                                        m.swing[leg] = false;
                                        m.steps[leg]++;
                                    }
                                    if (m.toes[leg] != null && f.y < 7.20 && m.toes[leg].y < 7.20)
                                        m.slip +=
                                                Math.hypot(
                                                        f.x - m.toes[leg].x, f.z - m.toes[leg].z);
                                    m.toes[leg] = new Vector3d(f);
                                }
                                if (support == 0) m.airborne++;
                            } else if (m.stop != null)
                                m.drift =
                                        Math.max(
                                                m.drift,
                                                Math.hypot(pos.x - m.stop.x, pos.z - m.stop.z));
                        }
                        if (t < end) {
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        for (int i = 0; i < 8; i++) {
                            var m = trials[i];
                            var row = new LinkedHashMap<String, Object>();
                            row.put("scenario", scenario[0]);
                            if (diagonal) row.put("command_gain", gains[i]);
                            row.put("cadence", envelope[i][0]);
                            row.put("slew", envelope[i][1]);
                            row.put("power", horrific ? envelope[i][2] : 1);
                            row.put("failed", m.failed);
                            row.put(
                                    "speed",
                                    m.stop == null
                                            ? 0
                                            : (cmd[0] * (m.start.z - m.stop.z)
                                                            + cmd[1] * (m.stop.x - m.start.x))
                                                    / seconds);
                            row.put("yaw_rate", m.yaw / seconds);
                            row.put("minimum_up", m.minUp);
                            row.put("anchor", m.anchor);
                            row.put("slip_per_foot_second", m.slip / (4 * seconds));
                            row.put("clearance", m.clearance);
                            row.put("steps", m.steps);
                            row.put("stop_drift", m.drift);
                            row.put("tracking", m.tracking / (walkEnd - 100));
                            row.put("body_height_range", m.maxY - m.minY);
                            row.put("airborne_fraction", m.airborne / (double) (walkEnd - 100));
                            results.add(row);
                            MinecraftMachines.LOGGER.info("LEAPER_SPEED {}", row);
                        }
                        MinecraftMachinesLeaperLearning.write(
                                MinecraftMachinesLeaperLearning.mode(),
                                Map.of(
                                        "gait",
                                        gait,
                                        "settings",
                                        envelope,
                                        "commands",
                                        commands,
                                        "trials",
                                        results,
                                        "wall_seconds",
                                        (System.nanoTime() - began) / 1e9));
                        if (++scenario[0] < commands.length) {
                            begin.run();
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.succeed();
                    } catch (Exception e) {
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.fail("Speed sweep: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> next[0].run());
    }
}
