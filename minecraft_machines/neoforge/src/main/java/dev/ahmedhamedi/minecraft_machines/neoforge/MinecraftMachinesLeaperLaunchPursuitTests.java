package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.mojang.authlib.GameProfile;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperShockwaveClock;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.util.*;

/** Real blast/recovery followed by controlled player flight packets and a lost-airborne search. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperLaunchPursuitTests {
    @GameTest(template = "empty", timeoutTicks = 1800, batch = "leaperLaunchPursuit")
    public static void launchedPrey(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-behaviour")) {
            helper.succeed(); return;
        }
        var run = new Run(helper);
        try { run.begin(); } catch (Exception e) { run.close(); throw e; }
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
    private static final class Run {
        final GameTestHelper helper;
        final MinecraftMachinesLeaperShockwave pulse = new MinecraftMachinesLeaperShockwave();
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        MinecraftMachinesLeaperBehaviour behaviour;
        ServerPlayer player;
        Vector3d flight, direction, hiddenOrigin;
        Point frozen;
        int tick, blastTick = -1, caughtTick = -1, hiddenTick = -1, prepareTick = -1, airborneGoals, distantFrames;
        final Set<String> huntGoals = new HashSet<>(), alertGoals = new HashSet<>();
        double maxSeparation;
        Run(GameTestHelper helper) { this.helper = helper; }
        void begin() throws Exception {
            var level = helper.getLevel();
            var spawn = MinecraftMachinesLeaperLearning.arena(level, 8, Blocks.BEDROCK, 200);
            rig = MinecraftMachinesLeaper.spawn(level, spawn); rig.reset(0);
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, level, MinecraftMachinesLeaperPredatorExperiments.candidate());
            behaviour = new MinecraftMachinesLeaperBehaviour(new Point(spawn.x, 7, spawn.z), 24);
            player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "launched-prey"));
            player.setGameMode(GameType.SURVIVAL); player.setPos(spawn.x + 250, 7, spawn.z);
            level.addNewPlayer(player);
            loop();
        }
        void sightChecks() {
            var level = helper.getLevel(); var p = rig.corePivot();
            direction = rig.coreOrientation().transform(new Vector3d(0, 0, -1));
            direction.y = 0; direction.normalize();
            player.setPos(p.x + direction.x * 80, 7, p.z + direction.z * 80);
            require(MinecraftMachinesLeaperPursuitSight.sample(level, rig, player.getUUID(), null) != null,
                    "Known prey beyond acquisition range cannot be tracked");
            require(new MinecraftMachinesLeaperPerception().sample(level, rig, null, tick, null) == null,
                    "Long tracking accidentally extended passive acquisition");
            var middle = BlockPos.containing(p.x + direction.x * 40, 7, p.z + direction.z * 40);
            var wall = new ArrayList<BlockPos>();
            for (var b : BlockPos.betweenClosed(middle.offset(-2, 0, -2), middle.offset(2, 7, 2))) {
                var pos = b.immutable(); wall.add(pos); level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            }
            require(MinecraftMachinesLeaperPursuitSight.sample(level, rig, player.getUUID(), null) == null,
                    "Known-target tracking saw through a wall");
            wall.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
            player.setPos(p.x + direction.x * 4, 7, p.z + direction.z * 4);
            behaviour.forceHunt(player.getUUID(), new Point(player.getX(), player.getY(), player.getZ()));
        }
        void step() throws Exception {
            tick++;
            if (tick == 80) sightChecks();
            if (blastTick >= 0 && caughtTick < 0) {
                // Replay a strong knockback's network position updates. FakePlayer
                // has no client to integrate the launch packet; actual attack timing is retained.
                double age = Math.min(80, tick - blastTick), t = age / 80;
                player.setPos(flight.x + direction.x * 128 * t, 7 + 32 * Math.sin(Math.PI * t),
                        flight.z + direction.z * 128 * t);
                player.setOnGround(age >= 80);
            }
            if (prepareTick >= 0 && hiddenTick < 0 && tick - prepareTick >= 8) {
                frozen = behaviour.brain.destination(); hiddenTick = tick; hiddenOrigin = new Vector3d(rig.corePivot());
                player.setInvisible(true); player.setShiftKeyDown(true); player.setOnGround(false);
                player.setPos(player.getX() + 200, player.getY(), player.getZ() + 200);
            }
            if (tick >= 80) behaviour.step(helper.getLevel(), rig, nav, "horrific");
            pulse.step(helper.getLevel(), rig, nav, tick >= 80 && behaviour.canAttack(), player.getUUID(), false);
            if (pulse.clock.age() == LeaperShockwaveClock.CHARGE_TICKS && blastTick < 0) {
                require(player.getDeltaMovement().horizontalDistance() > 1, "Fixture did not actually launch its player");
                blastTick = tick; flight = new Vector3d(player.getX(), 7, player.getZ()); pulse.enabled = false;
            }
            nav.step();
            var p = rig.corePivot();
            require(rig.valid() && rig.upright() > .6 && rig.anchorError() < .16, "Launch pursuit destabilized the rig");
            if (blastTick >= 0 && caughtTick < 0) {
                double separation = Math.hypot(p.x - player.getX(), p.z - player.getZ());
                maxSeparation = Math.max(maxSeparation, separation);
                if (separation > 40) {
                    distantFrames++;
                    require(behaviour.mode() == LeaperBehaviour.Mode.HUNTING && behaviour.brain.memoryAge(tick + 1) < 12,
                            "Launched target was lost at the acquisition radius");
                }
                if (player.getY() > 31 && nav.goal != null && Math.abs(nav.goal.y - 7) < .1
                        && Math.hypot(nav.goal.x - player.getX(), nav.goal.z - player.getZ()) < 20) airborneGoals++;
                if (tick - blastTick >= 80 && separation < 12) {
                    caughtTick = tick;
                    require(distantFrames > 8 && airborneGoals > 8, "Fixture did not exercise distant high flight: "
                            + distantFrames + " distant frames, " + airborneGoals + " grounded goals, max separation " + maxSeparation);
                    var f = rig.coreOrientation().transform(new Vector3d(0, 0, -1)); f.y = 0; f.normalize();
                    player.setPos(p.x + f.x * 30, 39, p.z + f.z * 30); player.setOnGround(false);
                    prepareTick = tick;
                }
                require(tick - blastTick < 400, "Leaper failed to chase down the launched player");
            }
            if (hiddenTick >= 0) {
                require(behaviour.brain.destination().equals(frozen), "Search followed the hidden player's live position");
                if (nav.goal != null && Math.abs(nav.goal.y - 7) < .1
                        && Math.hypot(nav.goal.x - frozen.x(), nav.goal.z - frozen.z()) > 4) {
                    String key = Math.round(nav.goal.x) + ":" + Math.round(nav.goal.z);
                    if (behaviour.mode() == LeaperBehaviour.Mode.HUNTING) huntGoals.add(key);
                    if (behaviour.mode() == LeaperBehaviour.Mode.ALERT) alertGoals.add(key);
                }
                if (tick - hiddenTick >= LeaperBehaviour.LOST_SIGHT_TICKS + 200) {
                    require(huntGoals.size() >= 2 && alertGoals.size() >= 2,
                            "Red/orange search did not visit supported goals below the airborne memory: " + huntGoals.size() + "/" + alertGoals.size());
                    require(p.distance(hiddenOrigin) > 12, "Lost-airborne search remained stationary");
                    MinecraftMachinesLeaperLearning.write("predator-behaviour-launch-pursuit", Map.of(
                            "complete", true, "success", true, "caught_after_blast_ticks", caughtTick - blastTick,
                            "maximum_separation", maxSeparation, "distant_tracking_frames", distantFrames,
                            "airborne_ground_goal_frames", airborneGoals, "hunt_search_destinations", huntGoals.size(),
                            "alert_search_destinations", alertGoals.size(), "occlusion_and_acquisition_checked", true, "ticks", tick));
                    close(); helper.succeed(); return;
                }
            }
            require(tick < 1650, "Launch pursuit fixture timed out");
            loop();
        }
        void loop() { helper.runAfterDelay(1, () -> {
            try { step(); } catch (Exception e) { close(); helper.fail("Launch pursuit tick " + tick + ": " + e); }
        }); }
        void close() {
            pulse.cancel(helper.getLevel());
            if (player != null) helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            if (rig != null) rig.close();
        }
    }
}
