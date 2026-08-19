package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode;
import dev.ahmedhamedi.minecraft_machines.content.training.api.LocomotionCommand;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ResetStrategy;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.BridgeMessageType;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.BridgeSpecs;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.ProtocolEnvelope;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.StepRequest;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.StepResponse;
import dev.ahmedhamedi.minecraft_machines.content.training.bridge.TrainingBridgeProtocol;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.CurriculumStage;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchReset;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EnvironmentBatchStep;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.EpisodeDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.LocomotionVectorEnvironment;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodSchemas;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodTrainingScenarios;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PointTargetCommandMath;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.PointTargetCommandConfig;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.PointTargetDefinition;
import dev.ahmedhamedi.minecraft_machines.content.training.terrain.TerrainProfile;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public final class MinecraftMachinesTrainingBridge {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String HOST = "127.0.0.1";
    private static final String MORPHOLOGY_ID = MinecraftMachines.MOD_ID + ":duopod";
    private static final String MOD_VERSION = "0.1.0";
    private static final int DEFAULT_CONTROL_TICKS = 4;
    private static final int DEFAULT_MAX_CONTROL_STEPS = 200;
    private static final int SESSION_TIMEOUT_TICKS = 20 * 120;
    private static final int REQUEST_TIMEOUT_SECONDS = 180;

    private static volatile BridgeServer activeBridge;

    private MinecraftMachinesTrainingBridge() {
    }

    public static int startDuopod(
            final CommandSourceStack source,
            final int slotCount,
            final int requestedPort
    ) {
        if (!(source.getEntity() instanceof final ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only"));
            return 0;
        }
        if (activeBridge != null && !activeBridge.stopped()) {
            source.sendFailure(Component.literal("A training bridge is already active. Use /mm train bridge stop first."));
            return 0;
        }

        final Direction forward = player.getDirection();
        final BlockPos origin = player.blockPosition().relative(forward, 5);
        return startDuopodAt(
                source,
                player.serverLevel(),
                origin,
                forward,
                slotCount,
                requestedPort,
                player.serverLevel().getGameTime() ^ player.getUUID().getMostSignificantBits());
    }

    public static int startDuopodAt(
            final CommandSourceStack source,
            final int x,
            final int y,
            final int z,
            final String forwardName,
            final int slotCount,
            final int requestedPort
    ) {
        final Direction forward = parseHorizontalDirection(forwardName);
        if (forward == null) {
            source.sendFailure(Component.literal("forward must be one of north, south, east, or west"));
            return 0;
        }
        final ServerLevel level = source.getLevel();
        final BlockPos origin = new BlockPos(x, y, z);
        return startDuopodAt(
                source,
                level,
                origin,
                forward,
                slotCount,
                requestedPort,
                level.getGameTime() ^ origin.asLong() ^ ((long) forward.get3DDataValue() << 32));
    }

    private static int startDuopodAt(
            final CommandSourceStack source,
            final ServerLevel level,
            final BlockPos origin,
            final Direction forward,
            final int slotCount,
            final int requestedPort,
            final long seed
    ) {
        if (hasActiveBridge()) {
            source.sendFailure(Component.literal("A training bridge is already active. Use /mm train bridge stop first."));
            return 0;
        }
        if (MinecraftMachinesDuopodCemTrainer.hasActiveWork()) {
            source.sendFailure(Component.literal(
                    "Stop active Duopod CEM training, evaluation, benchmark, or replay before starting the dimension-global lockstep bridge."));
            return 0;
        }
        try {
            final BridgeServer bridge = BridgeServer.start(
                    level.dimension(),
                    origin,
                    forward,
                    slotCount,
                    requestedPort,
                    seed);
            activeBridge = bridge;
            final BridgeSpecs specs = bridge.specs();
            final String command = "python -m minecraft_machines_training.train_ppo --host %s --port %d --token %s --morphology %s --envs %d --timesteps 1000000 --curriculum %s --run-name duopod_ppo"
                    .formatted(HOST, bridge.port(), bridge.token(), MORPHOLOGY_ID, slotCount, DuopodTrainingScenarios.defaultTrainingCurriculumStage());
            source.sendSuccess(() -> Component.literal("Started Duopod PPO bridge on %s:%d token=%s morphology=%s slots=%d obs=%d/%s action=%d/%s"
                    .formatted(HOST,
                            bridge.port(),
                            bridge.token(),
                            MORPHOLOGY_ID,
                            specs.slotCount(),
                            specs.observationSpec().size(),
                            specs.observationSpec().compatibilityHash(),
                            specs.actionSpec().size(),
                            specs.actionSpec().compatibilityHash())), false);
            source.sendSuccess(() -> Component.literal(command), false);
            MinecraftMachines.LOGGER.info("MM_TRAINING_BRIDGE_START host={} port={} morphology={} slots={} observationHash={} actionHash={}",
                    HOST,
                    bridge.port(),
                    MORPHOLOGY_ID,
                    specs.slotCount(),
                    specs.observationSpec().compatibilityHash(),
                    specs.actionSpec().compatibilityHash());
            return 1;
        } catch (final IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Failed to start training bridge: " + e.getMessage()));
            return 0;
        }
    }

    private static Direction parseHorizontalDirection(final String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    public static int status(final CommandSourceStack source) {
        final BridgeServer bridge = activeBridge;
        if (bridge == null || bridge.stopped()) {
            source.sendSuccess(() -> Component.literal("Training bridge inactive."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Training bridge active on %s:%d morphology=%s slots=%d session=%s pendingStep=%s lockstepPaused=%s"
                .formatted(HOST,
                        bridge.port(),
                        MORPHOLOGY_ID,
                        bridge.slotCount(),
                        bridge.hasSession(),
                        bridge.hasPendingStep(),
                        bridge.physicsPaused())), false);
        return 1;
    }

    public static boolean hasActiveBridge() {
        final BridgeServer bridge = activeBridge;
        return bridge != null && !bridge.stopped();
    }

    public static boolean stopActive(final String reason) {
        final BridgeServer bridge = activeBridge;
        if (bridge == null || bridge.stopped()) {
            activeBridge = null;
            return false;
        }
        bridge.stopFromServerThread(reason);
        activeBridge = null;
        return true;
    }

    public static int stop(final CommandSourceStack source) {
        if (!stopActive("command_stop")) {
            source.sendFailure(Component.literal("No active training bridge."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stopped training bridge."), true);
        return 1;
    }

    public static void tick(final ServerTickEvent.Pre event) {
        final BridgeServer bridge = activeBridge;
        if (bridge == null) {
            return;
        }
        bridge.tick(event.getServer());
        if (bridge.stopped()) {
            activeBridge = null;
        }
    }

    static BridgeTestHandle startDuopodForGameTest(
            final ServerLevel level,
            final BlockPos origin,
            final Direction forwardDirection,
            final int slotCount,
            final int requestedPort,
            final long seed
    ) throws IOException {
        if (activeBridge != null && !activeBridge.stopped()) {
            throw new IllegalStateException("a training bridge is already active");
        }
        final BridgeServer bridge = BridgeServer.start(
                level.dimension(),
                origin,
                forwardDirection,
                slotCount,
                requestedPort,
                seed);
        activeBridge = bridge;
        final BridgeSpecs specs = bridge.specs();
        return new BridgeTestHandle(
                HOST,
                bridge.port(),
                bridge.token(),
                MORPHOLOGY_ID,
                specs.slotCount(),
                specs.controlTicks(),
                specs.observationSpec().size(),
                specs.observationSpec().compatibilityHash(),
                specs.actionSpec().size(),
                specs.actionSpec().compatibilityHash());
    }

    static void stopGameTestBridge() {
        final BridgeServer bridge = activeBridge;
        if (bridge != null) {
            bridge.stopFromServerThread("game_test_stop");
            activeBridge = null;
        }
    }

    static boolean hasPendingStepForGameTest() {
        final BridgeServer bridge = activeBridge;
        return bridge != null && bridge.hasPendingStep();
    }

    record BridgeTestHandle(
            String host,
            int port,
            String token,
            String morphologyId,
            int slotCount,
            int controlTicks,
            int observationSize,
            String observationSchemaHash,
            int actionSize,
            String actionSchemaHash
    ) {
    }

    private static BridgeSpecs specs(final int slotCount, final int controlTicks, final String curriculumStage, final long seed) {
        return new BridgeSpecs(
                TrainingBridgeProtocol.PROTOCOL_VERSION,
                MOD_VERSION,
                MORPHOLOGY_ID,
                DuopodSchemas.observationSpec(),
                DuopodSchemas.actionSpec(),
                slotCount,
                controlTicks,
                curriculumStage,
                seed);
    }

    private static String newToken() {
        final byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ProtocolEnvelope errorEnvelope(final String requestId, final String message) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("error", "bridge_error");
        payload.addProperty("message", message);
        return new ProtocolEnvelope(BridgeMessageType.ERROR, requestId, payload);
    }

    private static JsonObject specsPayload(final BridgeSpecs specs) {
        final JsonObject payload = TrainingBridgeProtocol.toJsonTree(specs);
        payload.addProperty("observationSchemaHash", specs.observationSpec().compatibilityHash());
        payload.addProperty("actionSchemaHash", specs.actionSpec().compatibilityHash());
        return payload;
    }

    private static JsonObject resetPayload(final EnvironmentBatchReset reset) {
        final JsonObject payload = new JsonObject();
        payload.add("observations", GSON.toJsonTree(reset.observations()));
        payload.add("infos", infosToJson(reset.infos()));
        return payload;
    }

    private static JsonArray infosToJson(final List<Map<String, Object>> infos) {
        final JsonArray array = new JsonArray(infos.size());
        for (final Map<String, Object> info : infos) {
            array.add(GSON.toJsonTree(info));
        }
        return array;
    }

    private static List<JsonObject> stepInfosToJson(
            final List<Map<String, Object>> infos,
            final int actualActionTicks
    ) {
        final List<JsonObject> converted = new ArrayList<>(infos.size());
        for (final Map<String, Object> info : infos) {
            final JsonObject convertedInfo = GSON.toJsonTree(info).getAsJsonObject();
            convertedInfo.addProperty("actual_action_ticks", actualActionTicks);
            convertedInfo.addProperty("bridge_lockstep", true);
            converted.add(convertedInfo);
        }
        return converted;
    }

    private static boolean[] readMask(final JsonObject payload, final int slotCount) {
        final JsonArray array = payload.getAsJsonArray("mask");
        if (array == null || array.size() != slotCount) {
            throw new IllegalArgumentException("mask must have one boolean per slot");
        }
        final boolean[] mask = new boolean[slotCount];
        for (int i = 0; i < slotCount; i++) {
            mask[i] = array.get(i).getAsBoolean();
        }
        return mask;
    }

    private static double[][] readMatrix(final JsonObject payload, final String name, final int rows, final int columns) {
        final JsonArray outer = payload.getAsJsonArray(name);
        if (outer == null || outer.size() != rows) {
            throw new IllegalArgumentException(name + " must have " + rows + " rows");
        }
        final double[][] values = new double[rows][columns];
        for (int row = 0; row < rows; row++) {
            final JsonArray inner = outer.get(row).getAsJsonArray();
            if (inner.size() != columns) {
                throw new IllegalArgumentException(name + " row " + row + " must have " + columns + " columns");
            }
            for (int col = 0; col < columns; col++) {
                values[row][col] = inner.get(col).getAsDouble();
                if (!Double.isFinite(values[row][col])) {
                    throw new IllegalArgumentException(name + " contains non-finite values");
                }
            }
        }
        return values;
    }

    private static String requireString(final JsonObject payload, final String name) {
        final JsonElement value = payload.get(name);
        if (value == null || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.getAsString();
    }

    private static final class BridgeServer {
        private final ResourceKey<Level> dimension;
        private final BlockPos origin;
        private final Direction forwardDirection;
        private final int slotCount;
        private final int controlTicks;
        private final int maximumControlSteps;
        private final long seed;
        private final String token;
        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final Queue<Runnable> serverThreadTasks = new ConcurrentLinkedQueue<>();
        private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();
        private volatile boolean stopped;
        private ServerLevel tickingLevel;
        private BridgeSession session;
        private volatile PendingStep pendingStep;
        private SubLevelPhysicsSystem ownedPhysicsSystem;
        private boolean previousPhysicsPaused;
        private String curriculumStage = DuopodTrainingScenarios.defaultTrainingCurriculumStage();
        private long lastActivityGameTime;

        private BridgeServer(
                final ResourceKey<Level> dimension,
                final BlockPos origin,
                final Direction forwardDirection,
                final int slotCount,
                final int requestedPort,
                final long seed
        ) throws IOException {
            this.dimension = dimension;
            this.origin = origin.immutable();
            this.forwardDirection = forwardDirection;
            this.slotCount = slotCount;
            this.controlTicks = DEFAULT_CONTROL_TICKS;
            this.maximumControlSteps = DEFAULT_MAX_CONTROL_STEPS;
            this.seed = seed;
            this.token = newToken();
            this.serverSocket = new ServerSocket();
            this.serverSocket.bind(new InetSocketAddress(InetAddress.getByName(HOST), requestedPort));
            this.acceptThread = new Thread(this::acceptLoop, "Minecraft Machines Training Bridge");
            this.acceptThread.setDaemon(true);
        }

        static BridgeServer start(
                final ResourceKey<Level> dimension,
                final BlockPos origin,
                final Direction forwardDirection,
                final int slotCount,
                final int requestedPort,
                final long seed
        ) throws IOException {
            if (slotCount < 1) {
                throw new IllegalArgumentException("slotCount must be positive");
            }
            if (requestedPort < 0 || requestedPort > 65_535) {
                throw new IllegalArgumentException("port must be in 0..65535");
            }
            final BridgeServer bridge = new BridgeServer(dimension, origin, forwardDirection, slotCount, requestedPort, seed);
            bridge.acceptThread.start();
            return bridge;
        }

        void tick(final MinecraftServer server) {
            if (this.stopped) {
                return;
            }
            final ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                this.stopFromServerThread("missing_level");
                return;
            }
            this.tickingLevel = level;
            try {
                this.advancePendingStep();
                Runnable task;
                while ((task = this.serverThreadTasks.poll()) != null) {
                    task.run();
                }
            } finally {
                this.tickingLevel = null;
            }
            if (this.session != null && this.lastActivityGameTime > 0L
                    && level.getGameTime() - this.lastActivityGameTime > SESSION_TIMEOUT_TICKS) {
                this.closeSession("timeout");
            }
        }

        int port() {
            return this.serverSocket.getLocalPort();
        }

        String token() {
            return this.token;
        }

        int slotCount() {
            return this.slotCount;
        }

        boolean stopped() {
            return this.stopped;
        }

        boolean hasSession() {
            return this.session != null;
        }

        boolean hasPendingStep() {
            return this.pendingStep != null;
        }

        BridgeSpecs specs() {
            return MinecraftMachinesTrainingBridge.specs(this.slotCount, this.controlTicks, this.curriculumStage, this.seed);
        }

        void stopFromServerThread(final String reason) {
            if (this.stopped) {
                return;
            }
            this.stopped = true;
            try {
                this.closeSession(reason);
            } catch (final RuntimeException e) {
                MinecraftMachines.LOGGER.warn("MM_TRAINING_BRIDGE_SESSION_CLOSE_FAILED reason={}", reason, e);
            } finally {
                closeQuietly(this.serverSocket);
                for (final ClientConnection client : this.clients) {
                    client.close();
                }
            }
            MinecraftMachines.LOGGER.info("MM_TRAINING_BRIDGE_STOP reason={}", reason);
        }

        private void acceptLoop() {
            while (!this.stopped) {
                try {
                    final Socket socket = this.serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    final ClientConnection connection = new ClientConnection(this, socket);
                    this.clients.add(connection);
                    connection.start();
                } catch (final SocketException e) {
                    if (!this.stopped) {
                        MinecraftMachines.LOGGER.warn("MM_TRAINING_BRIDGE_ACCEPT socket closed unexpectedly", e);
                    }
                    return;
                } catch (final IOException e) {
                    if (!this.stopped) {
                        MinecraftMachines.LOGGER.warn("MM_TRAINING_BRIDGE_ACCEPT failed", e);
                    }
                }
            }
        }

        private CompletableFuture<ProtocolEnvelope> submit(final ProtocolEnvelope envelope) {
            final CompletableFuture<ProtocolEnvelope> future = new CompletableFuture<>();
            this.serverThreadTasks.add(() -> this.handleQueued(envelope, future));
            return future;
        }

        private void onClientDisconnected(final ClientConnection client) {
            this.clients.remove(client);
            this.serverThreadTasks.add(() -> this.closeSession("client_disconnect"));
        }

        private void handleQueued(
                final ProtocolEnvelope envelope,
                final CompletableFuture<ProtocolEnvelope> future
        ) {
            if (this.stopped) {
                future.complete(errorEnvelope(envelope.requestId(), "bridge is stopped"));
                return;
            }
            final ServerLevel level = this.tickingLevel;
            if (level == null) {
                future.complete(errorEnvelope(envelope.requestId(), "bridge level is not loaded"));
                return;
            }
            this.lastActivityGameTime = level.getGameTime();
            try {
                if (envelope.type() == BridgeMessageType.STEP) {
                    this.beginStep(envelope, future);
                    return;
                }
                if (this.session != null && this.pendingStep == null) {
                    this.pausePhysics();
                }
                final JsonObject payload = this.handleImmediate(level, envelope);
                if (this.session != null && this.pendingStep == null) {
                    this.pausePhysics();
                }
                future.complete(new ProtocolEnvelope(envelope.type(), envelope.requestId(), payload));
            } catch (final RuntimeException e) {
                future.complete(errorEnvelope(envelope.requestId(), e.getMessage()));
            }
        }

        private JsonObject handleImmediate(final ServerLevel level, final ProtocolEnvelope envelope) {
            return switch (envelope.type()) {
                case HELLO, GET_SPECS -> specsPayload(this.specs());
                case PING -> {
                    final JsonObject payload = new JsonObject();
                    payload.addProperty("ok", true);
                    payload.addProperty("protocolVersion", TrainingBridgeProtocol.PROTOCOL_VERSION);
                    yield payload;
                }
                case CREATE_SESSION -> this.createSession(level, envelope.payload());
                case RESET_ALL -> {
                    this.requireNoPendingStep(envelope.type());
                    yield resetPayload(this.requireSession(envelope.payload()).resetAll());
                }
                case RESET_MASK -> {
                    this.requireNoPendingStep(envelope.type());
                    final BridgeSession session = this.requireSession(envelope.payload());
                    yield resetPayload(session.resetMask(readMask(envelope.payload(), this.slotCount)));
                }
                case SET_CURRICULUM -> {
                    this.requireNoPendingStep(envelope.type());
                    yield this.setCurriculum(envelope.payload());
                }
                case SET_TARGETS -> {
                    this.requireNoPendingStep(envelope.type());
                    yield this.setTargets(envelope.payload());
                }
                case UPDATE_TARGETS -> {
                    this.requireNoPendingStep(envelope.type());
                    yield this.updateTargets(envelope.payload());
                }
                case GET_METRICS -> this.requireSession(envelope.payload()).metricsPayload(this.physicsPaused());
                case CLOSE_SESSION -> {
                    this.requireSession(envelope.payload());
                    this.closeSession("client_close");
                    final JsonObject payload = new JsonObject();
                    payload.addProperty("closed", true);
                    yield payload;
                }
                case ERROR, STEP -> throw new IllegalArgumentException("unsupported request type " + envelope.type());
            };
        }

        private void requireNoPendingStep(final BridgeMessageType requestType) {
            if (this.pendingStep != null) {
                throw new IllegalArgumentException(requestType + " cannot run while STEP is pending");
            }
        }

        private JsonObject createSession(final ServerLevel level, final JsonObject payload) {
            final String suppliedToken = requireString(payload, "token");
            if (!this.token.equals(suppliedToken)) {
                throw new IllegalArgumentException("invalid bridge token");
            }
            final String morphology = payload.has("morphologyId") ? payload.get("morphologyId").getAsString() : MORPHOLOGY_ID;
            if (!MORPHOLOGY_ID.equals(morphology)) {
                throw new IllegalArgumentException("unsupported morphology " + morphology);
            }
            if (this.session != null) {
                throw new IllegalArgumentException("bridge session already exists");
            }
            this.acquirePhysicsClock(level);
            BridgeSession createdSession = null;
            try {
                createdSession = BridgeSession.create(
                        level,
                        this.origin,
                        this.forwardDirection,
                        this.slotCount,
                        this.controlTicks,
                        this.maximumControlSteps,
                        this.seed,
                        this.curriculumStage);
                this.pausePhysics();
                this.session = createdSession;
                final JsonObject response = specsPayload(this.specs());
                response.addProperty("sessionId", this.session.sessionId());
                response.addProperty("lockstep", true);
                response.addProperty("physicsPaused", true);
                response.add("reset", resetPayload(this.session.lastReset()));
                return response;
            } catch (final RuntimeException e) {
                try {
                    if (createdSession != null) {
                        createdSession.close();
                    }
                } finally {
                    this.releasePhysicsClock();
                }
                throw e;
            }
        }

        private void beginStep(
                final ProtocolEnvelope envelope,
                final CompletableFuture<ProtocolEnvelope> future
        ) {
            if (this.pendingStep != null) {
                throw new IllegalArgumentException("a STEP request is already pending");
            }
            final BridgeSession session = this.requireSession(envelope.payload());
            final StepRequest request = TrainingBridgeProtocol.fromJsonTree(envelope.payload(), StepRequest.class);
            request.validateAgainst(this.specs());
            this.unpausePhysicsForAction();
            try {
                session.beginStep(request.actions());
                this.pendingStep = new PendingStep(envelope.requestId(), future, this.controlTicks);
            } catch (final RuntimeException e) {
                this.pausePhysics();
                this.closeSession("step_begin_error");
                throw e;
            }
        }

        private void advancePendingStep() {
            final PendingStep pending = this.pendingStep;
            if (pending == null) {
                return;
            }
            if (this.ownedPhysicsSystem == null || this.ownedPhysicsSystem.getPaused()) {
                pending.future.complete(errorEnvelope(
                        pending.requestId,
                        "bridge lockstep physics clock was interrupted during STEP"));
                this.pendingStep = null;
                this.closeSession("lockstep_interrupted");
                return;
            }
            pending.actualActionTicks++;
            pending.ticksRemaining--;
            if (pending.ticksRemaining > 0) {
                return;
            }
            this.pausePhysics();
            try {
                final BridgeSession current = this.session;
                if (current == null) {
                    pending.future.complete(errorEnvelope(pending.requestId, "session closed while STEP was pending"));
                } else {
                    final StepResponse response = current.finishStep(this.specs(), pending.actualActionTicks);
                    pending.future.complete(new ProtocolEnvelope(
                            BridgeMessageType.STEP,
                            pending.requestId,
                            TrainingBridgeProtocol.toJsonTree(response)));
                }
            } catch (final RuntimeException e) {
                pending.future.complete(errorEnvelope(pending.requestId, e.getMessage()));
                this.pendingStep = null;
                this.closeSession("step_finish_error");
            } finally {
                this.pendingStep = null;
            }
        }

        private JsonObject setCurriculum(final JsonObject payload) {
            this.requireSession(payload);
            final String stage = DuopodTrainingScenarios.normalizeCurriculumStage(requireString(payload, "curriculumStage"));
            if (!DuopodTrainingScenarios.isSupportedBridgeCurriculumStage(stage)) {
                throw new IllegalArgumentException("unsupported curriculum stage " + stage);
            }
            this.curriculumStage = stage;
            this.session.setCurriculumStage(stage);
            this.session.clearManualOverrides();
            final JsonObject response = new JsonObject();
            response.addProperty("curriculumStage", stage);
            response.add("reset", resetPayload(this.session.resetAll()));
            return response;
        }

        private JsonObject setTargets(final JsonObject payload) {
            final BridgeSession current = this.requireSession(payload);
            if (payload.has("commands")) {
                current.setCommands(readCommands(payload));
                this.curriculumStage = DuopodTrainingScenarios.MANUAL_COMMANDS_STAGE;
                current.setCurriculumStage(DuopodTrainingScenarios.MANUAL_COMMANDS_STAGE);
            } else if (payload.has("targets")) {
                current.setPointTargets(readPointTargets(payload));
                this.curriculumStage = DuopodTrainingScenarios.MANUAL_POINT_GOALS_STAGE;
                current.setCurriculumStage(DuopodTrainingScenarios.MANUAL_POINT_GOALS_STAGE);
            } else {
                throw new IllegalArgumentException("SET_TARGETS requires commands or targets");
            }
            final JsonObject response = new JsonObject();
            response.addProperty("curriculumStage", this.curriculumStage);
            response.add("reset", resetPayload(current.resetAll()));
            return response;
        }

        private JsonObject updateTargets(final JsonObject payload) {
            final BridgeSession current = this.requireSession(payload);
            if (payload.has("commands")) {
                current.setCommands(readCommands(payload));
                this.curriculumStage = DuopodTrainingScenarios.MANUAL_COMMANDS_STAGE;
                current.setCurriculumStage(DuopodTrainingScenarios.MANUAL_COMMANDS_STAGE);
            } else if (payload.has("targets")) {
                current.setPointTargets(readPointTargets(payload));
                this.curriculumStage = DuopodTrainingScenarios.MANUAL_POINT_GOALS_STAGE;
                current.setCurriculumStage(DuopodTrainingScenarios.MANUAL_POINT_GOALS_STAGE);
            } else {
                throw new IllegalArgumentException("UPDATE_TARGETS requires commands or targets");
            }
            final JsonObject response = new JsonObject();
            response.addProperty("curriculumStage", this.curriculumStage);
            response.add("update", resetPayload(current.updateManualEpisodes()));
            return response;
        }

        private LocomotionCommand[] readCommands(final JsonObject payload) {
            final double[][] matrix = readMatrix(payload, "commands", this.slotCount, 3);
            final LocomotionCommand[] commands = new LocomotionCommand[this.slotCount];
            for (int slot = 0; slot < matrix.length; slot++) {
                commands[slot] = new LocomotionCommand(matrix[slot][0], matrix[slot][1], matrix[slot][2]);
            }
            return commands;
        }

        private PointTargetDefinition[] readPointTargets(final JsonObject payload) {
            final double[][] matrix = readMatrix(payload, "targets", this.slotCount, 2);
            final PointTargetDefinition[] targets = new PointTargetDefinition[this.slotCount];
            for (int slot = 0; slot < matrix.length; slot++) {
                targets[slot] = new PointTargetDefinition(
                        matrix[slot][0],
                        matrix[slot][1],
                        PointTargetCommandConfig.DEFAULT.successRadius());
            }
            return targets;
        }

        private BridgeSession requireSession(final JsonObject payload) {
            final BridgeSession current = this.session;
            if (current == null) {
                throw new IllegalArgumentException("no active bridge session");
            }
            final String requestedSession = requireString(payload, "sessionId");
            if (!current.sessionId().equals(requestedSession)) {
                throw new IllegalArgumentException("session id mismatch");
            }
            return current;
        }

        private void closeSession(final String reason) {
            if (this.pendingStep != null) {
                this.pendingStep.future.complete(errorEnvelope(this.pendingStep.requestId, "session closed: " + reason));
                this.pendingStep = null;
            }
            final BridgeSession closingSession = this.session;
            this.session = null;
            try {
                if (closingSession != null) {
                    closingSession.close();
                    MinecraftMachines.LOGGER.info("MM_TRAINING_BRIDGE_SESSION_CLOSE reason={}", reason);
                }
            } finally {
                this.releasePhysicsClock();
            }
        }

        private void acquirePhysicsClock(final ServerLevel level) {
            if (this.ownedPhysicsSystem != null) {
                throw new IllegalStateException("bridge already owns the physics clock");
            }
            final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
            if (physicsSystem == null) {
                throw new IllegalStateException("Sable physics system is unavailable for bridge dimension");
            }
            final boolean wasPaused = physicsSystem.getPaused();
            if (wasPaused) {
                throw new IllegalStateException("bridge requires an exclusive dimension with unpaused Sable physics");
            }
            this.ownedPhysicsSystem = physicsSystem;
            this.previousPhysicsPaused = wasPaused;
            physicsSystem.setPaused(true);
        }

        private void pausePhysics() {
            if (this.ownedPhysicsSystem == null) {
                throw new IllegalStateException("bridge does not own the physics clock");
            }
            this.ownedPhysicsSystem.setPaused(true);
        }

        private void unpausePhysicsForAction() {
            if (this.ownedPhysicsSystem == null) {
                throw new IllegalStateException("bridge does not own the physics clock");
            }
            if (!this.ownedPhysicsSystem.getPaused()) {
                throw new IllegalStateException("bridge physics must be paused before STEP");
            }
            this.ownedPhysicsSystem.setPaused(false);
        }

        private boolean physicsPaused() {
            return this.ownedPhysicsSystem != null && this.ownedPhysicsSystem.getPaused();
        }

        private void releasePhysicsClock() {
            if (this.ownedPhysicsSystem == null) {
                return;
            }
            this.ownedPhysicsSystem.setPaused(this.previousPhysicsPaused);
            this.ownedPhysicsSystem = null;
            this.previousPhysicsPaused = false;
        }
    }

    private static final class BridgeSession {
        private final String sessionId;
        private final int slotCount;
        private final int controlTicks;
        private final int maximumControlSteps;
        private final long seed;
        private final MinecraftMachinesDuopodTrainingMorphology morphology;
        private final LocomotionVectorEnvironment<MinecraftMachinesLiveDuopod> environment;
        private EnvironmentBatchReset lastReset;
        private String curriculumStage;
        private LocomotionCommand[] commands;
        private PointTargetDefinition[] pointTargets;
        private double[][] observations;
        private double[] lastRewards;
        private double[] episodeReturns;
        private long completedControlSteps;
        private int lastActualActionTicks;

        private BridgeSession(
                final String sessionId,
                final int slotCount,
                final int controlTicks,
                final int maximumControlSteps,
                final long seed,
                final MinecraftMachinesDuopodTrainingMorphology morphology,
                final String curriculumStage
        ) {
            this.sessionId = sessionId;
            this.slotCount = slotCount;
            this.controlTicks = controlTicks;
            this.maximumControlSteps = maximumControlSteps;
            this.seed = seed;
            this.morphology = morphology;
            this.curriculumStage = curriculumStage == null || curriculumStage.isBlank()
                    ? DuopodTrainingScenarios.defaultTrainingCurriculumStage()
                    : DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage);
            this.environment = new LocomotionVectorEnvironment<>(
                    morphology,
                    slotCount,
                    controlTicks,
                    ResetStrategy.RESPAWN,
                    this::episodeForSlot);
            this.observations = new double[slotCount][];
            this.lastRewards = new double[slotCount];
            this.episodeReturns = new double[slotCount];
        }

        static BridgeSession create(
                final ServerLevel level,
                final BlockPos origin,
                final Direction forwardDirection,
                final int slotCount,
                final int controlTicks,
                final int maximumControlSteps,
                final long seed,
                final String curriculumStage
        ) {
            final MinecraftMachinesDuopodTrainingMorphology morphology = new MinecraftMachinesDuopodTrainingMorphology(
                    level,
                    origin,
                    forwardDirection,
                    UUID.randomUUID(),
                    0);
            final BridgeSession session = new BridgeSession(
                    UUID.randomUUID().toString(),
                    slotCount,
                    controlTicks,
                    maximumControlSteps,
                    seed,
                    morphology,
                    curriculumStage);
            try {
                session.lastReset = session.resetAll();
                return session;
            } catch (final RuntimeException e) {
                session.close();
                throw e;
            }
        }

        String sessionId() {
            return this.sessionId;
        }

        EnvironmentBatchReset lastReset() {
            return this.lastReset;
        }

        EnvironmentBatchReset resetAll() {
            this.lastReset = this.environment.resetAll();
            this.observations = this.lastReset.observations();
            Arrays.fill(this.lastRewards, 0.0);
            Arrays.fill(this.episodeReturns, 0.0);
            return this.lastReset;
        }

        EnvironmentBatchReset resetMask(final boolean[] mask) {
            this.lastReset = this.environment.resetMask(mask);
            this.observations = this.lastReset.observations();
            for (int slot = 0; slot < mask.length; slot++) {
                if (mask[slot]) {
                    this.lastRewards[slot] = 0.0;
                    this.episodeReturns[slot] = 0.0;
                }
            }
            return this.lastReset;
        }

        void beginStep(final double[][] actions) {
            this.environment.beginStep(actions);
        }

        StepResponse finishStep(final BridgeSpecs specs, final int actualActionTicks) {
            if (actualActionTicks != this.controlTicks) {
                throw new IllegalStateException("expected " + this.controlTicks
                        + " action ticks but observed " + actualActionTicks);
            }
            final EnvironmentBatchStep step = this.environment.finishStep();
            final double[][] nextObservations = step.observations();
            final double[][] resetObservations = step.resetObservations();
            final double[] rewards = step.rewards();
            final boolean[] terminated = step.terminated();
            final boolean[] truncated = step.truncated();
            for (int slot = 0; slot < rewards.length; slot++) {
                this.lastRewards[slot] = rewards[slot];
                this.episodeReturns[slot] += rewards[slot];
                if ((terminated[slot] || truncated[slot]) && resetObservations[slot].length == specs.observationSpec().size()) {
                    nextObservations[slot] = resetObservations[slot];
                    this.episodeReturns[slot] = 0.0;
                }
            }
            this.observations = nextObservations;
            this.completedControlSteps++;
            this.lastActualActionTicks = actualActionTicks;
            final StepResponse response = new StepResponse(
                    nextObservations,
                    rewards,
                    terminated,
                    truncated,
                    stepInfosToJson(step.infos(), actualActionTicks),
                    resetObservations);
            response.validateAgainst(specs);
            return response;
        }

        void setCurriculumStage(final String curriculumStage) {
            this.curriculumStage = DuopodTrainingScenarios.normalizeCurriculumStage(curriculumStage);
        }

        void clearManualOverrides() {
            this.commands = null;
            this.pointTargets = null;
        }

        void setCommands(final LocomotionCommand[] commands) {
            if (commands.length != this.slotCount) {
                throw new IllegalArgumentException("command count must match slot count");
            }
            this.commands = Arrays.copyOf(commands, commands.length);
            this.pointTargets = null;
        }

        void setPointTargets(final PointTargetDefinition[] pointTargets) {
            if (pointTargets.length != this.slotCount) {
                throw new IllegalArgumentException("point target count must match slot count");
            }
            this.pointTargets = Arrays.copyOf(pointTargets, pointTargets.length);
            this.commands = null;
        }

        EnvironmentBatchReset updateManualEpisodes() {
            final EnvironmentBatchReset update = this.environment.updateEpisodes(this::manualEpisodeUpdate);
            this.observations = update.observations();
            return update;
        }

        JsonObject metricsPayload(final boolean physicsPaused) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("sessionId", this.sessionId);
            payload.addProperty("curriculumStage", this.curriculumStage);
            payload.addProperty("completedControlSteps", this.completedControlSteps);
            payload.addProperty("actual_action_ticks", this.lastActualActionTicks);
            payload.addProperty("bridge_lockstep", true);
            payload.addProperty("physics_paused", physicsPaused);
            payload.add("lastRewards", GSON.toJsonTree(this.lastRewards));
            payload.add("episodeReturns", GSON.toJsonTree(this.episodeReturns));
            payload.add("observations", GSON.toJsonTree(this.observations));
            return payload;
        }

        void close() {
            this.environment.close();
        }

        private EpisodeDefinition episodeForSlot(final int slot, final long episodeIndex) {
            final long episodeId = (slot * 1_000_000L) + episodeIndex;
            final long episodeSeed = this.seed + 65_537L * slot + episodeIndex;
            if (this.commands != null && slot < this.commands.length) {
                return new EpisodeDefinition(
                        episodeId,
                        episodeSeed,
                        dev.ahmedhamedi.minecraft_machines.content.training.api.EnvironmentTaskMode.COMMAND_TRACKING,
                        new CurriculumStage(this.curriculumStage, 0, "manual bridge command"),
                        this.commands[slot],
                        this.maximumControlSteps,
                        0.0,
                        DuopodTrainingScenarios.DEFAULT_GAIT_FREQUENCY_HZ,
                        TerrainProfile.none());
            }
            if (this.pointTargets != null && slot < this.pointTargets.length) {
                return DuopodTrainingScenarios.manualPointGoalEpisode(
                        episodeId,
                        episodeSeed,
                        this.maximumControlSteps,
                        this.pointTargets[slot]);
            }
            final String sampledStage = DuopodTrainingScenarios.isTrainingCurriculumStage(this.curriculumStage)
                    ? this.curriculumStage
                    : DuopodTrainingScenarios.defaultTrainingCurriculumStage();
            final int scenarioCount = DuopodTrainingScenarios.scenarioCount(sampledStage);
            final int episodeScenarioCycle = (int) Math.floorMod(episodeIndex, scenarioCount);
            final int scenario = DuopodTrainingScenarios.rollingTrainingScenario(
                    sampledStage,
                    episodeScenarioCycle,
                    Math.floorMod(slot, scenarioCount),
                    this.slotCount);
            return DuopodTrainingScenarios.episodeForStage(
                    sampledStage,
                    episodeId,
                    episodeSeed,
                    episodeScenarioCycle,
                    this.maximumControlSteps,
                    scenario);
        }

        private EpisodeDefinition manualEpisodeUpdate(final int slot, final EpisodeDefinition current) {
            if (this.commands != null && slot < this.commands.length) {
                return new EpisodeDefinition(
                        current.episodeId(),
                        current.seed(),
                        EnvironmentTaskMode.COMMAND_TRACKING,
                        new CurriculumStage(
                                DuopodTrainingScenarios.MANUAL_COMMANDS_STAGE,
                                current.curriculumStage().index(),
                                "manual bridge command update"),
                        this.commands[slot],
                        current.maximumControlSteps(),
                        0.0,
                        current.gaitFrequencyHz(),
                        current.terrainProfile());
            }
            if (this.pointTargets != null && slot < this.pointTargets.length) {
                final PointTargetDefinition target = this.pointTargets[slot];
                final LocomotionCommand command = PointTargetCommandMath.commandForLocalOffset(
                        PointTargetCommandConfig.DEFAULT,
                        target.localForwardBlocks(),
                        target.localRightBlocks());
                return new EpisodeDefinition(
                        current.episodeId(),
                        current.seed(),
                        EnvironmentTaskMode.POINT_GOAL,
                        new CurriculumStage(
                                DuopodTrainingScenarios.MANUAL_POINT_GOALS_STAGE,
                                current.curriculumStage().index(),
                                "manual bridge point goal update"),
                        command,
                        current.maximumControlSteps(),
                        target.initialDistanceBlocks(),
                        current.gaitFrequencyHz(),
                        current.terrainProfile(),
                        target);
            }
            throw new IllegalStateException("manual episode update requires commands or point targets");
        }
    }

    private static final class PendingStep {
        private final String requestId;
        private final CompletableFuture<ProtocolEnvelope> future;
        private int ticksRemaining;
        private int actualActionTicks;

        private PendingStep(
                final String requestId,
                final CompletableFuture<ProtocolEnvelope> future,
                final int ticksRemaining
        ) {
            this.requestId = requestId;
            this.future = future;
            this.ticksRemaining = ticksRemaining;
        }
    }

    private static final class ClientConnection implements Closeable {
        private final BridgeServer bridge;
        private final Socket socket;
        private final Thread thread;

        private ClientConnection(final BridgeServer bridge, final Socket socket) {
            this.bridge = bridge;
            this.socket = socket;
            this.thread = new Thread(this::run, "Minecraft Machines Training Bridge Client");
            this.thread.setDaemon(true);
        }

        void start() {
            this.thread.start();
        }

        @Override
        public void close() {
            closeQuietly(this.socket);
        }

        private void run() {
            try (DataInputStream input = new DataInputStream(this.socket.getInputStream());
                 DataOutputStream output = new DataOutputStream(this.socket.getOutputStream())) {
                while (!this.bridge.stopped()) {
                    final ProtocolEnvelope request;
                    try {
                        request = readEnvelope(input);
                    } catch (final EOFException e) {
                        return;
                    }
                    final ProtocolEnvelope response = this.bridge.submit(request)
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    writeEnvelope(output, response);
                }
            } catch (final Exception e) {
                if (!this.bridge.stopped()) {
                    MinecraftMachines.LOGGER.info("MM_TRAINING_BRIDGE_CLIENT_CLOSE reason={}", e.getMessage());
                }
            } finally {
                this.bridge.onClientDisconnected(this);
                close();
            }
        }

        private static ProtocolEnvelope readEnvelope(final DataInputStream input) throws IOException {
            final int length = input.readInt();
            if (length < 0 || length > TrainingBridgeProtocol.DEFAULT_MAX_MESSAGE_BYTES) {
                throw new IOException("invalid bridge frame length " + length);
            }
            final byte[] payload = new byte[length];
            input.readFully(payload);
            return TrainingBridgeProtocol.decodeEnvelope(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
        }

        private static void writeEnvelope(final DataOutputStream output, final ProtocolEnvelope envelope) throws IOException {
            final byte[] payload = TrainingBridgeProtocol.encodeLengthPrefixed(
                    TrainingBridgeProtocol.encodeEnvelope(envelope),
                    TrainingBridgeProtocol.DEFAULT_MAX_MESSAGE_BYTES);
            output.write(payload);
            output.flush();
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
        }
    }
}
