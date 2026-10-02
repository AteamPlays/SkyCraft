package dev.skycraft.mixin;

import dev.skycraft.world.SkyClip;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft projectiles treat exact Skyrim geometry as real block hits. */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Shadow
    protected abstract void onHit(HitResult hitResult);

    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
    private void skycraft$hitSkyrim(
        HitResult hitResult,
        CallbackInfoReturnable<ProjectileDeflection> cir
    ) {
        if (hitResult instanceof SkyClip.SkyrimHitResult) {
            this.onHit(hitResult);
            cir.setReturnValue(ProjectileDeflection.NONE);
        }
    }
}
