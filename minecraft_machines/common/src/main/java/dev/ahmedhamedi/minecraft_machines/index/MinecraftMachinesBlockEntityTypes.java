package dev.ahmedhamedi.minecraft_machines.index;

import com.tterrag.registrate.util.entry.BlockEntityEntry;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ahmedhamedi.minecraft_machines.content.servo.ServoJointLinkBlockEntity;
import dev.simulated_team.simulated.registrate.SimulatedRegistrate;

public final class MinecraftMachinesBlockEntityTypes {
    private static final SimulatedRegistrate REGISTRATE = MinecraftMachines.getRegistrate();

    public static final BlockEntityEntry<RoboticServoJointBlockEntity> ROBOTIC_SERVO_JOINT = REGISTRATE
            .blockEntity("robotic_servo_joint", RoboticServoJointBlockEntity::new)
            .validBlocks(MinecraftMachinesBlocks.ROBOTIC_SERVO_JOINT)
            .register();

    public static final BlockEntityEntry<ServoJointLinkBlockEntity> SERVO_JOINT_LINK = REGISTRATE
            .blockEntity("servo_joint_link", ServoJointLinkBlockEntity::new)
            .validBlocks(MinecraftMachinesBlocks.SERVO_JOINT_LINK)
            .register();

    private MinecraftMachinesBlockEntityTypes() {
    }

    public static void register() {
    }
}
