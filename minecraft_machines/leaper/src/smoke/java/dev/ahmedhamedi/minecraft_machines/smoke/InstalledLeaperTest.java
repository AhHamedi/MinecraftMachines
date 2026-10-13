package dev.ahmedhamedi.minecraft_machines.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Runs against the actual distributed JAR, with Sable as its sole mod dependency. */
@Mod("minecraft_machines_smoke")
@GameTestHolder("minecraft_machines_smoke")
@PrefixGameTestTemplate(false)
public final class InstalledLeaperTest {
    public InstalledLeaperTest(IEventBus bus) {}

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void installedJarWalksWithoutTraining(GameTestHelper helper) throws Exception {
        new Run(helper).begin();
    }

    private static Object field(Object instance, String name) throws Exception {
        Field f = instance.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(instance);
    }

    private static Object call(Object instance, String name) throws Exception {
        Method m = instance.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(instance);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static final class Run {
        final GameTestHelper helper;
        final Map<String, Object> report = new LinkedHashMap<>();
        Map<UUID, Object> liveMap;
        FakePlayer owner;
        Object live, rig, nav;
        Vector3d start;
        int tick;
        double maximumTravel, minimumUp = 1, maximumAnchor;

        Run(GameTestHelper helper) { this.helper = helper; }

        @SuppressWarnings("unchecked")
        void begin() throws Exception {
            require(ModList.get().isLoaded("sable"), "Missing Sable");
            for (String id : new String[] {"create", "simulated", "aeronautics"})
                require(!ModList.get().isLoaded(id), "Unexpected dependency: " + id);
            for (String key : new String[] {"leaperMode", "leaperAgile", "leaperLeap", "leaperHighStepCandidate"})
                require(System.getProperty("minecraft_machines." + key) == null, "Unexpected launcher flag: " + key);
            for (String name : new String[] {"MinecraftMachinesTrainingBridge", "MinecraftMachinesLeaperTrainer",
                    "MinecraftMachinesDuopodManager", "MinecraftMachinesQuadrupedManager"}) {
                try {
                    Class.forName("dev.ahmedhamedi.minecraft_machines.neoforge." + name);
                    throw new IllegalStateException("Training/prototype class shipped: " + name);
                } catch (ClassNotFoundException expected) {}
            }
            var manager = Class.forName("dev.ahmedhamedi.minecraft_machines.neoforge.MinecraftMachinesLeaperManager");
            var code = manager.getProtectionDomain().getCodeSource().getLocation().toString();
            require(code.contains(".jar"), "Must test the distributed JAR, not development classes: " + code);
            report.put("loaded_from", code);
            var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("mm");
            require(root != null && root.getChildren().size() == 1 && root.getChild("leaper") != null,
                    "Leaper-only command tree not installed");
            require(!Files.exists(Path.of("leaper_learning")), "Test must not inherit external controller inputs");
            var level = helper.getLevel();
            for (int cx = -3; cx <= 3; cx++)
                for (int cz = -3; cz <= 3; cz++) level.setChunkForced(cx, cz, true);
            for (int x = -40; x <= 40; x++)
                for (int z = -40; z <= 40; z++)
                    level.setBlockAndUpdate(new BlockPos(x, 6, z), Blocks.SMOOTH_STONE.defaultBlockState());
            owner = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "leaper-install-test"));
            owner.moveTo(.5, 7, .5, 0, 0);
            Field f = manager.getDeclaredField("LIVE"); f.setAccessible(true);
            liveMap = (Map<UUID, Object>) f.get(null);
            require(command("spawn") == 1, "Installed Leaper failed to spawn");
            live = liveMap.remove(owner.getUUID());
            require(live != null, "Spawn did not create a rig");
            rig = field(live, "rig"); nav = field(live, "terrain");
            require((int) call(rig, "bodyCount") == 13, "Incomplete physical rig");
            require((boolean) field(field(nav, "leap"), "enabled"), "Leaps disabled without launcher flags");
            var control = field(nav, "control");
            require((boolean) field(control, "agile"), "Movement disabled without launcher flags");
            require((double) field(control, "extraLift") == 1.4, "Bundled gait settings not applied");
            require(field(field(control, "policy"), "packagedHighStepSettings") != null,
                    "Bundled movement profile not loaded");
            command("stop");
            helper.runAfterDelay(1, this::step);
        }

        int command(String suffix) throws Exception {
            if (live != null) liveMap.put(owner.getUUID(), live);
            try {
                return helper.getLevel().getServer().getCommands().getDispatcher().execute(
                        "mm leaper " + suffix, owner.createCommandSourceStack().withPermission(4));
            } finally {
                if (live != null) liveMap.remove(owner.getUUID());
            }
        }

        void step() {
            try {
                tick++;
                call(nav, "step");
                var p = (Vector3d) call(rig, "position");
                if (tick == 80) {
                    start = new Vector3d(p);
                    require(command("goto 0.5 -23.5") == 1, "Installed destination command failed");
                }
                if (start != null) {
                    maximumTravel = Math.max(maximumTravel, Math.hypot(p.x - start.x, p.z - start.z));
                    minimumUp = Math.min(minimumUp, (double) call(rig, "upright"));
                    maximumAnchor = Math.max(maximumAnchor, (double) call(rig, "anchorError"));
                    require((boolean) call(rig, "valid") && minimumUp > .5 && maximumAnchor < .16,
                            "Installed movement lost physical stability");
                }
                if (tick >= 200 && (boolean) field(nav, "arrived") && maximumTravel > 16) {
                    report.put("success", true);
                    report.put("complete", true);
                    report.put("ticks", tick);
                    report.put("travel", maximumTravel);
                    report.put("minimum_upright", minimumUp);
                    report.put("maximum_anchor_error", maximumAnchor);
                    report.put("external_controller_files", false);
                    report.put("launcher_flags", false);
                    report.put("training_commands", false);
                    report.put("create_dependency", false);
                    report.put("leaps_enabled", true);
                    save();
                    command("remove"); live = null;
                    helper.succeed();
                } else {
                    require(tick < 600, "Installed Leaper did not reach its destination: " + maximumTravel);
                    helper.runAfterDelay(1, this::step);
                }
            } catch (Exception e) {
                try {
                    report.put("success", false); report.put("error", e.toString());
                    report.put("ticks", tick); report.put("travel", maximumTravel); save();
                    if (live != null) command("remove");
                } catch (Exception ignored) {}
                helper.fail(e.toString());
            }
        }

        void save() throws Exception {
            Files.writeString(Path.of("leaper-installed-check.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        }
    }
}
