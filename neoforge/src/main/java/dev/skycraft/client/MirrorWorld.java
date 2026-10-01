package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Opens or creates the dedicated empty world SkyCraft uses for Minecraft simulation.
 */
public final class MirrorWorld {
    private static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(
        Registries.WORLD_PRESET,
        ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "mirror")
    );

    private static boolean attempted;
    private static long lastLog;

    private MirrorWorld() {}

    public static boolean isReady(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        return minecraft.level != null
            && minecraft.player != null
            && server != null
            && SkyCraft.WORLD_NAME.equals(server.getWorldData().getLevelName());
    }

    public static void tick(Minecraft minecraft, SkyLink.SkyState sky) {
        if (!sky.inGame() || minecraft.level != null) {
            return;
        }

        if (!(minecraft.screen instanceof TitleScreen title)) {
            if (System.currentTimeMillis() - lastLog > 5000L) {
                lastLog = System.currentTimeMillis();
                SkyCraft.LOG.info(
                    "SkyCraft: linked to Skyrim; waiting for title screen before opening mirror world (screen={})",
                    minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName()
                );
            }
            return;
        }

        if (attempted) {
            return;
        }
        attempted = true;

        if (minecraft.getLevelSource().levelExists(SkyCraft.WORLD_NAME)) {
            SkyCraft.LOG.info("SkyCraft: opening NeoForge mirror world");
            minecraft.createWorldOpenFlows().openWorld(SkyCraft.WORLD_NAME, () -> attempted = false);
            return;
        }

        SkyCraft.LOG.info("SkyCraft: creating NeoForge 1.21.1 mirror world");
        LevelSettings settings = new LevelSettings(
            SkyCraft.WORLD_NAME,
            GameType.SURVIVAL,
            false,
            Difficulty.NORMAL,
            true,
            new GameRules(),
            WorldDataConfiguration.DEFAULT
        );

        minecraft.createWorldOpenFlows().createFreshLevel(
            SkyCraft.WORLD_NAME,
            settings,
            new WorldOptions(0L, false, false),
            registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(PRESET)
                .createWorldDimensions(),
            title
        );
    }
}
