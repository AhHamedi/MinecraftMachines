package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.util.*;

@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperScaleTests {
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "leaperScale")
    public static void physicalScale(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-scale")) { helper.succeed(); return; }
        new Run(helper).begin();
    }
    static final class Run {
        final GameTestHelper helper;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final double[] sizes = {1, 1.5, 2, 1};
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        Vector3d spawn, start;
        int index, tick;
        boolean scaledAttack;
        Object live;
        net.neoforged.neoforge.common.util.FakePlayer owner;
        MinecraftMachinesQuadrupedChunkLease lease;
        Object brain;
        double minUp = 1, maxError;
        Run(GameTestHelper helper) { this.helper = helper; }
        void begin() throws Exception {
            spawn = MinecraftMachinesLeaperLearning.arena(helper.getLevel(), 9, Blocks.BEDROCK, 180);
            create(); loop();
        }
        void create() throws Exception {
            double scale = sizes[index];
            var center = new Vector3d(spawn.x, 7 + MinecraftMachinesLeaper.HEIGHT * scale, spawn.z);
            if (index == 0) {
                rig = MinecraftMachinesLeaper.spawn(helper.getLevel(), center, scale);
                rig.reset(0);
                nav = new MinecraftMachinesLeaperPredatorNavigator(rig, helper.getLevel(), MinecraftMachinesLeaperPredatorExperiments.candidate());
                owner = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
                        new com.mojang.authlib.GameProfile(UUID.randomUUID(), "scale-owner"));
                lease = new MinecraftMachinesQuadrupedChunkLease(helper.getLevel());
                var type = Arrays.stream(MinecraftMachinesLeaperManager.class.getDeclaredClasses())
                        .filter(c -> c.getSimpleName().equals("Live")).findFirst().orElseThrow();
                var ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
                live = ctor.newInstance(rig, lease, helper.getLevel());
                set("terrain", nav);
                brain = new MinecraftMachinesLeaperBehaviour(
                        new dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point(spawn.x, 7, spawn.z), 24);
                set("behaviour", brain); set("mode", "hold");
            } else {
                var old = rig;
                int cooldown = ((MinecraftMachinesLeaperShockwave) get("shockwave")).clock.cooldown();
                if (command(scale) != 1) throw new IllegalStateException("Resize command rejected size " + scale);
                rig = (MinecraftMachinesLeaper) get("rig");
                nav = (MinecraftMachinesLeaperPredatorNavigator) get("terrain");
                if (((MinecraftMachinesLeaperShockwave) get("shockwave")).clock.cooldown() != cooldown || rig == old || old.valid() || get("behaviour") != brain || !get("mode").equals("hold"))
                    throw new IllegalStateException("Resize did not preserve brain/pause or dispose old body");
            }
            tick = 0; minUp = 1; maxError = 0;
            if (rig.bodyCount() != 13 || rig.anchorError() > .05)
                throw new IllegalStateException("Scaled assembly/joints misaligned: " + rig.bodyCount() + "/" + rig.anchorError());
        }
        Object get(String name) throws Exception { var f = live.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(live); }
        void set(String name, Object value) throws Exception { var f = live.getClass().getDeclaredField(name); f.setAccessible(true); f.set(live, value); }
        @SuppressWarnings("unchecked")
        int command(double scale) throws Exception {
            var f = MinecraftMachinesLeaperManager.class.getDeclaredField("LIVE"); f.setAccessible(true);
            var all = (Map<UUID, Object>) f.get(null);
            var method = MinecraftMachinesLeaperManager.class.getDeclaredMethod("scale", net.minecraft.commands.CommandSourceStack.class, Double.class);
            method.setAccessible(true);
            all.put(owner.getUUID(), live);
            try { return (int) method.invoke(null, owner.createCommandSourceStack(), scale); }
            finally { all.remove(owner.getUUID()); }
        }
        void rejectionChecks() throws Exception {
            if (command(1) != 1 || command(1.25) != 0 || get("rig") != rig)
                throw new IllegalStateException("Absolute size/invalid-size guard failed");
            var pulse = (MinecraftMachinesLeaperShockwave) get("shockwave");
            for (int i = 0; i < 41; i++) pulse.clock.tick(true, true);
            int burstAge = pulse.clock.age();
            rig.disturb(3, 0, 1);
            if (command(1.5) != 1 || !pulse.clock.active() || pulse.clock.age() != burstAge || get("rig") == rig)
                throw new IllegalStateException("Resize failed during an active burst");
            rig = (MinecraftMachinesLeaper) get("rig");
            nav = (MinecraftMachinesLeaperPredatorNavigator) get("terrain");
            if (!nav.pulseActive) throw new IllegalStateException("Resize lost pulse movement lock");
            pulse.clock.cancel();
            var pivot = rig.corePivot();
            var block = net.minecraft.core.BlockPos.containing(pivot.x, pivot.y, pivot.z).offset(0, 3, 0);
            var before = helper.getLevel().getBlockState(block);
            helper.getLevel().setBlockAndUpdate(block, Blocks.STONE.defaultBlockState());
            try {
                // A fresh navigator has no established footing. Resizing must still work.
                if (command(2) != 1 || get("rig") == rig
                        || !helper.getLevel().getBlockState(block).is(Blocks.STONE))
                    throw new IllegalStateException("Obstructed/unsupported resize failed or modified terrain");
                if (command(1) != 1) throw new IllegalStateException("Immediate resize back failed");
                rig = (MinecraftMachinesLeaper) get("rig");
                nav = (MinecraftMachinesLeaperPredatorNavigator) get("terrain");
            } finally { helper.getLevel().setBlockAndUpdate(block, before); }
        }

        void step() throws Exception {
            tick++;
            if (tick == 500 && index == 0) rejectionChecks();
            if (index == 2 && tick == 100) {
                var pulse = (MinecraftMachinesLeaperShockwave) get("shockwave");
                while (pulse.clock.cooldown() > 0) pulse.clock.tick(false, true);
                var p = rig.corePivot(); owner.setPos(p.x, 7, p.z - 4);
                helper.getLevel().addNewPlayer(owner);
                pulse.step(helper.getLevel(), rig, nav, true, owner.getUUID(), false);
                if (!pulse.clock.active()) throw new IllegalStateException("Giant cannot trigger attack against a ground-level player");
                scaledAttack = true;
                owner.setPos(p.x + 200, 7, p.z);
            }
            if (index == 2 && tick > 100 && tick <= 200)
                ((MinecraftMachinesLeaperShockwave) get("shockwave")).step(helper.getLevel(), rig, nav, false, owner.getUUID(), false);
            if (index == 2 && tick == 201) {
                owner.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                if (!rig.valid()) throw new IllegalStateException("Scaled blast damaged its own body");
            }
            if (tick == 140) { start = rig.corePivot(); nav.go(spawn.x, 7, spawn.z + (index % 2 == 0 ? -30 : 30) * rig.scale); }
            nav.step();
            if (tick >= 100) {
                minUp = Math.min(minUp, rig.upright());
                maxError = Math.max(maxError, rig.anchorError());
            }
            if (tick % 40 == 0) rows.add(Map.of("size", sizes[index], "tick", tick,
                    "up", rig.upright(), "anchor", rig.anchorError(), "y", rig.corePivot().y, "mass", rig.assemblyMass(), "reason", nav.reason, "supports", nav.leap.supports(),
                    "position", new double[] {rig.corePivot().x, rig.corePivot().z}, "toe", new double[] {rig.toe(0).y, nav.control.ground, nav.control.policy.value[4]}));
            if (tick == 480) {
                double distance = rig.corePivot().distance(start);
                if (minUp < .75 || maxError > .16 * rig.scale || distance < 8 * rig.scale)
                    throw new IllegalStateException("Scale " + rig.scale + " failed: up=" + minUp + " anchor=" + maxError + " movement=" + distance);
                nav.stop();
            }
            if (tick == 600) {
                if (++index == sizes.length) {
                    MinecraftMachinesLeaperLearning.write("predator-scale", Map.of("complete", true, "success", true, "scaled_ground_attack", scaledAttack, "command_rejections", true, "state_preserved", true, "rows", rows));
                    rig.close(); rig = null; lease.close(); helper.succeed(); return;
                }
                create();
            }
            loop();
        }
        void loop() {
            helper.runAfterDelay(1, () -> {
                try { step(); } catch (Exception e) {
                    dev.ahmedhamedi.minecraft_machines.MinecraftMachines.LOGGER.error("Physical scale fixture", e);
                    if (rig != null) rig.close();
                    try { MinecraftMachinesLeaperLearning.write("predator-scale", Map.of("complete", true, "success", false, "error", e.toString(), "rows", rows)); }
                    catch (Exception ignored) {}
                    helper.fail(e.toString());
                }
            });
        }
    }
}
