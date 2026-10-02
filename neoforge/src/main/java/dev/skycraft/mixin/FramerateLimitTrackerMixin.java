package dev.skycraft.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skyrim is the foreground renderer. Do not let Minecraft's normal unfocused-window throttling
 * starve the hand/HUD overlay while the player is actually using Skyrim.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void skycraft$unlimitedWhileLinked(CallbackInfoReturnable<Integer> cir) {
        if (SkyClient.linked()) {
            cir.setReturnValue(260);
        }
    }
}
