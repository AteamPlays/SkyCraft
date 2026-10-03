package dev.skycraft.compat;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.MirrorWorld;
import dev.skycraft.client.SkyClient;
import dev.skycraft.registry.SkyBlocks;
import dev.skycraft.world.SkyrimCollisionMirror;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.ModList;

/**
 * Create: Aeronautics / Sable compatibility bridge.
 *
 * Rendering needs no replacement renderer: Sable/Flywheel sub-levels are already in Minecraft's
 * framebuffer before SkyCraft captures it.
 *
 * Physics is virtualized: SkyrimCollisionMirror exposes proxy BlockStates only through Sable's
 * LevelAccelerator mixin. Nothing is placed into the actual Minecraft world, so mobs, assemblers,
 * mining, redstone and normal block updates never see Skyrim collision as Minecraft blocks.
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

        SableBridge.tick(minecraft);

        if (!loggedPhysics && SkyrimCollisionMirror.blockCount() > 0) {
            loggedPhysics = true;
            SkyCraft.LOG.info(
                "SkyCraft: virtual Sable Skyrim-collision bridge active ({} collision cells)",
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

    /**
     * Kept in a nested class so SkyCraft's base path does not resolve Sable classes when Sable is
     * absent. This class is entered only after ModList confirms Sable is loaded.
     */
    private static final class SableBridge {
        private static final AtomicBoolean SERVER_TASK_QUEUED = new AtomicBoolean();
        private static volatile boolean legacyCleanupDone;

        private static void tick(Minecraft minecraft) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            if (server == null || !SERVER_TASK_QUEUED.compareAndSet(false, true)) {
                return;
            }

            BlockPos center = minecraft.player != null
                ? minecraft.player.blockPosition()
                : BlockPos.ZERO;

            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics =
                        dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.get(level);
                    if (physics == null) return;

                    if (!legacyCleanupDone) {
                        int removed = purgeLegacyMaterializedProxies(level, physics, center);
                        legacyCleanupDone = true;
                        SkyrimCollisionMirror.markAllSectionsDirty();
                        if (removed > 0) {
                            SkyCraft.LOG.info(
                                "SkyCraft: removed {} legacy materialized Skyrim proxy blocks from the test world",
                                removed
                            );
                        }
                    }

                    SkyrimCollisionMirror.takeDirtySections(24, sectionKey -> {
                        int sx = SectionPos.x(sectionKey);
                        int sy = SectionPos.y(sectionKey);
                        int sz = SectionPos.z(sectionKey);

                        LevelChunk chunk = level.getChunk(sx, sz);
                        int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
                        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                            return;
                        }

                        LevelChunkSection section = chunk.getSections()[sectionIndex];
                        physics.getPipeline().handleChunkSectionAddition(section, sx, sy, sz, false);
                    });
                } catch (Throwable t) {
                    SkyCraft.LOG.error("SkyCraft: Sable Skyrim-collision refresh failed", t);
                } finally {
                    SERVER_TASK_QUEUED.set(false);
                }
            });
        }

        /**
         * The first Aeronautics build wrote collision proxies into the save. Remove only those
         * SkyCraft-owned blocks around the current Skyrim collision radius, without firing normal
         * block-neighbor/destruction events. They are invisible implementation details, not world
         * content.
         */
        private static int purgeLegacyMaterializedProxies(
            ServerLevel level,
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics,
            BlockPos center
        ) {
            int removed = 0;
            int pcx = center.getX() >> 4;
            int pcz = center.getZ() >> 4;
            int minSy = SectionPos.blockToSectionCoord(center.getY() - 64);
            int maxSy = SectionPos.blockToSectionCoord(center.getY() + 64);

            for (int cx = pcx - 3; cx <= pcx + 3; cx++) {
                for (int cz = pcz - 3; cz <= pcz + 3; cz++) {
                    LevelChunk chunk = level.getChunk(cx, cz);
                    boolean chunkChanged = false;

                    for (int sy = minSy; sy <= maxSy; sy++) {
                        int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
                        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) continue;

                        LevelChunkSection section = chunk.getSections()[sectionIndex];
                        boolean sectionChanged = false;

                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    if (section.getBlockState(x, y, z).is(SkyBlocks.SKYRIM_COLLISION.get())) {
                                        section.setBlockState(x, y, z, Blocks.AIR.defaultBlockState(), false);
                                        removed++;
                                        sectionChanged = true;
                                    }
                                }
                            }
                        }

                        if (sectionChanged) {
                            chunkChanged = true;
                            // Immediately replace the old real-block section in Rapier. The
                            // LevelAccelerator mixin supplies current virtual Skyrim states.
                            physics.getPipeline().handleChunkSectionAddition(section, cx, sy, cz, false);
                        }
                    }

                    if (chunkChanged) {
                        chunk.setUnsaved(true);
                    }
                }
            }
            return removed;
        }
    }
}
