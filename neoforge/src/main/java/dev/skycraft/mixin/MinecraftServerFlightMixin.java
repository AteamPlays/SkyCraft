package dev.skycraft.mixin;

import dev.skycraft.link.SkyLink;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While SkyCraft owns movement, Minecraft may legitimately see the player hovering on Skyrim
 * geometry it cannot represent as vanilla blocks. Do not let the integrated server kick for flying.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerFlightMixin {
    @Inject(method = "isFlightAllowed", at = @At("HEAD"), cancellable = true, require = 0)
    private void skycraft$allowSkyrimFlight(CallbackInfoReturnable<Boolean> cir) {
        if (SkyLink.active()) {
            cir.setReturnValue(true);
        }
    }
}
