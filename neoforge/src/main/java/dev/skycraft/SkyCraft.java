package dev.skycraft;

import dev.skycraft.client.SkyClient;
import dev.skycraft.link.SkyLink;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = SkyCraft.MOD_ID, dist = Dist.CLIENT)
public final class SkyCraft {
    public static final String MOD_ID = "skycraft";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public SkyCraft() {
        LOG.info("SkyCraft: NeoForge 1.21.1 core loaded");
        SkyLink.announceRunning();
        NeoForge.EVENT_BUS.addListener(SkyCraft::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        SkyClient.clientTick();
    }
}
