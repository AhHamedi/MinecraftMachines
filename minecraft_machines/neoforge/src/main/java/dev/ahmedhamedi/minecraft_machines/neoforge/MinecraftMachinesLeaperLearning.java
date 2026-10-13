package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.GsonBuilder;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperPartBlock;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperLearning {
    static String mode() {
        return System.getProperty("minecraft_machines.leaperMode", "packaged");
    }

    static String profile() {
        String p = System.getProperty("minecraft_machines.leaperProfile", "careful");
        if (!Set.of("careful", "brisk", "horrific").contains(p))
            throw new IllegalArgumentException("Unknown Leaper profile: " + p);
        return p;
    }

    static boolean horrific() {
        return profile().equals("horrific");
    }

    static boolean brisk() {
        return !profile().equals("careful");
    }

    static String packagedPace() {
        return horrific() ? "horrific" : brisk() ? "fast" : "careful";
    }

    static double cadence() {
        return horrific() ? .8 : brisk() ? 3 : 1;
    }

    static double slew() {
        return horrific() ? 3.5 : brisk() ? 1.8 : .6;
    }

    static double power() {
        return horrific() ? 2 : 1;
    }

    static double[] action(
            double[] p,
            double seconds,
            double heading,
            double ux,
            double uz,
            double forward,
            double strafe,
            double turn) {
        return LeaperGait.action(p, seconds, heading, ux, uz, forward, strafe, turn, horrific());
    }

    static double period(double[] p) {
        return LeaperGait.period(p, horrific());
    }

    static MinecraftMachinesLeaperModel candidate(double[] gait, double[] navigation) {
        return new MinecraftMachinesLeaperModel(
                gait, navigation, cadence(), slew(), power(), horrific());
    }

    static double minimumUp() {
        return horrific() ? .90 : .96;
    }

    static String reportName(String name) {
        return brisk() ? profile() + "-" + name : name;
    }

    @GameTest(template = "empty", timeoutTicks = 100000, batch = "leaperTraining")
    public static void trainGait(GameTestHelper helper) throws Exception {
        if (!mode().equals("train")) {
            helper.succeed();
            return;
        }
        new MinecraftMachinesLeaperTrainer(helper).run();
    }

    static Vector3d arena(ServerLevel level, int slot, Block floor, int radius) {
        int spacing = horrific() ? 576 : brisk() ? 288 : 112;
        int x = (slot % 4) * spacing, z = (slot / 4) * spacing;
        for (int cx = (x - radius - 2) >> 4; cx <= (x + radius + 2) >> 4; cx++)
            for (int cz = (z - radius - 2) >> 4; cz <= (z + radius + 2) >> 4; cz++)
                level.setChunkForced(cx, cz, true);
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                level.setBlockAndUpdate(new BlockPos(x + dx, 6, z + dz), floor.defaultBlockState());
        return new Vector3d(x + .5, 7 + MinecraftMachinesLeaper.HEIGHT, z + .5);
    }

    static void write(String name, Object data) throws Exception {
        name = reportName(name);
        Path folder = Path.of("leaper_learning");
        Files.createDirectories(folder);
        Path tmp = folder.resolve(name + ".tmp");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        var payload = gson.toJsonTree(data);
        if (name.contains("predator") && payload.isJsonObject()) {
            if (Boolean.getBoolean("minecraft_machines.leaperHighStepCandidate")
                    && !payload.getAsJsonObject().has("highstep_settings"))
                payload.getAsJsonObject()
                        .add("highstep_settings", MinecraftMachinesLeaperHighStepSettings.read());
            payload.getAsJsonObject()
                    .addProperty(
                            "contact_grip",
                            Boolean.parseBoolean(
                                    System.getProperty("minecraft_machines.leaperGrip", "true")));
            payload.getAsJsonObject()
                    .addProperty(
                            "physics_substeps",
                            Integer.getInteger("minecraft_machines.leaperSubsteps", 4));
            payload.getAsJsonObject()
                    .addProperty(
                            "balance",
                            Double.parseDouble(
                                    System.getProperty("minecraft_machines.leaperBalance", "1")));
            try (var geometry =
                    LeaperPartBlock.class.getResourceAsStream(
                            "/data/minecraft_machines/leaper/collision_geometry.json")) {
                if (geometry != null)
                    payload.getAsJsonObject()
                            .addProperty(
                                    "collision_sha256",
                                    java.util.HexFormat.of()
                                            .formatHex(
                                                    java.security.MessageDigest.getInstance(
                                                                    "SHA-256")
                                                            .digest(geometry.readAllBytes())));
            }
            payload.getAsJsonObject()
                    .addProperty(
                            "implementation_sha256",
                            System.getProperty(
                                    "minecraft_machines.leaperImplementationSha256", "unrecorded"));
        }
        Files.writeString(tmp, gson.toJson(payload));
        Files.move(
                tmp,
                folder.resolve(name + ".json"),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    @GameTest(template = "empty", timeoutTicks = 1600, batch = "leaperProbe")
    public static void primitives(GameTestHelper helper) throws Exception {
        if (!mode().startsWith("probe")) {
            helper.succeed();
            return;
        }
        int count = Integer.getInteger("minecraft_machines.leaperPopulation", 8);
        var rigs = new ArrayList<MinecraftMachinesLeaper>();
        double[][] commands = {
            {1, 0}, {-1, 0}, {0, .8}, {0, -.8}, {.8, .4}, {.8, -.4}, {0, 0}, {.5, 0}
        };
        var rows = new ArrayList<Map<String, Object>>();
        Vector3d[] starts = new Vector3d[count];
        double[] minUp = new double[count],
                yaw = new double[count],
                lastYaw = new double[count],
                path = new double[count],
                anchor = new double[count],
                tracking = new double[count];
        Vector3d[] previous = new Vector3d[count];
        Arrays.fill(minUp, 1);
        double[][] clearance = new double[count][4];
        double[] slip = new double[count], fkError = new double[count];
        Vector3d[][] lastToes = new Vector3d[count][4];
        double friction =
                PhysicsBlockPropertyHelper.getFriction(
                        MinecraftMachinesBlocks.LEAPER_PART
                                .getDefaultState()
                                .setValue(LeaperPartBlock.PART, 43));
        for (int i = 0; i < count; i++) {
            var rig =
                    MinecraftMachinesLeaper.spawn(
                            helper.getLevel(),
                            arena(helper.getLevel(), i, Blocks.SMOOTH_STONE, 40));
            rigs.add(rig);
            starts[i] = rig.position();
            previous[i] = rig.position();
        }
        long began = System.nanoTime();
        int[] tick = {0};
        Runnable[] next = {null};
        next[0] =
                () -> {
                    try {
                        int t = ++tick[0];
                        for (int i = 0; i < count; i++) {
                            var r = rigs.get(i);
                            var p = r.position();
                            var up = r.up();
                            if (t == 100) {
                                starts[i] = new Vector3d(p);
                                previous[i] = new Vector3d(p);
                                lastYaw[i] = r.heading();
                            }
                            double[] cmd =
                                    mode().equals("probe-grid")
                                            ? new double[] {i % 8 < 4 ? 1 : 0, i % 8 < 4 ? 0 : .9}
                                            : commands[i % commands.length];
                            double[] genome =
                                    mode().equals("probe-grid")
                                            ? LeaperGait.probeSeed(i % 4)
                                            : LeaperGait.seed();
                            double ramp = Math.clamp((t - 100) / 60.0, 0, 1);
                            double[] action =
                                    LeaperGait.action(
                                            genome,
                                            Math.max(0, t - 100) * .05,
                                            r.heading(),
                                            up.x,
                                            up.z,
                                            cmd[0] * ramp,
                                            cmd[1] * ramp);
                            r.command(action, .05);
                            if (t >= 100) {
                                minUp[i] = Math.min(minUp[i], up.y);
                                anchor[i] = Math.max(anchor[i], r.anchorError());
                                yaw[i] += LeaperGait.wrap(r.heading() - lastYaw[i]);
                                lastYaw[i] = r.heading();
                                path[i] += Math.hypot(p.x - previous[i].x, p.z - previous[i].z);
                                previous[i] = new Vector3d(p);
                                var q = r.angles();
                                for (int j = 0; j < 12; j++)
                                    tracking[i] = Math.max(tracking[i], Math.abs(q[j] - action[j]));
                                for (int leg = 0; leg < 4; leg++) {
                                    var toe = r.toe(leg);
                                    clearance[i][leg] = Math.max(clearance[i][leg], toe.y - 7.18);
                                    if (lastToes[i][leg] != null
                                            && toe.y < 7.20
                                            && lastToes[i][leg].y < 7.20)
                                        slip[i] +=
                                                Math.hypot(
                                                        toe.x - lastToes[i][leg].x,
                                                        toe.z - lastToes[i][leg].z);
                                    lastToes[i][leg] = new Vector3d(toe);
                                    var expected =
                                            LeaperGait.forward(
                                                    leg,
                                                    q[leg * 3],
                                                    q[leg * 3 + 1],
                                                    q[leg * 3 + 2]);
                                    fkError[i] =
                                            Math.max(
                                                    fkError[i],
                                                    r.localToe(leg)
                                                            .distance(
                                                                    new Vector3d(
                                                                            expected[0],
                                                                            expected[1],
                                                                            expected[2])));
                                }
                            }
                            if (t % 100 == 0) {
                                var toes = new ArrayList<Object>();
                                for (int leg = 0; leg < 4; leg++) {
                                    var f = r.toe(leg);
                                    toes.add(List.of(f.x, f.y, f.z));
                                }
                                rows.add(
                                        Map.of(
                                                "tick",
                                                t,
                                                "slot",
                                                i,
                                                "position",
                                                List.of(p.x, p.y, p.z),
                                                "up",
                                                up.y,
                                                "heading",
                                                yaw[i],
                                                "angles",
                                                r.angles(),
                                                "targets",
                                                action,
                                                "toes",
                                                toes));
                            }
                        }
                        if (t % 200 == 0)
                            MinecraftMachines.LOGGER.info(
                                    "LEAPER_PROBE tick={} first={} up={}",
                                    t,
                                    rigs.getFirst().position(),
                                    rigs.getFirst().upright());
                        if (t < 1300) {
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        var results = new ArrayList<Object>();
                        for (int i = 0; i < count; i++) {
                            var p = rigs.get(i).position();
                            results.add(
                                    Map.of(
                                            "slot",
                                            i,
                                            "command",
                                            mode().equals("probe-grid")
                                                    ? new double[] {
                                                        i % 8 < 4 ? 1 : 0, i % 8 < 4 ? 0 : .9
                                                    }
                                                    : commands[i % commands.length],
                                            "dx",
                                            p.x - starts[i].x,
                                            "dz",
                                            p.z - starts[i].z,
                                            "yaw",
                                            yaw[i],
                                            "minimum_up",
                                            minUp[i],
                                            "path",
                                            path[i],
                                            "anchor_error",
                                            anchor[i],
                                            "max_tracking_error",
                                            tracking[i]));
                        }
                        write(
                                mode() + "-" + count,
                                Map.of(
                                        "wall_seconds",
                                        (System.nanoTime() - began) / 1e9,
                                        "simulation_seconds",
                                        65,
                                        "population",
                                        count,
                                        "foot_friction",
                                        friction,
                                        "results",
                                        results,
                                        "history",
                                        rows,
                                        "clearance",
                                        clearance,
                                        "slip",
                                        slip,
                                        "fk_error",
                                        fkError));
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.succeed();
                    } catch (Exception e) {
                        rigs.forEach(MinecraftMachinesLeaper::close);
                        helper.fail("Leaper probe: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> next[0].run());
    }
}
