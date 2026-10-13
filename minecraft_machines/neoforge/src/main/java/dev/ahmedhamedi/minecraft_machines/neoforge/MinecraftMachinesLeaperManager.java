package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.joml.Vector3d;

import java.util.*;

/** Player-owned Leapers use the same feedback controller and gait as qualification. */
final class MinecraftMachinesLeaperManager {
    private static final Map<UUID, Live> LIVE = new HashMap<>();

    private static class Live {
        MinecraftMachinesLeaper rig;
        final MinecraftMachinesQuadrupedChunkLease lease;
        final ServerLevel level;
        double time;
        int gaitTick, navigationTick, timeout;
        String mode = "stand";
        String pace = "horrific";
        boolean watch, announced;
        boolean hunting;
        MinecraftMachinesLeaperBehaviour behaviour;
        boolean terrainEnabled = true;
        final MinecraftMachinesLeaperShockwave shockwave = new MinecraftMachinesLeaperShockwave();
        MinecraftMachinesLeaperPredatorNavigator terrain;
        MinecraftMachinesLeaperModel model;
        LeaperPointController controller;
        LeaperPointController.Command command;

        Live(MinecraftMachinesLeaper r, MinecraftMachinesQuadrupedChunkLease l, ServerLevel world) {
            rig = r;
            lease = l;
            level = world;
        }

        void close() {
            shockwave.cancel(level);
            try {
                rig.close();
            } finally {
                lease.close();
            }
        }
    }

    static LiteralArgumentBuilder<CommandSourceStack> commands() {
        var root =
                Commands.literal("leaper")
                        .then(Commands.literal("spawn").executes(c -> spawn(c.getSource())))
                        .then(
                                Commands.literal("stick")
                                        .executes(
                                                c ->
                                                        MinecraftMachinesLeaperStick.give(
                                                                c.getSource())))
                        .then(Commands.literal("stop").executes(c -> stop(c.getSource())))
                        .then(Commands.literal("hunt").executes(c -> hunt(c.getSource())))
                        .then(Commands.literal("patrol")
                                .executes(c -> patrol(c.getSource(), 24))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 48))
                                        .executes(c -> patrol(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
                        .then(Commands.literal("remove").executes(c -> remove(c.getSource())))
                        .then(Commands.literal("status").executes(c -> status(c.getSource())))
                        .then(Commands.literal("scale")
                                .executes(c -> scale(c.getSource(), null))
                                .then(Commands.argument("size", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1, 2))
                                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[] {"1", "1.5", "2"}, b))
                                        .executes(c -> scale(c.getSource(), com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "size")))))
                        .then(
                                Commands.literal("goto")
                                        .then(
                                                Commands.argument("point", Vec2Argument.vec2())
                                                        .executes(
                                                                c -> {
                                                                    var v =
                                                                            Vec2Argument.getVec2(
                                                                                    c, "point");
                                                                    return go(
                                                                            c.getSource(),
                                                                            v.x,
                                                                            v.y);
                                                                })));
        for (String mode : List.of("stand", "crouch", "articulate"))
            root.then(Commands.literal(mode).executes(c -> setPose(c.getSource(), mode)));
        var pace = Commands.literal("pace");
        for (String name : List.of("careful", "fast", "horrific", "super_horrific"))
            pace.then(Commands.literal(name).executes(c -> setPace(c.getSource(), name)));
        pace.then(Commands.literal("standard").executes(c -> setPace(c.getSource(), "fast")));
        root.then(pace);
        var version = Commands.literal("version");
        for (String name : List.of("apex"))
            version.then(Commands.literal(name).executes(c -> setVersion(c.getSource(), name)));
        root.then(version);
        root.then(
                Commands.literal("terrain")
                        .then(
                                Commands.literal("auto")
                                        .executes(c -> setTerrain(c.getSource(), true)))
                        .then(
                                Commands.literal("flat")
                                        .executes(c -> setTerrain(c.getSource(), false))));
        root.then(Commands.literal("watch").executes(c -> setWatch(c.getSource(), true)));
        root.then(Commands.literal("unwatch").executes(c -> setWatch(c.getSource(), false)));
        var shockwave = Commands.literal("shockwave");
        for (boolean enabled : new boolean[] {true, false})
            shockwave.then(Commands.literal(enabled ? "on" : "off").executes(c -> {
                var live = owned(c.getSource());
                if (live == null) return 0;
                live.shockwave.enabled = enabled;
                c.getSource().sendSuccess(() -> Component.literal("Leaper shockwave: " + (enabled ? "on" : "off")), false);
                return 1;
            }));
        root.then(shockwave);
        return root;
    }

    private static int setVersion(CommandSourceStack source, String version) {
        if (!version.equals("apex")) {
            source.sendFailure(Component.literal("Apex is the Leaper's only gameplay version."));
            return 0;
        }
        var live = owned(source);
        if (live == null) return 0;
        try {
            if (live.terrain == null) {
                live.terrain =
                        new MinecraftMachinesLeaperPredatorNavigator(
                                live.rig, live.level, MinecraftMachinesLeaperPredatorPolicy.load());
                live.terrain.pace = live.pace;
                live.terrainEnabled = true;
                live.mode = "hold";
                live.controller = null;
                live.command = null;
            }
            live.rig.apexVersion = true;
            live.terrain.apex.enabled = live.rig.apexVersion;
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    "Leaper version: "
                                            + (live.rig.apexVersion ? "Apex" : "Classic")),
                    false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(
                    Component.literal("Cannot select Leaper version: " + e.getMessage()));
            return 0;
        }
    }

    private static int remove(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player != null) {
            var live = LIVE.remove(player.getUUID());
            if (live != null) live.close();
        }
        return 1;
    }

    private static int status(CommandSourceStack source) {
        var live = owned(source);
        if (live == null) return 0;
        if (live.terrainEnabled && live.terrain != null) {
            var n = live.terrain;
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    String.format(
                                            Locale.ROOT,
                                            "Leaper %s: %s; %s speed preference, %s gait; tilt"
                                                    + " %.2f°; anchor %.4f blocks%s.",
                                            "Apex " + live.rig.scale + "x",
                                            live.behaviour == null ? live.mode : live.behaviour.detail(),
                                            paceName(live.pace),
                                            "continuous",
                                            Math.toDegrees(
                                                    Math.acos(
                                                            Math.clamp(live.rig.upright(), -1, 1))),
                                            live.rig.anchorError(),
                                            n.goal == null
                                                    ? ""
                                                    : String.format(
                                                            Locale.ROOT,
                                                            "; target %.2f, %.2f, %.2f; remaining"
                                                                    + " %.2f",
                                                            n.goal.x,
                                                            n.goal.y,
                                                            n.goal.z,
                                                            n.distance))),
                    false);
            return 1;
        }
        var pos = live.rig.position();
        double tilt = Math.toDegrees(Math.acos(Math.clamp(live.rig.upright(), -1, 1)));
        String goal =
                live.controller == null
                        ? ""
                        : String.format(
                                Locale.ROOT,
                                "; target %.2f, %.2f; remaining %.3f blocks",
                                live.controller.goalX(),
                                live.controller.goalZ(),
                                Math.hypot(
                                        pos.x - live.controller.goalX(),
                                        pos.z - live.controller.goalZ()));
        String message =
                String.format(
                        Locale.ROOT,
                        "Leaper: %s (%s pace); 13 physical bodies, 12 limited joints; tilt %.2f°;"
                                + " anchor error %.4f blocks%s.",
                        live.mode,
                        paceName(live.pace),
                        tilt,
                        live.rig.anchorError(),
                        goal);
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int setPose(CommandSourceStack source, String mode) {
        var live = owned(source);
        if (live == null) return 0;
        disableBehaviour(live);
        live.mode = mode;
        live.hunting = false;
        live.time = 0;
        live.controller = null;
        live.command = null;
        if (live.terrain != null) live.terrain.stop();
        live.rig.disablePredator(false);
        live.terrain = null;
        source.sendSuccess(() -> Component.literal("Leaper pose: " + mode), false);
        return 1;
    }

    private static String paceName(String pace) {
        return pace.equals("super_horrific")
                ? "Super Horrific"
                : pace.equals("fast") ? "standard" : pace;
    }

    private static int setTerrain(CommandSourceStack source, boolean enabled) {
        var l = owned(source);
        if (l == null) return 0;
        try {
            if (enabled && l.terrain == null)
                l.terrain =
                        new MinecraftMachinesLeaperPredatorNavigator(
                                l.rig, l.level, MinecraftMachinesLeaperPredatorPolicy.load());
            if (l.terrain != null) l.terrain.stop();
            disableBehaviour(l);
            l.hunting = false;
            if (!enabled) {
                l.rig.disablePredator();
                l.terrain = null;
            }
            l.terrainEnabled = enabled;
            l.mode = "hold";
            l.command = null;
            l.controller = null;
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    enabled
                                            ? "Terrain adaptation enabled. Select a supported"
                                                    + " surface."
                                            : "Legacy flat-ground controller selected."),
                    false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int setPace(CommandSourceStack source, String pace) {
        var live = owned(source);
        if (live == null) return 0;
        try {
            boolean superPace = pace.equals("super_horrific");
            boolean enableTerrain = superPace && !live.terrainEnabled;
            var model = MinecraftMachinesLeaperModel.load(superPace ? "horrific" : pace);
            if ((live.terrainEnabled || superPace) && live.terrain == null)
                live.terrain =
                        new MinecraftMachinesLeaperPredatorNavigator(
                                live.rig, live.level, MinecraftMachinesLeaperPredatorPolicy.load());
            if (enableTerrain) {
                live.terrainEnabled = true;
                live.mode = "hold";
                live.hunting = false;
                live.command = null;
                live.controller = null;
            }
            if (live.terrainEnabled && live.terrain != null) {
                live.model = model;
                live.pace = pace;
                live.terrain.pace = pace;
                live.terrain.control.setPace(pace);
                source.sendSuccess(
                        () ->
                                Component.literal(
                                        "Leaper pace: "
                                                + paceName(pace)
                                                + (enableTerrain
                                                        ? ". Terrain control enabled; select a"
                                                                + " destination."
                                                        : ". Continuing the current route.")),
                        false);
                return 1;
            }
            if (live.pace.equals(pace) && live.model != null) {
                source.sendSuccess(
                        () ->
                                Component.literal(
                                        "Leaper is already using " + paceName(pace) + " pace."),
                        false);
                return 1;
            }
            live.model = model;
            live.pace = pace;
            live.mode = "hold";
            // The flat-mode point controller contains world coordinates only; retain
            // its destination when this optional inspection mode is selected.
            live.command = null;
            // Keep the physical bodies and current actuator targets. Bounded servos
            // settle into the new stance before any newly selected journey begins.
            live.gaitTick = 0;
            live.navigationTick = 0;
            live.announced = false;
            if (live.terrain != null) {
                live.terrain.stop();
                live.terrain.pace = pace;
            }
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    "Leaper pace: "
                                            + paceName(pace)
                                            + ". Settling into stance; select a destination."),
                    false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int setWatch(CommandSourceStack source, boolean watch) {
        var live = owned(source);
        if (live == null) return 0;
        live.watch = watch;
        var player = source.getPlayer();
        if (!watch && player != null && player.getAbilities().mayfly) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        return 1;
    }

    private static Live owned(CommandSourceStack source) {
        var p = source.getPlayer();
        var l = p == null ? null : LIVE.get(p.getUUID());
        if (l == null) source.sendFailure(Component.literal("Use /mm leaper spawn first."));
        return l;
    }

    static int stop(CommandSourceStack source) {
        var l = owned(source);
        if (l == null) return 0;
        disableBehaviour(l);
        l.hunting = false;
        l.mode = l.model == null || l.terrainEnabled && l.terrain == null ? "stand" : "hold";
        l.controller = null;
        l.command = null;
        if (l.terrain != null) l.terrain.stop();
        source.sendSuccess(() -> Component.literal("Leaper stopping."), false);
        return 1;
    }

    private static void disableBehaviour(Live live) {
        live.behaviour = null;
        live.hunting = false;
        live.rig.setBehaviourMode(LeaperBehaviour.Mode.PATROLLING);
        if (live.terrain != null) live.terrain.behaviourAllowsLeaps = true;
    }

    private static LeaperBehaviour.Point home(Live live) {
        var p = live.rig.corePivot();
        double floor = live.terrain.leap.supportFloor();
        if (!Double.isFinite(floor)) floor = p.y - live.terrain.control.standingHeight();
        return new LeaperBehaviour.Point(p.x, floor, p.z);
    }

    private static int patrol(CommandSourceStack source, int radius) {
        var live = owned(source);
        if (live == null) return 0;
        if (source.getLevel() != live.level) {
            source.sendFailure(Component.literal("The Leaper must be in your dimension."));
            return 0;
        }
        try {
            if (live.terrain == null) live.terrain = new MinecraftMachinesLeaperPredatorNavigator(
                    live.rig, live.level, MinecraftMachinesLeaperPredatorPolicy.load());
            live.terrain.stop();
            live.behaviour = new MinecraftMachinesLeaperBehaviour(home(live), radius);
            live.rig.setBehaviourMode(LeaperBehaviour.Mode.PATROLLING);
            live.hunting = false;
            live.terrainEnabled = true;
            live.mode = "navigate";
            live.timeout = Integer.MAX_VALUE;
            live.navigationTick = 0;
            live.watch = false;
            // The flat-mode point controller contains world coordinates only; retain
            // its destination when this optional inspection mode is selected.
            live.command = null;
            source.sendSuccess(() -> Component.literal("Leaper patrol area centered on its current position, radius " + radius + " blocks."), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    static int hunt(CommandSourceStack source) {
        var l = owned(source);
        var player = source.getPlayer();
        if (l == null || player == null) return 0;
        if (player.serverLevel() != l.level) {
            source.sendFailure(Component.literal("The Leaper must be in your dimension."));
            return 0;
        }
        try {
            if (l.terrain == null)
                l.terrain =
                        new MinecraftMachinesLeaperPredatorNavigator(
                                l.rig, l.level, MinecraftMachinesLeaperPredatorPolicy.load());
            l.terrainEnabled = true;
            l.hunting = true;
            l.mode = "navigate";
            l.announced = false;
            l.timeout = Integer.MAX_VALUE;
            l.navigationTick = 0;
            l.watch = false;
            if (l.behaviour == null) l.behaviour = new MinecraftMachinesLeaperBehaviour(home(l), 24);
            l.behaviour.forceHunt(player.getUUID(), new LeaperBehaviour.Point(player.getX(), player.getY(), player.getZ()));
            l.rig.setBehaviourMode(LeaperBehaviour.Mode.HUNTING);
            l.terrain.stop();
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    "Leaper is hunting you and will investigate if it loses you. Use /mm leaper stop or"
                                            + " Shift-right-click the targeting stick to stop."),
                    false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    static int go(CommandSourceStack source, double x, double z) {
        int y =
                source.getLevel()
                        .getHeight(
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                (int) Math.floor(x),
                                (int) Math.floor(z));
        double surface =
                new MinecraftMachinesLeaperTerrainMap(source.getLevel()).height(x, z, y + 1);
        return go(source, x, surface, z);
    }

    static int go(CommandSourceStack source, double x, double y, double z) {
        var l = owned(source);
        if (l == null) return 0;
        double distance = Math.hypot(x - l.rig.position().x, z - l.rig.position().z);
        if (source.getLevel() != l.level || !Double.isFinite(x + y + z) || distance > 512) {
            source.sendFailure(
                    Component.literal(
                            "Choose a point in the Leaper's dimension, within 512 blocks of it."));
            return 0;
        }
        try {
            if (l.terrainEnabled) {
                if (l.terrain == null)
                    l.terrain =
                            new MinecraftMachinesLeaperPredatorNavigator(
                                    l.rig, l.level, MinecraftMachinesLeaperPredatorPolicy.load());
                l.terrain.pace = l.pace;
                if (!l.terrain.go(x, y, z)) {
                    source.sendFailure(Component.literal(l.terrain.reason));
                    return 0;
                }
                disableBehaviour(l);
                l.hunting = false;
                l.mode = "navigate";
                l.announced = false;
                l.navigationTick = 0;
                l.timeout = (int) (20 * (60 + distance / .15));
                String acceptedStatus =
                        l.terrain.pendingGoal != null
                                ? "New destination queued."
                                : l.terrain.reason.isBlank()
                                        ? "Destination accepted."
                                        : l.terrain.reason;
                source.sendSuccess(
                        () ->
                                Component.literal(
                                        String.format(
                                                Locale.ROOT,
                                                "Leaper destination: %.2f, %.2f, %.2f — %s",
                                                x,
                                                y,
                                                z,
                                                acceptedStatus)),
                        false);
                return 1;
            }
            if (l.model == null) l.model = MinecraftMachinesLeaperModel.load();
            if (!l.mode.equals("navigate") && !l.mode.equals("hold")) l.gaitTick = 0;
            if (l.controller == null)
                l.controller = new LeaperPointController(l.model.navigation(), x, z);
            else l.controller.setGoal(x, z);
            disableBehaviour(l);
            l.mode = "navigate";
            l.announced = false;
            l.navigationTick = 0;
            l.timeout = (int) (20 * (50 + distance / .18));
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    String.format(
                                            Locale.ROOT,
                                            "Leaper destination: %.2f, %.2f (%.1f blocks).",
                                            x,
                                            z,
                                            distance)),
                    false);
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
    }

    private static int scale(CommandSourceStack source, Double requested) {
        var live = owned(source);
        if (live == null) return 0;
        if (requested == null || requested == live.rig.scale) {
            source.sendSuccess(() -> Component.literal("Leaper Apex physical size: " + live.rig.scale
                    + "x. Use /mm leaper scale 1, 1.5 or 2; 1 restores normal size."), false);
            return 1;
        }
        MinecraftMachinesLeaper replacement = null;
        try {
            MinecraftMachinesLeaperScaling.validate(requested);
            var center = MinecraftMachinesLeaperScaling.resizeCenter(live.rig, live.terrain, requested);
            replacement = MinecraftMachinesLeaper.spawn(live.level, center, requested);
            replacement.reset(live.rig.heading());
            var next = new MinecraftMachinesLeaperPredatorNavigator(replacement, live.level,
                    live.terrain == null ? MinecraftMachinesLeaperPredatorPolicy.load() : live.terrain.control.policy);
            next.pace = live.pace;
            next.pulseActive = live.shockwave.clock.active();
            replacement.setBehaviourMode(live.rig.behaviourMode());
            // Prepare all fallible resources before changing ownership. Brain, home, mode,
            // attack cooldown and settings remain on the existing Live object.
            var old = live.rig;
            var oldGoal = live.terrain == null || live.terrain.goal == null ? null : new Vector3d(live.terrain.goal);
            if (oldGoal != null) next.go(oldGoal.x, oldGoal.y, oldGoal.z);
            live.rig = replacement;
            live.terrain = next;
            // The flat-mode point controller contains world coordinates only; retain
            // its destination when this optional inspection mode is selected.
            live.command = null;
            replacement = null;
            old.close();
            source.sendSuccess(() -> Component.literal("Leaper Apex resized to " + requested
                    + "x: physical body, joints, feet and clearance updated."), false);
            return 1;
        } catch (RuntimeException ex) {
            if (replacement != null) replacement.close();
            source.sendFailure(Component.literal("Cannot resize Leaper: " + ex.getMessage()));
            return 0;
        }
    }

    private static int spawn(CommandSourceStack source) {
        var p = source.getPlayer();
        if (p == null) return 0;
        if (LIVE.containsKey(p.getUUID())) {
            source.sendFailure(Component.literal("Remove your existing Leaper first."));
            return 0;
        }
        var level = source.getLevel();
        var lease = new MinecraftMachinesQuadrupedChunkLease(level);
        try {
            var center =
                    new Vector3d(p.getX(), p.getY() + MinecraftMachinesLeaper.HEIGHT, p.getZ());
            lease.follow(center);
            var rig = MinecraftMachinesLeaper.spawn(level, center);
            var live = new Live(rig, lease, level);
            try {
                live.model = MinecraftMachinesLeaperModel.load();
                live.terrain =
                        new MinecraftMachinesLeaperPredatorNavigator(
                                rig, level, MinecraftMachinesLeaperPredatorPolicy.load());
                live.mode = "hold";
            } catch (RuntimeException absentModel) {
                rig.close();
                throw absentModel;
            }
            live.behaviour = new MinecraftMachinesLeaperBehaviour(
                    new LeaperBehaviour.Point(p.getX(), p.getY(), p.getZ()), 24);
            live.mode = "navigate";
            live.timeout = Integer.MAX_VALUE;
            LIVE.put(p.getUUID(), live);
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    "Leaper is patrolling its spawn area (blue eyes, radius 24)."
                                        + " /mm leaper patrol [8–48] sets a new patrol area here;"
                                        + " /mm leaper stop pauses behaviour. Use /mm leaper stick for manual control."),
                    false);
            return 1;
        } catch (Exception ex) {
            lease.close();
            source.sendFailure(Component.literal("Cannot spawn Leaper: " + ex.getMessage()));
            MinecraftMachines.LOGGER.error("Leaper spawn", ex);
            return 0;
        }
    }

    static double[] pose(String mode, double seconds) {
        var a = new double[12];
        if (mode.equals("crouch"))
            for (int i = 0; i < 4; i++) {
                a[3 * i + 1] = .25;
                a[3 * i + 2] = -.40;
            }
        if (mode.equals("articulate")) {
            double f = (1 - Math.cos(seconds * Math.PI / 6)) / 2;
            for (int i = 0; i < 4; i++) {
                a[3 * i] = (i % 2 == 0 ? 1 : -1) * .12 * Math.sin(seconds * Math.PI / 6);
                a[3 * i + 1] = .18 * f;
                a[3 * i + 2] = -.28 * f;
            }
        }
        return a;
    }

    static void tick(ServerTickEvent.Pre event) {
        for (var entry : List.copyOf(LIVE.entrySet())) {
            var l = entry.getValue();
            var player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !l.rig.valid()) {
                l.close();
                LIVE.remove(entry.getKey());
                continue;
            }
            try {
                // Also upgrades already-running rigs and prevents stale launch flags or
                // commands from leaving the gameplay controller in the retired version.
                l.rig.apexVersion = true;
                if (l.terrain != null) l.terrain.apex.enabled = true;
                l.time += .05;
                l.lease.follow(l.rig.position());
                if (l.behaviour != null && l.terrain != null
                        && MinecraftMachinesLeaperBehaviourRecovery.hold(l.terrain, l.shockwave)) {
                    if (l.terrain.tick % 20 == 0) player.displayClientMessage(
                            Component.literal("Leaper • " + l.behaviour.status() + " • Recovering footing"), true);
                    continue;
                }
                if (l.behaviour != null && l.terrain != null) {
                    l.behaviour.step(l.level, l.rig, l.terrain, l.pace);
                    l.hunting = l.behaviour.mode() == LeaperBehaviour.Mode.HUNTING;
                }
                boolean attacking = l.behaviour == null
                        ? l.shockwave.step(l.level, l.rig, l.terrain, false, null, l.mode.equals("navigate"))
                        : l.shockwave.step(l.level, l.rig, l.terrain,
                                l.behaviour.canAttack(), l.behaviour.attackTarget(), true);
                if (l.terrainEnabled
                        && l.terrain != null
                        && (l.mode.equals("navigate") || l.mode.equals("hold"))) {
                    var n = l.terrain;
                    if (l.behaviour == null) n.pace = l.pace;
                    if (l.behaviour == null && player.serverLevel() != l.level) {
                        n.stop();
                        l.hunting = false;
                        l.mode = "hold";
                    }
                    if (l.mode.equals("navigate")
                            && (l.rig.upright() < .35
                                    || l.rig.jointStrain() > .16
                                    || ++l.navigationTick > l.timeout)) {
                        if (l.rig.jointStrain() > .16 && l.navigationTick <= l.timeout
                                && l.shockwave.canClearTrap(l.level, l.rig, n)) {
                            // Stop straining against the obstruction, but retain travel intent
                            // so the stalled-motion detector can charge a clearing pulse.
                            n.waitingForClearingPulse = true;
                        } else {
                            disableBehaviour(l);
                            n.stop();
                            l.hunting = false;
                            l.mode = "hold";
                            player.sendSystemMessage(
                                    Component.literal(
                                            "Leaper stopped: terrain exceeds the current controller's"
                                                    + " limits."));
                        }
                    }
                    n.step();
                    if (Boolean.getBoolean("minecraft_machines.leaperTelemetry")
                            && n.tick % 20 == 0) {
                        var p = l.rig.position();
                        MinecraftMachines.LOGGER.info(
                                String.format(
                                        Locale.ROOT,
                                        "LEAPER_TERRAIN_LIVE tick=%d x=%.4f y=%.4f z=%.4f"
                                                + " speed=%.4f up=%.6f anchor=%.6f gait=%s pace=%s"
                                                + " distance=%.4f arrived=%s",
                                        n.tick,
                                        p.x,
                                        p.y,
                                        p.z,
                                        Math.hypot(l.rig.velocity().x, l.rig.velocity().z),
                                        l.rig.upright(),
                                        l.rig.anchorError(),
                                        "continuous",
                                        l.pace,
                                        n.distance,
                                        n.arrived));
                    }
                    if (n.arrived && !l.announced && l.behaviour == null && !l.hunting) {
                        l.announced = true;
                        player.sendSystemMessage(
                                Component.literal(
                                        String.format(
                                                Locale.ROOT,
                                                "Leaper arrived — %.2f blocks from the selected"
                                                        + " surface point.",
                                                n.distance)));
                    }
                    if (n.arrived && l.behaviour == null && !l.hunting) l.timeout = l.navigationTick + 1000;
                    if (n.goal != null && n.tick % 20 == 0) {
                        var g = n.pendingGoal != null ? n.pendingGoal : n.goal;
                        String navigationStatus;
                        if (n.waitingForClearingPulse)
                            navigationStatus = "Clearing trapped stance";
                        else if (n.escape != null && n.tick < n.backUntil && n.route.isEmpty())
                            navigationStatus = "Stepping away from nearby obstacles";
                        else if (n.leap.state == MinecraftMachinesLeaperLeap.State.LAND)
                            navigationStatus =
                                    n.pendingGoal != null
                                            ? "Landing; target queued"
                                            : "Recovering landing";
                        else if (n.leap.state == MinecraftMachinesLeaperLeap.State.FLIGHT)
                            navigationStatus =
                                    n.pendingGoal != null ? "In flight; target queued" : "Leaping";
                        else if (n.planning != null || n.leap.search != null)
                            navigationStatus = "Finding a way";
                        else if (n.leap.state == MinecraftMachinesLeaperLeap.State.ALIGN
                                || n.leap.state == MinecraftMachinesLeaperLeap.State.CROUCH)
                            navigationStatus = "Preparing leap";
                        else if (n.leap.state == MinecraftMachinesLeaperLeap.State.RETREAT)
                            navigationStatus = "Creating launch room";
                        else if (n.routeBlocked)
                            navigationStatus =
                                    "Blocked: "
                                            + (n.reason.isBlank() ? "no clear route" : n.reason);
                        else if (n.pendingGoal != null) navigationStatus = "Destination queued";
                        else if (l.behaviour != null) navigationStatus = l.behaviour.status();
                        else if (l.hunting) navigationStatus = "Hunting";
                        else navigationStatus = n.arrived ? "Arrived" : "Moving";
                        if (l.behaviour == null) l.level.sendParticles(
                                ParticleTypes.HAPPY_VILLAGER,
                                g.x,
                                g.y + .15,
                                g.z,
                                5,
                                .15,
                                .05,
                                .15,
                                0);
                        player.displayClientMessage(
                                Component.literal(
                                        String.format(
                                                Locale.ROOT,
                                                "Leaper • %s • %s • %.1f blocks",
                                                n.pace.equals("super_horrific")
                                                        ? "Super Horrific"
                                                        : n.pace.equals("fast")
                                                                ? "Standard"
                                                                : n.pace.equals("careful")
                                                                        ? "Careful"
                                                                        : "Horrific",
                                                navigationStatus,
                                                n.pendingGoal == null
                                                        ? n.distance
                                                        : Math.hypot(
                                                                g.x - l.rig.position().x,
                                                                g.z - l.rig.position().z))),
                                true);
                    }
                    if (l.behaviour == null && n.goal == null && !n.reason.isEmpty()) {
                        player.sendSystemMessage(Component.literal(n.reason));
                        n.reason = "";
                        l.hunting = false;
                        l.mode = "hold";
                    }
                } else if (attacking) {
                    // Navigation owns movement during attacks. Inspection poses still need
                    // their supported hold controller when no navigation is running.
                    if (l.terrain != null) {
                        l.terrain.control.step(0, 0, 0, .35);
                        l.terrain.apex.brake();
                    } else l.rig.command(pose("stand", 0), .05);
                } else if (l.model != null
                        && (l.mode.equals("navigate") || l.mode.equals("hold"))) {
                    ++l.gaitTick;
                    var pos = l.rig.position();
                    var up = l.rig.up();
                    if (l.mode.equals("navigate")) {
                        // A settled arrival is a continuing hold, not an overdue journey.
                        // Retain a bounded recovery budget if a later disturbance moves it.
                        if (l.command != null && l.command.arrived())
                            l.timeout = l.navigationTick + 20 * 50;
                        if (player.serverLevel() != l.level
                                || up.y < l.model.minimumUp()
                                || l.rig.jointStrain() > .04
                                || ++l.navigationTick > l.timeout) {
                            l.mode = "hold";
                            l.command = null;
                            player.sendSystemMessage(
                                    Component.literal(
                                            "Leaper stopped. Select a clear destination on flat"
                                                    + " ground."));
                        } else if (l.gaitTick > 100
                                && (l.command == null || (l.gaitTick - 100) % 2 == 1)) {
                            l.command = l.controller.update(pos.x, pos.z, l.rig.heading(), .1);
                            if (l.command.arrived() && !l.announced) {
                                l.announced = true;
                                player.sendSystemMessage(
                                        Component.literal(
                                                String.format(
                                                        Locale.ROOT,
                                                        "Leaper arrived — %.2f blocks from the"
                                                                + " point.",
                                                        l.command.distance())));
                            }
                        }
                        if (l.controller != null && l.navigationTick % 20 == 0) {
                            double gx = l.controller.goalX(), gz = l.controller.goalZ();
                            int gy =
                                    l.level.getHeight(
                                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                            (int) Math.floor(gx),
                                            (int) Math.floor(gz));
                            l.level.sendParticles(
                                    ParticleTypes.HAPPY_VILLAGER,
                                    gx,
                                    gy + .15,
                                    gz,
                                    5,
                                    .15,
                                    .05,
                                    .15,
                                    0);
                            player.displayClientMessage(
                                    Component.literal(
                                            String.format(
                                                    Locale.ROOT,
                                                    "Leaper • %s pace • %s • %.1f blocks",
                                                    paceName(l.pace),
                                                    l.announced ? "Arrived" : "Moving",
                                                    Math.hypot(pos.x - gx, pos.z - gz))),
                                    true);
                        }
                    }
                    var cmd = l.mode.equals("navigate") && l.gaitTick > 100 ? l.command : null;
                    l.rig.command(
                            l.model.action(
                                    Math.max(0, l.gaitTick - 100) * .05,
                                    l.rig.heading(),
                                    up.x,
                                    up.z,
                                    cmd == null ? 0 : cmd.forward(),
                                    cmd == null ? 0 : cmd.strafe(),
                                    cmd == null ? 0 : cmd.turn()),
                            .05,
                            l.model.slew(),
                            l.model.power());
                } else l.rig.command(pose(l.mode, l.time), .05);
                if (l.watch && player.serverLevel() == l.level) {
                    var c = l.rig.position();
                    double cameraY =
                            Math.max(
                                    c.y + 6,
                                    l.level.getHeight(
                                                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                    (int) Math.floor(c.x + 10),
                                                    (int) Math.floor(c.z - 13))
                                            + 3);
                    float pitch =
                            (float) Math.toDegrees(Math.atan2(cameraY - c.y, Math.hypot(10, 13)));
                    player.teleportTo(l.level, c.x + 10, cameraY, c.z - 13, 38, pitch);
                }
            } catch (Exception ex) {
                l.close();
                LIVE.remove(entry.getKey());
                MinecraftMachines.LOGGER.error("Leaper control failed", ex);
                player.sendSystemMessage(
                        Component.literal("Leaper stopped after a control error."));
            }
        }
    }

    static void stopping(ServerStoppingEvent event) {
        for (var l : LIVE.values()) l.close();
        LIVE.clear();
    }
}
