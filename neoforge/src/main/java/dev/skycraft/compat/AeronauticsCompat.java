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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.fml.ModList;

/**
 * Create: Aeronautics / Sable compatibility bridge.
 *
 * Skyrim collision stays virtual to Sable. The hot path is deliberately incremental: changed
 * collision cells are pushed directly into Rapier instead of re-uploading entire chunk sections.
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
                "SkyCraft: optimized virtual Sable Skyrim-collision bridge active ({} cached cells)",
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

    private static final class SableBridge {
        /**
         * Each Rapier cell update refreshes the changed voxel plus its six neighbors. Keeping this
         * budget modest prevents collision streaming from stealing an integrated-server tick.
         */
        private static final int BLOCK_UPDATES_PER_TICK = 192;
        private static final int CLEANUP_SECTIONS_PER_TICK = 1;

        private static final AtomicBoolean SERVER_TASK_QUEUED = new AtomicBoolean();

        private static boolean cleanupInitialized;
        private static boolean cleanupDone;
        private static int cleanupCenterChunkX;
        private static int cleanupCenterChunkZ;
        private static int cleanupMinSectionY;
        private static int cleanupMaxSectionY;
        private static int cleanupChunkIndex;
        private static int cleanupSectionY;
        private static int cleanupRemoved;

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
                    if (physics == null) {
                        SkyrimCollisionMirror.setPhysicsTracking(false);
                        return;
                    }

                    var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
                    boolean hasCraft = container != null && container.getLoadedCount() > 0;
                    SkyrimCollisionMirror.setPhysicsTracking(hasCraft);

                    if (!cleanupDone) {
                        cleanupLegacyProxiesIncrementally(level, physics, center, hasCraft);
                    }

                    // With no craft, there is nothing for Rapier to collide against. The mirror
                    // keeps the latest Skyrim snapshot but queues no physics deltas.
                    if (!hasCraft) {
                        return;
                    }

                    SkyrimCollisionMirror.takeDirtyBlocks(BLOCK_UPDATES_PER_TICK, key -> {
                        BlockPos pos = BlockPos.of(key);
                        LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
                        int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
                        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                            return;
                        }

                        LevelChunkSection section = chunk.getSections()[sectionIndex];
                        var state = SkyrimCollisionMirror.virtualState(pos);
                        if (state == null) {
                            state = Blocks.AIR.defaultBlockState();
                        }

                        // Call Rapier directly. SubLevelPhysicsSystem.handleBlockChange performs
                        // mass/wakeup bookkeeping intended for real Minecraft edits; Skyrim's
                        // static world stream needs only the collider update.
                        physics.getPipeline().handleBlockChange(
                            SectionPos.of(pos),
                            section,
                            pos.getX() & 15,
                            pos.getY() & 15,
                            pos.getZ() & 15,
                            Blocks.AIR.defaultBlockState(),
                            state
                        );
                    });
                } catch (Throwable t) {
                    SkyCraft.LOG.error("SkyCraft: Sable Skyrim-collision refresh failed", t);
                } finally {
                    SERVER_TASK_QUEUED.set(false);
                }
            });
        }

        /**
         * Older test builds materialized Skyrim collision into the save. Scan only one loaded
         * section per tick, and skip air sections outright, so cleanup is essentially invisible
         * to frame/tick time.
         */
        private static void cleanupLegacyProxiesIncrementally(
            ServerLevel level,
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics,
            BlockPos center,
            boolean hasCraft
        ) {
            if (!cleanupInitialized) {
                cleanupInitialized = true;
                cleanupCenterChunkX = center.getX() >> 4;
                cleanupCenterChunkZ = center.getZ() >> 4;
                cleanupMinSectionY = SectionPos.blockToSectionCoord(center.getY() - 64);
                cleanupMaxSectionY = SectionPos.blockToSectionCoord(center.getY() + 64);
                cleanupChunkIndex = 0;
                cleanupSectionY = cleanupMinSectionY;
            }

            for (int budget = 0; budget < CLEANUP_SECTIONS_PER_TICK && !cleanupDone; budget++) {
                if (cleanupChunkIndex >= 49) {
                    cleanupDone = true;
                    if (cleanupRemoved > 0) {
                        SkyCraft.LOG.info(
                            "SkyCraft: incrementally removed {} legacy Skyrim proxy blocks",
                            cleanupRemoved
                        );
                    }
                    return;
                }

                int cx = cleanupCenterChunkX + (cleanupChunkIndex % 7) - 3;
                int cz = cleanupCenterChunkZ + (cleanupChunkIndex / 7) - 3;
                int sy = cleanupSectionY;

                cleanupSectionY++;
                if (cleanupSectionY > cleanupMaxSectionY) {
                    cleanupSectionY = cleanupMinSectionY;
                    cleanupChunkIndex++;
                }

                var access = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(access instanceof LevelChunk chunk)) {
                    continue;
                }

                int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
                if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                    continue;
                }

                LevelChunkSection section = chunk.getSections()[sectionIndex];
                if (section.hasOnlyAir()) {
                    continue;
                }

                boolean changed = false;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (section.getBlockState(x, y, z).is(SkyBlocks.SKYRIM_COLLISION.get())) {
                                section.setBlockState(x, y, z, Blocks.AIR.defaultBlockState(), false);
                                cleanupRemoved++;
                                changed = true;
                            }
                        }
                    }
                }

                if (changed) {
                    chunk.setUnsaved(true);
                    if (hasCraft) {
                        physics.getPipeline().handleChunkSectionAddition(section, cx, sy, cz, false);
                    }
                }
            }
        }
    }
}
