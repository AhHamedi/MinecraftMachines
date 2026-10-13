package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Mode;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.*;

/** One continuous encounter with a server-side player and the production perception/coordinator. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperEncounterTests {
    @GameTest(template = "empty", timeoutTicks = 2700, batch = "leaperBehaviourEncounter")
    public static void continuousEncounter(GameTestHelper helper) throws Exception {
        if (!Set.of("predator-behaviour", "predator-encounter").contains(MinecraftMachinesLeaperLearning.mode())) {
            helper.succeed(); return;
        }
        var run = new Run(helper);
        try { run.begin(); } catch (Exception e) { run.close(); throw e; }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static final class Run {
        final GameTestHelper helper;
        MinecraftMachinesLeaper rig;
        MinecraftMachinesLeaperPredatorNavigator nav;
        MinecraftMachinesLeaperBehaviour behaviour;
        ServerPlayer player;
        Point home, frozenMemory;
        Vector3d chaseOrigin, direction, chaseBody;
        int tick, phase, phaseTick, huntTicks, lossFrames, lostRoutes, modeChanges;
        Mode previous = Mode.PATROLLING;
        final Set<String> patrolGoals = new HashSet<>();
        final Set<String> visitedPatrolGoals = new HashSet<>();
        final Set<String> huntingSearchGoals = new HashSet<>();
        final List<Map<String, Object>> cases = new ArrayList<>();
        double patrolTravel, chaseTravel;

        Run(GameTestHelper helper) { this.helper = helper; }

        void begin() throws Exception {
            var level = helper.getLevel();
            var spawn = MinecraftMachinesLeaperLearning.arena(level, 5, Blocks.SMOOTH_STONE, 100);
            rig = MinecraftMachinesLeaper.spawn(level, spawn);
            rig.reset(0);
            nav = new MinecraftMachinesLeaperPredatorNavigator(rig, level, MinecraftMachinesLeaperPredatorExperiments.candidate());
            require(rig.apexVersion && nav.apex.enabled, "Encounter must use Apex");
            home = new Point(spawn.x, 7, spawn.z);
            behaviour = new MinecraftMachinesLeaperBehaviour(home, 24);
            // FakePlayer has a server packet sink; vanilla's mock login cannot negotiate
            // Simulated's required custom payloads in a headless GameTest server.
            player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "leaper-encounter"));
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(spawn.x + 150, 7, spawn.z);
            level.addNewPlayer(player);
            loop();
        }

        Vector3d forward() { return rig.coreOrientation().transform(new Vector3d(0, 0, -1)); }

        void place(double x, double z, boolean hidden) {
            player.setPos(x, 7, z);
            player.setDeltaMovement(0, 0, 0);
            player.setOnGround(true);
            player.setInvisible(hidden);
            player.setShiftKeyDown(hidden);
        }

        void step() throws Exception {
            ++tick;
            var p = rig.corePivot();
            if (phase == 1) {
                var f = forward();
                double side = Math.sin((tick - phaseTick) * .15) * 1.4;
                place(p.x - f.x * 6 + f.z * side,
                        p.z - f.z * 6 - f.x * side, false);
            } else if (phase == 2 || phase == 5) {
                var f = forward();
                place(p.x + f.x * 14, p.z + f.z * 14, false);
            } else if (phase == 3) {
                int age = tick - phaseTick;
                place(chaseOrigin.x + direction.x * age * .26,
                        chaseOrigin.z + direction.z * age * .26, age % 40 < 4);
            } else if (phase == 4) {
                if (behaviour.mode() == Mode.HUNTING && nav.goal != null
                        && Math.hypot(nav.goal.x - frozenMemory.x(), nav.goal.z - frozenMemory.z()) >= 4)
                    huntingSearchGoals.add(Math.round(nav.goal.x * 10) + ":" + Math.round(nav.goal.z * 10));
                place(frozenMemory.x() + 10, frozenMemory.z() + 10, true);
            }
            var oldRoute = nav.route;
            if (phase == 0 && nav.goal != null && Math.hypot(p.x - nav.goal.x, p.z - nav.goal.z) <= 2.5)
                visitedPatrolGoals.add(Math.round(nav.goal.x * 10) + ":" + Math.round(nav.goal.z * 10));
            behaviour.step(helper.getLevel(), rig, nav, "horrific");
            if (previous != behaviour.mode()) { modeChanges++; previous = behaviour.mode(); }
            if (phase == 3 && player.isInvisible()) {
                lossFrames++;
                if (!oldRoute.isEmpty() && nav.route.isEmpty()) lostRoutes++;
            }
            nav.step();

            if (phase == 0) {
                if (nav.goal != null) {
                    String goal = Math.round(nav.goal.x * 10) + ":" + Math.round(nav.goal.z * 10);
                    patrolGoals.add(goal);
                    if (nav.arrived) visitedPatrolGoals.add(goal);
                }
                patrolTravel = Math.max(patrolTravel, Math.hypot(p.x - home.x(), p.z - home.z()));
                require(behaviour.mode() == Mode.PATROLLING, "Remote player generated phantom awareness");
                require(tick < 700, "Patrol failed to visit distinct local destinations: "
                            + patrolGoals + "; travel=" + patrolTravel + "; body=" + p + "; goal=" + nav.goal
                            + "; visited=" + visitedPatrolGoals + "; reason=" + nav.reason);
                if (tick >= 260 && visitedPatrolGoals.size() >= 2 && patrolGoals.size() >= 3 && patrolTravel > 6) {
                    cases.add(Map.of("name", "continuous-patrol", "success", true,
                            "destinations", patrolGoals.size(), "arrivals", visitedPatrolGoals.size(),
                            "ticks", tick, "travel", patrolTravel));
                    phase = 1; phaseTick = tick;
                }
            } else if (phase == 1) {
                if (behaviour.mode() == Mode.ALERT) {
                    require(behaviour.brain.reason().equals("heard-player"), "Behind-player encounter must begin with hearing");
                    cases.add(Map.of("name", "actual-height-ground-footsteps", "success", true,
                            "reaction_ticks", tick - phaseTick));
                    phase = 2; phaseTick = tick;
                }
                require(tick - phaseTick < 60, "Ground footsteps behind the Leaper never alerted it");
            } else if (phase == 2) {
                if (behaviour.mode() == Mode.HUNTING) {
                    cases.add(Map.of("name", "natural-visual-confirmation", "success", true,
                            "reaction_ticks", tick - phaseTick));
                    phase = 3; phaseTick = tick;
                    chaseOrigin = new Vector3d(player.getX(), 7, player.getZ());
                    direction = new Vector3d(player.getX() - p.x, 0, player.getZ() - p.z).normalize();
                    chaseBody = new Vector3d(p);
                }
                require(tick - phaseTick < 60, "Clearly visible prey was not naturally hunted");
            } else if (phase == 3) {
                require(behaviour.mode() == Mode.HUNTING, "Brief visibility loss changed the hunting mode");
                huntTicks++;
                chaseTravel = Math.hypot(p.x - chaseBody.x, p.z - chaseBody.z);
                if (tick - phaseTick >= 120) {
                    require(chaseTravel > 15 && lossFrames >= 8 && lostRoutes == 0,
                            "Pursuit stopped or discarded its route on brief visibility gaps");
                    cases.add(Map.of("name", "continuous-chase-through-visibility-gaps", "success", true,
                            "hunt_ticks", huntTicks, "travel", chaseTravel, "lost_routes", lostRoutes));
                    phase = 4; phaseTick = tick;
                    frozenMemory = behaviour.brain.destination();
                }
            } else if (phase == 4) {
                if (tick - phaseTick < LeaperBehaviour.LOST_SIGHT_TICKS - 4)
                    require(behaviour.mode() == Mode.HUNTING && behaviour.brain.destination().equals(frozenMemory),
                            "Hidden player moved the last-seen destination or ended hunt prematurely");
                if (behaviour.mode() == Mode.PATROLLING) {
                    require(huntingSearchGoals.size() >= 2, "Lost-sight hunt did not actively check nearby positions");
                    cases.add(Map.of("name", "active-hunt-around-frozen-memory", "success", true,
                            "search_destinations", huntingSearchGoals.size()));
                    require(behaviour.brain.returningHome(), "Encounter must still be away from home after searching");
                    cases.add(Map.of("name", "finite-last-seen-search", "success", true));
                    phase = 5; phaseTick = tick;
                }
            } else if (phase == 5) {
                if (behaviour.mode() == Mode.HUNTING) {
                    require(Math.hypot(p.x - home.x(), p.z - home.z()) > 8,
                            "Reacquisition waited until the return home completed");
                    require(modeChanges == 6, "Encounter made an unexpected extra awareness transition");
                    cases.add(Map.of("name", "visible-prey-interrupts-return", "success", true,
                            "reaction_ticks", tick - phaseTick));
                    MinecraftMachinesLeaperLearning.write("predator-behaviour-encounter", Map.of(
                            "success", true, "complete", true, "controller", "apex", "ticks", tick,
                            "mode_changes", modeChanges, "cases", cases));
                    close(); helper.succeed(); return;
                }
                require(tick - phaseTick < 60, "Return home suppressed a new visible encounter");
            }
            require(rig.valid() && rig.upright() > .6 && rig.anchorError() < .16, "Encounter destabilized the physical rig");
            require(tick < 2600, "Continuous encounter timed out in phase " + phase);
            loop();
        }

        void loop() { helper.runAfterDelay(1, () -> {
            try { step(); } catch (Exception e) { close(); helper.fail("Encounter phase " + phase + ", tick " + tick + ": " + e); }
        }); }
        void close() {
            if (player != null) helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            if (rig != null) rig.close();
        }
    }
}
