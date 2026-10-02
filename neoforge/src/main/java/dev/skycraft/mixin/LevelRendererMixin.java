package dev.skycraft.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skyrim is the visible world. While linked, suppress Minecraft's own level (terrain, sky,
 * clouds, entities, weather) and clear the main target transparent. GameRenderer continues
 * afterwards, so the first-person hand, HUD and GUI screens are still rendered and can be
 * exported by FrameExporter just like the original SkyCraft client.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void skycraft$skipLevel(
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        if (!SkyClient.linked()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        var target = minecraft.getMainRenderTarget();
        target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(false);
        ci.cancel();
    }
}
