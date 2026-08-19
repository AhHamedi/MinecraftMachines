package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlock;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodTrainingScenarios;
import dev.ahmedhamedi.minecraft_machines.content.worm.training.WormCemConfig;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ahmedhamedi.minecraft_machines.test.PhysicsSpawnTest;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftMachinesCommands {
    private MinecraftMachinesCommands() {
    }

    public static void register(final RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(MinecraftMachines.MOD_ID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn_test_block")
                        .executes(context -> spawnTestBlock(context.getSource())))
                .then(Commands.literal("spawn_servo_test")
                        .executes(context -> spawnServoTest(context.getSource())))
                .then(Commands.literal("spawn_servo_swing_test")
                        .executes(context -> spawnServoSwingTest(context.getSource())))
                .then(Commands.literal("spawn_servo_diagnostic_test")
                        .executes(context -> spawnServoDiagnosticTest(context.getSource())))
                .then(Commands.literal("spawn_worm")
                        .executes(context -> spawnWorm(context.getSource())))
                .then(duopodCommands())
                .then(trainCommands())
                .then(wormCemCommands())
                .then(Commands.literal("servo_telemetry_start")
                        .executes(context -> startServoTelemetry(context.getSource())))
                .then(Commands.literal("servo_telemetry_stop")
                        .executes(context -> stopServoTelemetry(context.getSource())))
                .then(Commands.literal("servo_telemetry_once")
                        .executes(context -> dumpServoTelemetry(context.getSource())))
                .then(Commands.literal("stop_servo_demos")
                        .executes(context -> stopServoDemos(context.getSource()))));

        event.getDispatcher().register(Commands.literal("mm")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn_test_block")
                        .executes(context -> spawnTestBlock(context.getSource())))
                .then(Commands.literal("spawn_servo_test")
                        .executes(context -> spawnServoTest(context.getSource())))
                .then(Commands.literal("spawn_servo_swing_test")
                        .executes(context -> spawnServoSwingTest(context.getSource())))
                .then(Commands.literal("spawn_servo_diagnostic_test")
                        .executes(context -> spawnServoDiagnosticTest(context.getSource())))
                .then(Commands.literal("spawn_worm")
                        .executes(context -> spawnWorm(context.getSource())))
                .then(duopodCommands())
                .then(trainCommands())
                .then(wormCemCommands())
                .then(Commands.literal("servo_telemetry_start")
                        .executes(context -> startServoTelemetry(context.getSource())))
                .then(Commands.literal("servo_telemetry_stop")
                        .executes(context -> stopServoTelemetry(context.getSource())))
                .then(Commands.literal("servo_telemetry_once")
                        .executes(context -> dumpServoTelemetry(context.getSource())))
                .then(Commands.literal("stop_servo_demos")
                        .executes(context -> stopServoDemos(context.getSource()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> wormCemCommands() {
        return Commands.literal("worm_cem")
                .then(Commands.literal("start")
                        .executes(context -> MinecraftMachinesWormCemTrainer.start(context.getSource(), WormCemConfig.DEFAULT))
                        .then(Commands.argument("population", IntegerArgumentType.integer(1, 256))
                                .executes(context -> MinecraftMachinesWormCemTrainer.start(context.getSource(), WormCemConfig.DEFAULT
                                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))))
                                .then(Commands.argument("generations", IntegerArgumentType.integer(1, 500))
                                        .executes(context -> MinecraftMachinesWormCemTrainer.start(context.getSource(), WormCemConfig.DEFAULT
                                                .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                                .withMaxGenerations(IntegerArgumentType.getInteger(context, "generations"))))
                                        .then(Commands.argument("episodeTicks", IntegerArgumentType.integer(20, 2000))
                                                .executes(context -> MinecraftMachinesWormCemTrainer.start(context.getSource(), WormCemConfig.DEFAULT
                                                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                                        .withMaxGenerations(IntegerArgumentType.getInteger(context, "generations"))
                                                        .withEpisodeLengthTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))))
                                                .then(Commands.argument("spacing", IntegerArgumentType.integer(0, 32))
                                                        .executes(context -> MinecraftMachinesWormCemTrainer.start(context.getSource(), WormCemConfig.DEFAULT
                                                                .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                                                .withMaxGenerations(IntegerArgumentType.getInteger(context, "generations"))
                                                                .withEpisodeLengthTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                                                                .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing")))))))))
                .then(Commands.literal("stop")
                        .executes(context -> MinecraftMachinesWormCemTrainer.stop(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> MinecraftMachinesWormCemTrainer.status(context.getSource())))
                .then(Commands.literal("replay_best")
                        .executes(context -> MinecraftMachinesWormCemTrainer.replayBest(context.getSource())))
                .then(Commands.literal("servo")
                        .then(Commands.literal("status")
                                .executes(context -> MinecraftMachinesWormCemTrainer.servoStatus(context.getSource())))
                        .then(Commands.literal("reset")
                                .executes(context -> MinecraftMachinesWormCemTrainer.resetServoControl(context.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("maxSpeedDegS", DoubleArgumentType.doubleArg(1.0, 3600.0))
                                        .then(Commands.argument("stiffness", DoubleArgumentType.doubleArg(0.0, 100_000.0))
                                                .then(Commands.argument("damping", DoubleArgumentType.doubleArg(0.0, 100_000.0))
                                                        .then(Commands.argument("maxTorque", DoubleArgumentType.doubleArg(0.0, 100_000_000.0))
                                                                .executes(context -> MinecraftMachinesWormCemTrainer.setServoControl(
                                                                        context.getSource(),
                                                                        DoubleArgumentType.getDouble(context, "maxSpeedDegS"),
                                                                        DoubleArgumentType.getDouble(context, "stiffness"),
                                                                        DoubleArgumentType.getDouble(context, "damping"),
                                                                        DoubleArgumentType.getDouble(context, "maxTorque")))))))))
                .then(Commands.literal("clear")
                        .executes(context -> MinecraftMachinesWormCemTrainer.clear(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> trainCommands() {
        return Commands.literal("train")
                .then(Commands.literal("status")
                        .executes(context -> trainStatus(context.getSource())))
                .then(Commands.literal("stop")
                        .executes(context -> trainStop(context.getSource())))
                .then(Commands.literal("clear")
                        .executes(context -> trainClear(context.getSource())))
                .then(Commands.literal("target")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> MinecraftMachinesDuopodCemTrainer.setReplayWorldTarget(
                                                        context.getSource(),
                                                        DoubleArgumentType.getDouble(context, "x"),
                                                        DoubleArgumentType.getDouble(context, "y"),
                                                        DoubleArgumentType.getDouble(context, "z")))))))
                .then(lookTargetCommand("look_target"))
                .then(rewardVisualizationCommand())
                .then(trainBridgeCommands())
                .then(trainCemCommands())
                .then(Commands.literal("duopod")
                        .then(duopodCemCommands()));
    }

    private static int trainStatus(final CommandSourceStack source) {
        MinecraftMachinesTrainingBridge.status(source);
        MinecraftMachinesDuopodCemTrainer.status(source);
        return 1;
    }

    private static int trainStop(final CommandSourceStack source) {
        int stopped = 0;
        if (MinecraftMachinesTrainingBridge.stopActive("generic_command_stop")) {
            stopped++;
        }
        stopped += MinecraftMachinesDuopodCemTrainer.stopActive(source.getServer(), "generic_command_stop");
        if (stopped == 0) {
            source.sendFailure(Component.literal("No active training bridge, CEM run, evaluation, or target-change validation."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stopped active training tasks."), true);
        return stopped;
    }

    private static int trainClear(final CommandSourceStack source) {
        int cleared = 0;
        if (MinecraftMachinesTrainingBridge.stopActive("generic_command_clear")) {
            cleared++;
        }
        cleared += MinecraftMachinesDuopodCemTrainer.clearActive(source.getServer());
        if (cleared == 0) {
            source.sendSuccess(() -> Component.literal("No active training state to clear."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Cleared active training state."), true);
        return cleared;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> trainBridgeCommands() {
        return Commands.literal("bridge")
                .then(Commands.literal("start")
                        .then(Commands.literal("duopod")
                                .then(Commands.argument("slotCount", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> MinecraftMachinesTrainingBridge.startDuopod(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "slotCount"),
                                                0))
                                        .then(Commands.argument("port", IntegerArgumentType.integer(0, 65_535))
                                                .executes(context -> MinecraftMachinesTrainingBridge.startDuopod(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "slotCount"),
                                                        IntegerArgumentType.getInteger(context, "port"))))))
                        .then(Commands.literal("duopod_at")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("forward", StringArgumentType.word())
                                                                .then(Commands.argument("slotCount", IntegerArgumentType.integer(1, 64))
                                                                        .executes(context -> MinecraftMachinesTrainingBridge.startDuopodAt(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                                IntegerArgumentType.getInteger(context, "z"),
                                                                                StringArgumentType.getString(context, "forward"),
                                                                                IntegerArgumentType.getInteger(context, "slotCount"),
                                                                                0))
                                                                        .then(Commands.argument("port", IntegerArgumentType.integer(0, 65_535))
                                                                                .executes(context -> MinecraftMachinesTrainingBridge.startDuopodAt(
                                                                                        context.getSource(),
                                                                                        IntegerArgumentType.getInteger(context, "x"),
                                                                                        IntegerArgumentType.getInteger(context, "y"),
                                                                                        IntegerArgumentType.getInteger(context, "z"),
                                                                                        StringArgumentType.getString(context, "forward"),
                                                                                        IntegerArgumentType.getInteger(context, "slotCount"),
                                                                                        IntegerArgumentType.getInteger(context, "port")))))))))))
                .then(Commands.literal("status")
                        .executes(context -> MinecraftMachinesTrainingBridge.status(context.getSource())))
                .then(Commands.literal("stop")
                        .executes(context -> MinecraftMachinesTrainingBridge.stop(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> trainCemCommands() {
        return Commands.literal("cem")
                .then(Commands.literal("start")
                        .then(duopodCemStartArguments(Commands.literal("duopod"))))
                .then(Commands.literal("stop")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.stop(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.status(context.getSource())))
                .then(Commands.literal("replay_best")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.replayBest(context.getSource())))
                .then(lookTargetCommand("look_target"))
                .then(rewardVisualizationCommand())
                .then(Commands.literal("save")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.saveCheckpoint(context.getSource()))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> MinecraftMachinesDuopodCemTrainer.saveCheckpoint(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("load")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.loadCheckpoint(context.getSource()))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> MinecraftMachinesDuopodCemTrainer.loadCheckpoint(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duopodCemCommands() {
        return Commands.literal("cem")
                .then(duopodCemStartCommand())
                .then(duopodCemStartAtCommand())
                .then(Commands.literal("start_fresh")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.startFresh(
                                context.getSource(),
                                MinecraftMachinesDuopodCemTrainer.Config.DEFAULT)))
                .then(duopodCemStartFreshAtCommand())
                .then(Commands.literal("stop")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.stop(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.status(context.getSource())))
                .then(Commands.literal("replay_best")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.replayBest(context.getSource())))
                .then(Commands.literal("evaluate_best")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.evaluateBest(context.getSource()))
                        .then(Commands.argument("maxControlSteps", IntegerArgumentType.integer(1, 2000))
                                .executes(context -> MinecraftMachinesDuopodCemTrainer.evaluateBest(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "maxControlSteps")))))
                .then(duopodCemEvaluateBestAtCommand())
                .then(Commands.literal("benchmark_walk_forward")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.benchmarkWalkForward(context.getSource()))
                        .then(Commands.argument("maxControlSteps", IntegerArgumentType.integer(1, 2000))
                                .executes(context -> MinecraftMachinesDuopodCemTrainer.benchmarkWalkForward(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "maxControlSteps")))))
                .then(duopodCemBenchmarkWalkForwardAtCommand())
                .then(Commands.literal("validate_target_change")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.validateTargetChange(context.getSource()))
                        .then(Commands.argument("switchControlStep", IntegerArgumentType.integer(1, 2000))
                                .executes(context -> MinecraftMachinesDuopodCemTrainer.validateTargetChange(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "switchControlStep"),
                                        IntegerArgumentType.getInteger(context, "switchControlStep") + 60))
                                .then(Commands.argument("maxControlSteps", IntegerArgumentType.integer(2, 4000))
                                        .executes(context -> MinecraftMachinesDuopodCemTrainer.validateTargetChange(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "switchControlStep"),
                                                IntegerArgumentType.getInteger(context, "maxControlSteps"))))))
                .then(duopodCemValidateTargetChangeAtCommand())
                .then(Commands.literal("replay_target")
                        .then(Commands.argument("forwardBlocks", DoubleArgumentType.doubleArg(-64.0, 64.0))
                                .then(Commands.argument("rightBlocks", DoubleArgumentType.doubleArg(-64.0, 64.0))
                                        .executes(context -> MinecraftMachinesDuopodCemTrainer.setReplayTarget(
                                                context.getSource(),
                                                DoubleArgumentType.getDouble(context, "forwardBlocks"),
                                                DoubleArgumentType.getDouble(context, "rightBlocks"))))))
                .then(lookTargetCommand("look_target"))
                .then(rewardVisualizationCommand())
                .then(Commands.literal("save")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.saveCheckpoint(context.getSource()))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> MinecraftMachinesDuopodCemTrainer.saveCheckpoint(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("load")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.loadCheckpoint(context.getSource()))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> MinecraftMachinesDuopodCemTrainer.loadCheckpoint(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                // Retain the original spellings for existing scripts while the concise forms above
                // are the documented interface.
                .then(Commands.literal("save_checkpoint")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.saveCheckpoint(context.getSource())))
                .then(Commands.literal("load_checkpoint")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.loadCheckpoint(context.getSource())))
                .then(Commands.literal("clear")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.clear(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> lookTargetCommand(final String name) {
        return Commands.literal(name)
                .executes(context -> MinecraftMachinesDuopodCemTrainer.toggleLookTarget(context.getSource()))
                .then(Commands.literal("on")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.setLookTarget(context.getSource(), true)))
                .then(Commands.literal("off")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.setLookTarget(context.getSource(), false)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rewardVisualizationCommand() {
        return Commands.literal("reward_viz")
                .executes(context -> MinecraftMachinesDuopodCemTrainer.toggleRewardVisualization(context.getSource()))
                .then(Commands.literal("on")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.setRewardVisualization(context.getSource(), true)))
                .then(Commands.literal("off")
                        .executes(context -> MinecraftMachinesDuopodCemTrainer.setRewardVisualization(context.getSource(), false)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duopodCemStartCommand() {
        return duopodCemStartArguments(Commands.literal("start"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duopodCemEvaluateBestAtCommand() {
        final var command = Commands.literal("evaluate_best_at");
        final var x = Commands.argument("x", IntegerArgumentType.integer());
        final var y = Commands.argument("y", IntegerArgumentType.integer());
        final var z = Commands.argument("z", IntegerArgumentType.integer());
        final var forward = Commands.argument("forward", StringArgumentType.word())
                .executes(context -> MinecraftMachinesDuopodCemTrainer.evaluateBestAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        MinecraftMachinesDuopodCemTrainer.DEFAULT_EVALUATION_MAX_CONTROL_STEPS));
        forward.then(Commands.argument("maxControlSteps", IntegerArgumentType.integer(1, 2000))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.evaluateBestAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        IntegerArgumentType.getInteger(context, "maxControlSteps"))));
        z.then(forward);
        y.then(z);
        x.then(y);
        command.then(x);
        return command;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duopodCemBenchmarkWalkForwardAtCommand() {
        final var command = Commands.literal("benchmark_walk_forward_at");
        final var x = Commands.argument("x", IntegerArgumentType.integer());
        final var y = Commands.argument("y", IntegerArgumentType.integer());
        final var z = Commands.argument("z", IntegerArgumentType.integer());
        final var forward = Commands.argument("forward", StringArgumentType.word())
                .executes(context -> MinecraftMachinesDuopodCemTrainer.benchmarkWalkForwardAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        MinecraftMachinesDuopodWalkForwardBenchmark.DEFAULT_MAX_CONTROL_STEPS));
        forward.then(Commands.argument("maxControlSteps", IntegerArgumentType.integer(1, 2000))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.benchmarkWalkForwardAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        IntegerArgumentType.getInteger(context, "maxControlSteps"))));
        z.then(forward);
        y.then(z);
        x.then(y);
        command.then(x);
        return command;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duopodCemValidateTargetChangeAtCommand() {
        final var command = Commands.literal("validate_target_change_at");
        final var x = Commands.argument("x", IntegerArgumentType.integer());
        final var y = Commands.argument("y", IntegerArgumentType.integer());
        final var z = Commands.argument("z", IntegerArgumentType.integer());
        final var forward = Commands.argument("forward", StringArgumentType.word())
                .executes(context -> MinecraftMachinesDuopodCemTrainer.validateTargetChangeAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        MinecraftMachinesDuopodCemTrainer.DEFAULT_TARGET_CHANGE_SWITCH_STEP,
                        MinecraftMachinesDuopodCemTrainer.DEFAULT_TARGET_CHANGE_MAX_CONTROL_STEPS));
        final var switchControlStep = Commands.argument("switchControlStep", IntegerArgumentType.integer(1, 2000))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.validateTargetChangeAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        IntegerArgumentType.getInteger(context, "switchControlStep"),
                        IntegerArgumentType.getInteger(context, "switchControlStep") + 60));
        switchControlStep.then(Commands.argument("maxControlSteps", IntegerArgumentType.integer(2, 4000))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.validateTargetChangeAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        IntegerArgumentType.getInteger(context, "switchControlStep"),
                        IntegerArgumentType.getInteger(context, "maxControlSteps"))));
        forward.then(switchControlStep);
        z.then(forward);
        y.then(z);
        x.then(y);
        command.then(x);
        return command;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duopodCemStartAtCommand() {
        final var startAt = Commands.literal("start_at");
        final var x = Commands.argument("x", IntegerArgumentType.integer());
        final var y = Commands.argument("y", IntegerArgumentType.integer());
        final var z = Commands.argument("z", IntegerArgumentType.integer());
        final var forward = Commands.argument("forward", StringArgumentType.word())
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT));
        final var population = Commands.argument("population", IntegerArgumentType.integer(1, 128))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))));
        final var generations = Commands.argument("generations", IntegerArgumentType.integer(1, 500))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))));
        final var episodeTicks = Commands.argument("episodeTicks", IntegerArgumentType.integer(20, 4000))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))));
        final var controlTicks = Commands.argument("controlTicks", IntegerArgumentType.integer(1, 40))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))));
        final var scenarios = Commands.argument("scenarios", IntegerArgumentType.integer(1, Math.max(
                        DuopodTrainingScenarios.FLAT_COMMAND_SCENARIO_COUNT,
                        DuopodTrainingScenarios.POINT_GOAL_SCENARIO_COUNT)))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))));
        final var spacing = Commands.argument("spacing", IntegerArgumentType.integer(0, 32))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))));
        final var curriculum = Commands.argument("curriculum", StringArgumentType.word())
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))));
        final var maxSlots = Commands.literal("max_slots");
        final var maxConcurrentSlots = Commands.argument("maxConcurrentSlots", IntegerArgumentType.integer(1, 128))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))));
        final var curriculumAfterMaxSlots = Commands.argument("curriculum", StringArgumentType.word())
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))
                        .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))));
        final var targetsAfterCurriculum = Commands.literal("targets");
        final var targetNearAfterCurriculum = Commands.argument("nearBlocks", DoubleArgumentType.doubleArg(1.0, 64.0));
        final var targetFarAfterCurriculum = Commands.argument("farBlocks", DoubleArgumentType.doubleArg(1.0, 64.0))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))
                        .withPointGoalTargetDistances(
                                DoubleArgumentType.getDouble(context, "nearBlocks"),
                                DoubleArgumentType.getDouble(context, "farBlocks"))));
        final var targetsAfterMaxSlotsCurriculum = Commands.literal("targets");
        final var targetNearAfterMaxSlotsCurriculum = Commands.argument("nearBlocks", DoubleArgumentType.doubleArg(1.0, 64.0));
        final var targetFarAfterMaxSlotsCurriculum = Commands.argument("farBlocks", DoubleArgumentType.doubleArg(1.0, 64.0))
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))
                        .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))
                        .withPointGoalTargetDistances(
                                DoubleArgumentType.getDouble(context, "nearBlocks"),
                                DoubleArgumentType.getDouble(context, "farBlocks"))));

        final var seedAfterCurriculum = Commands.literal("seed");
        seedAfterCurriculum.then(Commands.argument("seedValue", LongArgumentType.longArg())
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                                .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                                .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                                .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                                .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                                .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                                .withCurriculumStage(StringArgumentType.getString(context, "curriculum")),
                        LongArgumentType.getLong(context, "seedValue"))));
        final var seedAfterMaxSlotsCurriculum = Commands.literal("seed");
        seedAfterMaxSlotsCurriculum.then(Commands.argument("seedValue", LongArgumentType.longArg())
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                                .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                                .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                                .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                                .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                                .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                                .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))
                                .withCurriculumStage(StringArgumentType.getString(context, "curriculum")),
                        LongArgumentType.getLong(context, "seedValue"))));
        final var seedAfterTargets = Commands.literal("seed");
        seedAfterTargets.then(Commands.argument("seedValue", LongArgumentType.longArg())
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                                .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                                .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                                .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                                .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                                .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                                .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))
                                .withPointGoalTargetDistances(
                                        DoubleArgumentType.getDouble(context, "nearBlocks"),
                                        DoubleArgumentType.getDouble(context, "farBlocks")),
                        LongArgumentType.getLong(context, "seedValue"))));
        final var seedAfterMaxSlotsTargets = Commands.literal("seed");
        seedAfterMaxSlotsTargets.then(Commands.argument("seedValue", LongArgumentType.longArg())
                .executes(context -> duopodCemStartAt(context, MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                                .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                                .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                                .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                                .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                                .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                                .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))
                                .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))
                                .withPointGoalTargetDistances(
                                        DoubleArgumentType.getDouble(context, "nearBlocks"),
                                        DoubleArgumentType.getDouble(context, "farBlocks")),
                        LongArgumentType.getLong(context, "seedValue"))));

        curriculum.then(seedAfterCurriculum);
        curriculumAfterMaxSlots.then(seedAfterMaxSlotsCurriculum);
        targetFarAfterCurriculum.then(seedAfterTargets);
        targetFarAfterMaxSlotsCurriculum.then(seedAfterMaxSlotsTargets);
        targetNearAfterCurriculum.then(targetFarAfterCurriculum);
        targetsAfterCurriculum.then(targetNearAfterCurriculum);
        curriculum.then(targetsAfterCurriculum);
        targetNearAfterMaxSlotsCurriculum.then(targetFarAfterMaxSlotsCurriculum);
        targetsAfterMaxSlotsCurriculum.then(targetNearAfterMaxSlotsCurriculum);
        curriculumAfterMaxSlots.then(targetsAfterMaxSlotsCurriculum);
        maxConcurrentSlots.then(curriculumAfterMaxSlots);
        maxSlots.then(maxConcurrentSlots);
        spacing.then(maxSlots);
        spacing.then(curriculum);
        scenarios.then(spacing);
        controlTicks.then(scenarios);
        episodeTicks.then(controlTicks);
        generations.then(episodeTicks);
        population.then(generations);
        forward.then(population);
        z.then(forward);
        y.then(z);
        x.then(y);
        startAt.then(x);
        return startAt;
    }

    /**
     * Explicit clean-run entry point. Unlike {@code start_at}, this never
     * initializes from the loaded checkpoint; the checkpoint file itself is not
     * deleted or rewritten. Requiring the complete run configuration keeps an
     * accidental fresh long-running job unlikely.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> duopodCemStartFreshAtCommand() {
        final var command = Commands.literal("start_fresh_at");
        final var x = Commands.argument("x", IntegerArgumentType.integer());
        final var y = Commands.argument("y", IntegerArgumentType.integer());
        final var z = Commands.argument("z", IntegerArgumentType.integer());
        final var forward = Commands.argument("forward", StringArgumentType.word());
        final var population = Commands.argument("population", IntegerArgumentType.integer(1, 128));
        final var generations = Commands.argument("generations", IntegerArgumentType.integer(1, 500));
        final var episodeTicks = Commands.argument("episodeTicks", IntegerArgumentType.integer(20, 4000));
        final var controlTicks = Commands.argument("controlTicks", IntegerArgumentType.integer(1, 40));
        final var scenarios = Commands.argument("scenarios", IntegerArgumentType.integer(1, Math.max(
                DuopodTrainingScenarios.FLAT_COMMAND_SCENARIO_COUNT,
                DuopodTrainingScenarios.POINT_GOAL_SCENARIO_COUNT)));
        final var spacing = Commands.argument("spacing", IntegerArgumentType.integer(0, 32));
        final var maxSlots = Commands.literal("max_slots");
        final var maxConcurrentSlots = Commands.argument("maxConcurrentSlots", IntegerArgumentType.integer(1, 128));
        final var curriculum = Commands.argument("curriculum", StringArgumentType.word())
                .executes(context -> MinecraftMachinesDuopodCemTrainer.startFreshAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                                .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                                .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                                .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                                .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                                .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                                .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))
                                .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))));
        final var seed = Commands.literal("seed");
        final var seedValue = Commands.argument("seedValue", LongArgumentType.longArg())
                .executes(context -> MinecraftMachinesDuopodCemTrainer.startFreshAt(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"),
                        StringArgumentType.getString(context, "forward"),
                        MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                                .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                                .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                                .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                                .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                                .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                                .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                                .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))
                                .withCurriculumStage(StringArgumentType.getString(context, "curriculum")),
                        LongArgumentType.getLong(context, "seedValue")));

        seed.then(seedValue);
        curriculum.then(seed);
        maxConcurrentSlots.then(curriculum);
        maxSlots.then(maxConcurrentSlots);
        spacing.then(maxSlots);
        scenarios.then(spacing);
        controlTicks.then(scenarios);
        episodeTicks.then(controlTicks);
        generations.then(episodeTicks);
        population.then(generations);
        forward.then(population);
        z.then(forward);
        y.then(z);
        x.then(y);
        command.then(x);
        return command;
    }

    private static int duopodCemStartAt(
            final CommandContext<CommandSourceStack> context,
            final MinecraftMachinesDuopodCemTrainer.Config config
    ) {
        return MinecraftMachinesDuopodCemTrainer.startAt(
                context.getSource(),
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"),
                StringArgumentType.getString(context, "forward"),
                config);
    }

    private static int duopodCemStartAt(
            final CommandContext<CommandSourceStack> context,
            final MinecraftMachinesDuopodCemTrainer.Config config,
            final long seed
    ) {
        return MinecraftMachinesDuopodCemTrainer.startAt(
                context.getSource(),
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"),
                StringArgumentType.getString(context, "forward"),
                config,
                seed);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duopodCemStartArguments(final LiteralArgumentBuilder<CommandSourceStack> start) {
        start.executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT));
        final var population = Commands.argument("population", IntegerArgumentType.integer(1, 128))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))));
        final var generations = Commands.argument("generations", IntegerArgumentType.integer(1, 500))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))));
        final var episodeTicks = Commands.argument("episodeTicks", IntegerArgumentType.integer(20, 4000))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))));
        final var controlTicks = Commands.argument("controlTicks", IntegerArgumentType.integer(1, 40))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))));
        final var scenarios = Commands.argument("scenarios", IntegerArgumentType.integer(1, Math.max(
                        DuopodTrainingScenarios.FLAT_COMMAND_SCENARIO_COUNT,
                        DuopodTrainingScenarios.POINT_GOAL_SCENARIO_COUNT)))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))));
        final var spacing = Commands.argument("spacing", IntegerArgumentType.integer(0, 32))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))));
        final var curriculum = Commands.argument("curriculum", StringArgumentType.word())
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))));
        final var maxSlots = Commands.literal("max_slots");
        final var maxConcurrentSlots = Commands.argument("maxConcurrentSlots", IntegerArgumentType.integer(1, 128))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))));
        final var curriculumAfterMaxSlots = Commands.argument("curriculum", StringArgumentType.word())
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))
                        .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))));
        final var targetsAfterCurriculum = Commands.literal("targets");
        final var targetNearAfterCurriculum = Commands.argument("nearBlocks", DoubleArgumentType.doubleArg(1.0, 64.0));
        final var targetFarAfterCurriculum = Commands.argument("farBlocks", DoubleArgumentType.doubleArg(1.0, 64.0))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))
                        .withPointGoalTargetDistances(
                                DoubleArgumentType.getDouble(context, "nearBlocks"),
                                DoubleArgumentType.getDouble(context, "farBlocks"))));
        final var targetsAfterMaxSlotsCurriculum = Commands.literal("targets");
        final var targetNearAfterMaxSlotsCurriculum = Commands.argument("nearBlocks", DoubleArgumentType.doubleArg(1.0, 64.0));
        final var targetFarAfterMaxSlotsCurriculum = Commands.argument("farBlocks", DoubleArgumentType.doubleArg(1.0, 64.0))
                .executes(context -> MinecraftMachinesDuopodCemTrainer.start(context.getSource(), MinecraftMachinesDuopodCemTrainer.Config.DEFAULT
                        .withPopulationSize(IntegerArgumentType.getInteger(context, "population"))
                        .withMaximumGenerations(IntegerArgumentType.getInteger(context, "generations"))
                        .withEpisodeTicks(IntegerArgumentType.getInteger(context, "episodeTicks"))
                        .withControlTicks(IntegerArgumentType.getInteger(context, "controlTicks"))
                        .withEpisodesPerCandidate(IntegerArgumentType.getInteger(context, "scenarios"))
                        .withSpacingBlocks(IntegerArgumentType.getInteger(context, "spacing"))
                        .withMaxConcurrentSlots(IntegerArgumentType.getInteger(context, "maxConcurrentSlots"))
                        .withCurriculumStage(StringArgumentType.getString(context, "curriculum"))
                        .withPointGoalTargetDistances(
                                DoubleArgumentType.getDouble(context, "nearBlocks"),
                                DoubleArgumentType.getDouble(context, "farBlocks"))));

        targetNearAfterCurriculum.then(targetFarAfterCurriculum);
        targetsAfterCurriculum.then(targetNearAfterCurriculum);
        curriculum.then(targetsAfterCurriculum);
        targetNearAfterMaxSlotsCurriculum.then(targetFarAfterMaxSlotsCurriculum);
        targetsAfterMaxSlotsCurriculum.then(targetNearAfterMaxSlotsCurriculum);
        curriculumAfterMaxSlots.then(targetsAfterMaxSlotsCurriculum);
        maxConcurrentSlots.then(curriculumAfterMaxSlots);
        maxSlots.then(maxConcurrentSlots);
        spacing.then(maxSlots);
        spacing.then(curriculum);
        scenarios.then(spacing);
        controlTicks.then(scenarios);
        episodeTicks.then(controlTicks);
        generations.then(episodeTicks);
        population.then(generations);
        start.then(population);
        return start;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duopodCommands() {
        return Commands.literal("duopod")
                .then(Commands.literal("spawn")
                        .executes(context -> MinecraftMachinesDuopodManager.spawn(context.getSource())))
                .then(Commands.literal("remove_nearest")
                        .executes(context -> MinecraftMachinesDuopodManager.removeNearest(context.getSource())))
                .then(Commands.literal("control_sweep")
                        .executes(context -> MinecraftMachinesDuopodManager.controlSweep(context.getSource())))
                .then(Commands.literal("control_sweep_at")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("forward", StringArgumentType.word())
                                                        .executes(context -> MinecraftMachinesDuopodManager.controlSweepAt(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"),
                                                                StringArgumentType.getString(context, "forward"))))))))
                .then(Commands.literal("telemetry")
                        .then(Commands.literal("on")
                                .executes(context -> MinecraftMachinesDuopodManager.telemetry(context.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(context -> MinecraftMachinesDuopodManager.telemetry(context.getSource(), false))));
    }

    private static int spawnTestBlock(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }

        final Vec3 look = player.getLookAngle();
        final BlockPos target = BlockPos.containing(
                player.getX() + look.x * 4.0,
                player.getEyeY() + look.y * 4.0,
                player.getZ() + look.z * 4.0
        );

        final PhysicsSpawnTest.SpawnResult result = PhysicsSpawnTest.spawnIronBlock(player.serverLevel(), target);
        if (!result.success()) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("%s at %s; sublevel %s; offset %s".formatted(
                result.message(),
                target.toShortString(),
                result.subLevelId(),
                result.offset().toShortString()
        )), true);
        return 1;
    }

    private static int spawnServoTest(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }

        final Vec3 look = player.getLookAngle();
        final BlockPos servoPos = BlockPos.containing(
                player.getX() + look.x * 4.0,
                player.getEyeY() + look.y * 4.0,
                player.getZ() + look.z * 4.0
        );
        final Direction facing = player.getDirection();
        final BlockPos shaftPos = servoPos.relative(facing);

        if (!player.serverLevel().getBlockState(servoPos).canBeReplaced()) {
            source.sendFailure(Component.literal("Servo target position is not empty: " + servoPos.toShortString()));
            return 0;
        }
        if (!player.serverLevel().getBlockState(shaftPos).canBeReplaced()) {
            source.sendFailure(Component.literal("Shaft target position is not empty: " + shaftPos.toShortString()));
            return 0;
        }

        final BlockState servoState = MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get()
                .defaultBlockState()
                .setValue(RoboticServoJointBlock.FACING, facing)
                .setValue(RoboticServoJointBlock.ASSEMBLED, false);
        player.serverLevel().setBlockAndUpdate(servoPos, servoState);
        player.serverLevel().setBlockAndUpdate(shaftPos, shaftState(facing.getAxis()));

        if (player.serverLevel().getBlockEntity(servoPos) instanceof final RoboticServoJointBlockEntity servo) {
            servo.setAngleLimitsDegrees(-180.0, 180.0);
            servo.setMaxAngularSpeedDegreesPerSecond(180.0);
            servo.setTargetAngleDegrees(90.0);
        } else {
            source.sendFailure(Component.literal("Servo block entity did not initialize at " + servoPos.toShortString()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Spawned self-powered Robotic Servo Joint test: servo at %s -> shaft at %s, targeting 90 degrees."
                .formatted(servoPos.toShortString(), shaftPos.toShortString())), true);
        return 1;
    }

    private static int spawnServoSwingTest(final CommandSourceStack source) {
        return spawnServoSwingTest(source, false);
    }

    private static int spawnServoDiagnosticTest(final CommandSourceStack source) {
        return spawnServoSwingTest(source, true);
    }

    private static int spawnServoSwingTest(final CommandSourceStack source, final boolean startTelemetry) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }

        final ServerLevel level = player.serverLevel();
        final Direction servoAxis = player.getDirection();
        final Direction armDirection = servoAxis.getClockWise();
        final BlockPos servoPos = findServoDemoGround(level, player.blockPosition().relative(servoAxis, 5));
        if (servoPos == null) {
            source.sendFailure(Component.literal("Could not find solid ground with empty space for the servo demo."));
            return 0;
        }

        final BlockPos shaftPos = servoPos.relative(servoAxis);
        final BlockPos bearingPos = shaftPos.relative(servoAxis);
        final BlockPos hingePos = bearingPos.relative(servoAxis);
        final List<DemoBlock> blocks = createSwingDemoBlocks(hingePos, armDirection);

        if (!level.getBlockState(servoPos).canBeReplaced()) {
            source.sendFailure(Component.literal("Servo target position is not empty: " + servoPos.toShortString()));
            return 0;
        }
        if (!level.getBlockState(shaftPos).canBeReplaced()) {
            source.sendFailure(Component.literal("Shaft target position is not empty: " + shaftPos.toShortString()));
            return 0;
        }
        if (!level.getBlockState(bearingPos).canBeReplaced()) {
            source.sendFailure(Component.literal("Swivel bearing target position is not empty: " + bearingPos.toShortString()));
            return 0;
        }

        for (final DemoBlock block : blocks) {
            if (!level.getBlockState(block.pos()).canBeReplaced()) {
                source.sendFailure(Component.literal("Demo arm target position is not empty: " + block.pos().toShortString()));
                return 0;
            }
        }

        final BlockState servoState = MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT.get()
                .defaultBlockState()
                .setValue(RoboticServoJointBlock.FACING, servoAxis)
                .setValue(RoboticServoJointBlock.ASSEMBLED, false);
        level.setBlockAndUpdate(servoPos, servoState);
        level.setBlockAndUpdate(shaftPos, shaftState(servoAxis.getAxis()));
        level.setBlockAndUpdate(bearingPos, SimBlocks.SWIVEL_BEARING.getDefaultState()
                .setValue(SwivelBearingBlock.FACING, servoAxis)
                .setValue(SwivelBearingBlock.ASSEMBLED, false)
                .setValue(SwivelBearingBlock.POWERED, false));

        for (final DemoBlock block : blocks) {
            level.setBlockAndUpdate(block.pos(), block.state());
        }

        if (level.getBlockEntity(servoPos) instanceof final RoboticServoJointBlockEntity servo) {
            servo.setAngleLimitsDegrees(-80.0, 80.0);
            servo.setMaxAngularSpeedDegreesPerSecond(360.0);
            servo.setTargetAngleDegrees(0.0);
        } else {
            source.sendFailure(Component.literal("Servo block entity did not initialize at " + servoPos.toShortString()));
            return 0;
        }

        if (level.getBlockEntity(bearingPos) instanceof final SwivelBearingBlockEntity bearing) {
            bearing.assembleNextTick = true;
        } else {
            source.sendFailure(Component.literal("Swivel bearing block entity did not initialize at " + bearingPos.toShortString()));
            return 0;
        }

        MinecraftMachinesServoDemo.start(level, servoPos);
        if (startTelemetry) {
            MinecraftMachinesServoTelemetry.start(player, servoPos, 240);
        }

        source.sendSuccess(() -> Component.literal("Spawned self-powered servo demo at %s: servo -> shaft -> Simulated swivel bearing -> physics arm."
                .formatted(servoPos.toShortString())), true);
        return 1;
    }

    private static int spawnWorm(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }

        final ServerLevel level = player.serverLevel();
        final Direction rowDirection = player.getDirection();
        final Direction columnDirection = rowDirection.getClockWise();
        final BlockPos servoPos = MinecraftMachinesWormSpawner.findWormSpawn(level, player.blockPosition().relative(rowDirection, 5), rowDirection, columnDirection);
        if (servoPos == null) {
            source.sendFailure(Component.literal("Could not find clear surface space for a worm spawn."));
            return 0;
        }

        final MinecraftMachinesWormSpawner.SpawnResult result = MinecraftMachinesWormSpawner.spawn(level, servoPos, rowDirection, columnDirection, false);
        if (!result.success() || result.worm() == null) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Spawned physics worm at %s: base body assembled, servo -> side cog -> swivel bearing, bearing iron row assembled as its own child body."
                .formatted(result.worm().servoPos().toShortString())), true);
        return 1;
    }

    private static BlockPos findServoDemoGround(final ServerLevel level, final BlockPos origin) {
        final int top = Math.min(level.getMaxBuildHeight() - 8, origin.getY() + 6);
        final int bottom = Math.max(level.getMinBuildHeight() + 1, origin.getY() - 16);
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(origin.getX(), top, origin.getZ());
        for (int y = top; y >= bottom; y--) {
            mutable.setY(y);
            final BlockPos supportPos = mutable.immutable();
            final BlockPos servoPos = supportPos.above();
            if (!level.getBlockState(supportPos).canBeReplaced()
                    && level.getBlockState(servoPos).canBeReplaced()
                    && level.getBlockState(servoPos.above()).canBeReplaced()
                    && level.getBlockState(servoPos.above(2)).canBeReplaced()) {
                return servoPos;
            }
        }
        return null;
    }

    private static int startServoTelemetry(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        return MinecraftMachinesServoTelemetry.startNearest(player);
    }

    private static int stopServoTelemetry(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        return MinecraftMachinesServoTelemetry.stop(player);
    }

    private static int dumpServoTelemetry(final CommandSourceStack source) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        return MinecraftMachinesServoTelemetry.dumpNearest(player);
    }

    private static int stopServoDemos(final CommandSourceStack source) {
        final int count = MinecraftMachinesServoDemo.stopAll();
        source.sendSuccess(() -> Component.literal("Stopped %d active servo demo(s).".formatted(count)), true);
        return count;
    }

    private static List<DemoBlock> createSwingDemoBlocks(final BlockPos hingePos, final Direction armDirection) {
        final List<DemoBlock> blocks = new ArrayList<>();
        for (int i = 0; i <= 4; i++) {
            blocks.add(new DemoBlock(hingePos.relative(armDirection, i), Blocks.SLIME_BLOCK.defaultBlockState()));
        }

        blocks.add(new DemoBlock(hingePos.relative(armDirection, 5), Blocks.REDSTONE_BLOCK.defaultBlockState()));
        blocks.add(new DemoBlock(hingePos.relative(armDirection, 4).above(), Blocks.SLIME_BLOCK.defaultBlockState()));
        blocks.add(new DemoBlock(hingePos.relative(armDirection, 5).above(), Blocks.REDSTONE_BLOCK.defaultBlockState()));
        return blocks;
    }


    private static BlockState shaftState(final Direction.Axis axis) {
        return AllBlocks.SHAFT.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, axis);
    }

    private record DemoBlock(BlockPos pos, BlockState state) {
    }
}
