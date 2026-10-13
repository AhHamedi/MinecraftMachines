package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Mode;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperPartBlock;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native rig replay: replicated eyes, physical patrol, and finite pursuit with no live prey. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperBehaviourIntegrationTests {
    static final String MODE = "predator-behaviour";
    static final int COORDINATOR_START = 28, HUNT_TICK = 200;
    static final int END = HUNT_TICK + LeaperBehaviour.LOST_SIGHT_TICKS
            + LeaperBehaviour.SEARCH_TICKS + 40;
    static final double PATROL_RADIUS = 16;

    @GameTest(template = "empty", timeoutTicks = 1800, batch = "leaperBehaviourRig")
    public static void behaviour(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals(MODE)) {
            helper.succeed();
            return;
        }
        var run = new Run(helper);
        try {
            run.begin();
        } catch (Exception e) {
            run.close();
            throw e;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static final class Run {
        final GameTestHelper helper;
        final List<Map<String, Object>> history = new ArrayList<>();
        final Map<BlockPos, BlockState> initialStates = new LinkedHashMap<>();
        final Map<BlockPos, List<AABB>> shapes = new LinkedHashMap<>();
        final List<Double> masses = new ArrayList<>();
        List<ServerSubLevel> bodies;
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        MinecraftMachinesLeaperBehaviour behaviour;
        Vector3d origin, patrolStart;
        Point home;
        int tick, previousSubsteps, patrolGoals, alertTick = -1, returnedTick = -1;
        int eyeChecks, lastPhysicsCheck = -1;
        boolean physicsChanged, closed;
        double maximumAnchor, minimumUp = 1, patrolTravel;
        Mode lastMode = Mode.PATROLLING;

        Run(GameTestHelper helper) { this.helper = helper; }

        @SuppressWarnings("unchecked")
        void begin() throws Exception {
            var level = helper.getLevel();
            var physics = SubLevelPhysicsSystem.get(level);
            previousSubsteps = physics.getConfig().substepsPerTick;
            physics.getConfig().substepsPerTick = 4;
            physics.onConfigUpdated();
            physicsChanged = true;
            origin = MinecraftMachinesLeaperLearning.arena(level, 3, Blocks.SMOOTH_STONE, 48);
            int ox = (int) Math.floor(origin.x), oz = (int) Math.floor(origin.z);
            // This test only runs in the disposable GameTest world.
            for (int x = ox - 48; x <= ox + 48; x++)
                for (int z = oz - 48; z <= oz + 48; z++)
                    for (int y = 7; y <= 34; y++) {
                        var p = new BlockPos(x, y, z);
                        if (!level.getBlockState(p).isAir())
                            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                    }
            rig = MinecraftMachinesLeaper.spawn(level, origin);
            rig.reset(0);
            nav = new MinecraftMachinesLeaperPredatorNavigator(
                    rig, level, MinecraftMachinesLeaperPredatorExperiments.candidate());
            home = new Point(origin.x, 7, origin.z);
            behaviour = new MinecraftMachinesLeaperBehaviour(home, PATROL_RADIUS);
            bodies = MinecraftMachinesLeaperPounceCommandTests.bodies(rig);
            for (var body : bodies) masses.add(body.getMassTracker().getMass());
            var field = MinecraftMachinesLeaper.class.getDeclaredField("collisionCells");
            field.setAccessible(true);
            for (var cell : (List<BlockPos>) field.get(rig)) {
                var state = level.getBlockState(cell);
                initialStates.put(cell, state);
                shapes.put(cell, state.getCollisionShape(level, cell).toAabbs());
            }
            var expectedParts = new LinkedHashMap<Integer, Integer>();
            for (var part : List.of("core", "hip", "upper", "lower"))
                for (var cell : LeaperPartBlock.PARTS.get(part))
                    expectedParts.put(cell.index(), part.equals("core") ? 1 : 4);
            var actualParts = new LinkedHashMap<Integer, Integer>();
            for (var state : initialStates.values())
                actualParts.merge(state.getValue(LeaperPartBlock.PART), 1, Integer::sum);
            int expectedCells = expectedParts.values().stream().mapToInt(Integer::intValue).sum();
            require(bodies.size() == 13 && initialStates.size() == expectedCells
                            && actualParts.equals(expectedParts),
                    "Rig cells do not match generated core + four hip/upper/lower assemblies: expected "
                            + expectedCells + ", got " + initialStates.size());
            verifyEyesAndPhysics(Mode.PATROLLING);
            loop();
        }

        void verifyEyesAndPhysics(Mode mode) {
            var level = helper.getLevel();
            require(rig.behaviourMode() == mode, "Rig reports the wrong awareness mode");
            int eyes = 0;
            for (var entry : initialStates.entrySet()) {
                var pos = entry.getKey();
                var initial = entry.getValue();
                var current = level.getBlockState(pos);
                require(current.getBlock() == initial.getBlock()
                                && current.getValue(LeaperPartBlock.PART).equals(initial.getValue(LeaperPartBlock.PART))
                                && current.getValue(LeaperPartBlock.PRECISE).equals(initial.getValue(LeaperPartBlock.PRECISE)),
                        "Eye mode changed a physical block or collision selector");
                boolean eye = LeaperPartBlock.isEyePart(current.getValue(LeaperPartBlock.PART));
                String expected = eye ? mode.name() : "PATROLLING";
                require(current.getValue(LeaperPartBlock.EYE_MODE).name().equals(expected),
                        "Incorrect replicated eye state at " + pos);
                require(current.getCollisionShape(level, pos).toAabbs().equals(shapes.get(pos)),
                        "Eye mode changed a collision shape");
                var restored = NbtUtils.readBlockState(level.registryAccess().lookupOrThrow(Registries.BLOCK),
                        NbtUtils.writeBlockState(current));
                require(restored.equals(current), "Eye mode did not survive blockstate serialization");
                if (eye) eyes++;
            }
            require(eyes == 8, "Expected exactly eight visible eye cells");
            for (int i = 0; i < bodies.size(); i++)
                require(Math.abs(bodies.get(i).getMassTracker().getMass() - masses.get(i)) < 1e-9,
                        "Eye transition changed native body mass " + i);
            eyeChecks++;
            lastPhysicsCheck = tick;
        }

        void step() {
            ++tick;
            // Leave each visual state in the native world across several physics ticks.
            if (tick == 8) rig.setBehaviourMode(Mode.ALERT);
            if (tick == 16) rig.setBehaviourMode(Mode.HUNTING);
            if (tick == 24) rig.setBehaviourMode(Mode.PATROLLING);
            if (tick <= COORDINATOR_START && (tick == 1 || tick % 8 == 0 || tick % 8 == 1))
                verifyEyesAndPhysics(rig.behaviourMode());

            if (tick >= COORDINATOR_START) {
                if (tick == HUNT_TICK) {
                    require(patrolGoals > 0, "Calm patrol never selected a supported waypoint");
                    patrolTravel = patrolStart == null ? 0 : Math.hypot(
                            rig.corePivot().x - patrolStart.x, rig.corePivot().z - patrolStart.z);
                    require(patrolTravel > .25, "Patrol selected a point but never moved physically");
                    var p = rig.corePivot();
                    behaviour.forceHunt(UUID.fromString("37dc49b2-5654-4df8-a77d-e8e3f3f8e037"),
                            new Point(p.x, 7, p.z - 6));
                    rig.setBehaviourMode(Mode.HUNTING);
                }
                behaviour.step(helper.getLevel(), rig, nav, "horrific");
                require(behaviour.canAttack() == (behaviour.mode() == Mode.HUNTING),
                        "Attack eligibility must follow the established hunt, including cover");
                if (behaviour.mode() != lastMode) {
                    if (behaviour.mode() == Mode.ALERT) alertTick = tick;
                    if (lastMode == Mode.ALERT && behaviour.mode() == Mode.PATROLLING) returnedTick = tick;
                    lastMode = behaviour.mode();
                    verifyEyesAndPhysics(lastMode);
                } else if (tick == lastPhysicsCheck + 1) {
                    // Verify mass again after native physics has consumed the block updates.
                    verifyEyesAndPhysics(lastMode);
                    lastPhysicsCheck = -1;
                }
                if (tick < HUNT_TICK) {
                    require(behaviour.mode() == Mode.PATROLLING, "Empty world created phantom suspicion");
                    require(nav.pace.equals("careful") && !nav.behaviourAllowsLeaps,
                            "Patrol must keep its calm walking pace");
                    require(nav.leap.launches == 0, "Calm patrol launched a hunting leap");
                    if (nav.goal != null) {
                        require(Math.hypot(nav.goal.x - home.x(), nav.goal.z - home.z()) <= PATROL_RADIUS,
                                "Patrol waypoint left its configured area");
                        require(nav.leap.supportedDestination(nav.goal.x, nav.goal.y, nav.goal.z),
                                "Patrol selected an unsupported destination");
                        patrolGoals++;
                        if (patrolStart == null) patrolStart = rig.corePivot();
                    }
                }
            } else nav.pace = "careful";
            nav.step();
            maximumAnchor = Math.max(maximumAnchor, rig.anchorError());
            minimumUp = Math.min(minimumUp, rig.upright());
            require(rig.valid() && rig.corePivot().isFinite() && rig.assemblyVelocity().isFinite()
                            && maximumAnchor < .16 && minimumUp > .45 && rig.limitViolation() < .15,
                    "Awareness replay violated native rig stability");
            if (tick % 8 == 0) {
                var row = new LinkedHashMap<String, Object>();
                row.put("tick", tick);
                row.put("mode", behaviour.mode().name());
                row.put("core", MinecraftMachinesLeaperPursuitRegressionTests.xyz(rig.corePivot()));
                row.put("goal", nav.goal == null ? null : MinecraftMachinesLeaperPursuitRegressionTests.xyz(nav.goal));
                row.put("pace", nav.pace);
                row.put("leap", nav.leap.state.name());
                row.put("reason", behaviour.brain.reason());
                history.add(row);
            }
        }

        void finish() throws Exception {
            require(alertTick >= HUNT_TICK && alertTick <= HUNT_TICK + LeaperBehaviour.LOST_SIGHT_TICKS + 4,
                    "Hunt did not give up after losing sight");
            require(returnedTick > alertTick && returnedTick <= alertTick + LeaperBehaviour.SEARCH_TICKS + 4,
                    "Investigation did not return to patrol within its finite search budget");
            require(behaviour.brain.target() == null, "Completed search retained a live target");
            verifyEyesAndPhysics(Mode.PATROLLING);
            MinecraftMachinesLeaperLearning.write(MODE + "-rig", Map.of(
                    "complete", true, "success", true, "eye_checks", eyeChecks,
                    "patrol_distance", patrolTravel, "alert_tick", alertTick,
                    "returned_tick", returnedTick, "maximum_anchor", maximumAnchor,
                    "minimum_up", minimumUp, "history", history));
        }

        void loop() {
            helper.runAfterDelay(1, () -> {
                try {
                    step();
                    if (tick >= END) {
                        finish();
                        close();
                        helper.succeed();
                    } else loop();
                } catch (Exception e) {
                    close();
                    helper.fail("Leaper behaviour integration at tick " + tick + ": " + e);
                }
            });
        }

        void close() {
            if (closed) return;
            closed = true;
            if (rig != null) rig.close();
            if (physicsChanged) {
                var physics = SubLevelPhysicsSystem.get(helper.getLevel());
                physics.getConfig().substepsPerTick = previousSubsteps;
                physics.onConfigUpdated();
                physicsChanged = false;
            }
        }
    }
}
