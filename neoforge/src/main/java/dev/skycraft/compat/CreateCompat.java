package dev.skycraft.compat;

import dev.skycraft.SkyCraft;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * Optional Create/Flywheel compatibility bootstrap.
 *
 * The outer class deliberately contains no Create types so SkyCraft still loads when Create is
 * absent. The nested bridge is only resolved after NeoForge confirms that Create is installed.
 */
public final class CreateCompat {
    private static final boolean CREATE_LOADED = ModList.get().isLoaded("create");
    private static volatile boolean contraptionsPresent;
    private static boolean initialized;

    private CreateCompat() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        if (!CREATE_LOADED) {
            SkyCraft.LOG.info("SkyCraft: Create not present; vanilla framebuffer compatibility active");
            return;
        }

        CreateBridge.init();
    }

    public static void tick(Minecraft minecraft) {
        if (!CREATE_LOADED) {
            contraptionsPresent = false;
            return;
        }
        CreateBridge.tick(minecraft);
    }

    public static boolean loaded() {
        return CREATE_LOADED;
    }

    public static boolean contraptionsPresent() {
        return contraptionsPresent;
    }

    private static String version(String modId) {
        return ModList.get()
            .getModContainerById(modId)
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("not loaded");
    }

    /**
     * This nested class is the first compile-time Create integration point. Keeping Create types
     * here makes the dependency optional in production while CI still compiles against the exact
     * Create 6.0.10 API.
     */
    private static final class CreateBridge {
        private static boolean loggedContraption;

        private static void init() {
            SkyCraft.LOG.info(
                "SkyCraft: Create compatibility enabled (Create {}, Flywheel {}, Ponder {})",
                version("create"),
                version("flywheel"),
                version("ponder")
            );
        }

        private static void tick(Minecraft minecraft) {
            if (minecraft.level == null || minecraft.player == null) {
                contraptionsPresent = false;
                return;
            }

            var nearby = minecraft.level.getEntitiesOfClass(
                com.simibubi.create.content.contraptions.AbstractContraptionEntity.class,
                minecraft.player.getBoundingBox().inflate(256.0)
            );

            contraptionsPresent = !nearby.isEmpty();
            if (contraptionsPresent && !loggedContraption) {
                loggedContraption = true;
                SkyCraft.LOG.info(
                    "SkyCraft: Create moving contraption detected; keeping it in the native Create/Flywheel framebuffer path"
                );
            }
        }
    }
}
