package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.mojang.authlib.GameProfile;
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
import java.util.*;

/** Exercises the native attack wrapper, where lost visibility used to cancel the clock. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperCommittedBlastTests {
    @GameTest(template = "empty", timeoutTicks = 350, batch = "leaperCommittedBlast", attempts = 3, requiredSuccesses = 3)
    public static void committedBlast(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-behaviour")) {
            helper.succeed(); return;
        }
        var run = new Run(helper);
        try { run.begin(); } catch (Exception e) { run.close(); throw e; }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static final class Run {
        final GameTestHelper helper;
        final MinecraftMachinesLeaperShockwave pulse = new MinecraftMachinesLeaperShockwave();
        final List<BlockPos> outer = new ArrayList<>(), beyond = new ArrayList<>(), underneath = new ArrayList<>();
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        ServerPlayer player;
        MinecraftMachinesLeaperBehaviour behaviour;
        final List<BlockPos> cover = new ArrayList<>();
        int tick, started = -1, blasts, ended = -1;

        Run(GameTestHelper helper) { this.helper = helper; }

        void begin() throws Exception {
            var level = helper.getLevel();
            // Unbreakable support isolates blast extent from falling into its crater.
            var spawn = MinecraftMachinesLeaperLearning.arena(level, 7, Blocks.BEDROCK, 35);
            rig = MinecraftMachinesLeaper.spawn(level, spawn);
            rig.reset(0);
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, level, MinecraftMachinesLeaperPredatorExperiments.candidate());
            behaviour = new MinecraftMachinesLeaperBehaviour(
                    new dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point(spawn.x, 7, spawn.z), 24);
            require(!behaviour.canAttack(), "Patrol must not attack unknown players through terrain");
            nav.pace = "careful";
            nav.behaviourAllowsLeaps = false;
            player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "leaper-blast"));
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(spawn.x + 30, 7, spawn.z);
            level.addNewPlayer(player);
            loop();
        }

        void step() throws Exception {
            tick++;
            var p = rig.corePivot();
            if (tick == 81) {
                // Established prey hides eight blocks away in a two-block-deep hole.
                // This must trigger despite cover, and exceeds the old five-block range.
                player.setPos(p.x + 8, 5, p.z);
                var hole = BlockPos.containing(player.getX(), 5, player.getZ());
                for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                    helper.getLevel().setBlockAndUpdate(hole.offset(dx,0,dz), Blocks.AIR.defaultBlockState());
                    helper.getLevel().setBlockAndUpdate(hole.offset(dx,1,dz), Blocks.AIR.defaultBlockState());
                    var roof=hole.offset(dx,2,dz);cover.add(roof);
                    helper.getLevel().setBlockAndUpdate(roof,Blocks.GLASS.defaultBlockState());
                }
                behaviour.forceHunt(player.getUUID(), new dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point(
                        player.getX(),player.getY(),player.getZ()));
                require(behaviour.canAttack() && player.getUUID().equals(behaviour.attackTarget()),
                        "Established hidden prey lost attack eligibility");
                int y = (int) Math.floor(p.y);
                // Breakable ground directly beneath the chassis, with bedrock under
                // the distant feet so this test isolates excavation from crater falls.
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    var below = BlockPos.containing(p.x + dx, 6, p.z + dz);
                    underneath.add(below);
                    helper.getLevel().setBlockAndUpdate(below, Blocks.GLASS.defaultBlockState());
                }
                for (int i = 0; i < 12; i++) {
                    double angle = i * Math.PI / 6;
                    var block = BlockPos.containing(p.x + Math.cos(angle) * 8, y, p.z + Math.sin(angle) * 8);
                    outer.add(block);
                    helper.getLevel().setBlockAndUpdate(block, Blocks.GLASS.defaultBlockState());
                    var outside = BlockPos.containing(p.x + Math.cos(angle) * 12, y, p.z + Math.sin(angle) * 12);
                    beyond.add(outside);
                    helper.getLevel().setBlockAndUpdate(outside, Blocks.GLASS.defaultBlockState());
                }
            }
            if (started >= 0 && tick == started + 1) {
                player.setPos(p.x + 30, p.y, p.z); // Leaves both sight permission and attack range.
                player.setInvisible(true);
                nav.stop(); // A changed movement intention must not cancel the charge.
            }
            if (started >= 0 && tick == started + 12) pulse.enabled = false;
            int before = pulse.clock.age();
            boolean active = pulse.step(helper.getLevel(), rig, nav, tick == 81 && behaviour.canAttack(), behaviour.attackTarget(), false);
            if (before < 0 && pulse.clock.age() == 0) {
                require(started < 0, "Attack restarted unexpectedly");
                started = tick;
                // Exercise the clearing-specific path that formerly shielded all
                // blocks below the support plane, independently of target loss.
                var clearing = MinecraftMachinesLeaperShockwave.class.getDeclaredField("freeingPulse");
                clearing.setAccessible(true);
                clearing.setBoolean(pulse, true);
            }
            if (pulse.clock.age() == LeaperShockwaveClock.CHARGE_TICKS) {
                blasts++;
                require(tick - started == 60, "Committed blast did not fire at three seconds");
            }
            if (started >= 0 && tick - started < LeaperShockwaveClock.END_TICKS)
                require(active && nav.pulseActive, "Native wrapper cancelled a committed charge/recovery");
            if (started >= 0 && !active && ended < 0) {
                ended = tick;
                require(pulse.clock.cooldown() == 80, "Attack cooldown must be four seconds");
            }
            nav.step();
            require(rig.valid() && rig.upright() > .6 && rig.anchorError() < .16,
                    "Larger blast destabilized the protected physical rig");
            if (ended >= 0 && tick - ended >= 84) {
                long destroyed = outer.stream().filter(b -> helper.getLevel().getBlockState(b).isAir()).count();
                long excavated = underneath.stream().filter(b -> helper.getLevel().getBlockState(b).isAir()).count();
                require(cover.stream().anyMatch(b -> helper.getLevel().getBlockState(b).isAir()), "Burst did not excavate hidden prey cover");
                require(excavated > 0, "Clearing pulse protected blocks underneath the Leaper");
                require(blasts == 1, "A committed attack must explode exactly once");
                require(destroyed > 0, "Larger blast did not reach terrain beyond the old five-block radius");
                require(beyond.stream().allMatch(b -> helper.getLevel().getBlockState(b).is(Blocks.GLASS)),
                        "Terrain damage escaped the ten-block limit");
                require(!pulse.clock.active(), "Disabling future attacks still allowed a new charge");
                MinecraftMachinesLeaperLearning.write("predator-behaviour-shockwave", Map.of(
                        "complete", true, "success", true, "blasts", blasts, "charge_ticks", 60,
                        "cooldown_ticks", 80, "block_radius", 10, "outer_blocks_destroyed", destroyed,
                        "commitment_survived_target_loss_stop_and_disable", true,
                        "outside_radius_preserved", true, "underneath_blocks_destroyed", excavated));
                close(); helper.succeed(); return;
            }
            require(tick < 320, "Committed blast fixture timed out");
            loop();
        }

        void loop() { helper.runAfterDelay(1, () -> {
            try { step(); } catch (Exception e) { close(); helper.fail("Committed blast tick " + tick + ": " + e); }
        }); }

        void close() {
            pulse.cancel(helper.getLevel());
            if (player != null) helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            if (rig != null) rig.close();
        }
    }
}
