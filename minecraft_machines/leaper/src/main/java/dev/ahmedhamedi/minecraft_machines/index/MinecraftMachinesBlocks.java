package dev.ahmedhamedi.minecraft_machines.index;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperPartBlock;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperScaledPartBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MinecraftMachinesBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MinecraftMachines.MOD_ID);
    public static final Armour<LeaperPartBlock> LEAPER_PART = new Armour<>(BLOCKS.register("leaper_part",
            () -> new LeaperPartBlock(properties())));
    public static final Armour<LeaperScaledPartBlock> LEAPER_SCALED_PART = new Armour<>(BLOCKS.register("leaper_scaled_part",
            () -> new LeaperScaledPartBlock(properties())));

    public record Armour<T extends Block>(DeferredBlock<T> block) {
        public T get() { return block.get(); }
        public BlockState getDefaultState() { return get().defaultBlockState(); }
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of().noOcclusion().strength(-1.0f, 3600000.0f);
    }

    public static void register(IEventBus bus) { BLOCKS.register(bus); }
    private MinecraftMachinesBlocks() {}
}
