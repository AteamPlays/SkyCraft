package dev.skycraft.mixin;

import dev.skycraft.link.SkyLink;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Exact Skyrim ground is not Minecraft block collision for Player edge checks.
 * Preserve original SkyCraft behavior so crouching does not freeze movement indoors/on ledges.
 */
@Mixin(Player.class)
public abstract class PlayerEdgeMixin {
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void skycraft$crouchWalkAnywhere(
        Vec3 delta,
        MoverType moverType,
        CallbackInfoReturnable<Vec3> cir
    ) {
        if (SkyLink.active()) {
            cir.setReturnValue(delta);
        }
    }
}
