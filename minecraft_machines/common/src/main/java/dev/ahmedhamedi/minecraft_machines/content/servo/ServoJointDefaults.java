package dev.ahmedhamedi.minecraft_machines.content.servo;

public final class ServoJointDefaults {
    public static final boolean ENABLED = true;
    public static final double MINIMUM_ANGLE_RAD = Math.toRadians(-90.0);
    public static final double MAXIMUM_ANGLE_RAD = Math.toRadians(90.0);
    public static final double TARGET_ANGLE_RAD = 0.0;
    public static final double MAX_ANGULAR_SPEED_RAD_PER_SECOND = Math.toRadians(180.0);
    public static final double MAX_TORQUE = 10_000_000.0;
    public static final double STIFFNESS = 13000.0;
    public static final double DAMPING = 1000.0;
    public static final double PASSIVE_DAMPING = 2.0;

    private ServoJointDefaults() {
    }
}
