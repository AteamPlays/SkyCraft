package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;

import com.sun.jna.Pointer;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Skyrim's exact collision triangles streamed through SkyCraft's shared-memory ring.
 *
 * This first NeoForge collision slice intentionally ignores the 8x8x8 voxel masks used
 * for non-player entities. The local player gets the exact triangle collider first; voxel
 * collision for mobs/items is layered back in after this path is proven.
 */
public final class SkyCollision {
    public static final int REGION_SIZE = 8;

    private static final ConcurrentHashMap<Long, SkyTri[]> TRIS = new ConcurrentHashMap<>();
    private static final Set<Long> KNOWN_REGIONS = ConcurrentHashMap.newKeySet();
    private static volatile int epoch = -1;
    private static Thread consumer;

    private SkyCollision() {}

    private static long regionKey(int rx, int ry, int rz) {
        return BlockPos.asLong(rx, ry, rz);
    }

    public static boolean active() {
        return !KNOWN_REGIONS.isEmpty();
    }

    public static int regionCount() {
        return KNOWN_REGIONS.size();
    }

    public static int triangleCount() {
        int n = 0;
        for (SkyTri[] tris : TRIS.values()) n += tris.length;
        return n;
    }

    public static boolean isKnown(int x, int y, int z) {
        return KNOWN_REGIONS.contains(regionKey(
            Math.floorDiv(x, REGION_SIZE),
            Math.floorDiv(y, REGION_SIZE),
            Math.floorDiv(z, REGION_SIZE)
        ));
    }

    /** True when collision has arrived for the current region and the region below. */
    public static boolean readyAround(double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        return isKnown(bx, by, bz) && isKnown(bx, by - REGION_SIZE, bz);
    }

    public static void trianglesNear(AABB box, List<SkyTri> out) {
        if (TRIS.isEmpty()) return;
        int rx0 = Math.floorDiv((int) Math.floor(box.minX), REGION_SIZE);
        int rx1 = Math.floorDiv((int) Math.floor(box.maxX), REGION_SIZE);
        int ry0 = Math.floorDiv((int) Math.floor(box.minY), REGION_SIZE);
        int ry1 = Math.floorDiv((int) Math.floor(box.maxY), REGION_SIZE);
        int rz0 = Math.floorDiv((int) Math.floor(box.minZ), REGION_SIZE);
        int rz1 = Math.floorDiv((int) Math.floor(box.maxZ), REGION_SIZE);
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int ry = ry0; ry <= ry1; ry++) {
                for (int rz = rz0; rz <= rz1; rz++) {
                    SkyTri[] tris = TRIS.get(regionKey(rx, ry, rz));
                    if (tris == null) continue;
                    for (SkyTri tri : tris) {
                        if (tri.maxX >= box.minX && tri.minX <= box.maxX
                            && tri.maxY >= box.minY && tri.minY <= box.maxY
                            && tri.maxZ >= box.minZ && tri.minZ <= box.maxZ) {
                            out.add(tri);
                        }
                    }
                }
            }
        }
    }

    public static synchronized void startConsumer() {
        if (consumer != null) return;
        consumer = new Thread(SkyCollision::consumeLoop, "SkyCraft collision");
        consumer.setDaemon(true);
        consumer.start();
    }

    private static void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!drainOnce()) Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                SkyCraft.LOG.error("SkyCraft: collision consumer error", t);
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static boolean drainOnce() {
        Pointer s = SkyLink.segment();
        if (s == null) return false;

        long head = SkyLink.collisionHead();
        long tail = SkyLink.collisionTail();
        if (tail >= head) return false;

        long data = OFF_COLLISION_RING + CR_DATA;
        while (tail < head) {
            long pos = tail % CR_DATA_BYTES;
            int type = s.getInt(data + pos);
            int payloadBytes = s.getInt(data + pos + 4L);
            if (type == COL_PAD) {
                tail += CR_DATA_BYTES - pos;
                continue;
            }

            long payload = data + pos + 8L;
            switch (type) {
                case COL_CLEAR -> clear(s.getInt(payload));
                case COL_REGION -> readRegionHeader(s, payload);
                case COL_TRIS -> readTris(s, payload);
                default -> SkyCraft.LOG.warn("SkyCraft: unknown collision message {}", type);
            }
            tail += align8(8L + Integer.toUnsignedLong(payloadBytes));
        }
        SkyLink.setCollisionTail(tail);
        return true;
    }

    private static long align8(long value) {
        return (value + 7L) & ~7L;
    }

    private static void adoptEpochIfFresh(int messageEpoch) {
        if (epoch == -1) {
            epoch = messageEpoch;
            SkyCraft.LOG.info("SkyCraft: joined collision epoch {} already in progress", messageEpoch);
        }
    }

    private static void clear(int newEpoch) {
        TRIS.clear();
        KNOWN_REGIONS.clear();
        epoch = newEpoch;
        SkyCraft.LOG.info("SkyCraft: collision cleared (epoch {})", newEpoch);
    }

    /**
     * COL_REGION's voxel payload is ignored for this first slice, but its inclusive bounds
     * tell us which streamed regions are complete and safe for local-player physics.
     */
    private static void readRegionHeader(Pointer s, long p) {
        int minX = s.getInt(p);
        int minY = s.getInt(p + 4L);
        int minZ = s.getInt(p + 8L);
        int maxX = s.getInt(p + 12L);
        int maxY = s.getInt(p + 16L);
        int maxZ = s.getInt(p + 20L);
        int messageEpoch = s.getInt(p + 24L);
        adoptEpochIfFresh(messageEpoch);
        if (messageEpoch != epoch) return;

        for (int rx = Math.floorDiv(minX, REGION_SIZE); rx <= Math.floorDiv(maxX, REGION_SIZE); rx++) {
            for (int ry = Math.floorDiv(minY, REGION_SIZE); ry <= Math.floorDiv(maxY, REGION_SIZE); ry++) {
                for (int rz = Math.floorDiv(minZ, REGION_SIZE); rz <= Math.floorDiv(maxZ, REGION_SIZE); rz++) {
                    KNOWN_REGIONS.add(regionKey(rx, ry, rz));
                }
            }
        }
    }

    private static void readTris(Pointer s, long p) {
        int minX = s.getInt(p);
        int minY = s.getInt(p + 4L);
        int minZ = s.getInt(p + 8L);
        int messageEpoch = s.getInt(p + 24L);
        int count = s.getInt(p + 28L);
        adoptEpochIfFresh(messageEpoch);
        if (messageEpoch != epoch || count < 0 || count > 1_000_000) return;

        List<SkyTri> kept = new ArrayList<>(Math.min(count, 4096));
        float[] vertices = new float[9];
        long e = p + COL_REGION_HEADER_BYTES;
        for (int i = 0; i < count; i++, e += COL_TRI_BYTES) {
            for (int k = 0; k < 9; k++) vertices[k] = s.getFloat(e + k * 4L);
            int flags = s.getInt(e + 36L);
            if ((flags & TRI_GHOST) != 0) continue;
            SkyTri tri = new SkyTri(vertices, 0, flags);
            if (!tri.degenerate()) kept.add(tri);
        }

        long key = regionKey(
            Math.floorDiv(minX, REGION_SIZE),
            Math.floorDiv(minY, REGION_SIZE),
            Math.floorDiv(minZ, REGION_SIZE)
        );
        TRIS.put(key, kept.toArray(SkyTri[]::new));
    }
}
