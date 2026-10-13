package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperLongRangeTest {
    @GameTest(template = "empty", timeoutTicks = 8200, batch = "leaperLongRange")
    public static void sustainedNinetySixBlockArrival(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("long-range")) {
            helper.succeed();
            return;
        }
        var model =
                MinecraftMachinesLeaperModel.load(MinecraftMachinesLeaperLearning.packagedPace());
        var rig =
                MinecraftMachinesLeaper.spawn(
                        helper.getLevel(),
                        MinecraftMachinesLeaperLearning.arena(
                                helper.getLevel(), 0, Blocks.SMOOTH_STONE, 112));
        var episode =
                new MinecraftMachinesLeaperEpisode(rig, model, 0, -96, .7, true, 7600, true, 104);
        Runnable[] next = {null};
        next[0] =
                () -> {
                    try {
                        episode.step();
                        if (!episode.done()) {
                            helper.runAfterDelay(1, () -> next[0].run());
                            return;
                        }
                        MinecraftMachinesLeaperLearning.write("long-range", episode.result());
                        rig.close();
                        helper.assertTrue(episode.success, "Long-range Leaper arrival failed");
                        helper.succeed();
                    } catch (Exception e) {
                        rig.close();
                        helper.fail("Long-range Leaper: " + e);
                    }
                };
        helper.runAfterDelay(1, () -> next[0].run());
    }
}
