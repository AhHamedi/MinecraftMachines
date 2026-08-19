package dev.ahmedhamedi.minecraft_machines.content.servo;

record ServoJointMotorCommand(
        double targetAngleRad,
        double stiffness,
        double damping,
        boolean forceLimited,
        double maxTorque
) {
    static ServoJointMotorCommand active(final double targetAngleRad, final double stiffness, final double damping, final double maxTorque) {
        return new ServoJointMotorCommand(targetAngleRad, stiffness, damping, true, maxTorque);
    }

    static ServoJointMotorCommand passive(final double passiveDamping, final double maxTorque) {
        return new ServoJointMotorCommand(0.0, 0.0, passiveDamping, true, maxTorque);
    }
}
