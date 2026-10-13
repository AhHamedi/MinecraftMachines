package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(MinecraftMachines.MOD_ID)
public final class MinecraftMachinesNeoForge {
    public MinecraftMachinesNeoForge(IEventBus modEventBus) {
        MinecraftMachinesBlocks.register(modEventBus);
        MinecraftMachinesLeaperShockwave.SOUNDS.register(modEventBus);
        modEventBus.addListener(MinecraftMachinesLeaperShockwavePayload::register);
        modEventBus.addListener(MinecraftMachinesLeaperLaunchPayload::register);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesLeaperManager::tick);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesLeaperManager::stopping);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesLeaperStick::rightClickItem);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesLeaperStick::rightClickBlock);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesNeoForge::commands);
    }

    private static void commands(RegisterCommandsEvent event) {
        for (String name : new String[] {"mm", MinecraftMachines.MOD_ID})
            event.getDispatcher().register(Commands.literal(name)
                    .requires(source -> source.hasPermission(2))
                    .then(MinecraftMachinesLeaperManager.commands()));
    }
}
