package dev.ahmedhamedi.minecraft_machines;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

public final class MinecraftMachines {
    public static final String MOD_ID = "minecraft_machines";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static ResourceLocation path(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private MinecraftMachines() {}
}
