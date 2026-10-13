package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Full server-owned velocity: vanilla's short-encoded motion packet clips each axis to 3.9. */
record MinecraftMachinesLeaperLaunchPayload(int entityId, double x, double y, double z)
        implements CustomPacketPayload {
    static final Type<MinecraftMachinesLeaperLaunchPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("minecraft_machines", "leaper_launch"));
    static final StreamCodec<RegistryFriendlyByteBuf, MinecraftMachinesLeaperLaunchPayload> CODEC =
            new StreamCodec<>() {
                public MinecraftMachinesLeaperLaunchPayload decode(RegistryFriendlyByteBuf b) {
                    return new MinecraftMachinesLeaperLaunchPayload(
                            b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble());
                }
                public void encode(RegistryFriendlyByteBuf b, MinecraftMachinesLeaperLaunchPayload p) {
                    b.writeVarInt(p.entityId); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
                }
            };

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, CODEC, (p, context) -> {
            var player = context.player();
            if (player.getId() == p.entityId) player.setDeltaMovement(p.x, p.y, p.z);
        });
    }

    static void send(ServerPlayer player) {
        var velocity = player.getDeltaMovement();
        PacketDistributor.sendToPlayer(player, new MinecraftMachinesLeaperLaunchPayload(
                player.getId(), velocity.x, velocity.y, velocity.z));
        // This message replaces the self-directed motion packet for this impulse. Other
        // tracking clients still receive the normal hasImpulse movement updates.
        player.hurtMarked = false;
    }

    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
