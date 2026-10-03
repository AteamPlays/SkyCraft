package dev.skycraft.mixin;

import dev.skycraft.world.SkyrimCollisionMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives Sable's private world accelerator a virtual view of Skyrim collision without ever
 * materializing those proxy blocks in the real Minecraft level.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.util.LevelAccelerator", remap = false)
public abstract class SableLevelAcceleratorMixin {
    @Inject(
        method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void skycraft$virtualSkyrim(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        replaceAirWithVirtual(pos, cir);
    }

    @Inject(
        method = "getBlockState(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void skycraft$virtualSkyrimCached(
        LevelChunk chunk,
        BlockPos pos,
        CallbackInfoReturnable<BlockState> cir
    ) {
        replaceAirWithVirtual(pos, cir);
    }

    private static void replaceAirWithVirtual(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        BlockState real = cir.getReturnValue();
        if (real == null || !real.isAir()) return;

        BlockState virtual = SkyrimCollisionMirror.virtualState(pos);
        if (virtual != null) {
            cir.setReturnValue(virtual);
        }
    }
}
