package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyDigBlast;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1 explosion parity: Skyrim dirt/rock/buildings contribute resistance, reachable cells
 * become Minecraft material before vanilla finalizes block destruction, and the resulting crater
 * is persisted as ordinary SkyCraft dug geometry.
 */
@Mixin(Explosion.class)
public abstract class ServerExplosionMixin {
    @Shadow @Final private Level level;
    @Unique private SkyDigBlast skycraft$blast;

    @Inject(method = "explode", at = @At("HEAD"))
    private void skycraft$begin(CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel) {
            this.skycraft$blast = SkyDigBlast.begin((Explosion)(Object)this, serverLevel);
        }
    }

    @WrapOperation(
        method = "explode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getBlockExplosionResistance(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"
        )
    )
    private Optional<Float> skycraft$skyrimResists(
        ExplosionDamageCalculator calculator,
        Explosion explosion,
        BlockGetter level,
        BlockPos pos,
        BlockState block,
        FluidState fluid,
        Operation<Optional<Float>> original
    ) {
        Optional<Float> vanilla = original.call(calculator, explosion, level, pos, block, fluid);
        return this.skycraft$blast != null ? this.skycraft$blast.resistance(pos, vanilla) : vanilla;
    }

    @Inject(method = "explode", at = @At("RETURN"))
    private void skycraft$materialize(CallbackInfo ci) {
        if (this.skycraft$blast != null) {
            Explosion self = (Explosion)(Object)this;
            this.skycraft$blast.materialize(self.getToBlow(), self.interactsWithBlocks());
        }
    }

    @Inject(method = "finalizeExplosion", at = @At("RETURN"))
    private void skycraft$finish(boolean spawnParticles, CallbackInfo ci) {
        Explosion self = (Explosion)(Object)this;
        if (this.skycraft$blast != null) {
            this.skycraft$blast.finish();
            this.skycraft$blast = null;
        }

        if (SkyLink.active() && this.level instanceof ServerLevel) {
            var center = self.center();
            SkyLink.pushEvent(
                Proto.EV_EXPLOSION, 0,
                (float)center.x, (float)center.y, (float)center.z,
                self.radius(), 0
            );
        }
    }
}
