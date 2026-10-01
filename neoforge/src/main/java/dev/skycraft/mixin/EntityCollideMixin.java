package dev.skycraft.mixin;

import dev.skycraft.client.MirrorWorld;
import dev.skycraft.client.SkyClient;
import dev.skycraft.client.SkyCollider;
import dev.skycraft.world.SkyCollision;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds smooth Skyrim triangle collision after vanilla Minecraft block collision. */
@Mixin(Entity.class)
public abstract class EntityCollideMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void skycraft$collideWithSkyrim(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof LocalPlayer player
            && SkyClient.linked()
            && MirrorWorld.isReady(Minecraft.getInstance())
            && SkyCollision.active()
            && !player.noPhysics) {
            cir.setReturnValue(SkyCollider.collide(player, cir.getReturnValue()));
        }
    }
}
