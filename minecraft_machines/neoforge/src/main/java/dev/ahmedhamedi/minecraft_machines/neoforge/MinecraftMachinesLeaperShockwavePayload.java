package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

/** Small, server-authored state snapshots; geometry is generated locally at render frequency. */
record MinecraftMachinesLeaperShockwavePayload(UUID id, long startTick, double x, double y,
        double z, double floor, double scale, boolean cancelled) implements CustomPacketPayload {
    static final Type<MinecraftMachinesLeaperShockwavePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("minecraft_machines", "leaper_shockwave"));
    static Consumer<MinecraftMachinesLeaperShockwavePayload> receiver = ignored -> {};
    static final StreamCodec<RegistryFriendlyByteBuf, MinecraftMachinesLeaperShockwavePayload> CODEC =
            new StreamCodec<>() {
                public MinecraftMachinesLeaperShockwavePayload decode(RegistryFriendlyByteBuf b) {
                    return new MinecraftMachinesLeaperShockwavePayload(b.readUUID(), b.readLong(),
                            b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readBoolean());
                }
                public void encode(RegistryFriendlyByteBuf b, MinecraftMachinesLeaperShockwavePayload p) {
                    b.writeUUID(p.id); b.writeLong(p.startTick); b.writeDouble(p.x); b.writeDouble(p.y);
                    b.writeDouble(p.z); b.writeDouble(p.floor); b.writeDouble(p.scale); b.writeBoolean(p.cancelled);
                }
            };
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(TYPE, CODEC, (p, context) -> receiver.accept(p));
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
