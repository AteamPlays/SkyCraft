package dev.skycraft.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skyrim owns the foreground window while linked. Minecraft must continue rendering/simulating
 * in the background so its hand/HUD overlay and physics do not pause when focus moves to Skyrim.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
    private void skycraft$focused(CallbackInfoReturnable<Boolean> cir) {
        if (SkyClient.linked()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
    private void skycraft$notIconified(CallbackInfoReturnable<Boolean> cir) {
        if (SkyClient.linked()) {
            cir.setReturnValue(false);
        }
    }
}
