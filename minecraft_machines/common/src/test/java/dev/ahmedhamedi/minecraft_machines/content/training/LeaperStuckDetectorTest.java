package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperStuckDetector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeaperStuckDetectorTest {
    @Test void requiresThreeContinuousSecondsOfAttemptedTravel() {
        var detector = new LeaperStuckDetector();
        for (int i=0;i<59;i++) assertFalse(detector.update(true,true,0,4,0));
        assertTrue(detector.update(true,true,0,4,0));
    }
    @Test void normalWalkingAndSlowProgressDoNotCountAsTrapped() {
        var detector = new LeaperStuckDetector();
        for (int i=0;i<200;i++) assertFalse(detector.update(true,true,i*.02,4,0));
    }
    @Test void ContactJitterDoesNotHideAStall() {
        var detector = new LeaperStuckDetector();
        for (int i=0;i<59;i++) detector.update(true,true,.1*Math.sin(i),4+.15*Math.cos(i),0);
        assertTrue(detector.update(true,true,0,4,0));
    }
    @Test void idleOrAirborneLeapersNeverAccumulateAStall() {
        var detector = new LeaperStuckDetector();
        for (int i=0;i<100;i++) {
            assertFalse(detector.update(false,true,0,4,0));
            assertFalse(detector.update(true,false,0,4,0));
        }
    }
    @Test void LandingAndNewTravelMustStartFresh() {
        var detector = new LeaperStuckDetector();
        for (int i=0;i<59;i++) detector.update(true,true,0,4,0);
        detector.update(true,false,0,4,0);
        assertFalse(detector.update(true,true,0,4,0));
        for (int i=0;i<58;i++) detector.update(true,true,0,4,0);
        detector.update(false,true,0,4,0);
        assertFalse(detector.update(true,true,0,4,0));
    }
    @Test void verticalProgressAndExplicitResetClearTheStall() {
        var detector = new LeaperStuckDetector();
        for (int i=0;i<59;i++) detector.update(true,true,0,4,0);
        assertFalse(detector.update(true,true,0,5.1,0));
        for (int i=0;i<59;i++) detector.update(true,true,0,5.1,0);
        detector.reset();
        assertFalse(detector.update(true,true,0,5.1,0));
    }
}
