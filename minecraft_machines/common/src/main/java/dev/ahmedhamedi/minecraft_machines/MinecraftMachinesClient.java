package dev.ahmedhamedi.minecraft_machines;

public final class MinecraftMachinesClient {
    private MinecraftMachinesClient() {
    }

    public static void init() {
        MinecraftMachines.LOGGER.debug("{} client initialized", MinecraftMachines.MOD_NAME);
    }
}
