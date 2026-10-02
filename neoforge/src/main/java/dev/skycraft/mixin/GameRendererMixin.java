package dev.skycraft.mixin;

import dev.skycraft.client.FrameExporter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Splits Minecraft's frame at the exact 1.21.1 world/hand boundary.
 *
 * The renderHand field is read only after LevelRenderer and NeoForge's AFTER_LEVEL stage have
 * completed, so Create/Flywheel world rendering is already in the framebuffer. FrameExporter
 * preserves that colour+depth and clears transparent before vanilla renders the first-person hand.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderHand:Z",
            opcode = Opcodes.GETFIELD,
            ordinal = 0
        )
    )
    private void skycraft$captureWorldBeforeHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        FrameExporter.captureWorld();
    }
}
