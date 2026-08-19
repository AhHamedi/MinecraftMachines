package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(MinecraftMachines.MOD_ID)
public final class MinecraftMachinesNeoForge {
    public MinecraftMachinesNeoForge(@SuppressWarnings("unused") IEventBus modEventBus) {
        MinecraftMachines.getRegistrate().registerEventListeners(modEventBus);
        MinecraftMachines.init();
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesDuopodFlatArena::recoverOutstanding);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesCommands::register);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, MinecraftMachinesTrainingBridge::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, MinecraftMachinesDuopodCemTrainer::tick);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesServoDemo::tick);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesServoTelemetry::tick);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesWormCemTrainer::tick);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesDuopodManager::tick);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesSubLevelKineticTicker::tick);
    }
}
