package dev.skycraft.mixin;

import dev.skycraft.client.SkyDigClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Breaking an exposed Minecraft block inside a Skyrim hole reveals the next material layer. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelDigMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void skycraft$blockChanged(
        BlockPos pos,
        BlockState oldState,
        BlockState newState,
        CallbackInfo ci
    ) {
        SkyDigClient.blockChanged((ClientLevel)(Object)this, pos, oldState, newState);
    }
}
