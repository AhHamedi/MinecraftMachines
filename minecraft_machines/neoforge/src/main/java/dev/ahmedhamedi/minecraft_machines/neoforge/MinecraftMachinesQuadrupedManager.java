package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonParser;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedDirectionalGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedGaitPolicy;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedPointController;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.joml.Vector3d;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Player-facing replay. Goal commands use only the qualified physical feedback controller. */
final class MinecraftMachinesQuadrupedManager {
    private static final List<Replay> REPLAYS = new ArrayList<>();

    private MinecraftMachinesQuadrupedManager() {}

    static LiteralArgumentBuilder<CommandSourceStack> commands() {
        return Commands.literal("quadruped")
                .then(
                        Commands.literal("stick")
                                .executes(c -> MinecraftMachinesQuadrupedStick.give(c.getSource())))
                .then(Commands.literal("spawn").executes(c -> spawn(c.getSource())))
                .then(Commands.literal("stop").executes(c -> setWalking(c.getSource(), false)))
                .then(Commands.literal("walk").executes(c -> setWalking(c.getSource(), true)))
                .then(
                        Commands.literal("goto")
                                .then(
                                        Commands.argument("destination", Vec2Argument.vec2(false))
                                                .executes(
                                                        c -> {
                                                            var point =
                                                                    Vec2Argument.getVec2(
                                                                            c, "destination");
                                                            return go(
                                                                    c.getSource(),
                                                                    point.x,
                                                                    point.y);
                                                        })))
                .then(Commands.literal("demo").executes(c -> demo(c.getSource())))
                .then(Commands.literal("remove").executes(c -> remove(c.getSource())))
                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                .then(Commands.literal("watch").executes(c -> watch(c.getSource(), true)))
                .then(Commands.literal("unwatch").executes(c -> watch(c.getSource(), false)));
    }

    private static int spawn(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Use this command as a player."));
            return 0;
        }
        if (find(player.getUUID()) != null) {
            source.sendFailure(Component.literal("Remove your existing quadruped first."));
            return 0;
        }
        var lease = new MinecraftMachinesQuadrupedChunkLease(source.getLevel());
        try {
            double[] genome = loadValidatedGenome();
            var level = source.getLevel();
            int x = player.blockPosition().getX(), z = player.blockPosition().getZ() - 12;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 3;
            BlockPos center = new BlockPos(x, y, z);
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    var ground = center.offset(dx, -4, dz);
                    if (!level.getBlockState(ground).isSolidRender(level, ground))
                        throw new IllegalArgumentException(
                                "Choose a clear, flat area at least 9 blocks wide.");
                }
            lease.follow(new Vector3d(x, y, z));
            var rig = MinecraftMachinesQuadruped.spawn(level, center);
            REPLAYS.add(new Replay(player.getUUID(), level, rig, genome, lease));
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    "Quadruped spawned 12 blocks north. Use /mm quadruped goto <x>"
                                            + " <z>, walk, or demo."),
                    false);
            return 1;
        } catch (Exception e) {
            lease.close();
            source.sendFailure(Component.literal("Cannot spawn quadruped: " + e.getMessage()));
            return 0;
        }
    }

    static double[] loadValidatedGenome() throws Exception {
        try (var stream =
                MinecraftMachinesQuadrupedManager.class.getResourceAsStream(
                        "/data/minecraft_machines/quadruped/trained.json")) {
            if (stream == null)
                throw new IllegalStateException("No validated quadruped model is installed.");
            var model =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!model.get("complete").getAsBoolean()
                    || !model.get("morphology")
                            .getAsString()
                            .equals(MinecraftMachinesQuadruped.CONTRACT)
                    || !model.get("policy").getAsString().equals(QuadrupedGaitPolicy.VERSION))
                throw new IllegalStateException("Model contract mismatch");
            int trials = 0;
            for (var element : model.getAsJsonArray("evaluation")) {
                var trial = element.getAsJsonObject();
                if (!trial.get("controller").getAsString().equals("trained")) continue;
                trials++;
                if (trial.get("fell").getAsBoolean()
                        || trial.get("forward_blocks").getAsDouble() < 12)
                    throw new IllegalStateException("Model did not pass locomotion acceptance");
            }
            if (trials != 6) throw new IllegalStateException("Missing evaluation trials");
            var values = model.getAsJsonArray("genome");
            if (values.size() != QuadrupedGaitPolicy.PARAMETERS)
                throw new IllegalStateException("Invalid policy size");
            double[] genome = new double[values.size()];
            for (int i = 0; i < genome.length; i++) {
                genome[i] = values.get(i).getAsDouble();
                if (!Double.isFinite(genome[i]))
                    throw new IllegalStateException("Invalid policy parameter");
            }
            return genome;
        }
    }

    private static Replay find(UUID owner) {
        return REPLAYS.stream().filter(r -> r.owner.equals(owner)).findFirst().orElse(null);
    }

    private static Replay owned(CommandSourceStack source) {
        var player = source.getPlayer();
        return player == null ? null : find(player.getUUID());
    }

    static int go(CommandSourceStack source, double x, double z) {
        var replay = owned(source);
        if (replay == null) {
            source.sendFailure(Component.literal("Spawn a quadruped first."));
            return 0;
        }
        if (source.getLevel() != replay.level) {
            source.sendFailure(Component.literal("Return to the quadruped's dimension first."));
            return 0;
        }
        if (!Double.isFinite(x + z)
                || Math.hypot(x - replay.rig.position().x, z - replay.rig.position().z) > 512) {
            source.sendFailure(Component.literal("Choose a destination within 512 blocks."));
            return 0;
        }
        try {
            replay.route.clear();
            startGoal(replay, x, z);
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    String.format(
                                            Locale.ROOT,
                                            "Walking to X %.2f, Z %.2f. Keep the route flat and"
                                                    + " clear.",
                                            x,
                                            z)),
                    false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Cannot navigate: " + e.getMessage()));
            return 0;
        }
    }

    private static void startGoal(Replay replay, double x, double z) throws Exception {
        if (replay.model == null)
            replay.model = MinecraftMachinesQuadrupedNavigationModel.packaged();
        if (replay.navigation == null)
            replay.navigation = new QuadrupedPointController(replay.model.navigation(), x, z);
        else replay.navigation.setGoal(x, z);
        replay.walking = false;
        replay.announcedArrival = false;
        replay.arrivalTick = 0;
        double distance = Math.hypot(x - replay.rig.position().x, z - replay.rig.position().z);
        replay.deadline = replay.tick + (int) (20 * (40 + distance / .3));
    }

    private static int demo(CommandSourceStack source) {
        var replay = owned(source);
        if (replay == null) {
            source.sendFailure(Component.literal("Spawn a quadruped first."));
            return 0;
        }
        if (source.getLevel() != replay.level) {
            source.sendFailure(Component.literal("Return to the quadruped's dimension first."));
            return 0;
        }
        try {
            var p = replay.rig.position();
            replay.route.clear();
            startGoal(replay, p.x + 20, p.z);
            replay.route.add(new double[] {p.x + 20, p.z + 20});
            replay.route.add(new double[] {p.x, p.z + 20});
            replay.route.add(new double[] {p.x, p.z});
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    "Visiting four points around a 20-block square, then holding at"
                                            + " the starting point."),
                    false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Cannot start demonstration: " + e.getMessage()));
            return 0;
        }
    }

    static int setWalking(CommandSourceStack source, boolean walking) {
        var replay = owned(source);
        if (replay == null) {
            source.sendFailure(Component.literal("Spawn a quadruped first."));
            return 0;
        }
        replay.navigation = null;
        replay.route.clear();
        replay.walking = walking;
        replay.walkHeading = replay.rig.heading();
        replay.walkStart = replay.rig.position();
        source.sendSuccess(
                () ->
                        Component.literal(
                                walking
                                        ? "Quadruped walking in its current direction."
                                        : "Quadruped stopping; destination cleared."),
                false);
        return 1;
    }

    private static int watch(CommandSourceStack source, boolean watching) {
        var replay = owned(source);
        if (replay == null) {
            source.sendFailure(Component.literal("Spawn a quadruped first."));
            return 0;
        }
        replay.watching = watching;
        source.sendSuccess(
                () ->
                        Component.literal(
                                watching
                                        ? "Following quadruped. Use /mm quadruped unwatch to"
                                                + " release the camera."
                                        : "Camera released."),
                false);
        return 1;
    }

    private static int remove(CommandSourceStack source) {
        var replay = owned(source);
        if (replay == null) return 0;
        replay.close();
        REPLAYS.remove(replay);
        source.sendSuccess(() -> Component.literal("Quadruped removed."), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        var replay = owned(source);
        if (replay == null) {
            source.sendFailure(Component.literal("No active quadruped."));
            return 0;
        }
        var p = replay.rig.position();
        String state =
                replay.navigation != null
                        ? (replay.announcedArrival
                                ? "holding destination"
                                : "travelling to destination")
                        : replay.walking ? "walking" : "standing";
        String goal =
                replay.navigation == null
                        ? ""
                        : String.format(
                                Locale.ROOT,
                                "; target %.2f, %.2f; %.3f blocks remaining",
                                replay.navigation.goalX(),
                                replay.navigation.goalZ(),
                                Math.hypot(
                                        replay.navigation.goalX() - p.x,
                                        replay.navigation.goalZ() - p.z));
        source.sendSuccess(
                () ->
                        Component.literal(
                                String.format(
                                        Locale.ROOT,
                                        "Quadruped: %s; X %.3f, Z %.3f; tilt %.2f°%s",
                                        state,
                                        p.x,
                                        p.z,
                                        Math.toDegrees(
                                                Math.acos(Math.clamp(replay.rig.upright(), -1, 1))),
                                        goal)),
                false);
        return 1;
    }

    static void tick(ServerTickEvent.Pre event) {
        for (var replay : List.copyOf(REPLAYS)) {
            var player = event.getServer().getPlayerList().getPlayer(replay.owner);
            if (player == null || !replay.rig.valid()) {
                replay.close();
                REPLAYS.remove(replay);
                continue;
            }
            try {
                var pos = replay.rig.position();
                replay.lease.follow(pos);
                if (replay.watching)
                    player.teleportTo(replay.level, pos.x + 11, pos.y + 6, pos.z + 10, 132f, 22f);
                replay.tick++;
                if (replay.tick < 20) continue;
                var up = replay.rig.up();
                if (up.y < .9 && (replay.walking || replay.navigation != null)) {
                    replay.walking = false;
                    replay.navigation = null;
                    replay.route.clear();
                    player.sendSystemMessage(
                            Component.literal(
                                    "Quadruped stopped because it lost its stable posture."));
                }
                if (replay.tick > 80 && (replay.walking || replay.navigation != null))
                    replay.phaseSeconds += .05;
                if (replay.tick > 80 && replay.tick % 2 != 0) continue;
                double forward = 0, turn = 0;
                if (replay.tick > 80 && replay.navigation != null) {
                    var command = replay.navigation.update(pos.x, pos.z, replay.rig.heading(), .1);
                    forward = command.forward();
                    turn = command.turn();
                    if (command.arrived() && !replay.announcedArrival) {
                        replay.announcedArrival = true;
                        replay.arrivalTick = replay.tick;
                        player.sendSystemMessage(
                                Component.literal(
                                        String.format(
                                                Locale.ROOT,
                                                "Quadruped arrived at X %.2f, Z %.2f; error %.3f"
                                                        + " blocks.",
                                                replay.navigation.goalX(),
                                                replay.navigation.goalZ(),
                                                command.distance())));
                    }
                    if (replay.announcedArrival
                            && !replay.route.isEmpty()
                            && replay.tick - replay.arrivalTick >= 20) {
                        double[] next = replay.route.removeFirst();
                        startGoal(replay, next[0], next[1]);
                    } else if (!replay.announcedArrival && replay.tick > replay.deadline) {
                        replay.navigation = null;
                        replay.route.clear();
                        forward = 0;
                        turn = 0;
                        player.sendSystemMessage(
                                Component.literal(
                                        "Quadruped stopped: it could not reach the destination in"
                                                + " time."));
                    }
                    if (replay.navigation != null && replay.tick % 20 == 0) {
                        player.displayClientMessage(
                                Component.literal(
                                        String.format(
                                                Locale.ROOT,
                                                replay.announcedArrival
                                                        ? "At destination · %.2f blocks error"
                                                        : "Destination · %.2f blocks remaining",
                                                command.distance())),
                                true);
                        if (command.distance() < 64) {
                            double y =
                                    replay.level.getHeight(
                                                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                    (int) Math.floor(replay.navigation.goalX()),
                                                    (int) Math.floor(replay.navigation.goalZ()))
                                            + .15;
                            replay.level.sendParticles(
                                    ParticleTypes.HAPPY_VILLAGER,
                                    replay.navigation.goalX(),
                                    y,
                                    replay.navigation.goalZ(),
                                    6,
                                    .3,
                                    .05,
                                    .3,
                                    0);
                        }
                    }
                } else if (replay.tick > 80 && replay.walking) {
                    double cross =
                            (pos.x - replay.walkStart.x) * Math.cos(replay.walkHeading)
                                    + (pos.z - replay.walkStart.z) * Math.sin(replay.walkHeading);
                    forward = 1;
                    turn =
                            Math.clamp(
                                    -3
                                                    * QuadrupedPointController.wrap(
                                                            replay.rig.heading()
                                                                    - replay.walkHeading)
                                            - .15 * cross,
                                    -.5,
                                    .5);
                }
                double[] gait = replay.navigation == null ? replay.genome : replay.model.gait();
                double[] targets =
                        QuadrupedDirectionalGait.action(
                                gait,
                                replay.phaseSeconds,
                                replay.rig.heading(),
                                up.x,
                                up.z,
                                forward,
                                turn);
                if (replay.tick < 60)
                    for (int i = 0; i < 8; i++) targets[i] *= (replay.tick - 20) / 40.0;
                replay.rig.command(targets);
            } catch (Exception e) {
                replay.close();
                REPLAYS.remove(replay);
                player.sendSystemMessage(
                        Component.literal(
                                "Quadruped removed after a control error: " + e.getMessage()));
                dev.ahmedhamedi.minecraft_machines.MinecraftMachines.LOGGER.error(
                        "Quadruped controller failed", e);
            }
        }
    }

    static void stopping(ServerStoppingEvent event) {
        for (var replay : REPLAYS) replay.close();
        REPLAYS.clear();
    }

    private static final class Replay implements AutoCloseable {
        final UUID owner;
        final ServerLevel level;
        final MinecraftMachinesQuadruped rig;
        final double[] genome;
        final MinecraftMachinesQuadrupedChunkLease lease;
        final ArrayDeque<double[]> route = new ArrayDeque<>();
        MinecraftMachinesQuadrupedNavigationModel model;
        QuadrupedPointController navigation;
        Vector3d walkStart;
        double walkHeading, phaseSeconds;
        int tick, deadline, arrivalTick;
        boolean walking, watching, announcedArrival;

        Replay(
                UUID owner,
                ServerLevel level,
                MinecraftMachinesQuadruped rig,
                double[] genome,
                MinecraftMachinesQuadrupedChunkLease lease) {
            this.owner = owner;
            this.level = level;
            this.rig = rig;
            this.genome = genome;
            this.lease = lease;
            walkStart = rig.position();
        }

        public void close() {
            try {
                rig.close();
            } finally {
                lease.close();
            }
        }
    }
}
