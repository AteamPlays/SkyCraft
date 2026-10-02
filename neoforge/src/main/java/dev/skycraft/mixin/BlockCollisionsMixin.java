package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Original SkyCraft virtual-collision hook for Minecraft 1.21.1.
 *
 * Skyrim collision masks are merged into vanilla collision queries without placing proxy blocks
 * in the mirror world. Players are excluded from the voxel layer and use exact triangles instead.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
    @WrapOperation(
        method = "computeNext",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
        )
    )
    private VoxelShape skycraft$addSkyrimShape(
        BlockState state,
        BlockGetter level,
        BlockPos pos,
        CollisionContext context,
        Operation<VoxelShape> original
    ) {
        VoxelShape blockShape = original.call(state, level, pos, context);

        if (context instanceof EntityCollisionContext entityContext
            && SkyCollision.usesSmoothCollider(entityContext.getEntity())) {
            return blockShape;
        }

        VoxelShape sky = SkyCollision.shapeAt(pos);
        if (sky == null) {
            return blockShape;
        }
        return blockShape.isEmpty() ? sky : Shapes.or(blockShape, sky);
    }
}
