package dev.ahmedhamedi.minecraft_machines.mixin;

import dev.ahmedhamedi.minecraft_machines.content.worm.WormCollisionCallback;
import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockWithSubLevelCollisionCallback.class, remap = false)
public interface BlockWithSubLevelCollisionCallbackMixin {
    @Inject(method = "sable$getCallback", at = @At("RETURN"), cancellable = true, remap = false)
    private static void minecraftMachines$wrapWormCollisionFilter(final BlockState state,
                                                                  final CallbackInfoReturnable<BlockSubLevelCollisionCallback> cir) {
        if (state == null || state.isAir()) {
            return;
        }
        cir.setReturnValue(WormCollisionCallback.wrap(cir.getReturnValue()));
    }
}
