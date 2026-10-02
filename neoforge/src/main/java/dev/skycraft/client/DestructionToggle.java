package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.world.SkyDig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Pause-menu toggle for whether Minecraft mining/explosions can alter Skyrim geometry. */
public final class DestructionToggle {
    private static final String KEY = "destruction";

    private DestructionToggle() {}

    public static void register() {
        load();
        NeoForge.EVENT_BUS.addListener(DestructionToggle::onScreenInit);
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("skycraft.properties");
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause)
            || !pause.showsPauseMenu()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        boolean host = minecraft.hasSingleplayerServer();
        Button button = Button.builder(label(), b -> {
            SkyDig.destruction = !SkyDig.destruction;
            b.setMessage(label());
            save();
            SkyCraft.LOG.info(
                "SkyCraft: Skyrim destruction {}",
                SkyDig.destruction ? "on" : "off"
            );
        }).bounds(4, 4, 150, 20)
          .tooltip(Tooltip.create(Component.literal(
              host
                  ? "Mining Skyrim ground/rocks and Minecraft explosions can dig into Skyrim. Existing holes remain."
                  : "The host's Skyrim destruction setting decides."
          )))
          .build();

        button.active = host;
        event.addListener(button);
    }

    private static Component label() {
        return Component.literal(
            "Skyrim destruction: " + (SkyDig.destruction ? "On" : "Off")
        );
    }

    private static void load() {
        Properties props = new Properties();
        try (var in = Files.newBufferedReader(file())) {
            props.load(in);
        } catch (NoSuchFileException ignored) {
            return;
        } catch (IOException e) {
            SkyCraft.LOG.warn("SkyCraft: couldn't read {}", file(), e);
            return;
        }
        SkyDig.destruction =
            !"false".equalsIgnoreCase(props.getProperty(KEY, "true").trim());
    }

    private static void save() {
        Path file = file();
        String setting = KEY + "=" + SkyDig.destruction;
        try {
            List<String> lines = Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file))
                : new ArrayList<>(List.of("# SkyCraft"));
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith(KEY + "=")) {
                    lines.set(i, setting);
                    found = true;
                }
            }
            if (!found) {
                lines.add("# Mining and explosions dig into Skyrim's world.");
                lines.add(setting);
            }
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            SkyCraft.LOG.warn("SkyCraft: couldn't save {}", file, e);
        }
    }
}
