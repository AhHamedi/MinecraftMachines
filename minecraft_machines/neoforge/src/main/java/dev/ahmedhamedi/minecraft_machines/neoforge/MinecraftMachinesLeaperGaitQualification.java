package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;

import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperGaitQualification {
    @GameTest(template = "empty", timeoutTicks = 5000, batch = "leaperGaitQualification")
    public static void physicalMotorPrimitives(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("qualify-gait")) {
            helper.succeed();
            return;
        }
        double[] gait = MinecraftMachinesLeaperNavigationTests.readGait();
        boolean horrific = MinecraftMachinesLeaperLearning.horrific();
        boolean brisk = MinecraftMachinesLeaperLearning.brisk();
        double cadence = MinecraftMachinesLeaperLearning.cadence(),
                slew = MinecraftMachinesLeaperLearning.slew(),
                power = MinecraftMachinesLeaperLearning.power();
        int measureTicks = horrific ? 400 : 800;
        double duration = measureTicks * .05;
        double footSeconds = 4 * duration;
        double[][] commands = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
            {0, 0, .8},
            {0, 0, -.8},
            {.7, .7, 0},
            {.7, -.7, 0}
        };
        Block[] floors = {
            Blocks.SMOOTH_STONE, Blocks.GRASS_BLOCK, Blocks.OAK_PLANKS, Blocks.COBBLESTONE
        };
        var rigs = new ArrayList<MinecraftMachinesLeaper>();
        for (int i = 0; i < 8; i++)
            rigs.add(
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            MinecraftMachinesLeaperLearning.arena(
                                    helper.getLevel(),
                                    i,
                                    floors[i % 4],
                                    horrific ? 224 : brisk ? 128 : 44)));
        var results = new ArrayList<Object>();
        double[] progress = new double[8],
                yaw = new double[8],
                lastHeading = new double[8],
                minUp = new double[8],
                slip = new double[8],
                anchor = new double[8],
                limit = new double[8];
        double[][] clear = new double[8][4];
        Vector3d[] previous = new Vector3d[8];
        Vector3d[][] toes = new Vector3d[8][4];
        int[][] steps = new int[8][4];
        boolean[][] airborne = new boolean[8][4];
        double[] minY = new double[8], maxY = new double[8];
        int[] flightTicks = new int[8];
        int[] tick = {0}, batch = {0};
        Runnable begin =
                () -> {
                    tick[0] = 0;
                    Arrays.fill(progress, 0);
                    Arrays.fill(yaw, 0);
                    Arrays.fill(minUp, 1);
                    Arrays.fill(slip, 0);
                    Arrays.fill(anchor, 0);
                    Arrays.fill(limit, 0);
                    Arrays.fill(minY, Double.POSITIVE_INFINITY);
                    Arrays.fill(maxY, Double.NEGATIVE_INFINITY);
                    Arrays.fill(flightTicks, 0);
                    for (int i = 0; i < 8; i++) {
                        rigs.get(i).reset(batch[0] == 0 ? 0 : 1.3);
                        Arrays.fill(clear[i], 0);
                        Arrays.fill(toes[i], null);
                        Arrays.fill(steps[i], 0);
                        Arrays.fill(airborne[i], false);
                        previous[i] = rigs.get(i).position();
                        lastHeading[i] = rigs.get(i).heading();
                    }
                };
        begin.run();
        Runnable[] next = {null};
        next[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        double scale = batch[0] == 0 ? 1 : .5;
                        for (int i = 0; i < 8; i++) {
                            var r = rigs.get(i);
                            var p = r.position();
                            var up = r.up();
                            var cmd = commands[i];
                            double ramp = Math.clamp((t - 100) / 60.0, 0, 1);
                            r.command(
                                    MinecraftMachinesLeaperLearning.action(
                                            gait,
                                            Math.max(0, t - 100) * .05 * cadence,
                                            r.heading(),
                                            up.x,
                                            up.z,
                                            cmd[0] * scale * ramp,
                                            cmd[1] * scale * ramp,
                                            cmd[2] * scale * ramp),
                                    .05,
                                    slew,
                                    power);
                            if (t > 200) {
                                minY[i] = Math.min(minY[i], p.y);
                                maxY[i] = Math.max(maxY[i], p.y);
                                int support = 0;
                                double dx = p.x - previous[i].x,
                                        dz = p.z - previous[i].z,
                                        h = r.heading(),
                                        norm = Math.hypot(cmd[0], cmd[1]);
                                if (norm > 0)
                                    progress[i] +=
                                            ((Math.sin(h) * dx - Math.cos(h) * dz) * cmd[0]
                                                            + (Math.cos(h) * dx + Math.sin(h) * dz)
                                                                    * cmd[1])
                                                    / norm;
                                yaw[i] += LeaperGait.wrap(h - lastHeading[i]);
                                minUp[i] = Math.min(minUp[i], up.y);
                                anchor[i] = Math.max(anchor[i], r.anchorError());
                                var q = r.angles();
                                for (int j = 0; j < 12; j++)
                                    limit[i] =
                                            Math.max(
                                                    limit[i],
                                                    Math.max(
                                                            MinecraftMachinesLeaper.MIN[j % 3]
                                                                    - q[j],
                                                            q[j]
                                                                    - MinecraftMachinesLeaper.MAX[
                                                                            j % 3]));
                                for (int leg = 0; leg < 4; leg++) {
                                    var foot = r.toe(leg);
                                    clear[i][leg] = Math.max(clear[i][leg], foot.y - 7.18);
                                    if (foot.y < 7.25) support++;
                                    if (foot.y > 7.28) airborne[i][leg] = true;
                                    if (foot.y < 7.22 && airborne[i][leg]) {
                                        airborne[i][leg] = false;
                                        steps[i][leg]++;
                                    }
                                    if (toes[i][leg] != null
                                            && foot.y < 7.20
                                            && toes[i][leg].y < 7.20)
                                        slip[i] +=
                                                Math.hypot(
                                                        foot.x - toes[i][leg].x,
                                                        foot.z - toes[i][leg].z);
                                    toes[i][leg] = foot;
                                }
                                if (support == 0) flightTicks[i]++;
                            }
                            previous[i] = new Vector3d(p);
                            lastHeading[i] = r.heading();
                        }
                        if (t < 200 + measureTicks) {
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        for (int i = 0; i < 8; i++) {
                            double speed = progress[i] / duration,
                                    turnRate = yaw[i] / duration,
                                    minClear = Arrays.stream(clear[i]).min().orElse(0);
                            double slipRate = slip[i] / footSeconds;
                            double slipPerBlock = slipRate / Math.max(.1, Math.abs(speed));
                            // Walking grip is compared per distance travelled as well as
                            // per second; stationary yaw has no translational denominator.
                            boolean grip =
                                    !brisk
                                            ? slipRate < .25
                                            : i == 4 || i == 5
                                                    ? slipRate < (horrific ? 2.5 : .75)
                                                    : slipRate < (horrific ? 3.5 : 1)
                                                            && slipPerBlock < (horrific ? .7 : .6);
                            boolean passed =
                                    minUp[i] > MinecraftMachinesLeaperLearning.minimumUp()
                                            && anchor[i] < .04
                                            && limit[i] < .04
                                            && minClear > (batch[0] == 0 ? .10 : .03)
                                            && maxY[i] - minY[i] < (horrific ? 1.5 : .5)
                                            && flightTicks[i] < (horrific ? measureTicks * .4 : 40)
                                            && (batch[0] != 0
                                                    || Arrays.stream(steps[i]).min().orElse(0)
                                                            >= (brisk
                                                                    ? Math.max(
                                                                            5,
                                                                            (int)
                                                                                    ((horrific
                                                                                                    ? .5
                                                                                                    : .6)
                                                                                            * duration
                                                                                            * cadence
                                                                                            / MinecraftMachinesLeaperLearning
                                                                                                    .period(
                                                                                                            gait)))
                                                                    : 3))
                                            && grip
                                            && (i == 4 || i == 5
                                                    ? turnRate * Math.signum(commands[i][2])
                                                            > (batch[0] == 0 ? .03 : .01)
                                                    : speed
                                                            > (brisk
                                                                    ? (batch[0] == 0
                                                                            ? (horrific ? 3.5 : 1.0)
                                                                            : .10)
                                                                    : (batch[0] == 0 ? .15 : .03)));
                            var row = new LinkedHashMap<String, Object>();
                            row.put("command", commands[i]);
                            row.put("scale", scale);
                            row.put("floor", floors[i % 4].getDescriptionId());
                            row.put("passed", passed);
                            row.put("speed", speed);
                            row.put("turn_rate", turnRate);
                            row.put("minimum_up", minUp[i]);
                            row.put("anchor_error", anchor[i]);
                            row.put("limit_violation", limit[i]);
                            row.put("clearance", clear[i].clone());
                            row.put("steps", steps[i].clone());
                            row.put("slip_per_foot_second", slip[i] / footSeconds);
                            if (i != 4 && i != 5)
                                row.put("foot_motion_per_travel_block", slipPerBlock);
                            results.add(row);
                            row.put("body_height_range", maxY[i] - minY[i]);
                            row.put("airborne_fraction", flightTicks[i] / (double) measureTicks);
                            var finalPosition = rigs.get(i).position();
                            row.put(
                                    "final_position",
                                    List.of(finalPosition.x, finalPosition.y, finalPosition.z));
                        }
                        MinecraftMachines.LOGGER.info(
                                "LEAPER_GAIT_QUALIFICATION batch={} results={}", batch[0], results);
                        MinecraftMachinesLeaperLearning.write(
                                "gait-qualification",
                                Map.of(
                                        "gait",
                                        gait,
                                        "trials",
                                        results,
                                        "cadence",
                                        cadence,
                                        "slew",
                                        slew,
                                        "power",
                                        power,
                                        "gait_policy",
                                        horrific ? LeaperGait.SPRINT_VERSION : LeaperGait.VERSION,
                                        "measured_seconds",
                                        duration));
                        if (++batch[0] < 2) {
                            begin.run();
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.assertTrue(
                                results.stream()
                                        .allMatch(row -> (boolean) ((Map<?, ?>) row).get("passed")),
                                "Leaper gait qualification failed; see report");
                        helper.succeed();
                    } catch (Exception e) {
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.fail("Leaper gait qualification: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> next[0].run());
    }
}
