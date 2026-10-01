package dev.skycraft.neoforge;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.SkyCraftClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * Thin NeoForge bootstrap. SkyCraft's existing initialization remains in the original
 * classes while the 1.21.1 backport is brought up incrementally.
 */
@Mod(value = SkyCraft.MOD_ID, dist = Dist.CLIENT)
public final class SkyCraftNeoForge {
    public SkyCraftNeoForge() {
        SkyCraft.LOG.info("SkyCraft: starting NeoForge 1.21.1 compatibility layer");
        new SkyCraft().onInitialize();
        new SkyCraftClient().onInitializeClient();
    }
}
