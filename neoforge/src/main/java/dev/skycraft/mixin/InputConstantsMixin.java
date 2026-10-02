package dev.skycraft.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.skycraft.client.InputBridge;
import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While Skyrim owns input, GLFW's physical state belongs to the background Minecraft window.
 * Make vanilla's direct key-state polls see the replayed Skyrim state instead.
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void skycraft$isKeyDown(long window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (SkyClient.linked()) {
            cir.setReturnValue(InputBridge.isGlfwKeyDown(key));
        }
    }

    @Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
    private static void skycraft$keepSkyrimMouse(long window, int mode, double x, double y, CallbackInfo ci) {
        if (SkyClient.linked()) {
            ci.cancel();
        }
    }
}
