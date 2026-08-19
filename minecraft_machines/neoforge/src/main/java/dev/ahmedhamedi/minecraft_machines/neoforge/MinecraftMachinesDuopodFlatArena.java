package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A temporary, controlled set of identical flat lanes for Duopod training and
 * evaluation.
 *
 * <p>The old experiment searched arbitrary nearby world terrain independently
 * for every slot. That made controller identity inseparable from spawn-site
 * geometry. This lease replaces the bounded corridor with identical smooth
 * stone lanes, remembers every changed block, and restores the world when the
 * task closes. Block-entity positions are rejected before any mutation because
 * a {@link BlockState} snapshot cannot preserve their data safely.</p>
 */
final class MinecraftMachinesDuopodFlatArena implements AutoCloseable {
    static final String ARENA_ID = "minecraft_machines:temporary_flat_duopod_lane_groups_v2";
    static final String SLOT_LAYOUT_ID = "minecraft_machines:isolated_three_lane_groups_v1";
    static final int LANE_HALF_WIDTH_BLOCKS = 5;
    static final int MINIMUM_SLOT_SPACING_BLOCKS = 12;
    static final int LANES_PER_CANDIDATE_GROUP = 3;

    private static final String RECOVERY_JOURNAL_FORMAT = "minecraft_machines_duopod_flat_arena_recovery_v1";
    private static final int RECOVERY_JOURNAL_FORMAT_VERSION = 1;
    private static final String RECOVERY_DIRECTORY = "duopod_arena_recovery";
    private static final String RECOVERY_FILE_PREFIX = "arena_";
    private static final String RECOVERY_FILE_SUFFIX = ".nbt";
    private static final int MAXIMUM_RECOVERY_ENTRIES = 2_000_000;
    private static final int SUPPORT_BELOW_REQUESTED_ORIGIN_BLOCKS = 1;
    private static final int CLEAR_HEIGHT_BLOCKS = 10;
    private static final int BACKWARD_RUNOUT_BLOCKS = 8;
    private static final int FORWARD_RUNOUT_PADDING_BLOCKS = 12;
    private static final double MAXIMUM_COMMAND_SPEED_BLOCKS_PER_SECOND = 1.1;
    private static final int SET_BLOCK_FLAGS = 3;
    private static final BlockState FLOOR = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ServerLevel level;
    private final BlockPos morphologyOrigin;
    private final Direction forwardDirection;
    private final Direction rightDirection;
    private final int slotCount;
    private final int spacingBlocks;
    private final int supportY;
    private final int minimumForwardOffset;
    private final int maximumForwardOffset;
    private final LinkedHashMap<BlockPos, BlockState> originalStates;
    private final Path recoveryJournalPath;
    private boolean closed;

    private MinecraftMachinesDuopodFlatArena(
            final ServerLevel level,
            final BlockPos morphologyOrigin,
            final Direction forwardDirection,
            final int slotCount,
            final int spacingBlocks,
            final int maximumControlSteps,
            final int controlTicks
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.morphologyOrigin = Objects.requireNonNull(morphologyOrigin, "morphologyOrigin").immutable();
        this.forwardDirection = Objects.requireNonNull(forwardDirection, "forwardDirection");
        if (forwardDirection.getAxis().isVertical()) {
            throw new IllegalArgumentException("flat arena direction must be horizontal");
        }
        if (slotCount < 1 || maximumControlSteps < 1 || controlTicks < 1) {
            throw new IllegalArgumentException("slotCount, maximumControlSteps, and controlTicks must be positive");
        }
        if (spacingBlocks < MINIMUM_SLOT_SPACING_BLOCKS) {
            throw new IllegalArgumentException("flat arena spacing must be at least " + MINIMUM_SLOT_SPACING_BLOCKS + " blocks");
        }
        this.rightDirection = forwardDirection.getClockWise();
        this.slotCount = slotCount;
        this.spacingBlocks = spacingBlocks;
        this.supportY = morphologyOrigin.getY() - SUPPORT_BELOW_REQUESTED_ORIGIN_BLOCKS;
        if (this.supportY < level.getMinBuildHeight()
                || this.supportY + CLEAR_HEIGHT_BLOCKS >= level.getMaxBuildHeight()) {
            throw new IllegalArgumentException("flat arena vertical bounds are outside the dimension build height");
        }
        this.minimumForwardOffset = -BACKWARD_RUNOUT_BLOCKS;
        this.maximumForwardOffset = (int) Math.ceil(
                MAXIMUM_COMMAND_SPEED_BLOCKS_PER_SECOND * maximumControlSteps * controlTicks / 20.0)
                + FORWARD_RUNOUT_PADDING_BLOCKS;
        ensureNoOutstandingRecoveryJournals(level.getServer());
        final PreparedArena preparedArena = this.prepareTransactional();
        this.originalStates = preparedArena.originalStates();
        this.recoveryJournalPath = preparedArena.recoveryJournalPath();
    }

    static MinecraftMachinesDuopodFlatArena prepare(
            final ServerLevel level,
            final BlockPos morphologyOrigin,
            final Direction forwardDirection,
            final int slotCount,
            final int spacingBlocks,
            final int maximumControlSteps,
            final int controlTicks
    ) {
        return new MinecraftMachinesDuopodFlatArena(
                level,
                morphologyOrigin,
                forwardDirection,
                slotCount,
                spacingBlocks,
                maximumControlSteps,
                controlTicks);
    }

    BlockPos morphologyOrigin() {
        return this.morphologyOrigin;
    }

    int slotSpacingBlocks() {
        return this.spacingBlocks;
    }

    int slotCount() {
        return this.slotCount;
    }

    int supportY() {
        return this.supportY;
    }

    int minimumForwardOffset() {
        return this.minimumForwardOffset;
    }

    int maximumForwardOffset() {
        return this.maximumForwardOffset;
    }

    int changedBlockCount() {
        return this.originalStates.size();
    }

    Path recoveryJournalPath() {
        return this.recoveryJournalPath;
    }

    BlockPos requestedOriginForSlot(final int slot) {
        this.checkSlot(slot);
        return this.morphologyOrigin.relative(
                this.rightDirection,
                rightOffsetForSlot(slot, this.spacingBlocks));
    }

    BlockPos expectedSpawnCenterForSlot(final int slot) {
        return this.requestedOriginForSlot(slot).atY(this.supportY + 4);
    }

    private PreparedArena prepareTransactional() {
        final LinkedHashMap<BlockPos, BlockState> targets = new LinkedHashMap<>();
        for (int slot = 0; slot < this.slotCount; slot++) {
            final BlockPos laneOrigin = this.requestedOriginForSlot(slot);
            for (int forward = this.minimumForwardOffset; forward <= this.maximumForwardOffset; forward++) {
                for (int right = -LANE_HALF_WIDTH_BLOCKS; right <= LANE_HALF_WIDTH_BLOCKS; right++) {
                    final BlockPos column = laneOrigin
                            .relative(this.forwardDirection, forward)
                            .relative(this.rightDirection, right);
                    targets.put(column.atY(this.supportY), FLOOR);
                    for (int dy = 1; dy <= CLEAR_HEIGHT_BLOCKS; dy++) {
                        targets.put(column.atY(this.supportY + dy), AIR);
                    }
                }
            }
        }

        final LinkedHashMap<BlockPos, BlockState> originals = new LinkedHashMap<>();
        for (final Map.Entry<BlockPos, BlockState> target : targets.entrySet()) {
            final BlockPos pos = target.getKey();
            final BlockState original = this.level.getBlockState(pos);
            if (original.equals(target.getValue())) {
                continue;
            }
            if (this.level.getBlockEntity(pos) != null) {
                throw new IllegalStateException("Controlled Duopod arena would overwrite a block entity at " + pos.toShortString());
            }
            originals.put(pos, original);
        }

        final Path journalPath = writeRecoveryJournal(this.level, originals);
        final List<BlockPos> applied = new ArrayList<>(originals.size());
        try {
            for (final BlockPos pos : originals.keySet()) {
                final BlockState target = targets.get(pos);
                if (!this.level.setBlock(pos, target, SET_BLOCK_FLAGS) && !this.level.getBlockState(pos).equals(target)) {
                    throw new IllegalStateException("Could not prepare controlled Duopod arena block at " + pos.toShortString());
                }
                applied.add(pos);
            }
            return new PreparedArena(originals, journalPath);
        } catch (final RuntimeException e) {
            RuntimeException rollbackFailure = null;
            for (int i = applied.size() - 1; i >= 0; i--) {
                final BlockPos pos = applied.get(i);
                try {
                    final BlockState original = originals.get(pos);
                    if (!this.level.setBlock(pos, original, SET_BLOCK_FLAGS)
                            && !this.level.getBlockState(pos).equals(original)) {
                        rollbackFailure = new IllegalStateException(
                                "Could not roll back controlled Duopod arena block at " + pos.toShortString());
                    }
                } catch (final RuntimeException rollbackException) {
                    rollbackFailure = rollbackException;
                }
            }
            if (rollbackFailure == null) {
                try {
                    deleteRecoveryJournal(journalPath);
                } catch (final RuntimeException deleteFailure) {
                    e.addSuppressed(deleteFailure);
                }
            } else {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    private void checkSlot(final int slot) {
        if (slot < 0 || slot >= this.slotCount) {
            throw new IndexOutOfBoundsException("slot " + slot + " outside 0.." + (this.slotCount - 1));
        }
    }

    /**
     * Walk-forward evaluates one candidate on exactly three speed lanes. Insert
     * one unused lane-width between candidate groups so every candidate sees
     * the same local three-lane topology as the fixed benchmark instead of an
     * interior position in one almost-contiguous 24-lane floor.
     */
    static int rightOffsetForSlot(final int slot, final int spacingBlocks) {
        if (slot < 0 || spacingBlocks < MINIMUM_SLOT_SPACING_BLOCKS) {
            throw new IllegalArgumentException("slot must be non-negative and spacing must satisfy the arena minimum");
        }
        return Math.multiplyExact(slot + slot / LANES_PER_CANDIDATE_GROUP, spacingBlocks);
    }

    static int maximumRightOffsetForSlots(final int slotCount, final int spacingBlocks) {
        if (slotCount < 1) {
            throw new IllegalArgumentException("slotCount must be positive");
        }
        return rightOffsetForSlot(slotCount - 1, spacingBlocks);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        final List<Map.Entry<BlockPos, BlockState>> entries = new ArrayList<>(this.originalStates.entrySet());
        IllegalStateException firstFailure = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            final Map.Entry<BlockPos, BlockState> entry = entries.get(i);
            if (!this.level.setBlock(entry.getKey(), entry.getValue(), SET_BLOCK_FLAGS)
                    && !this.level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                if (firstFailure == null) {
                    firstFailure = new IllegalStateException("Could not restore controlled Duopod arena block at "
                            + entry.getKey().toShortString());
                }
            }
        }
        if (firstFailure != null) {
            // Leave the lease retryable. Every position was attempted, and a
            // later close can retry any block the world rejected transiently.
            throw firstFailure;
        }
        deleteRecoveryJournal(this.recoveryJournalPath);
        this.closed = true;
        this.originalStates.clear();
    }

    static void recoverOutstanding(final ServerStartedEvent event) {
        final MinecraftServer server = event.getServer();
        try {
            final List<Path> journals = outstandingRecoveryJournals(server);
            for (final Path journal : journals) {
                recoverJournal(server, journal);
            }
        } catch (final RuntimeException | IOException e) {
            MinecraftMachines.LOGGER.error(
                    "Could not recover a temporary Duopod arena. Training remains fail-closed and the journal is retained.",
                    e);
            throw new IllegalStateException("Could not recover a temporary Duopod arena", e);
        }
    }

    private static Path writeRecoveryJournal(
            final ServerLevel level,
            final LinkedHashMap<BlockPos, BlockState> originals
    ) {
        if (originals.isEmpty()) {
            return null;
        }
        final CompoundTag root = new CompoundTag();
        root.putString("format", RECOVERY_JOURNAL_FORMAT);
        root.putInt("format_version", RECOVERY_JOURNAL_FORMAT_VERSION);
        root.putString("dimension", level.dimension().location().toString());
        root.putLong("created_at_epoch_millis", System.currentTimeMillis());
        final ListTag entries = new ListTag();
        for (final Map.Entry<BlockPos, BlockState> original : originals.entrySet()) {
            final CompoundTag entry = new CompoundTag();
            entry.putLong("position", original.getKey().asLong());
            entry.put("block_state", NbtUtils.writeBlockState(original.getValue()));
            entries.add(entry);
        }
        root.put("entries", entries);

        final Path directory = recoveryDirectory(level.getServer());
        final Path destination = directory.resolve(
                RECOVERY_FILE_PREFIX + UUID.randomUUID() + RECOVERY_FILE_SUFFIX);
        Path temporary = null;
        try {
            createRecoveryDirectory(directory);
            final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, encoded);
            temporary = Files.createTempFile(directory, ".arena_", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                final ByteBuffer bytes = ByteBuffer.wrap(encoded.toByteArray());
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            moveAtomically(temporary, destination);
            forceDirectory(directory);
            return destination;
        } catch (final IOException e) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (final IOException deleteFailure) {
                    e.addSuppressed(deleteFailure);
                }
            }
            throw new IllegalStateException(
                    "Could not persist the controlled Duopod arena recovery journal before mutation",
                    e);
        }
    }

    private static void recoverJournal(final MinecraftServer server, final Path journalPath) throws IOException {
        final CompoundTag root = NbtIo.readCompressed(journalPath, NbtAccounter.unlimitedHeap());
        if (!RECOVERY_JOURNAL_FORMAT.equals(root.getString("format"))
                || root.getInt("format_version") != RECOVERY_JOURNAL_FORMAT_VERSION) {
            throw new IOException("Unsupported Duopod arena recovery journal " + journalPath.getFileName());
        }
        final String dimension = root.getString("dimension");
        ServerLevel level = null;
        for (final ServerLevel candidate : server.getAllLevels()) {
            if (candidate.dimension().location().toString().equals(dimension)) {
                level = candidate;
                break;
            }
        }
        if (level == null) {
            throw new IOException("Recovery journal references an unavailable dimension: " + dimension);
        }
        final ListTag entries = root.getList("entries", Tag.TAG_COMPOUND);
        if (entries.isEmpty() || entries.size() > MAXIMUM_RECOVERY_ENTRIES) {
            throw new IOException("Recovery journal entry count is outside the safe range: " + entries.size());
        }
        final LinkedHashMap<BlockPos, BlockState> originals = new LinkedHashMap<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            final CompoundTag entry = entries.getCompound(index);
            final CompoundTag encodedState = entry.getCompound("block_state");
            if (!entry.contains("position", Tag.TAG_LONG)
                    || !encodedState.contains("Name", Tag.TAG_STRING)) {
                throw new IOException("Recovery journal contains a malformed block entry at index " + index);
            }
            final BlockPos position = BlockPos.of(entry.getLong("position")).immutable();
            final BlockState state = NbtUtils.readBlockState(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK),
                    encodedState);
            if (originals.put(position, state) != null) {
                throw new IOException("Recovery journal contains duplicate position " + position.toShortString());
            }
        }
        final List<Map.Entry<BlockPos, BlockState>> ordered = new ArrayList<>(originals.entrySet());
        for (int index = ordered.size() - 1; index >= 0; index--) {
            final Map.Entry<BlockPos, BlockState> original = ordered.get(index);
            if (!level.setBlock(original.getKey(), original.getValue(), SET_BLOCK_FLAGS)
                    && !level.getBlockState(original.getKey()).equals(original.getValue())) {
                throw new IOException(
                        "Could not recover controlled Duopod arena block at "
                                + original.getKey().toShortString());
            }
        }
        deleteRecoveryJournal(journalPath);
        MinecraftMachines.LOGGER.info(
                "Recovered {} original blocks from temporary Duopod arena journal {}",
                originals.size(),
                journalPath.getFileName());
    }

    private static void ensureNoOutstandingRecoveryJournals(final MinecraftServer server) {
        try {
            final List<Path> journals = outstandingRecoveryJournals(server);
            if (!journals.isEmpty()) {
                throw new IllegalStateException(
                        "Unrecovered Duopod arena journal exists: " + journals.getFirst().getFileName());
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Could not inspect Duopod arena recovery journals", e);
        }
    }

    private static List<Path> outstandingRecoveryJournals(final MinecraftServer server) throws IOException {
        final Path directory = recoveryDirectory(server);
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return paths
                    .filter(path -> path.getFileName().toString().startsWith(RECOVERY_FILE_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(RECOVERY_FILE_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static Path recoveryDirectory(final MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("minecraft_machines")
                .resolve(RECOVERY_DIRECTORY);
    }

    private static void createRecoveryDirectory(final Path directory) throws IOException {
        if (Files.exists(directory)) {
            return;
        }
        Files.createDirectories(directory);
        final Path parent = directory.getParent();
        if (parent != null) {
            forceDirectory(parent);
        }
        forceDirectory(directory);
    }

    private static void moveAtomically(final Path source, final Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }

    private static void deleteRecoveryJournal(final Path journalPath) {
        if (journalPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(journalPath);
            final Path parent = journalPath.getParent();
            if (parent != null) {
                forceDirectory(parent);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Could not durably remove restored Duopod arena recovery journal "
                            + journalPath.getFileName(),
                    e);
        }
    }

    private static void forceDirectory(final Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private record PreparedArena(
            LinkedHashMap<BlockPos, BlockState> originalStates,
            Path recoveryJournalPath
    ) {
    }
}
