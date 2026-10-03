package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;

import com.sun.jna.Pointer;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import dev.skycraft.registry.SkyBlocks;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Virtual Skyrim collision exposed only to Sable's physics lookup.
 *
 * The actual Minecraft level is never populated with proxy blocks. Sable sees these states through
 * SableLevelAcceleratorMixin while vanilla mobs, assembly search, redstone and mining see only the
 * real Minecraft world.
 *
 * Performance rule: streamed collision is always kept current in DESIRED, but change events are
 * queued for Rapier only while at least one Sable sub-level is loaded. A newly assembled craft
 * gets the current virtual world through Sable's normal section upload, so rebuilding physics
 * sections continuously while no craft exists is pure wasted work.
 */
public final class SkyrimCollisionMirror {
    private static final Map<Long, Integer> DESIRED = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> SECTION_COUNTS = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<Long> DIRTY_BLOCKS = new ConcurrentLinkedQueue<>();
    private static final java.util.Set<Long> QUEUED_BLOCKS = ConcurrentHashMap.newKeySet();

    private static volatile boolean physicsTracking;
    private static volatile int epoch = -1;
    private static volatile long lastCountLog;

    private SkyrimCollisionMirror() {}

    public static int blockCount() {
        return DESIRED.size();
    }

    /**
     * Enable incremental Rapier updates only while a physics craft exists.
     * Initial craft collision is supplied by Sable's ordinary section upload.
     */
    public static void setPhysicsTracking(boolean enabled) {
        if (physicsTracking == enabled) return;
        physicsTracking = enabled;

        // Old queued deltas are irrelevant when tracking starts/stops. When a craft starts,
        // Sable uploads its nearby world sections from the current DESIRED snapshot.
        DIRTY_BLOCKS.clear();
        QUEUED_BLOCKS.clear();
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

    /**
     * Drain coalesced changed cells. Rapier's handleBlockChange updates only this voxel and its
     * six neighbors, avoiding the old 4096-cell section rebuild for every streamed region.
     */
    public static int takeDirtyBlocks(int max, LongConsumer consumer) {
        int count = 0;
        Long key;
        while (count < max && (key = DIRTY_BLOCKS.poll()) != null) {
            QUEUED_BLOCKS.remove(key);
            consumer.accept(key);
            count++;
        }
        return count;
    }

    /** Called only by SkyCollision, the single shared-ring consumer. */
    static void acceptClear(int newEpoch) {
        if (physicsTracking) {
            for (Long key : DESIRED.keySet()) {
                markBlockDirty(key);
            }
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

                    if (physicsTracking) {
                        markBlockDirty(blockKey);
                    }
                }
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastCountLog > 5000L) {
            lastCountLog = now;
            SkyCraft.LOG.info(
                "SkyCraft: {} Skyrim collision cells cached for Sable ({} sections, incremental={})",
                DESIRED.size(), SECTION_COUNTS.size(), physicsTracking
            );
        }
    }

    private static void markBlockDirty(long key) {
        if (QUEUED_BLOCKS.add(key)) {
            DIRTY_BLOCKS.add(key);
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
