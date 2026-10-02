package dev.skycraft;

import dev.skycraft.client.SkyClient;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.registry.SkyBlocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = SkyCraft.MOD_ID, dist = Dist.CLIENT)
public final class SkyCraft {
    public static final String MOD_ID = "skycraft";
    public static final String WORLD_NAME = "SkyCraft";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public SkyCraft(IEventBus modBus) {
        SkyBlocks.register(modBus);
        LOG.info("SkyCraft: NeoForge 1.21.1 core loaded");
        SkyLink.announceRunning();
        SkyCollision.startConsumer();
        NeoForge.EVENT_BUS.addListener(SkyCraft::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        SkyClient.clientTick();
    }
}
