package dev.skycraft.compat;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.MirrorWorld;
import dev.skycraft.client.SkyClient;
import dev.skycraft.world.SkyrimCollisionMirror;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * Create: Aeronautics / Sable compatibility bridge.
 *
 * Rendering needs no replacement renderer: Sable and Flywheel render their sub-levels into
 * Minecraft's world framebuffer, and SkyCraft captures that framebuffer after the level pass.
 *
 * Physics does need a bridge. Skyrim collision arrives as exact triangles + 1/8-block occupancy;
 * Sable/Rapier expects stable Minecraft BlockStates in the parent level. SkyrimCollisionMirror
 * materializes only that physics representation as invisible blocks so moving Aeronautics
 * sub-levels can collide with Skyrim while the player continues using exact triangle collision.
 */
public final class AeronauticsCompat {
    private static final boolean SABLE_LOADED = ModList.get().isLoaded("sable");
    private static final boolean SIMULATED_LOADED = ModList.get().isLoaded("simulated");
    private static final boolean AERONAUTICS_LOADED = ModList.get().isLoaded("aeronautics");

    private static boolean initialized;
    private static boolean loggedPhysics;

    private AeronauticsCompat() {}

    public static void init() {
        if (initialized) return;
        initialized = true;

        if (!SABLE_LOADED && !AERONAUTICS_LOADED) {
            SkyCraft.LOG.info("SkyCraft: Aeronautics/Sable not present");
            return;
        }

        SkyCraft.LOG.info(
            "SkyCraft: Aeronautics compatibility enabled (Aeronautics {}, Simulated {}, Sable {})",
            version("aeronautics"),
            version("simulated"),
            version("sable")
        );

        if (AERONAUTICS_LOADED && !SABLE_LOADED) {
            SkyCraft.LOG.warn("SkyCraft: Aeronautics is loaded without Sable; physics compatibility cannot start");
        }
    }

    public static void tick(Minecraft minecraft) {
        if (!SABLE_LOADED || !SkyClient.linked() || !MirrorWorld.isReady(minecraft)) {
            return;
        }

        // Applies a bounded batch on the integrated-server thread. The queue is filled directly
        // by SkyCollision's single shared-memory consumer, so there is no second ring reader.
        SkyrimCollisionMirror.flushToMirrorWorld(minecraft);

        if (!loggedPhysics && SkyrimCollisionMirror.blockCount() > 0) {
            loggedPhysics = true;
            SkyCraft.LOG.info(
                "SkyCraft: Sable Skyrim-collision bridge active ({} proxy blocks streamed)",
                SkyrimCollisionMirror.blockCount()
            );
        }
    }

    public static boolean loaded() {
        return AERONAUTICS_LOADED;
    }

    public static boolean sableLoaded() {
        return SABLE_LOADED;
    }

    private static String version(String modId) {
        return ModList.get()
            .getModContainerById(modId)
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("not loaded");
    }
}
