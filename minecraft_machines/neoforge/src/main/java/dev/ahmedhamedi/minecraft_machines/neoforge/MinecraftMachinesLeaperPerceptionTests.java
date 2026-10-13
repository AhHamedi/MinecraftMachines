package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native voxel and Player fixtures for the same sensing functions used by live Leapers. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperPerceptionTests {
    @GameTest(template = "empty", timeoutTicks = 200, batch = "leaperBehaviourPerception")
    public static void minecraftPerceptionAndMemory(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals("predator-behaviour")) {
            helper.succeed();
            return;
        }
        var fixture = new Fixture(helper);
        try {
            fixture.geometry();
            fixture.signalRules();
            fixture.perceptionAndMemory();
            fixture.prepareMovingWall();
        } catch (RuntimeException | Error failure) {
            fixture.restore();
            throw failure;
        }
        // Assembly updates local block storage synchronously; Sable publishes the new
        // body's world bounds during its tick. Let that spatial index become observable.
        helper.runAfterDelay(4, () -> {
            try {
                fixture.movingWallChecks();
            } finally {
                fixture.restore();
            }
            var failures = fixture.rows.stream().filter(row -> !Boolean.TRUE.equals(row.get("success")))
                    .map(row -> row.get("name")).toList();
            try {
                MinecraftMachinesLeaperLearning.write(
                        "predator-behaviour-perception", Map.of("validated", failures.isEmpty(), "restored", true, "cases", fixture.rows));
            } catch (Exception error) {
                throw new IllegalStateException("Could not write perception report", error);
            }
            helper.assertTrue(failures.isEmpty(), "Perception failures: " + failures);
            helper.succeed();
        });
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos origin;
        final Map<BlockPos, BlockState> saved = new LinkedHashMap<>();
        final List<Map<String, Object>> rows = new ArrayList<>();
        ServerSubLevel movingWall;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            // GameTest randomly places its marker millions of blocks away. Keep native
            // sub-block collision checks near the other fixed physics arenas so float
            // coordinate precision cannot move the one-block acoustic wall off its ray.
            origin = new BlockPos(3400, 40, 3400);
        }

        void put(int x, int y, int z, BlockState state) {
            var pos = origin.offset(x, y, z);
            // Only fixture setup loads chunks. Production sensory reads never do.
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            saved.putIfAbsent(pos, level.getBlockState(pos));
            level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) put(x, y, z, state);
        }

        void room() {
            fill(0, 0, 0, 12, 8, 12, Blocks.STONE.defaultBlockState());
            fill(1, 1, 1, 11, 7, 11, Blocks.AIR.defaultBlockState());
        }

        void wall(int x) { fill(x, 1, 1, x, 7, 11, Blocks.STONE.defaultBlockState()); }

        Vec3 p(double x, double y, double z) {
            return new Vec3(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
        }

        void check(String name, boolean passed) { rows.add(Map.of("name", name, "success", passed)); }

        void geometry() {
            room();
            var from = p(3.5, 3.5, 3.5);
            var to = p(9.5, 3.5, 3.5);
            check("open-air-visible", MinecraftMachinesLeaperPerception.visible(level, from, to));
            check("open-air-audible", MinecraftMachinesLeaperPerception.soundPathDistance(level, from, to, 18) == 6);
            check("diagonal-footsteps-use-open-air-distance", MinecraftMachinesLeaperPerception.soundPathDistance(
                    level, p(3.5, 4.5, 3.5), p(9.5, 3.5, 8.5), 10) == 8);
            wall(6);
            check("stone-wall-blocks-vision", !MinecraftMachinesLeaperPerception.visible(level, from, to));
            check("sealed-wall-blocks-hearing", MinecraftMachinesLeaperPerception.soundPathDistance(level, from, to, 18) < 0);
            fill(6, 3, 7, 6, 4, 7, Blocks.AIR.defaultBlockState());
            check("around-corner-still-unseen", !MinecraftMachinesLeaperPerception.visible(level, from, to));
            check("around-corner-short-air-route-audible", MinecraftMachinesLeaperPerception.soundPathDistance(level, from, to, 18) == 14);
            check("quiet-footstep-cannot-follow-long-detour", MinecraftMachinesLeaperPerception.soundPathDistance(level, from, to, 10) < 0);

            room();
            fill(1, 4, 1, 11, 4, 11, Blocks.STONE.defaultBlockState());
            var above = p(6.5, 5.5, 6.5);
            var cave = p(6.5, 2.5, 6.5);
            check("nearby-cave-under-roof-unseen", !MinecraftMachinesLeaperPerception.visible(level, above, cave));
            check("nearby-cave-under-roof-inaudible", MinecraftMachinesLeaperPerception.soundPathDistance(level, above, cave, 18) < 0);

            room();
            put(6, 3, 3, Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            check("slab-solid-half-blocks-sight", !MinecraftMachinesLeaperPerception.visible(level, p(3.5, 3.25, 3.5), p(9.5, 3.25, 3.5)));
            check("slab-empty-half-permits-sight", MinecraftMachinesLeaperPerception.visible(level, p(3.5, 3.75, 3.5), p(9.5, 3.75, 3.5)));
            fill(1, 1, 1, 11, 7, 11, Blocks.STONE.defaultBlockState());
            fill(3, 3, 3, 9, 3, 3, Blocks.AIR.defaultBlockState());
            var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
            put(6, 3, 3, door);
            check("closed-door-blocks-hearing", MinecraftMachinesLeaperPerception.soundPathDistance(level, from, to, 18) < 0);
            put(6, 3, 3, door.setValue(DoorBlock.OPEN, true));
            check("open-door-connects-hearing", MinecraftMachinesLeaperPerception.soundPathDistance(level, from, to, 18) == 6);
            put(6, 3, 3, Blocks.WATER.defaultBlockState());
            check("water-does-not-relay-air-footsteps", MinecraftMachinesLeaperPerception.soundPathDistance(level, from, to, 18) < 0);

        }

        void prepareMovingWall() {
            room();
            fill(1, 1, 1, 11, 7, 11, Blocks.STONE.defaultBlockState());
            fill(3, 3, 3, 9, 3, 3, Blocks.AIR.defaultBlockState());
            // Detach a real physical wall into Sable's storage. Its world cell becomes air,
            // so this catches false hearing through moving geometry that cell search misses.
            put(6, 3, 3, Blocks.STONE.defaultBlockState());
            var position = origin.offset(6, 3, 3);
            var positions = List.of(position);
            movingWall = SubLevelAssemblyHelper.assembleBlocks(level, position, positions, BoundingBox3i.from(positions));
            if (movingWall == null) throw new IllegalStateException("Sable refused acoustic wall fixture");
        }

        void movingWallChecks() {
            var from = p(3.5, 3.5, 3.5);
            var to = p(9.5, 3.5, 3.5);
            check("physical-wall-world-cell-is-air", level.getBlockState(origin.offset(6, 3, 3)).isAir());
            check("physical-sublevel-wall-blocks-sight", !MinecraftMachinesLeaperPerception.visible(level, from, to));
            check("physical-sublevel-wall-blocks-hearing", MinecraftMachinesLeaperPerception.soundPathDistance(level, from, to, 18) < 0);
        }

        void signalRules() {
            var zero = Vec3.ZERO;
            var front = new Vec3(1, 0, 0);
            check("rear-target-outside-visual-cone", MinecraftMachinesLeaperPerception.visionStrength(zero, front, new Vec3(-8, 0, 0), 15, false, false, 2) == 0);
            check("darkness-reduces-visual-range", MinecraftMachinesLeaperPerception.visionStrength(zero, front, new Vec3(20, 0, 0), 0, false, false, 2) == 0
                    && MinecraftMachinesLeaperPerception.visionStrength(zero, front, new Vec3(20, 0, 0), 15, false, false, 2) > 0);
            check("crouching-reduces-visual-range", MinecraftMachinesLeaperPerception.visionStrength(zero, front, new Vec3(30, 0, 0), 15, true, false, 2) == 0
                    && MinecraftMachinesLeaperPerception.visionStrength(zero, front, new Vec3(30, 0, 0), 15, false, false, 2) > 0);
            check("invisibility-prevents-visual-lock", MinecraftMachinesLeaperPerception.visionStrength(zero, front, front, 15, false, true, 2) == 0);
            check("no-vision-without-visible-sample", MinecraftMachinesLeaperPerception.visionStrength(zero, front, front, 15, false, false, 0) == 0);
            check("stationary-sprint-is-not-a-footstep", !MinecraftMachinesLeaperPerception.makesMovementNoise(zero, zero, 4, false, true));
            check("walking-generates-footsteps", MinecraftMachinesLeaperPerception.makesMovementNoise(zero, front, 4, false, true));
            check("sneaking-generates-no-footsteps", !MinecraftMachinesLeaperPerception.makesMovementNoise(zero, front, 4, true, true));
            check("airborne-motion-is-not-footsteps", !MinecraftMachinesLeaperPerception.makesMovementNoise(zero, front, 4, false, false));
            check("teleport-is-not-footsteps", !MinecraftMachinesLeaperPerception.makesMovementNoise(zero, new Vec3(40, 0, 0), 4, false, true));
            var survival = helper.makeMockPlayer(GameType.SURVIVAL);
            var adventure = helper.makeMockPlayer(GameType.ADVENTURE);
            var creative = helper.makeMockPlayer(GameType.CREATIVE);
            check("survival-and-adventure-eligible", MinecraftMachinesLeaperPerception.eligible(survival, null) && MinecraftMachinesLeaperPerception.eligible(adventure, null));
            check("creative-not-passively-targeted", !MinecraftMachinesLeaperPerception.eligible(creative, null));
            check("explicit-creative-hunt-supported", MinecraftMachinesLeaperPerception.eligible(creative, creative.getUUID()));
            check("spectator-never-targeted", !MinecraftMachinesLeaperPerception.eligible(helper.makeMockPlayer(GameType.SPECTATOR), null));
            survival.setHealth(0);
            check("dead-player-not-targeted", !MinecraftMachinesLeaperPerception.eligible(survival, null));
        }

        void perceptionAndMemory() {
            room();
            var eye = p(3.5, 3.5, 3.5);
            var front = new Vec3(1, 0, 0);
            var body = point(eye);
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setPos(p(7.5, 2, 3.5));
            var brain = new LeaperBehaviour(body);
            var sight = MinecraftMachinesLeaperPerception.sense(level, null, eye, front, player, false);
            check("native-player-produces-visible-evidence", sight != null && sight.visible());
            for (int tick = 0; tick <= 16; tick += 4) brain.tick(tick, body, sight, false, false, false);
            check("native-confirmed-sight-enters-hunt", brain.mode() == LeaperBehaviour.Mode.HUNTING);
            var remembered = brain.destination();
            wall(5);
            player.setPos(p(9.5, 2, 5.5));
            var hidden = MinecraftMachinesLeaperPerception.sense(level, null, eye, front, player, false);
            check("native-player-behind-wall-provides-no-evidence", hidden == null);
            for (int tick = 20; tick <= 16 + LeaperBehaviour.LOST_SIGHT_TICKS; tick += 4) brain.tick(tick, body, hidden, false, false, false);
            check("lost-native-sight-downgrades-to-alert", brain.mode() == LeaperBehaviour.Mode.ALERT);
            check("unseen-player-movement-does-not-change-last-seen-position", remembered.equals(brain.destination()));

            room();
            wall(6);
            fill(6, 3, 7, 6, 4, 7, Blocks.AIR.defaultBlockState());
            player.setPos(p(9.5, 3, 3.5));
            // Only sprint footsteps have sufficient range for the 14-block acoustic detour.
            player.setSprinting(true);
            var sound = MinecraftMachinesLeaperPerception.sense(level, null, eye, front, player, true);
            check("native-footstep-around-corner-is-heard-unseen", sound != null && !sound.visible());
            check("native-sound-location-is-coarse", sound != null && !sound.position().equals(point(player.position())));
            brain = new LeaperBehaviour(body);
            for (int tick = 0; tick < LeaperBehaviour.SEARCH_TICKS; tick += 4) brain.tick(tick, body, sound, false, false, false);
            check("repeated-native-footsteps-alert-without-hunting", brain.mode() == LeaperBehaviour.Mode.ALERT);
            brain.tick(LeaperBehaviour.SEARCH_TICKS, body, sound, false, false, false);
            check("fruitless-acoustic-search-eventually-returns-to-patrol", brain.mode() == LeaperBehaviour.Mode.PATROLLING);
        }

        static LeaperBehaviour.Point point(Vec3 p) { return new LeaperBehaviour.Point(p.x, p.y, p.z); }

        void restore() {
            if (movingWall != null) {
                var container = SubLevelContainer.getContainer(level);
                if (!movingWall.isRemoved()) container.removeSubLevel(movingWall, SubLevelRemovalReason.REMOVED);
                container.processSubLevelRemovals();
                movingWall = null;
            }
            saved.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        }
    }
}
