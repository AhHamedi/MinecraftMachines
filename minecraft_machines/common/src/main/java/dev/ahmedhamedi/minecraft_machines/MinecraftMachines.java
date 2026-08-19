package dev.ahmedhamedi.minecraft_machines;

import com.mojang.logging.LogUtils;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.eriksonn.aeronautics.Aeronautics;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlockEntityTypes;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.registrate.SimulatedRegistrate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import org.slf4j.Logger;

public final class MinecraftMachines {
    public static final String MOD_ID = "minecraft_machines";
    public static final String MOD_NAME = "Minecraft Machines";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final NonNullSupplier<SimulatedRegistrate> REGISTRATE = NonNullSupplier.lazy(() ->
            (SimulatedRegistrate) new SimulatedRegistrate(Simulated.path("simulated"), MOD_ID)
                    .defaultCreativeTab((ResourceKey<CreativeModeTab>) null));

    private static boolean initialized;

    private MinecraftMachines() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        MinecraftMachinesBlocks.register();
        MinecraftMachinesBlockEntityTypes.register();

        LOGGER.info("{} loaded with Create Aeronautics ({}) and Sable available", MOD_NAME, Aeronautics.MOD_ID);
    }

    public static SimulatedRegistrate getRegistrate() {
        return REGISTRATE.get();
    }

    public static ResourceLocation path(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
