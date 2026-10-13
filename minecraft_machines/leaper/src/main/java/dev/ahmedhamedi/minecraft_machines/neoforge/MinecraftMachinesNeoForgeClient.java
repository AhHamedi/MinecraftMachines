package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = MinecraftMachines.MOD_ID, dist = Dist.CLIENT)
public final class MinecraftMachinesNeoForgeClient {
    public MinecraftMachinesNeoForgeClient(IEventBus bus) {
        MinecraftMachinesLeaperShockwaveClient.init();
        bus.addListener(MinecraftMachinesLeaperFxShader::register);
    }
}
