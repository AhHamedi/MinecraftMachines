package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Evidence;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Point;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.Mode.*;
import static dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour.*;
import static org.junit.jupiter.api.Assertions.*;

class LeaperBehaviourTest {
    private static final Point HOME = new Point(0, 64, 0);
    private static final Point PLAYER = new Point(24, 64, 0);
    private static final UUID PREY = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test void unseenUndergroundPlayerDoesNotCreateAwareness() {
        var brain = new LeaperBehaviour(HOME);
        for (int tick = 0; tick <= 1200; tick += 4) step(brain, tick, HOME, null);
        assertEquals(PATROLLING, brain.mode());
        assertEquals(HOME, brain.destination());
        assertNull(brain.target());
        assertEquals(0, brain.suspicion());
        assertEquals(Integer.MAX_VALUE, brain.memoryAge(1200));
    }

    @Test void clearSightRequiresConfirmationAndThenHunts() {
        var brain = new LeaperBehaviour(HOME);
        step(brain, 0, HOME, sight(PLAYER));
        assertEquals(ALERT, brain.mode());
        for (int tick = 4; tick <= 8; tick += 4) step(brain, tick, HOME, sight(PLAYER));
        assertEquals(ALERT, brain.mode());
        step(brain, 12, HOME, sight(PLAYER));
        assertEquals(HUNTING, brain.mode());
        assertEquals(PREY, brain.target());
        assertEquals(1, brain.suspicion());
    }

    @Test void oneGlimpseCannotAccumulateConfirmationWhileHidden() {
        var brain = new LeaperBehaviour(HOME);
        for (int tick = 0; tick < 100; tick += 4)
            step(brain, tick, HOME, tick % 12 == 0 ? sight(PLAYER) : null);
        assertEquals(ALERT, brain.mode());
    }

    @Test void hearingOnlyInvestigatesItsCoarseEvidencePosition() {
        var brain = new LeaperBehaviour(HOME);
        Point noisyArea = new Point(20, 64, 4);
        for (int tick = 0; tick < 200; tick += 4)
            step(brain, tick, HOME, new Evidence(PREY, noisyArea, 1, false));
        assertEquals(ALERT, brain.mode());
        assertEquals(noisyArea, brain.destination());
        assertEquals(PREY, brain.target());
        assertTrue(brain.suspicion() < 1);
    }

    @Test void huntKeepsLastSeenPositionWhenThePlayerMovesUnseen() {
        var brain = hunting();
        Point unseenLivePosition = new Point(-30, 58, 12);
        for (int tick = 4; tick < LOST_SIGHT_TICKS; tick += 4)
            step(brain, tick, HOME, new Evidence(PREY, unseenLivePosition, .8, false));
        assertEquals(HUNTING, brain.mode());
        assertEquals(PLAYER, brain.destination());
        assertEquals(LOST_SIGHT_TICKS - 4, brain.memoryAge(LOST_SIGHT_TICKS - 4));
        step(brain, LOST_SIGHT_TICKS, HOME, new Evidence(PREY, unseenLivePosition, .8, false));
        assertEquals(ALERT, brain.mode());
        assertEquals(PLAYER, brain.destination());
        assertEquals("lost-sight", brain.reason());
    }

    @Test void visibleMovementRefreshesMemoryAndRestartsTheLostSightClock() {
        var brain = hunting();
        Point lastSeen = new Point(26, 64, 3);
        step(brain, 80, HOME, sight(lastSeen));
        step(brain, 80 + LOST_SIGHT_TICKS - 4, HOME, null);
        assertEquals(HUNTING, brain.mode());
        assertEquals(lastSeen, brain.destination());
        step(brain, 80 + LOST_SIGHT_TICKS, HOME, null);
        assertEquals(ALERT, brain.mode());
        assertEquals(lastSeen, brain.destination());
        assertEquals(LOST_SIGHT_TICKS, brain.memoryAge(80 + LOST_SIGHT_TICKS));
    }

    @Test void alertReacquiresAfterFreshSustainedSight() {
        var brain = hunting();
        step(brain, LOST_SIGHT_TICKS, HOME, null);
        assertEquals(ALERT, brain.mode());
        for (int tick = LOST_SIGHT_TICKS + 4; tick <= LOST_SIGHT_TICKS + 12; tick += 4) step(brain, tick, HOME, sight(PLAYER));
        assertEquals(HUNTING, brain.mode());
    }

    @Test void anotherPlayersSightCannotHijackAHuntOrItsMemory() {
        var brain = hunting();
        var stranger = new Evidence(OTHER, new Point(4, 64, 0), 1, true);
        for (int tick = 4; tick <= LOST_SIGHT_TICKS; tick += 4) step(brain, tick, HOME, stranger);
        assertEquals(ALERT, brain.mode());
        assertEquals(PREY, brain.target());
        assertEquals(PLAYER, brain.destination());
        assertEquals(LOST_SIGHT_TICKS, brain.memoryAge(LOST_SIGHT_TICKS));
    }

    @Test void anotherPlayersSoundsCannotStealSearchIdentity() {
        var brain = new LeaperBehaviour(HOME);
        step(brain, 0, HOME, new Evidence(PREY, PLAYER, .5, false));
        for (int tick = 4; tick < 100; tick += 4)
            step(brain, tick, HOME, new Evidence(OTHER, HOME, 1, false));
        assertEquals(ALERT, brain.mode());
        assertEquals(PREY, brain.target());
        assertEquals(PLAYER, brain.destination());
    }

    @Test void newVisibleSuspectMustBuildItsOwnConfirmation() {
        var brain = new LeaperBehaviour(HOME);
        for (int tick = 0; tick <= 8; tick += 4) step(brain, tick, HOME, sight(PLAYER));
        var stranger = new Evidence(OTHER, new Point(8, 64, 0), 1, true);
        step(brain, 12, HOME, stranger);
        assertEquals(ALERT, brain.mode());
        assertEquals(OTHER, brain.target());
        assertEquals(stranger.position(), brain.destination());
        step(brain, 16, HOME, stranger);
        assertEquals(ALERT, brain.mode());
        step(brain, 20, HOME, stranger);
        assertEquals(HUNTING, brain.mode());
    }

    @Test void switchingSuspectsDoesNotRenewTheSearchDeadline() {
        var brain = new LeaperBehaviour(HOME);
        step(brain, 0, HOME, new Evidence(PREY, PLAYER, .5, false));
        for (int tick = 4; tick <= SEARCH_TICKS; tick += 4)
            step(brain, tick, HOME, tick % 8 == 0 ? sight(PLAYER)
                    : new Evidence(OTHER, new Point(8, 64, 0), 1, true));
        assertEquals(PATROLLING, brain.mode());
        assertNull(brain.target());
    }

    @Test void repeatedSoundsCannotResetTheSearchDeadline() {
        var brain = new LeaperBehaviour(HOME);
        Point body = new Point(12, 64, 0);
        for (int tick = 0; tick <= SEARCH_TICKS; tick += 4)
            step(brain, tick, body, new Evidence(PREY, PLAYER, .8, false));
        assertEquals(PATROLLING, brain.mode());
        assertTrue(brain.returningHome());
        assertEquals(HOME, brain.destination());
        assertNull(brain.target());
        assertEquals(Integer.MAX_VALUE, brain.memoryAge(SEARCH_TICKS));
    }

    @Test void searchWithoutEvidenceEventuallyEnds() {
        var brain = hunting();
        step(brain, LOST_SIGHT_TICKS, HOME, null);
        step(brain, LOST_SIGHT_TICKS + SEARCH_TICKS - 4, HOME, null);
        assertEquals(ALERT, brain.mode());
        step(brain, LOST_SIGHT_TICKS + SEARCH_TICKS, HOME, null);
        assertEquals(PATROLLING, brain.mode());
        assertEquals(HOME, brain.destination());
    }

    @Test void visibleUnreachablePreyTriggersGiveUpAndCannotInstantlyRehunt() {
        var brain = hunting();
        for (int tick = 4; tick <= NO_PROGRESS_TICKS + 4; tick += 4)
            brain.tick(tick, HOME, sight(PLAYER), false, true, false);
        assertEquals(ALERT, brain.mode());
        assertEquals("blocked-path", brain.reason());
        for (int tick = NO_PROGRESS_TICKS + 8; tick < NO_PROGRESS_TICKS + 4 + SEARCH_TICKS; tick += 4)
            brain.tick(tick, HOME, sight(PLAYER), false, true, false);
        assertEquals(ALERT, brain.mode());
        brain.tick(NO_PROGRESS_TICKS + 4 + SEARCH_TICKS, HOME, sight(PLAYER), false, true, false);
        assertEquals(PATROLLING, brain.mode());
        for (int tick = NO_PROGRESS_TICKS + 8 + SEARCH_TICKS; tick < NO_PROGRESS_TICKS + SEARCH_TICKS + 300; tick += 4)
            brain.tick(tick, HOME, sight(PLAYER), false, true, false);
        assertEquals(PATROLLING, brain.mode());
    }

    @Test void failedPreyCanBeReacquiredWhenItVisiblyLeavesTheInaccessiblePosition() {
        var brain = hunting();
        for (int tick = 4; tick <= NO_PROGRESS_TICKS + 4; tick += 4)
            brain.tick(tick, HOME, sight(PLAYER), false, true, false);
        Point relocated = new Point(12, 64, 0);
        for (int tick = NO_PROGRESS_TICKS + 8; tick < NO_PROGRESS_TICKS + 4 + FAILED_PURSUIT_COOLDOWN_TICKS; tick += 4) step(brain, tick, HOME, sight(relocated));
        assertEquals(ALERT, brain.mode());
        // A blocked report can still describe the obsolete route to the old location.
        for (int tick = NO_PROGRESS_TICKS + 4 + FAILED_PURSUIT_COOLDOWN_TICKS; tick <= NO_PROGRESS_TICKS + 12 + FAILED_PURSUIT_COOLDOWN_TICKS; tick += 4)
            brain.tick(tick, HOME, sight(relocated), false, true, false);
        assertEquals(HUNTING, brain.mode());
        assertEquals(relocated, brain.destination());
    }

    @Test void movingPreyDoesNotFabricateProgressForAStationaryLeaper() {
        var brain = hunting();
        for (int tick = 4; tick <= NO_PROGRESS_TICKS + 4; tick += 4)
            step(brain, tick, HOME, sight(new Point(24 + tick * .05, 64, 0)));
        assertEquals(ALERT, brain.mode());
        assertEquals("no-progress", brain.reason());
    }

    @Test void repeatedLossAndReacquisitionCannotResetAnUnreachablePursuit() {
        var brain = hunting();
        for (int tick = 4; tick <= NO_PROGRESS_TICKS + 20; tick += 4)
            step(brain, tick, HOME, tick % 120 < 20 ? sight(PLAYER) : null);
        assertEquals(ALERT, brain.mode());
        assertEquals("no-progress", brain.reason());
        for (int tick = NO_PROGRESS_TICKS + 24; tick <= NO_PROGRESS_TICKS + SEARCH_TICKS + 20; tick += 4)
            step(brain, tick, HOME, tick % 120 < 20 ? sight(PLAYER) : null);
        assertEquals(PATROLLING, brain.mode());
    }

    @Test void realApproachAndReachableAttackDistanceDoNotTimeOut() {
        var brain = hunting();
        for (int tick = 4; tick <= 800; tick += 4) {
            Point body = new Point(Math.min(22, tick * .03), 64, 0);
            brain.tick(tick, body, sight(PLAYER), body.x() >= 21, false, false);
        }
        assertEquals(HUNTING, brain.mode());
    }

    @Test void physicsCommitmentPausesStallButNotSightMemory() {
        var brain = hunting();
        for (int tick = 4; tick <= NO_PROGRESS_TICKS + 300; tick += 4)
            brain.tick(tick, HOME, sight(PLAYER), false, true, true);
        assertEquals(HUNTING, brain.mode());
        for (int tick = NO_PROGRESS_TICKS + 304; tick <= NO_PROGRESS_TICKS + 300 + LOST_SIGHT_TICKS; tick += 4)
            brain.tick(tick, HOME, null, false, true, true);
        assertEquals(ALERT, brain.mode());
        assertEquals("lost-sight", brain.reason());
    }

    @Test void reacquiringAfterCommittedMotionDuringSearchKeepsThePhysicsGrace() {
        var brain = hunting();
        for (int tick = 4; tick <= LOST_SIGHT_TICKS + SEARCH_TICKS - 60; tick += 4)
            brain.tick(tick, HOME, null, false, true, true);
        assertEquals(ALERT, brain.mode());
        for (int tick = LOST_SIGHT_TICKS + SEARCH_TICKS - 56; tick <= LOST_SIGHT_TICKS + SEARCH_TICKS - 20; tick += 4)
            brain.tick(tick, HOME, sight(PLAYER), false, true, true);
        assertEquals(HUNTING, brain.mode());
    }

    @Test void outOfTerritorySightCannotStartAChase() {
        var brain = new LeaperBehaviour(HOME);
        for (int tick = 0; tick <= 100; tick += 4)
            step(brain, tick, HOME, sight(new Point(TERRITORY_RADIUS + 1, 64, 0)));
        assertEquals(PATROLLING, brain.mode());
    }

    @Test void territoryReturnReacquiresInsideTheBoundaryMargin() {
        var brain = hunting();
        step(brain, 4, new Point(TERRITORY_RADIUS + 1, 64, 0), sight(PLAYER));
        assertEquals(PATROLLING, brain.mode());
        assertTrue(brain.returningHome());
        for (int tick = 8; tick <= 100; tick += 4)
            step(brain, tick, new Point(TERRITORY_RADIUS - 6, 64, 0), sight(PLAYER));
        assertEquals(PATROLLING, brain.mode());
        for (int tick = 104; tick <= 112; tick += 4)
            step(brain, tick, new Point(TERRITORY_RADIUS - 24, 64, 0), sight(PLAYER));
        assertEquals(HUNTING, brain.mode());
        assertFalse(brain.returningHome());
    }

    @Test void clearContactInterruptsReturnWithoutNeedingToReachHome() {
        var brain = hunting();
        Point body = new Point(30, 64, 0);
        step(brain, LOST_SIGHT_TICKS, body, null);
        step(brain, LOST_SIGHT_TICKS + SEARCH_TICKS, body, null);
        assertTrue(brain.returningHome());
        for (int tick = LOST_SIGHT_TICKS + SEARCH_TICKS + 4; tick <= LOST_SIGHT_TICKS + SEARCH_TICKS + 12; tick += 4) step(brain, tick, body, sight(PLAYER));
        assertEquals(HUNTING, brain.mode());
        assertFalse(brain.returningHome());
    }

    @Test void soundAloneDoesNotKeepInterruptingAnExhaustedSearchReturn() {
        var brain = hunting();
        Point body = new Point(30, 64, 0);
        step(brain, LOST_SIGHT_TICKS, body, null);
        step(brain, LOST_SIGHT_TICKS + SEARCH_TICKS, body, null);
        for (int tick = LOST_SIGHT_TICKS + SEARCH_TICKS + 4; tick <= LOST_SIGHT_TICKS + SEARCH_TICKS + 200; tick += 4)
            step(brain, tick, body, new Evidence(PREY, PLAYER, .4, false));
        assertEquals(PATROLLING, brain.mode());
        assertTrue(brain.returningHome());
    }

    @Test void intermittentPartialSightBuildsAStableHunt() {
        var brain = new LeaperBehaviour(HOME);
        for (int tick = 0; tick <= 80; tick += 4)
            step(brain, tick, HOME, tick % 12 == 8 ? null : new Evidence(PREY, PLAYER, .5, true));
        assertEquals(HUNTING, brain.mode());
    }

    @Test void supportedHomeApproachCompletesReturnAndRestoresAwareness() {
        var brain = hunting();
        Point approach = new Point(5, 65, 0);
        step(brain, LOST_SIGHT_TICKS, approach, null);
        step(brain, LOST_SIGHT_TICKS + SEARCH_TICKS, approach, null);
        assertTrue(brain.returningHome());
        brain.tick(LOST_SIGHT_TICKS + SEARCH_TICKS + 4, approach, null, true, false, false);
        assertFalse(brain.returningHome());
        assertEquals(HOME, brain.destination()); // The territory anchor stays fixed.
        for (int tick = LOST_SIGHT_TICKS + SEARCH_TICKS + 8; tick <= LOST_SIGHT_TICKS + SEARCH_TICKS + 16; tick += 4) step(brain, tick, approach, sight(PLAYER));
        assertEquals(HUNTING, brain.mode());
    }

    @Test void unsettledOrDistantArrivalCannotFinishReturningHome() {
        var brain = hunting();
        Point approach = new Point(5, 65, 0);
        step(brain, LOST_SIGHT_TICKS, approach, null);
        step(brain, LOST_SIGHT_TICKS + SEARCH_TICKS, approach, null);
        brain.tick(LOST_SIGHT_TICKS + SEARCH_TICKS + 4, approach, null, false, false, false);
        assertTrue(brain.returningHome());
        brain.tick(LOST_SIGHT_TICKS + SEARCH_TICKS + 8, new Point(20, 64, 0), null, true, false, false);
        assertTrue(brain.returningHome());
    }

    @Test void patrolCommandResetsMemoryAndTerritory() {
        var brain = hunting();
        Point newHome = new Point(300, 90, -250);
        brain.patrol(newHome);
        assertEquals(PATROLLING, brain.mode());
        assertEquals(newHome, brain.destination());
        assertNull(brain.target());
        assertEquals(0, brain.suspicion());
        assertFalse(brain.returningHome());
        assertEquals(Integer.MAX_VALUE, brain.memoryAge(100));
    }

    @Test void duplicateTicksCannotAccumulateVisualConfirmation() {
        var brain = new LeaperBehaviour(HOME);
        for (int i = 0; i < 100; i++) step(brain, 0, HOME, sight(PLAYER));
        assertEquals(ALERT, brain.mode());
        assertThrows(IllegalArgumentException.class, () -> step(brain, -1, HOME, null));
    }

    private static LeaperBehaviour hunting() {
        var brain = new LeaperBehaviour(HOME);
        brain.forceHunt(0, PREY, PLAYER);
        return brain;
    }

    private static Evidence sight(Point point) { return new Evidence(PREY, point, 1, true); }

    private static void step(LeaperBehaviour brain, int tick, Point body, Evidence evidence) {
        brain.tick(tick, body, evidence, false, false, false);
    }
}
