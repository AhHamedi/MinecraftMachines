package dev.ahmedhamedi.minecraft_machines.content.training;

import dev.ahmedhamedi.minecraft_machines.content.training.evaluation.FirstTerminalSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstTerminalSnapshotTest {
    @Test
    void freezesTheFirstTerminalValueAndItsOwnStep() {
        final FirstTerminalSnapshot<String> snapshot = new FirstTerminalSnapshot<>();

        assertTrue(snapshot.record("active-1", false, 1));
        assertTrue(snapshot.record("success-at-2", true, 2));
        assertFalse(snapshot.record("late-neutral-state", true, 120));

        assertTrue(snapshot.completed());
        assertEquals(2, snapshot.terminalStep());
        assertEquals("success-at-2", snapshot.value());
    }

    @Test
    void tracksTheLatestValueUntilCompletion() {
        final FirstTerminalSnapshot<String> snapshot = new FirstTerminalSnapshot<>();

        assertTrue(snapshot.record("active-1", false, 1));
        assertTrue(snapshot.record("active-2", false, 2));

        assertFalse(snapshot.completed());
        assertEquals(-1, snapshot.terminalStep());
        assertEquals("active-2", snapshot.value());
        assertThrows(IllegalArgumentException.class, () -> snapshot.record("invalid", false, -1));
    }
}
