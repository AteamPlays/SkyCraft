package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.registry.SkyBlocks;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lightweight framebuffer-era replacement for the non-visual parts of WorldExporter.
 *
 * Minecraft renders itself through the framebuffer passthrough now, but Skyrim still needs:
 *  - REN_SOLIDS: blocks its NPCs must not walk through.
 *  - REN_LIGHTS: Minecraft torches/lava/glowstone lighting and hazard metadata.
 *
 * Only dirty sections are scanned; no block meshes or texture atlases are built here.
 */
public final class AuxWorldExporter {
    private static final int SECTIONS_PER_TICK = 8;
    private static final int LIGHT_RECORD_BYTES = 8;

    private static final ArrayDeque<Long> DIRTY = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static final Set<Long> SENT_SOLIDS = new HashSet<>();
    private static final Set<Long> SENT_LIGHTS = new HashSet<>();

    private static int seenGeneration = Integer.MIN_VALUE;
    private static ClientLevel seenLevel;
    private static int seededPlayerChunkX = Integer.MIN_VALUE;
    private static int seededPlayerChunkZ = Integer.MIN_VALUE;
    private static boolean logged;

    private AuxWorldExporter() {}

    public static void markDirty(BlockPos pos) {
        markDirty(
            SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()),
            SectionPos.blockToSectionCoord(pos.getZ())
        );
    }

    public static void markDirty(int sx, int sy, int sz) {
        long key = SectionPos.asLong(sx, sy, sz);
        synchronized (DIRTY) {
            if (QUEUED.add(key)) {
                DIRTY.addLast(key);
            }
        }
    }

    public static void reset() {
        synchronized (DIRTY) {
            DIRTY.clear();
            QUEUED.clear();
        }
        SENT_SOLIDS.clear();
        SENT_LIGHTS.clear();
        seenLevel = null;
        seenGeneration = Integer.MIN_VALUE;
        seededPlayerChunkX = seededPlayerChunkZ = Integer.MIN_VALUE;
    }

    public static void tick(Minecraft minecraft) {
        if (!SkyLink.active() || minecraft.level == null || minecraft.player == null) {
            return;
        }

        ClientLevel level = minecraft.level;
        int generation = SkyLink.generation();
        int pcx = minecraft.player.getBlockX() >> 4;
        int pcz = minecraft.player.getBlockZ() >> 4;

        if (seenLevel != level || seenGeneration != generation) {
            reset();
            seenLevel = level;
            seenGeneration = generation;
            seedLoadedSections(minecraft, level, pcx, pcz);
        } else if (pcx != seededPlayerChunkX || pcz != seededPlayerChunkZ) {
            seedEnteringChunks(minecraft, level, seededPlayerChunkX, seededPlayerChunkZ, pcx, pcz);
        }

        for (int i = 0; i < SECTIONS_PER_TICK; i++) {
            Long key;
            synchronized (DIRTY) {
                key = DIRTY.pollFirst();
                if (key == null) break;
                QUEUED.remove(key);
            }
            exportSection(level, key);
        }

        if (!logged && (!SENT_SOLIDS.isEmpty() || !SENT_LIGHTS.isEmpty())) {
            logged = true;
            SkyCraft.LOG.info(
                "SkyCraft: framebuffer auxiliary world bridge active (Skyrim NPC block collision + Minecraft block lights)"
            );
        }
    }

    private static void seedLoadedSections(
        Minecraft minecraft,
        ClientLevel level,
        int pcx,
        int pcz
    ) {
        seededPlayerChunkX = pcx;
        seededPlayerChunkZ = pcz;
        int radius = minecraft.options.getEffectiveRenderDistance() + 1;

        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                seedChunk(level, cx, cz);
            }
        }
    }

    /**
     * Moving one chunk should not rescan the entire render-distance square. Seed only chunks that
     * entered the radius; normal block updates are already tracked by ClientLevelDigMixin.
     */
    private static void seedEnteringChunks(
        Minecraft minecraft,
        ClientLevel level,
        int oldX,
        int oldZ,
        int newX,
        int newZ
    ) {
        int radius = minecraft.options.getEffectiveRenderDistance() + 1;
        int oldMinX = oldX - radius;
        int oldMaxX = oldX + radius;
        int oldMinZ = oldZ - radius;
        int oldMaxZ = oldZ + radius;

        seededPlayerChunkX = newX;
        seededPlayerChunkZ = newZ;

        for (int cx = newX - radius; cx <= newX + radius; cx++) {
            for (int cz = newZ - radius; cz <= newZ + radius; cz++) {
                if (cx >= oldMinX && cx <= oldMaxX && cz >= oldMinZ && cz <= oldMaxZ) {
                    continue;
                }
                seedChunk(level, cx, cz);
            }
        }
    }

    private static void seedChunk(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return;

        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            int sy = chunk.getSectionYFromSectionIndex(index);
            long key = SectionPos.asLong(cx, sy, cz);
            if (!sections[index].hasOnlyAir()
                || SENT_SOLIDS.contains(key)
                || SENT_LIGHTS.contains(key)) {
                markDirty(cx, sy, cz);
            }
        }
    }

    private static void exportSection(ClientLevel level, long key) {
        int sx = SectionPos.x(key);
        int sy = SectionPos.y(key);
        int sz = SectionPos.z(key);

        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }

        int sectionIndex = level.getSectionIndexFromSectionY(sy);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            clearSection(sx, sy, sz, key);
            return;
        }

        LevelChunkSection section = chunk.getSections()[sectionIndex];
        if (section.hasOnlyAir()) {
            clearSection(sx, sy, sz, key);
            return;
        }

        long[] solidBits = new long[64];
        int solidCount = 0;
        ByteBuffer lights = ByteBuffer.allocate(16 * 16 * 16 * LIGHT_RECORD_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        int lightCount = 0;

        BlockPos origin = SectionPos.of(sx, sy, sz).origin();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir() || state.is(SkyBlocks.SKYRIM_COLLISION.get())) continue;

                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);

                    if (!state.getCollisionShape(level, pos).isEmpty()) {
                        int bit = x + 16 * z + 256 * y;
                        solidBits[bit >> 6] |= 1L << (bit & 63);
                        solidCount++;
                    }

                    int emission = state.getLightEmission();
                    if (emission > 0) {
                        lights.put((byte)x)
                            .put((byte)y)
                            .put((byte)z)
                            .put((byte)emission)
                            .putInt(BlockLightColors.of(state));
                        lightCount++;
                    }
                }
            }
        }

        sendSolids(sx, sy, sz, solidCount, solidBits, key);
        sendLights(sx, sy, sz, lightCount, lights, key);
    }

    private static void clearSection(int sx, int sy, int sz, long key) {
        if (SENT_SOLIDS.remove(key)) {
            sendHeaderOnly(Proto.REN_SOLIDS, sx, sy, sz, 0);
        }
        if (SENT_LIGHTS.remove(key)) {
            sendHeaderOnly(Proto.REN_LIGHTS, sx, sy, sz, 0);
        }
    }

    private static void sendSolids(
        int sx,
        int sy,
        int sz,
        int count,
        long[] bits,
        long key
    ) {
        if (count == 0) {
            if (SENT_SOLIDS.remove(key)) {
                sendHeaderOnly(Proto.REN_SOLIDS, sx, sy, sz, 0);
            }
            return;
        }

        ByteBuffer header = header(sx, sy, sz, count);
        ByteBuffer body = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        for (long word : bits) body.putLong(word);
        body.flip();

        if (SkyLink.tryWriteRender(Proto.REN_SOLIDS, header, body)) {
            SENT_SOLIDS.add(key);
        } else {
            markDirty(sx, sy, sz);
        }
    }

    private static void sendLights(
        int sx,
        int sy,
        int sz,
        int count,
        ByteBuffer lights,
        long key
    ) {
        if (count == 0) {
            if (SENT_LIGHTS.remove(key)) {
                sendHeaderOnly(Proto.REN_LIGHTS, sx, sy, sz, 0);
            }
            return;
        }

        lights.flip();
        if (SkyLink.tryWriteRender(Proto.REN_LIGHTS, header(sx, sy, sz, count), lights)) {
            SENT_LIGHTS.add(key);
        } else {
            markDirty(sx, sy, sz);
        }
    }

    private static void sendHeaderOnly(int type, int sx, int sy, int sz, int count) {
        if (!SkyLink.tryWriteRender(type, header(sx, sy, sz, count), null)) {
            markDirty(sx, sy, sz);
        }
    }

    private static ByteBuffer header(int sx, int sy, int sz, int count) {
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(sx).putInt(sy).putInt(sz).putInt(count).flip();
        return header;
    }
}
