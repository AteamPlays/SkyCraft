package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;

import com.sun.jna.Pointer;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import dev.skycraft.registry.SkyBlocks;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Virtual Skyrim collision exposed only to Sable's physics lookup.
 *
 * Unlike the first Aeronautics bridge, this class NEVER places proxy blocks into the actual
 * Minecraft level. Materializing Skyrim collision as real blocks made vanilla mobs collide twice,
 * let assemblers discover Skyrim as part of their structure, and caused block-update cascades.
 *
 * SkyCollision remains the single shared-memory consumer and forwards COL_CLEAR/COL_REGION here.
 */
public final class SkyrimCollisionMirror {
    private static final Map<Long, Integer> DESIRED = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> SECTION_COUNTS = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<Long> DIRTY_SECTIONS = new ConcurrentLinkedQueue<>();
    private static final Set<Long> QUEUED_SECTIONS = ConcurrentHashMap.newKeySet();

    private static volatile int epoch = -1;
    private static volatile long lastCountLog;

    private SkyrimCollisionMirror() {}

    public static int blockCount() {
        return DESIRED.size();
    }

    /** Virtual block state for Sable/Rapier. Real Minecraft blocks always win before this is used. */
    public static BlockState virtualState(BlockPos pos) {
        if (!SkyLink.active()) return null;
        Integer height = DESIRED.get(pos.asLong());
        if (height == null || height <= 0) return null;
        return SkyBlocks.SKYRIM_COLLISION.get().defaultBlockState()
            .setValue(SkyrimCollisionBlock.HEIGHT, height);
    }

    public static boolean hasSection(int sx, int sy, int sz) {
        if (!SkyLink.active()) return false;
        return SECTION_COUNTS.getOrDefault(SectionPos.asLong(sx, sy, sz), 0) > 0;
    }

    /** Re-upload all current virtual sections to Sable, e.g. after removing legacy real proxies. */
    public static void markAllSectionsDirty() {
        for (Long section : SECTION_COUNTS.keySet()) {
            markSectionDirty(section);
        }
    }

    /** Drains section IDs, not individual blocks, so Rapier can refresh whole sections efficiently. */
    public static int takeDirtySections(int max, LongConsumer consumer) {
        int count = 0;
        Long key;
        while (count < max && (key = DIRTY_SECTIONS.poll()) != null) {
            QUEUED_SECTIONS.remove(key);
            consumer.accept(key);
            count++;
        }
        return count;
    }

    /** Called only by SkyCollision, the single shared-ring consumer. */
    static void acceptClear(int newEpoch) {
        for (Long section : SECTION_COUNTS.keySet()) {
            markSectionDirty(section);
        }
        DESIRED.clear();
        SECTION_COUNTS.clear();
        epoch = newEpoch;
        SkyCraft.LOG.info("SkyCraft: virtual Sable collision cleared (epoch {})", newEpoch);
    }

    /** Called only by SkyCollision, the single shared-ring consumer. */
    static void acceptRegion(Pointer s, long p) {
        int minX = s.getInt(p);
        int minY = s.getInt(p + 4);
        int minZ = s.getInt(p + 8);
        int maxX = s.getInt(p + 12);
        int maxY = s.getInt(p + 16);
        int maxZ = s.getInt(p + 20);
        int msgEpoch = s.getInt(p + 24);
        int count = s.getInt(p + 28);

        if (epoch == -1) {
            epoch = msgEpoch;
            SkyCraft.LOG.info("SkyCraft: virtual Sable collision joined epoch {}", msgEpoch);
        }
        if (msgEpoch != epoch || count < 0 || count > 2_000_000) {
            return;
        }

        Map<Long, Integer> fresh = new HashMap<>(Math.max(16, count * 2));
        long entry = p + COL_REGION_HEADER_BYTES;

        for (int i = 0; i < count; i++, entry += COL_BLOCK_BYTES) {
            int x = s.getInt(entry);
            int y = s.getInt(entry + 4);
            int z = s.getInt(entry + 8);
            int height = heightFromBits(s, entry + 16);
            if (height > 0) {
                fresh.put(BlockPos.asLong(x, y, z), height);
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    long blockKey = BlockPos.asLong(x, y, z);
                    int next = fresh.getOrDefault(blockKey, 0);
                    Integer before = next == 0 ? DESIRED.remove(blockKey) : DESIRED.put(blockKey, next);
                    int old = before == null ? 0 : before;
                    if (old == next) continue;

                    long sectionKey = SectionPos.asLong(
                        SectionPos.blockToSectionCoord(x),
                        SectionPos.blockToSectionCoord(y),
                        SectionPos.blockToSectionCoord(z)
                    );

                    if (old == 0 && next != 0) {
                        SECTION_COUNTS.merge(sectionKey, 1, Integer::sum);
                    } else if (old != 0 && next == 0) {
                        SECTION_COUNTS.compute(sectionKey, (key, value) -> {
                            if (value == null || value <= 1) return null;
                            return value - 1;
                        });
                    }
                    markSectionDirty(sectionKey);
                }
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastCountLog > 3000L) {
            lastCountLog = now;
            SkyCraft.LOG.info(
                "SkyCraft: {} Skyrim collision cells available virtually to Sable ({} sections)",
                DESIRED.size(), SECTION_COUNTS.size()
            );
        }
    }

    private static void markSectionDirty(long key) {
        if (QUEUED_SECTIONS.add(key)) {
            DIRTY_SECTIONS.add(key);
        }
    }

    private static int heightFromBits(Pointer s, long bitsOff) {
        int top = -1;
        for (int y = 0; y < 8; y++) {
            if (s.getLong(bitsOff + y * 8L) != 0L) {
                top = y;
            }
        }
        return top + 1;
    }
}
