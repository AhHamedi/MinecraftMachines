package dev.ahmedhamedi.minecraft_machines.neoforge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/** A marked vanilla stick: targeting is resolved on the authoritative server. */
final class MinecraftMachinesQuadrupedStick {
    private static final String MARKER = "minecraft_machines:quadruped_targeting";

    private MinecraftMachinesQuadrupedStick() {}

    static int give(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) return 0;
        for (int i = 0; i < 9; i++) {
            if (isStick(player.getInventory().getItem(i))) {
                player.getInventory().selected = i;
                player.connection.send(
                        new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(
                                player.getInventory().selected));
                player.inventoryMenu.broadcastChanges();
                return 1;
            }
        }
        var stack = new ItemStack(Items.STICK);
        var tag = new CompoundTag();
        tag.putBoolean(MARKER, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Walker Targeting Stick"));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(
                DataComponents.LORE,
                new ItemLore(
                        List.of(
                                Component.literal("Right-click ground: walk there (128 blocks)"),
                                Component.literal("Shift + right-click: stop"))));
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        for (int i = 0; i < 9; i++)
            if (isStick(player.getInventory().getItem(i))) {
                player.getInventory().selected = i;
                break;
            }
        player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(
                        player.getInventory().selected));
        player.inventoryMenu.broadcastChanges();
        source.sendSuccess(
                () ->
                        Component.literal(
                                "Targeting stick ready. Right-click clear ground; Shift-right-click"
                                    + " to stop."),
                false);
        return 1;
    }

    private static boolean isStick(ItemStack stack) {
        return stack.is(Items.STICK)
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag()
                        .getBoolean(MARKER);
    }

    static void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!isStick(event.getItemStack()) || !(event.getEntity() instanceof ServerPlayer player))
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        use(player);
    }

    static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!isStick(event.getItemStack()) || !(event.getEntity() instanceof ServerPlayer player))
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        use(player);
    }

    private static void use(ServerPlayer player) {
        if (player.getCooldowns().isOnCooldown(Items.STICK)) return;
        player.getCooldowns().addCooldown(Items.STICK, 5);
        if (player.isShiftKeyDown()) {
            MinecraftMachinesQuadrupedManager.setWalking(player.createCommandSourceStack(), false);
            return;
        }
        var eye = player.getEyePosition();
        var hit =
                player.level()
                        .clip(
                                new ClipContext(
                                        eye,
                                        eye.add(player.getLookAngle().scale(128)),
                                        ClipContext.Block.COLLIDER,
                                        ClipContext.Fluid.NONE,
                                        player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) {
            player.displayClientMessage(
                    Component.literal("Aim at clear, flat ground within 128 blocks."), true);
            return;
        }
        var point = hit.getLocation();
        MinecraftMachinesQuadrupedManager.go(player.createCommandSourceStack(), point.x, point.z);
    }
}
