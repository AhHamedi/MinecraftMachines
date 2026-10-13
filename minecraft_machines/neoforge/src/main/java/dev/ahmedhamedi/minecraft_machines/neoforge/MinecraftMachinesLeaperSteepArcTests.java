package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperBallistics;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.*;

import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;

/** Short-hop angle regression plus actual bounded takeoff and landing, without a score claim. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperSteepArcTests {
    static final String MODE = "predator-steep-arc";

    @GameTest(template = "empty", timeoutTicks = 2400, batch = "leaperSteepArc")
    public static void steep(GameTestHelper h) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals(MODE)) {
            h.succeed();
            return;
        }
        new Run(h).begin();
    }

    static class Trial extends MinecraftMachinesLeaperLeapTests.Trial {
        boolean explicitlyPrepared;

        Trial(GameTestHelper h, int id) throws Exception {
            super(h, id, "predator-pounce-elevation");
            name = "steep-" + id;
        }

        @Override
        Vector3d elevationCourse(GameTestHelper h, int id) {
            double distance = id < 2 ? 13 : id < 4 ? 25 : id == 4 ? 40 : 64;
            int rise = id == 2 || id == 3 ? 6 : 0;
            double angle = id == 1 ? .76 : id == 3 ? .35 : 0;
            int radius = 112;
            var level = h.getLevel();
            var start =
                    MinecraftMachinesLeaperLearning.arena(level, id, Blocks.SMOOTH_STONE, radius);
            int ox = (int) Math.floor(start.x), oz = (int) Math.floor(start.z);
            for (int x = ox - radius; x <= ox + radius; x++)
                for (int z = oz - radius; z <= oz + radius; z++) {
                    double along = (x - ox) * Math.sin(angle) - (z - oz) * Math.cos(angle);
                    int floor = 7 + (along >= 12 ? rise : 0);
                    int top =
                            Math.max(
                                    7 + rise, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                    for (int y = 7; y < top; y++)
                        level.setBlockAndUpdate(
                                new BlockPos(x, y, z),
                                y < floor
                                        ? Blocks.STONE.defaultBlockState()
                                        : Blocks.AIR.defaultBlockState());
                }
            goal =
                    new Vector3d(
                            start.x + distance * Math.sin(angle),
                            7 + rise,
                            start.z - distance * Math.cos(angle));
            var map = new MinecraftMachinesLeaperTerrainMap(level);
            map.exposedSurfaces = true;
            double actual = map.height(goal.x, goal.z, goal.y + .55, 72);
            if (!Double.isFinite(actual) || Math.abs(actual - goal.y) > 1e-6)
                throw new IllegalStateException("Wrong exposed fixture goal");
            fixture.put("distance", distance);
            fixture.put("actual_elevation", actual - 7);
            fixture.put("course_angle", angle);
            fixture.put("bank_start", 12);
            fixture.put("radius", radius);
            fixture.put(
                    "preparation_trigger",
                    "Direct Search+prepare after native hold; actual ALIGN/CROUCH recheck, impulse,"
                            + " FLIGHT/LAND and navigation arrival");
            return start;
        }

        @Override
        void step() {
            if (!done
                    && !explicitlyPrepared
                    && nav.leap.launches == 0
                    && ticks > 80
                    && nav.planning == null
                    && nav.leap.state == MinecraftMachinesLeaperLeap.State.WALK) {
                var plan = nav.leap.plan(goal);
                if (plan == null) {
                    done = true;
                    reason = "No supported collision-clear arc";
                    return;
                }
                nav.leap.prepare(plan);
                explicitlyPrepared = true;
            }
            super.step();
        }

        @Override
        Map<String, Object> result() {
            var r = super.result();
            var arcs = new ArrayList<Object>();
            boolean anglesPass = true;
            if (nav != null)
                for (var event : nav.leap.events) {
                    if (!"takeoff".equals(event.get("event"))) continue;
                    var a = (LeaperBallistics.Arc) event.get("arc");
                    double angle = Math.toDegrees(Math.atan2(a.vy(), Math.hypot(a.vx(), a.vz())));
                    double apexTime =
                            a.drag() < 1e-9
                                    ? a.vy() / a.gravity()
                                    : Math.log1p(a.drag() * a.vy() / a.gravity()) / a.drag();
                    var end = a.at(a.seconds());
                    var row = new LinkedHashMap<String, Object>();
                    row.put("tick", event.get("tick"));
                    row.put("degrees", angle);
                    row.put("seconds", a.seconds());
                    row.put("horizontal_range", Math.hypot(end[0] - a.x(), end[2] - a.z()));
                    row.put("apex_above_endpoints", a.at(apexTime)[1] - Math.max(a.y(), end[1]));
                    row.put("velocity", new double[] {a.vx(), a.vy(), a.vz()});
                    arcs.add(diagnostic(row, "launch" + arcs.size()));
                    anglesPass &=
                            Double.isFinite(angle)
                                    && angle >= 55
                                    && a.vy() <= 24
                                    && a.seconds() <= 4.5
                                    && Math.hypot(a.vx(), a.vz()) <= 50;
                }
            boolean steep = !arcs.isEmpty() && anglesPass;
            r.put("route_success", r.get("success"));
            r.put("steep_selection_pass", steep);
            r.put("success", Boolean.TRUE.equals(r.get("success")) && steep);
            r.put("explicitly_prepared", explicitlyPrepared);
            r.put("actual_launches", arcs);
            return r;
        }
    }

    static class Run {
        final GameTestHelper helper;
        final List<Trial> trials = new ArrayList<>();
        int oldSubsteps;

        Run(GameTestHelper h) {
            helper = h;
        }

        void begin() throws Exception {
            if (!Boolean.getBoolean("minecraft_machines.leaperLeap")
                    || !Boolean.getBoolean("minecraft_machines.leaperAgile")
                    || !MinecraftMachinesLeaperLearning.profile().equals("horrific")
                    || Integer.getInteger("minecraft_machines.leaperSubsteps", 4) != 4
                    || !Boolean.parseBoolean(
                            System.getProperty("minecraft_machines.leaperGrip", "true"))
                    || Double.parseDouble(
                                    System.getProperty("minecraft_machines.leaperBalance", "1"))
                            != 1
                    || !Files.isRegularFile(
                            Path.of("leaper_learning", "horrific-predator-candidate.json")))
                throw new IllegalStateException(
                        "Require explicit base candidate, H/agile/leap/native4/grip/balance1");
            var physics = SubLevelPhysicsSystem.get(helper.getLevel());
            oldSubsteps = physics.getConfig().substepsPerTick;
            physics.getConfig().substepsPerTick = 4;
            physics.onConfigUpdated();
            for (int i = 0; i < 6; i++) trials.add(new Trial(helper, i));
            loop();
        }

        void persist() throws Exception {
            var r = new LinkedHashMap<String, Object>();
            r.put("mode", MODE);
            r.put("qualified", false);
            r.put("expected_trials", 6);
            r.put("complete", trials.size() == 6 && trials.stream().allMatch(t -> t.done));
            r.put("control_hz", 20);
            r.put("trials", trials.stream().map(Trial::result).toList());
            r.put("physics", SubLevelPhysicsSystem.get(helper.getLevel()).getConfig());
            MinecraftMachinesLeaperLearning.write(MODE, r);
        }

        void close() {
            for (var t : trials) if (t.rig != null) t.rig.close();
            var p = SubLevelPhysicsSystem.get(helper.getLevel());
            p.getConfig().substepsPerTick = oldSubsteps;
            p.onConfigUpdated();
        }

        void loop() {
            helper.runAfterDelay(
                    1,
                    () -> {
                        try {
                            for (var t : trials)
                                try {
                                    t.step();
                                } catch (Exception e) {
                                    t.done = true;
                                    t.success = false;
                                    t.reason = "Native exception: " + e;
                                    t.physicalFailure = true;
                                    t.captureFinal();
                                }
                            if (trials.stream().allMatch(t -> t.done)) {
                                persist();
                                close();
                                helper.succeed();
                            } else loop();
                        } catch (Exception e) {
                            try {
                                persist();
                            } catch (Exception write) {
                                e.addSuppressed(write);
                            }
                            close();
                            helper.fail(e.toString());
                        }
                    });
        }
    }
}
