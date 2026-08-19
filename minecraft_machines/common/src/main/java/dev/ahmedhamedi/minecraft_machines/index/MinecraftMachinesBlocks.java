package dev.ahmedhamedi.minecraft_machines.index;

import com.simibubi.create.AllTags;
import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.util.entry.BlockEntry;
import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlock;
import dev.ahmedhamedi.minecraft_machines.content.servo.ServoJointLinkBlock;
import dev.simulated_team.simulated.registrate.SimulatedRegistrate;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;

import static com.simibubi.create.foundation.data.ModelGen.customItemModel;
import static com.simibubi.create.foundation.data.TagGen.pickaxeOnly;

public final class MinecraftMachinesBlocks {
    private static final SimulatedRegistrate REGISTRATE = MinecraftMachines.getRegistrate();

    public static final BlockEntry<RoboticServoJointBlock> ROBOTIC_SERVO_JOINT = REGISTRATE
            .block("robotic_servo_joint", RoboticServoJointBlock::new)
            .initialProperties(SharedProperties::netheriteMetal)
            .properties(properties -> properties
                    .destroyTime(5.0f)
                    .explosionResistance(6.0f)
                    .mapColor(MapColor.COLOR_BLUE)
                    .noOcclusion())
            .tag(AllTags.AllBlockTags.SAFE_NBT.tag)
            .transform(pickaxeOnly())
            .lang("Robotic Servo Joint")
            .blockstate((ctx, prov) -> prov.getVariantBuilder(ctx.getEntry()).forAllStates(state -> {
                final Direction facing = state.getValue(RoboticServoJointBlock.FACING);
                final ConfiguredModel.Builder<?> builder = ConfiguredModel.builder()
                        .modelFile(prov.models().getExistingFile(prov.modLoc("block/robotic_servo_joint/"
                                + (facing.getAxis().isVertical() ? "block_vertical" : "block"))));

                switch (facing) {
                    case DOWN -> builder.rotationX(180);
                    case EAST -> builder.rotationY(270);
                    case NORTH -> builder.rotationY(180);
                    case WEST -> builder.rotationY(90);
                    default -> {
                    }
                }

                return builder.build();
            }))
            .loot((p, b) -> p.dropSelf(b))
            .item()
            .transform(customItemModel("robotic_servo_joint", "item"))
            .register();

    public static final BlockEntry<ServoJointLinkBlock> SERVO_JOINT_LINK = REGISTRATE
            .block("servo_joint_link", ServoJointLinkBlock::new)
            .initialProperties(SharedProperties::netheriteMetal)
            .properties(properties -> properties
                    .destroyTime(5.0f)
                    .explosionResistance(6.0f)
                    .mapColor(MapColor.COLOR_BLUE)
                    .noOcclusion())
            .tag(AllTags.AllBlockTags.NON_MOVABLE.tag)
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .blockstate((ctx, prov) -> prov.directionalBlock(ctx.getEntry(),
                    state -> prov.models().cubeAll(ctx.getName(), prov.mcLoc("block/blue_concrete"))))
            .loot((p, b) -> p.add(b, LootTable.lootTable()))
            .register();

    private MinecraftMachinesBlocks() {
    }

    public static void register() {
    }
}
