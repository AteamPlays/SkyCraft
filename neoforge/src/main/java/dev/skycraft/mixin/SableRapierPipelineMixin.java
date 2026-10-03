package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SkyrimCollisionMirror;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable normally skips a physics section when the real Minecraft section contains only air.
 * Skyrim can have solid geometry in exactly those cells, so keep the section upload alive when
 * SkyCraft's virtual collision store says the section contains Skyrim collision.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class SableRapierPipelineMixin {
    @WrapOperation(
        method = "handleChunkSectionAddition",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"
        ),
        remap = false
    )
    private boolean skycraft$includeVirtualSkyrimSections(
        LevelChunkSection section,
        Operation<Boolean> original,
        LevelChunkSection suppliedSection,
        int sx,
        int sy,
        int sz,
        boolean uploadDataIfGlobal
    ) {
        return original.call(section) && !SkyrimCollisionMirror.hasSection(sx, sy, sz);
    }
}
