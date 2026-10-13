package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.util.Map;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperShorePursuitTests {
    @GameTest(template="empty", timeoutTicks=900, batch="leaperShorePursuit")
    public static void enlargedShorePursuit(GameTestHelper helper) {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-behaviour")) { helper.succeed(); return; }
        new Run(helper).begin();
    }
    static final class Run {
        final GameTestHelper helper;
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        Vector3d player, start;
        int tick, accepted, rejected;
        Run(GameTestHelper helper) { this.helper=helper; }
        void begin() {
            var base=MinecraftMachinesLeaperLearning.arena(helper.getLevel(), 21, Blocks.STONE, 80);
            player=new Vector3d(base.x,7,base.z);
            // A dry player-sized island surrounded by water. The enlarged chassis
            // needs a shore approach beyond the old nine-block fallback radius.
            for(var pos:BlockPos.betweenClosed(BlockPos.containing(base.x-36,6,base.z-36),
                    BlockPos.containing(base.x+20,6,base.z+36)))
                helper.getLevel().setBlockAndUpdate(pos,Blocks.WATER.defaultBlockState());
            for(var pos:BlockPos.betweenClosed(BlockPos.containing(base.x-2,6,base.z-2),
                    BlockPos.containing(base.x+2,6,base.z+2)))
                helper.getLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
            rig=MinecraftMachinesLeaper.spawn(helper.getLevel(),new Vector3d(base.x+46,10.375,base.z),1.5);
            nav=new MinecraftMachinesLeaperPredatorNavigator(rig,helper.getLevel(),MinecraftMachinesLeaperPredatorPolicy.load());
            if(Double.isFinite(nav.planner(7).stanceFloor(player.x,7,player.z)))
                throw new IllegalStateException("Fixture island unexpectedly fits the enlarged Leaper");
            loop();
        }
        void loop() {
            helper.runAfterDelay(1,()->{try {
                tick++;
                if(tick==120)start=rig.corePivot();
                if(tick>=120 && tick%4==0) {
                    if(nav.pursue(player.x,player.y,player.z))accepted++; else rejected++;
                    if(nav.goal!=null && Math.hypot(nav.goal.x-player.x,nav.goal.z-player.z)<3)
                        throw new IllegalStateException("Pursuit accepted a player-only dry island");
                }
                nav.step();
                if(tick>=240 && start.distance(rig.corePivot())>6) {
                    MinecraftMachinesLeaperLearning.write("predator-shore-pursuit",Map.of("complete",true,
                        "success",true,"scale",1.5,"travel",start.distance(rig.corePivot()),
                        "accepted",accepted,"rejected",rejected,"ticks",tick));
                    rig.close();helper.succeed();return;
                }
                if(tick>=800)throw new IllegalStateException("Shore pursuit froze: "+nav.reason+" accepted="+accepted);
                loop();
            } catch(Exception e) { if(rig!=null)rig.close();helper.fail(e.toString()); }});
        }
    }
}
