package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Water/lava placed in Minecraft settle and flow over Skyrim terrain instead of through it. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    private static final float FALLING_SURFACE = 8.0F / 9.0F;
    private static final float MARGIN = 0.05F;

    @Inject(method = "canPassThroughWall", at = @At("HEAD"), cancellable = true)
    private static void skycraft$skyrimWall(
        Direction direction,
        BlockGetter level,
        BlockPos sourcePos,
        BlockState sourceState,
        BlockPos targetPos,
        BlockState targetState,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!targetState.isAir() || !SkyCollision.active() || direction == Direction.UP) return;

        if (!SkyCollision.isKnown(targetPos.getX(), targetPos.getY(), targetPos.getZ())) {
            cir.setReturnValue(false);
            return;
        }

        if (direction == Direction.DOWN) {
            if (SkyCollision.hasGeometry(sourcePos)) {
                cir.setReturnValue(false);
                return;
            }
            float top = SkyCollision.groundTop(targetPos);
            if (top >= FALLING_SURFACE - MARGIN) {
                cir.setReturnValue(false);
            }
            return;
        }

        if (!SkyCollision.hasGeometry(targetPos)) {
            if (SkyCollision.hasGeometry(targetPos.above())) {
                cir.setReturnValue(false);
            }
            return;
        }

        FluidState fluid = sourceState.getFluidState();
        float surface;
        if (fluid.isEmpty()) {
            surface = 0.8F;
        } else if (fluid.getValue(FlowingFluid.FALLING)) {
            surface = 7.0F / 9.0F;
        } else {
            int drop = fluid.is(FluidTags.LAVA) ? 2 : 1;
            surface = Math.max(0, fluid.getAmount() - drop) / 9.0F;
        }

        float top = SkyCollision.groundTop(targetPos);
        boolean flatOrDownhill = top <= SkyCollision.groundTop(sourcePos) + 0.13F;
        if (!flatOrDownhill && top >= surface - MARGIN) {
            cir.setReturnValue(false);
        }
    }
}
