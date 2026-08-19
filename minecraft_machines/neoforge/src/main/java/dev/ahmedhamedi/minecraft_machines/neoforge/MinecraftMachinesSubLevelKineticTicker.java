package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class MinecraftMachinesSubLevelKineticTicker {
    private MinecraftMachinesSubLevelKineticTicker() {
    }

    static void tick(final ServerTickEvent.Pre event) {
        for (final ServerLevel level : event.getServer().getAllLevels()) {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            final SubLevelPhysicsSystem physicsSystem = container.physicsSystem();
            if (physicsSystem.getPaused()) {
                continue;
            }
            final Set<BlockEntity> ticked = Collections.newSetFromMap(new IdentityHashMap<>());
            for (final ServerSubLevel subLevel : new ArrayList<>(container.getAllSubLevels())) {
                ticked.clear();
                final List<BlockEntitySubLevelActor> actors = new ArrayList<>();
                for (final BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
                    actors.add(actor);
                }
                for (final BlockEntitySubLevelActor actor : actors) {
                    if (actor instanceof final BlockEntity blockEntity) {
                        tickKineticBlockEntity(blockEntity, ticked);
                    }
                }
            }
        }
    }

    private static void tickKineticBlockEntity(final BlockEntity blockEntity, final Set<BlockEntity> ticked) {
        if (blockEntity.isRemoved() || !ticked.add(blockEntity)) {
            return;
        }
        if (blockEntity instanceof final SwivelBearingBlockEntity bearing) {
            bearing.tick();
        } else if (blockEntity instanceof final RoboticServoJointBlockEntity servo) {
            servo.tickSubLevelKineticServo();
        }
    }
}
