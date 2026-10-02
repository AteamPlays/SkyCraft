package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sand, gravel and concrete powder rest on Skyrim ground instead of falling through it. */
@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void skycraft$restOnSkyrim(
        BlockState state,
        ServerLevel level,
        BlockPos pos,
        RandomSource random,
        CallbackInfo ci
    ) {
        if (SkyCollision.supportsFromBelow(pos)) {
            ci.cancel();
        }
    }
}
