package dev.skycraft.mixin;

import dev.skycraft.client.SkyDigClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Left-clicking exact Skyrim geometry mines it like the material it is made from. */
@Mixin(Minecraft.class)
public abstract class MinecraftDigMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void skycraft$digStart(CallbackInfoReturnable<Boolean> cir) {
        Minecraft minecraft = (Minecraft)(Object)this;
        if (SkyDigClient.attack(minecraft)) {
            if (minecraft.player != null) {
                minecraft.player.swing(InteractionHand.MAIN_HAND);
            }
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"))
    private void skycraft$digHold(boolean down, CallbackInfo ci) {
        if (down) {
            SkyDigClient.attack((Minecraft)(Object)this);
        }
    }
}
