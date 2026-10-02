package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SkyClip;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Third-person camera pulls in against exact Skyrim walls/terrain, like original SkyCraft. */
@Mixin(Camera.class)
public abstract class CameraZoomMixin {
    @WrapOperation(
        method = "getMaxZoom",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/BlockGetter;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"
        )
    )
    private BlockHitResult skycraft$zoomAgainstSkyrim(
        BlockGetter level,
        ClipContext context,
        Operation<BlockHitResult> original
    ) {
        return SkyClip.refine(
            context.getFrom(),
            context.getTo(),
            original.call(level, context),
            SkyClip.Use.PROJECTILE
        );
    }
}
