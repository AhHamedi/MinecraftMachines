package dev.ahmedhamedi.minecraft_machines.content.servo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServoJointMathTest {
    @Test
    void clampsValuesToLimits() {
        assertEquals(-1.0, ServoJointMath.clamp(-5.0, -1.0, 1.0));
        assertEquals(0.5, ServoJointMath.clamp(0.5, -1.0, 1.0));
        assertEquals(1.0, ServoJointMath.clamp(5.0, -1.0, 1.0));
    }

    @Test
    void slewLimiterMovesTowardTargetWithoutOvershoot() {
        assertEquals(0.25, ServoJointMath.moveTowards(0.0, 1.0, 0.25));
        assertEquals(-0.25, ServoJointMath.moveTowards(0.0, -1.0, 0.25));
        assertEquals(0.1, ServoJointMath.moveTowards(0.0, 0.1, 0.25));
    }

    @Test
    void normalizesAnglesToSignedPiRange() {
        assertEquals(Math.PI / 2.0, ServoJointMath.normalizeSignedAngle(5.0 * Math.PI / 2.0), 1.0e-10);
        assertEquals(-Math.PI / 2.0, ServoJointMath.normalizeSignedAngle(-5.0 * Math.PI / 2.0), 1.0e-10);
    }

    @Test
    void rejectsInvalidConfigurationValues() {
        assertThrows(IllegalArgumentException.class, () -> ServoJointMath.requireFinite("target", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ServoJointMath.requireNonNegative("speed", -1.0));
    }

    @Test
    void activeMotorCommandUsesFiniteForceLimit() {
        final ServoJointMotorCommand command = ServoJointMotorCommand.active(0.25, 100.0, 10.0, 500.0);

        assertEquals(0.25, command.targetAngleRad());
        assertEquals(100.0, command.stiffness());
        assertEquals(10.0, command.damping());
        assertEquals(500.0, command.maxTorque());
        assertEquals(true, command.forceLimited());
    }

    @Test
    void passiveMotorCommandRemovesServoStiffness() {
        final ServoJointMotorCommand command = ServoJointMotorCommand.passive(2.0, 500.0);

        assertEquals(0.0, command.targetAngleRad());
        assertEquals(0.0, command.stiffness());
        assertEquals(2.0, command.damping());
        assertEquals(500.0, command.maxTorque());
        assertEquals(true, command.forceLimited());
    }
}
