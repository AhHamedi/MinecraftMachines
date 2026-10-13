package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperShockwaveClock;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperRecoveryTests {
    @GameTest(template = "empty", timeoutTicks = 1500, batch = "leaperBehaviourRecovery", attempts = 3, requiredSuccesses = 3)
    public static void retreatThenClear(GameTestHelper helper) throws Exception {
        if (!Set.of("predator-recovery", "predator-behaviour").contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed();
            return;
        }
        var run = new Run(helper);
        try { run.begin(); } catch (Exception e) { run.close(); throw e; }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    static final class Run {
        final GameTestHelper helper;
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        MinecraftMachinesLeaperShockwave pulse = new MinecraftMachinesLeaperShockwave();
        final List<BlockPos> roof = new ArrayList<>();
        final List<Map<String, Object>> cases = new ArrayList<>();
        final List<Map<String, Object>> burstHistory = new ArrayList<>();
        Vector3d origin, goal;
        BlockPos supportingGround;
        int tick, phase, phaseTick, charges, blasts;
        boolean sawRetreat;
        boolean resumedPhysicalHold;
        double retreatTravel, maxRecoveryMillis, ceilingStartHeight;

        Run(GameTestHelper helper) { this.helper = helper; }

        void begin() throws Exception {
            var level = helper.getLevel();
            var spawn = MinecraftMachinesLeaperLearning.arena(level, 3, Blocks.SMOOTH_STONE, 45);
            // Provide ordinary volumetric ground under the clearing-blast fixture.
            for (var pos : BlockPos.betweenClosed(BlockPos.containing(spawn.x, 2, spawn.z).offset(-45, 0, -45),
                    BlockPos.containing(spawn.x, 5, spawn.z).offset(45, 0, 45)))
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            for (var pos : BlockPos.betweenClosed(BlockPos.containing(spawn.x, 7, spawn.z).offset(-45, 0, -45),
                    BlockPos.containing(spawn.x, 7, spawn.z).offset(45, 22, 45)))
                if (pos.getY() >= 7) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            rig = MinecraftMachinesLeaper.spawn(level, spawn);
            rig.reset(0);
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, level, MinecraftMachinesLeaperPredatorExperiments.candidate());
            nav.pace = "careful";
            nav.behaviourAllowsLeaps = false;
            loop();
        }

        void terrainCases() {
            var level = helper.getLevel();
            var p = rig.corePivot();
            double floor = nav.leap.supportFloor();
            var probe = BlockPos.containing(p.x + 5.8, floor - 3, p.z);
            level.setBlockAndUpdate(probe, Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(probe.above(), Blocks.STONE.defaultBlockState());
            require(MinecraftMachinesLeaperEscape.terrain(nav).bodyClear(p.x, floor, p.z),
                    "Buried water invalidated solid dry ground");
            cases.add(Map.of("name", "buried-water-under-solid-floor", "success", true));
            var exposed = BlockPos.containing(p.x + 5.8, floor - 1, p.z);
            level.setBlockAndUpdate(exposed, Blocks.WATER.defaultBlockState());
            require(!MinecraftMachinesLeaperEscape.terrain(nav).bodyClear(p.x, floor, p.z),
                    "Exposed water was accepted as dry footing");
            level.setBlockAndUpdate(exposed, Blocks.SMOOTH_STONE.defaultBlockState());
            level.setBlockAndUpdate(probe, Blocks.STONE.defaultBlockState());
            cases.add(Map.of("name", "exposed-water-remains-rejected", "success", true));
            var bank = BlockPos.containing(p.x + 4, floor, p.z);
            level.setBlockAndUpdate(bank, Blocks.STONE.defaultBlockState());
            nav.goal = new Vector3d(p.x + 20, floor, p.z);
            require(!pulse.canClearTrap(level, rig, nav), "Ordinary low ground triggered destructive recovery");
            level.setBlockAndUpdate(bank, Blocks.AIR.defaultBlockState());
            nav.goal = null;
            cases.add(Map.of("name", "ordinary-bank-does-not-trigger-burst", "success", true));
            origin = new Vector3d(p.x, floor, p.z);
            var trunk = BlockPos.containing(p.x + 2, floor, p.z - 5.5);
            for (int y = 0; y <= 6; y++) level.setBlockAndUpdate(trunk.above(y), Blocks.OAK_LOG.defaultBlockState());
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                level.setBlockAndUpdate(trunk.offset(x, 5, z),
                        Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
            var map = MinecraftMachinesLeaperEscape.terrain(nav);
            require(!map.bodyClear(p.x, floor, p.z), "Tree must overlap the conservative leg allowance");
            require(!MinecraftMachinesLeaperEscape.canRetreat(map, origin,
                    new Vector3d(p.x + 2, floor, p.z - 4)), "Retreat moved toward the tree");
            cases.add(Map.of("name", "retreat-into-tree-rejected", "success", true));
            goal = new Vector3d(p.x + 20, floor, p.z);
            require(nav.go(goal.x, goal.y, goal.z), "Recovery travel request rejected");
        }

        void step() throws Exception {
            tick++;
            if (tick == 61) {
                var p = rig.corePivot();
                require(nav.go(p.x + 20, nav.leap.supportFloor(), p.z), "Hold fixture goal rejected");
                goal = new Vector3d(nav.goal);
                MinecraftMachinesLeaperBehaviourRecovery.begin(nav, pulse);
            }
            if (tick >= 61 && tick < 81 && !resumedPhysicalHold) {
                boolean held = MinecraftMachinesLeaperBehaviourRecovery.hold(nav, pulse);
                require(nav.goal != null && nav.goal.distance(goal) < .01, "Physical hold lost its destination");
                if (held) { loop(); return; }
                require(tick >= 73, "Physical hold skipped the stable interval");
                resumedPhysicalHold = true;
                nav.stop();
            }
            if (tick == 81) {
                require(resumedPhysicalHold, "Stable physical hold never resumed navigation");
                cases.add(Map.of("name", "temporary-physical-hold-resumes-preserved-goal", "success", true));
                terrainCases();
            }
            int age = pulse.clock.age();
            if (phase == 2 && age == 12) pulse.enabled = false;
            pulse.step(helper.getLevel(), rig, nav, false, null, true);
            if (age < 0 && pulse.clock.age() == 0) charges++;
            if (pulse.clock.age() == LeaperShockwaveClock.CHARGE_TICKS) blasts++;
            long began = System.nanoTime();
            nav.step();
            if (phase == 2 && pulse.clock.age() >= 54 && pulse.clock.age() <= 80)
                burstHistory.add(Map.of("tick", tick, "age", pulse.clock.age(), "anchor", rig.anchorError(),
                        "compression", nav.control.shockwaveCompression, "velocity", rig.assemblyVelocity().toString(),
                        "position", rig.corePivot().toString(), "waiting", nav.waitingForClearingPulse,
                        "support", nav.leap.supports(), "reason", nav.reason));
            if (nav.escape != null) maxRecoveryMillis = Math.max(maxRecoveryMillis, (System.nanoTime() - began) / 1e6);
            if (tick > 81 && phase == 0) {
                sawRetreat |= nav.escape != null;
                var p = rig.corePivot();
                retreatTravel = Math.hypot(p.x - origin.x, p.z - origin.z);
                if (sawRetreat && retreatTravel > 2 && nav.escape == null && !nav.routeBlocked && nav.planning == null) {
                    require(charges == 0 && blasts == 0, "A safe retreat unnecessarily charged a blast");
                    require(nav.goal.distance(goal) < .01, "Retreat lost the original destination");
                    cases.add(Map.of("name", "physical-retreat-resumes-original-route", "success", true,
                            "travel", retreatTravel));
                    nav.stop();
                    phase = 1;
                    phaseTick = tick;
                }
                require(tick < 400, "Tree allowance stranded the physically supported Leaper");
            }
            if (phase == 1 && tick - phaseTick >= 60) {
                var p = rig.corePivot();
                double floor = nav.leap.supportFloor();
                supportingGround = BlockPos.containing(p.x, floor - 1, p.z);
                ceilingStartHeight = p.y;
                var center = BlockPos.containing(p.x, floor + 6, p.z);
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    var block = center.offset(x, 0, z);
                    roof.add(block);
                    // A fragile, hard collision roof isolates recovery coordination
                    // from TNT's randomized resistance against a thick stone shelter.
                    helper.getLevel().setBlockAndUpdate(block, Blocks.GLASS.defaultBlockState());
                }
                require(nav.go(p.x - 20, floor, p.z), "Blocked travel request rejected");
                phase = 2;
                phaseTick = tick;
            }
            if (phase == 2 && blasts == 0)
                require(rig.corePivot().y <= ceilingStartHeight + .6,
                        "An overhead obstruction was mistaken for supporting ground");
            if (phase == 2 && blasts > 0 && !pulse.clock.active() && !nav.waitingForClearingPulse
                    && nav.planning == null && !nav.routeBlocked) {
                long removed = roof.stream().filter(b -> helper.getLevel().getBlockState(b).isAir()).count();
                require(nav.leap.recoveryAttempts > 0, "No escape leap search after clearing burst");
                require(charges == 1 && blasts == 1 && removed > 0,
                        "Non-combat recovery failed to complete one terrain-clearing burst");
                require(!pulse.enabled, "Clearing fixture must disable future attacks during its charge");
                require(helper.getLevel().getBlockState(supportingGround).isAir(),
                        "Clearing burst failed to excavate the ground underneath it");
                cases.add(Map.of("name", "non-combat-stall-completes-clearing-burst", "success", true,
                        "charges", charges, "blasts", blasts, "removed_obstructions", removed, "ground_excavated", true,
                        "committed_after_disable", true, "escape_searches", nav.leap.recoveryAttempts, "escape_plans", nav.leap.recoveryPlans));
                MinecraftMachinesLeaperLearning.write("predator-behaviour-recovery", Map.of(
                        "complete", true, "success", true, "cases", cases,
                        "maximum_recovery_step_ms", maxRecoveryMillis, "ticks", tick));
                close(); helper.succeed(); return;
            }
            require(tick < 1400, "Recovery burst timed out: " + nav.reason + "; state=" + nav.leap.state + "; attempts=" + nav.leap.recoveryAttempts + "; plans=" + nav.leap.recoveryPlans + "; launches=" + nav.leap.launches);
            require(rig.valid() && rig.upright() > .6 && rig.anchorError() < .16,
                    "Recovery violated physical stability: upright=" + rig.upright()
                            + "; anchor=" + rig.anchorError() + "; burst age=" + pulse.clock.age());
            loop();
        }

        void loop() {
            helper.runAfterDelay(1, () -> {
                try { step(); } catch (Exception e) {
                    try { MinecraftMachinesLeaperLearning.write("predator-behaviour-recovery-failure",
                            Map.of("failure", e.toString(), "history", burstHistory)); } catch (Exception ignored) {}
                    close(); helper.fail("Recovery tick " + tick + ": " + e);
                }
            });
        }
        void close() { if (rig != null) rig.close(); }
    }
}
