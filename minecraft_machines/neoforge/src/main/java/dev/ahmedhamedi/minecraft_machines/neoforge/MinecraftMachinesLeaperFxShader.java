package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import java.io.IOException;

/** Blended particle shading with continuous alpha, including Minecraft without a shader pack. */
final class MinecraftMachinesLeaperFxShader {
    static ShaderInstance shader;
    static void register(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath("minecraft_machines", "leaper_fx"),
                DefaultVertexFormat.POSITION_TEX_COLOR), loaded -> shader = loaded);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("Unable to load Leaper effect shader", failure);
        }
    }
}
