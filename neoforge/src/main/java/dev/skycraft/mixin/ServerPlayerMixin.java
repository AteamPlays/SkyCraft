package dev.skycraft.mixin;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "crit", at = @At("HEAD"))
    private void skycraft$critSkyrim(Entity entity, CallbackInfo ci) {
        if (entity instanceof SkyrimActorEntity proxy) {
            proxy.markCritical();
        }
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void skycraft$diesInSkyrim(DamageSource source, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer)(Object)this;
        if (!SkyCombat.isHost(self) || !SkyLink.active()) {
            return;
        }

        int attacker = SkyCombat.attackerFormId(source);
        SkyLink.pushEvent(Proto.EV_PLAYER_DIED, attacker, 0, 0, 0, 0, 0);
        SkyCraft.LOG.info("SkyCraft: Minecraft player died ({}); telling Skyrim", source.getMsgId());
    }
}
