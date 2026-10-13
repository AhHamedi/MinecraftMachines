package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Mode;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.joml.Vector3d;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reproduces arrival at a supported fallback near home while the original home remains farther away. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperReturnHomeTests {
    private static final int PRIMED_TICK = LeaperBehaviour.LOST_SIGHT_TICKS + LeaperBehaviour.SEARCH_TICKS;

    @GameTest(template = "empty", timeoutTicks = 500, batch = "leaperBehaviourReturnHome")
    public static void resolvedHomeArrivalResumesPatrol(GameTestHelper helper) throws Exception {
        if (!Set.of("predator-behaviour", "predator-behaviour-return-home")
                .contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed();
            return;
        }
        var run = new Run(helper);
        try {
            run.begin();
        } catch (Exception e) {
            run.close();
            throw e;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @GameTest(template = "empty", timeoutTicks = 2000, batch = "leaperBehaviourHomeRoute")
    public static void distantHomeUsesSupportedLegs(GameTestHelper helper) throws Exception {
        if (!Set.of("predator-behaviour", "predator-recovery").contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed();
            return;
        }
        var run = new Run(helper);
        run.distant = true;
        try { run.begin(); } catch (Exception e) { run.close(); throw e; }
    }

    private static final class Run {
        final GameTestHelper helper;
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        MinecraftMachinesLeaperBehaviour behaviour;
        Point home;
        Vector3d resolved;
        int tick, returnedAt = -1;
        double distanceAtReturn;
        boolean distant, sawLocalLeg;

        Run(GameTestHelper helper) { this.helper = helper; }

        void begin() throws Exception {
            var level = helper.getLevel();
            var origin = MinecraftMachinesLeaperLearning.arena(level, distant ? 4 : 2, Blocks.SMOOTH_STONE, distant ? 80 : 40);
            int ox = (int) Math.floor(origin.x), oz = (int) Math.floor(origin.z);
            for (int x = ox - 40; x <= ox + 40; x++)
                for (int z = oz - 40; z <= oz + 40; z++)
                    for (int y = 7; y <= 32; y++) {
                        var pos = new BlockPos(x, y, z);
                        if (!level.getBlockState(pos).isAir())
                            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
            rig = MinecraftMachinesLeaper.spawn(level, origin);
            rig.reset(0);
            nav = new MinecraftMachinesLeaperPredatorNavigator(
                    rig, level, MinecraftMachinesLeaperPredatorExperiments.candidate());
            require(nav.apex.enabled && rig.apexVersion, "Gameplay fixtures must use Apex");
            nav.pace = "careful";
            loop();
        }

        Point body() {
            var p = rig.corePivot();
            double floor = nav.leap.supportFloor();
            if (distant && !Double.isFinite(floor)) floor = p.y - nav.control.standingHeight();
            require(Double.isFinite(floor), "Return-home fixture lost physical footing");
            return new Point(p.x, floor, p.z);
        }

        void primeReturnHome() throws Exception {
            var body = body();
            home = new Point(body.x() - (distant ? 50 : 5), body.y(), body.z());
            if (distant) {
                // The first direct return leg and its nearby fallback centers are
                // unsupported. Finish a real zero-distance command first: its old
                // arrived flag must not pin every subsequent retry to that failed leg.
                int cx = (int) Math.floor(body.x() - 20), cz = (int) Math.floor(body.z());
                for (int x = cx - 6; x <= cx + 6; x++)
                    for (int z = cz - 5; z <= cz + 5; z++)
                        helper.getLevel().setBlockAndUpdate(new BlockPos(x, 6, z), Blocks.AIR.defaultBlockState());
                require(nav.go(body.x(), body.y(), body.z()), "Fixture must accept its initial supported arrival");
            }
            // The original center is unsupported; its +3X fallback remains solid and
            // the rig starts about two blocks beyond that fallback, as in the live hold.
            if (!distant) helper.getLevel().setBlockAndUpdate(
                    BlockPos.containing(home.x(), home.y() - 1, home.z()), Blocks.AIR.defaultBlockState());
            require(distant || !Double.isFinite(nav.map.height(home.x(), home.z(), home.y() + 1.5, 5)),
                    "Original home must require a supported fallback");
            behaviour = new MinecraftMachinesLeaperBehaviour(home, 16);
            // Fast-forward only the expired sensory memory, before native replay begins.
            var missing = UUID.fromString("e9e0fbf1-ad9d-4315-82ba-fb9b1b3d9f55");
            behaviour.brain.forceHunt(0, missing, body);
            behaviour.brain.tick(LeaperBehaviour.LOST_SIGHT_TICKS, body, null, false, false, false);
            behaviour.brain.tick(PRIMED_TICK, body, null, false, false, false);
            require(behaviour.brain.returningHome(), "Fixture must begin a return-home journey");
            var clock = MinecraftMachinesLeaperBehaviour.class.getDeclaredField("tick");
            clock.setAccessible(true);
            clock.setInt(behaviour, PRIMED_TICK);
        }

        void step() throws Exception {
            ++tick;
            if (tick == 81) primeReturnHome();
            if (behaviour != null) {
                boolean wasReturning = behaviour.brain.returningHome();
                boolean wasArrived = nav.arrived;
                var at = body();
                behaviour.step(helper.getLevel(), rig, nav, "horrific");
                if (distant && nav.goal != null && Math.hypot(at.x() - nav.goal.x, at.z() - nav.goal.z) > 4
                        && !sawLocalLeg) {
                    double local = Math.hypot(at.x() - nav.goal.x, at.z() - nav.goal.z);
                    require(local <= 24 && Math.hypot(home.x() - nav.goal.x, home.z() - nav.goal.z) > 24,
                            "Distant return must begin with a supported local leg");
                    sawLocalLeg = true;
                }
                if (distant && !behaviour.brain.returningHome()) {
                    require(sawLocalLeg && Math.hypot(at.x() - home.x(), at.z() - home.z()) <= 4,
                            "Distant return did not physically reach the original home");
                    MinecraftMachinesLeaperLearning.write("predator-behaviour-home-route", Map.of(
                            "success", true, "complete", true, "ticks", tick,
                            "initial_distance", 50, "final_distance", Math.hypot(at.x() - home.x(), at.z() - home.z()),
                            "local_leg_verified", true));
                    close(); helper.succeed(); return;
                }
                if (!distant && resolved == null && nav.goal != null) {
                    resolved = new Vector3d(nav.goal);
                    require(Math.abs(resolved.x - (home.x() + 3)) < .01
                                    && Math.abs(resolved.z - home.z()) < .01,
                            "Return route did not use the original home's +3X fallback");
                }
                if (!distant && wasReturning && !behaviour.brain.returningHome()) {
                    distanceAtReturn = Math.sqrt(Math.pow(at.x() - home.x(), 2)
                            + Math.pow(at.y() - home.y(), 2) + Math.pow(at.z() - home.z(), 2));
                    require(wasArrived && distanceAtReturn > 4 && distanceAtReturn < 8,
                            "Regression must resolve through verified fallback arrival, not the original radius");
                    returnedAt = tick;
                }
                if (returnedAt > 0 && tick - returnedAt > LeaperBehaviour.RETURN_CALM_TICKS
                        && nav.goal != null && nav.goal.distanceSquared(resolved) > 1) {
                    require(behaviour.mode() == Mode.PATROLLING, "Return did not resume ordinary patrol");
                    verifyReacquisition();
                    MinecraftMachinesLeaperLearning.write("predator-behaviour-return-home", Map.of(
                            "success", true, "complete", true, "ticks", tick,
                            "return_tick", returnedAt, "original_home_distance", distanceAtReturn,
                            "fallback", MinecraftMachinesLeaperPursuitRegressionTests.xyz(resolved),
                            "new_patrol_goal", MinecraftMachinesLeaperPursuitRegressionTests.xyz(nav.goal),
                            "reacquired_visible_player", true));
                    close();
                    helper.succeed();
                    return;
                }
            }
            nav.step();
            require(rig.valid() && rig.corePivot().isFinite() && rig.assemblyVelocity().isFinite()
                            && rig.anchorError() < .16 && rig.upright() > .6,
                    "Return-home fixture violated physical stability");
            require(tick < (distant ? 1800 : 400), "Arrival left patrol stuck returning home");
            loop();
        }

        void verifyReacquisition() {
            var p = rig.corePivot();
            var optical = rig.coreOrientation().transform(new Vector3d(0, 0, -1.08)).add(p);
            var forward = rig.coreOrientation().transform(new Vector3d(0, 0, -1));
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setPos(p.x + forward.x * 8, home.y(), p.z + forward.z * 8);
            var evidence = MinecraftMachinesLeaperPerception.sense(helper.getLevel(), rig,
                    new Vec3(optical.x, optical.y, optical.z),
                    new Vec3(forward.x, forward.y, forward.z), player, false);
            require(evidence != null && evidence.visible(), "Nearby player must be visible in the native world");
            behaviour.brain.tick(PRIMED_TICK + tick - 80 + 1, body(), evidence, false, false, false);
            require(behaviour.mode() == Mode.ALERT && player.getUUID().equals(behaviour.brain.target()),
                    "Resolved return-home latch still prevented player acquisition");
        }

        void loop() {
            helper.runAfterDelay(1, () -> {
                try {
                    step();
                } catch (Exception e) {
                    close();
                    helper.fail("Fallback home replay at tick " + tick + ": " + e);
                }
            });
        }

        void close() { if (rig != null) rig.close(); }
    }
}
