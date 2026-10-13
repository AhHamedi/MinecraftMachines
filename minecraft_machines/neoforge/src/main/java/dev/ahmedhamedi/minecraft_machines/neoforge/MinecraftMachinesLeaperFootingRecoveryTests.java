package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.util.Map;
import java.util.Set;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperFootingRecoveryTests {
    @GameTest(template = "empty", timeoutTicks = 800, batch = "leaperBehaviourFooting")
    public static void partialFootingRecovery(GameTestHelper helper) throws Exception {
        if (!Set.of("predator-behaviour", "predator-recovery").contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed(); return;
        }
        new Run(helper).begin();
    }

    private static final class Run {
        final GameTestHelper helper;
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        Vector3d origin, goal;
        int tick, settled;
        boolean partial, unsupported;
        Run(GameTestHelper helper) { this.helper = helper; }
        void begin() throws Exception {
            origin = MinecraftMachinesLeaperLearning.arena(helper.getLevel(), 6, Blocks.SMOOTH_STONE, 40);
            rig = MinecraftMachinesLeaper.spawn(helper.getLevel(), origin);
            rig.reset(0);
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, helper.getLevel(), MinecraftMachinesLeaperPredatorExperiments.candidate());
            nav.pace = "careful";
            nav.behaviourAllowsLeaps = false;
            goal = new Vector3d(origin.x, 7, origin.z - 12);
            loop();
        }
        void step() throws Exception {
            tick++;
            if (tick == 81) {
                // Remove real rear-foot terrain while keeping two planted front feet.
                int x = (int)Math.floor(origin.x), z = (int)Math.floor(origin.z);
                for (var pos : BlockPos.betweenClosed(new BlockPos(x - 6, -16, z), new BlockPos(x + 6, 6, z + 6)))
                    helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                require(nav.go(goal.x, goal.y, goal.z), "Supported recovery destination rejected");
            }
            int supports = nav.leap.supports();
            partial |= tick > 81 && nav.leap.launches == 0 && supports > 0 && supports < 3;
            unsupported |= nav.planning != null && nav.planning.failure.equals("No supported escape stance");
            nav.step();
            require(rig.valid() && rig.upright() > .6 && rig.anchorError() < .16,
                    "Footing recovery destabilized the rig");
            double distance = Math.hypot(rig.corePivot().x - goal.x, rig.corePivot().z - goal.z);
            settled = nav.arrived && supports > 0 && distance < 2.5 ? settled + 1 : 0;
            if (settled >= 40) {
                require(partial && unsupported && nav.leap.launches == 1,
                        "Recovery must jump once from a physically planted, unwalkable stance");
                require(nav.goal.distance(goal) < .01 && !nav.behaviourAllowsLeaps, "Recovery changed patrol's travel intention");
                MinecraftMachinesLeaperLearning.write("predator-behaviour-footing", Map.of(
                        "success", true, "complete", true, "ticks", tick, "launches", nav.leap.launches,
                        "partial_footing", partial, "walking_stance_unavailable", unsupported, "final_distance", distance));
                rig.close(); helper.succeed(); return;
            }
            require(tick < 700, "Partial footing recovery did not reach dry ground: " + nav.reason);
            loop();
        }
        void loop() { helper.runAfterDelay(1, () -> {
            try { step(); } catch (Exception e) { rig.close(); helper.fail("Footing recovery tick " + tick + ": " + e); }
        }); }
        void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    }
}
