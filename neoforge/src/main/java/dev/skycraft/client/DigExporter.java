package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyDig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Sends only SkyCraft's dug-cell masks to Skyrim.
 *
 * The original WorldExporter carried REN_DUG alongside legacy block meshes. The framebuffer
 * renderer no longer needs those meshes, but Skyrim's native Dig subsystem still needs the
 * 16x16x16 hole masks to cut its own geometry/collision. This keeps that one protocol feature.
 */
public final class DigExporter {
    private static final Map<Long, Integer> SENT = new HashMap<>();
    private static int seenWorld = Integer.MIN_VALUE;
    private static int seenGeneration = Integer.MIN_VALUE;
    private static int ticks;
    private static boolean logged;

    private DigExporter() {}

    public static void tick(Minecraft minecraft) {
        if (++ticks % 5 != 0 || !SkyLink.active()) {
            return;
        }

        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            SENT.clear();
            return;
        }

        int world = SkyDigClient.world();
        int generation = SkyLink.generation();
        if (world != seenWorld || generation != seenGeneration) {
            seenWorld = world;
            seenGeneration = generation;
            SENT.clear();
        }

        int radius = minecraft.options.getEffectiveRenderDistance() + 1;
        int pcx = minecraft.player.getBlockX() >> 4;
        int pcz = minecraft.player.getBlockZ() >> 4;
        Set<Long> current = new HashSet<>();

        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                SkyDig.DugColumn column = SkyDig.column(chunk);
                for (SkyDig.DugSection section : column.sections()) {
                    if (section.world() != world || section.bits().length != 64) {
                        continue;
                    }

                    long key = SectionPos.asLong(cx, section.sectionY(), cz);
                    current.add(key);
                    int hash = Arrays.hashCode(section.bits());
                    Integer before = SENT.get(key);
                    if (before == null || before.intValue() != hash) {
                        if (send(cx, section.sectionY(), cz, world, section.bits())) {
                            SENT.put(key, hash);
                        }
                    }
                }
            }
        }

        // A previously-sent section inside the current working radius can become completely clear.
        // Explicitly remove it from Skyrim's Dig map.
        Iterator<Map.Entry<Long, Integer>> it = SENT.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Integer> entry = it.next();
            long key = entry.getKey();
            int sx = SectionPos.x(key);
            int sz = SectionPos.z(key);
            if (Math.abs(sx - pcx) <= radius && Math.abs(sz - pcz) <= radius && !current.contains(key)) {
                if (send(sx, SectionPos.y(key), sz, world, null)) {
                    it.remove();
                }
            }
        }

        if (!logged && !SENT.isEmpty()) {
            logged = true;
            SkyCraft.LOG.info("SkyCraft: persistent Skyrim dig masks are streaming to SKSE");
        }
    }

    public static void resendAll() {
        SENT.clear();
    }

    private static boolean send(int sx, int sy, int sz, int world, long[] bits) {
        int count = 0;
        if (bits != null) {
            for (long word : bits) {
                count += Long.bitCount(word);
            }
        }

        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sx)
            .putInt(sy)
            .putInt(sz)
            .putInt(count)
            .putInt(world)
            .putInt(0);
        header.flip();

        ByteBuffer body = null;
        if (count > 0) {
            body = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 64; i++) {
                body.putLong(bits[i]);
            }
            body.flip();
        }

        return SkyLink.tryWriteRender(Proto.REN_DUG, header, body);
    }
}
