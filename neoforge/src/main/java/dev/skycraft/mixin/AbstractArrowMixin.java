package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pins Minecraft arrows/tridents into the real Skyrim actor skeleton after a combat-proxy hit. */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
    @Unique
    private Vec3 skycraft$hitAt;

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void skycraft$rememberHit(EntityHitResult hitResult, CallbackInfo ci) {
        skycraft$hitAt = hitResult.getLocation();
    }

    @WrapOperation(
        method = "onHitEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setArrowCount(I)V"
        )
    )
    private void skycraft$stickInSkyrimActor(
        LivingEntity mob,
        int count,
        Operation<Void> original
    ) {
        original.call(mob, count);
        if (!(mob instanceof SkyrimActorEntity actor)
            || skycraft$hitAt == null
            || !SkyLink.active()) {
            return;
        }

        AbstractArrow self = (AbstractArrow)(Object)this;
        Vec3 velocity = self.getDeltaMovement();
        float yaw = (float)(Mth.atan2(velocity.x, velocity.z) * Mth.RAD_TO_DEG);
        float pitch = (float)(Mth.atan2(velocity.y, velocity.horizontalDistance()) * Mth.RAD_TO_DEG);
        int texture = self instanceof SpectralArrow
            ? 2
            : self instanceof Arrow arrow && arrow.getColor() > 0 ? 1 : 0;

        Vec3 at = skycraft$hitAt;
        SkyLink.pushEvent(
            Proto.EV_ARROW_STUCK,
            actor.formId(),
            (float)at.x,
            (float)at.y,
            (float)at.z,
            yaw,
            Float.floatToRawIntBits(pitch),
            texture
        );
    }
}
