package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;

import com.sun.jna.Pointer;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Original SkyCraft collision model, ported to 1.21.1/JNA.
 *
 * Skyrim streams an 8x8x8 sub-voxel occupancy mask for ordinary Minecraft collision queries
 * plus exact triangles for the local player's smooth movement. Nothing is materialized as
 * Minecraft blocks.
 */
public final class SkyCollision {
    public static final int REGION_SIZE = 8;

    private static final ConcurrentHashMap<Long, VoxelShape> SHAPES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> FILL = new ConcurrentHashMap<>();
    private static final int FILL_LOWER = 1 << 10;
    private static final int FILL_UPPER = 1 << 11;
    private static final int FILL_TOP_SHIFT = 12;

    private static final ConcurrentHashMap<Long, SkyTri[]> TRIS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, SkyTri[]> GHOSTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> TRI_HASH = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Long> CHANGED = new ConcurrentLinkedQueue<>();
    private static final Set<Long> KNOWN_REGIONS = ConcurrentHashMap.newKeySet();

    private static volatile Predicate<Entity> smoothCollider = e -> false;
    private static volatile int epoch = -1;
    private static Thread consumer;

    private SkyCollision() {}

    private static long regionKey(int rx, int ry, int rz) {
        return BlockPos.asLong(rx, ry, rz);
    }

    public static VoxelShape shapeAt(BlockPos pos) {
        return SHAPES.isEmpty() ? null : SHAPES.get(pos.asLong());
    }

    public static void setSmoothCollider(Predicate<Entity> predicate) {
        smoothCollider = predicate == null ? e -> false : predicate;
    }

    public static boolean usesSmoothCollider(Entity entity) {
        return entity != null && smoothCollider.test(entity);
    }

    public static boolean active() {
        return !KNOWN_REGIONS.isEmpty();
    }

    public static int regionCount() {
        return KNOWN_REGIONS.size();
    }

    public static int blockCount() {
        return SHAPES.size();
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

    public static boolean hasSolidBelow(int x, int y, int z, int depth) {
        for (int dy = 0; dy <= depth; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (SHAPES.containsKey(BlockPos.asLong(x + dx, y - dy, z + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Collision has arrived around the feet and includes plausible supporting geometry. */
    public static boolean readyAround(double x, double y, double z) {
        int bx = (int)Math.floor(x);
        int by = (int)Math.floor(y);
        int bz = (int)Math.floor(z);
        return isKnown(bx, by, bz)
            && isKnown(bx, by - REGION_SIZE, bz)
            && hasSolidBelow(bx, by, bz, 12);
    }

    public static float solidFraction(BlockPos pos) {
        Integer fill = FILL.get(pos.asLong());
        return fill == null ? 0.0F : (fill & 0x3FF) / 512.0F;
    }

    public static boolean hasGeometry(BlockPos pos) {
        return FILL.containsKey(pos.asLong());
    }

    public static float groundTop(BlockPos pos) {
        Integer fill = FILL.get(pos.asLong());
        return fill == null ? 0.0F : (((fill >> FILL_TOP_SHIFT) & 7) + 1) / 8.0F;
    }

    public static boolean supportsFromBelow(BlockPos pos) {
        Integer here = FILL.get(pos.asLong());
        if (here != null && (here & FILL_LOWER) != 0) return true;
        Integer below = FILL.get(BlockPos.asLong(pos.getX(), pos.getY() - 1, pos.getZ()));
        return below != null && (below & FILL_UPPER) != 0;
    }

    public static void trianglesNear(AABB box, List<SkyTri> out) {
        near(TRIS, box, out);
    }

    public static void originalSurfacesNear(AABB box, List<SkyTri> out) {
        near(TRIS, box, out);
        near(GHOSTS, box, out);
    }

    private static void near(ConcurrentHashMap<Long, SkyTri[]> store, AABB box, List<SkyTri> out) {
        if (store.isEmpty()) return;

        int rx0 = Math.floorDiv((int)Math.floor(box.minX), REGION_SIZE);
        int rx1 = Math.floorDiv((int)Math.floor(box.maxX), REGION_SIZE);
        int ry0 = Math.floorDiv((int)Math.floor(box.minY), REGION_SIZE);
        int ry1 = Math.floorDiv((int)Math.floor(box.maxY), REGION_SIZE);
        int rz0 = Math.floorDiv((int)Math.floor(box.minZ), REGION_SIZE);
        int rz1 = Math.floorDiv((int)Math.floor(box.maxZ), REGION_SIZE);

        for (int rx = rx0; rx <= rx1; rx++) {
            for (int ry = ry0; ry <= ry1; ry++) {
                for (int rz = rz0; rz <= rz1; rz++) {
                    SkyTri[] tris = store.get(regionKey(rx, ry, rz));
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

    public static void takeChangedRegions(LongConsumer out) {
        Long key;
        while ((key = CHANGED.poll()) != null) out.accept(key);
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
                case COL_REGION -> readRegion(s, payload);
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
        SHAPES.clear();
        FILL.clear();
        TRIS.clear();
        GHOSTS.clear();
        TRI_HASH.clear();
        CHANGED.clear();
        KNOWN_REGIONS.clear();
        epoch = newEpoch;
        SkyrimCollisionMirror.acceptClear(newEpoch);
        SkyCraft.LOG.info("SkyCraft: virtual collision cleared (epoch {})", newEpoch);
    }

    private static void readRegion(Pointer s, long p) {
        int minX = s.getInt(p);
        int minY = s.getInt(p + 4L);
        int minZ = s.getInt(p + 8L);
        int maxX = s.getInt(p + 12L);
        int maxY = s.getInt(p + 16L);
        int maxZ = s.getInt(p + 20L);
        int messageEpoch = s.getInt(p + 24L);
        int count = s.getInt(p + 28L);

        adoptEpochIfFresh(messageEpoch);
        if (messageEpoch != epoch || count < 0 || count > 2_000_000) return;

        // Sable cannot consume SkyCraft's exact triangle store directly. Mirror the same streamed
        // occupancy into invisible BlockStates so Rapier can cache and collide Aeronautics bodies
        // against Skyrim terrain/buildings.
        SkyrimCollisionMirror.acceptRegion(s, p);

        java.util.HashMap<Long, VoxelShape> fresh = new java.util.HashMap<>(Math.max(16, count * 2));
        java.util.HashMap<Long, Integer> freshFill = new java.util.HashMap<>(Math.max(16, count * 2));

        long e = p + COL_REGION_HEADER_BYTES;
        for (int i = 0; i < count; i++, e += COL_BLOCK_BYTES) {
            int x = s.getInt(e);
            int y = s.getInt(e + 4L);
            int z = s.getInt(e + 8L);
            VoxelShape shape = buildShape(s, e + 16L);
            if (shape != null) {
                long key = BlockPos.asLong(x, y, z);
                fresh.put(key, shape);
                freshFill.put(key, fillInfo(s, e + 16L));
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    long key = BlockPos.asLong(x, y, z);
                    VoxelShape shape = fresh.get(key);
                    if (shape == null) {
                        SHAPES.remove(key);
                        FILL.remove(key);
                    } else {
                        SHAPES.put(key, shape);
                        FILL.put(key, freshFill.get(key));
                    }
                }
            }
        }

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

        SkyTri[] kept = new SkyTri[count];
        List<SkyTri> ghosts = new ArrayList<>();
        float[] vertices = new float[9];
        int n = 0;
        long hash = count;
        long e = p + COL_REGION_HEADER_BYTES;

        for (int i = 0; i < count; i++, e += COL_TRI_BYTES) {
            for (int k = 0; k < 9; k++) {
                vertices[k] = s.getFloat(e + k * 4L);
                hash = hash * 31 + Float.floatToRawIntBits(vertices[k]);
            }
            int flags = s.getInt(e + 36L);
            hash = hash * 31 + flags;
            SkyTri tri = new SkyTri(vertices, 0, flags);
            if (tri.degenerate()) continue;
            if ((flags & TRI_GHOST) != 0) ghosts.add(tri);
            else kept[n++] = tri;
        }

        long key = regionKey(
            Math.floorDiv(minX, REGION_SIZE),
            Math.floorDiv(minY, REGION_SIZE),
            Math.floorDiv(minZ, REGION_SIZE)
        );

        if (ghosts.isEmpty()) GHOSTS.remove(key);
        else GHOSTS.put(key, ghosts.toArray(SkyTri[]::new));

        TRIS.put(key, java.util.Arrays.copyOf(kept, n));
        Long before = TRI_HASH.put(key, hash);
        if (before == null || before.longValue() != hash) {
            CHANGED.add(BlockPos.asLong(minX, minY, minZ));
        }
    }

    private static int fillInfo(Pointer s, long bitsOff) {
        int count = 0;
        int info = 0;
        int top = 0;

        for (int y = 0; y < 8; y++) {
            long layer = s.getLong(bitsOff + y * 8L);
            count += Long.bitCount(layer);
            if (layer != 0L) {
                info |= y < 4 ? FILL_LOWER : FILL_UPPER;
                top = y;
            }
        }
        return info | count | (top << FILL_TOP_SHIFT);
    }

    private static VoxelShape buildShape(Pointer s, long bitsOff) {
        boolean any = false;
        boolean full = true;
        long[] layers = new long[8];

        for (int y = 0; y < 8; y++) {
            layers[y] = s.getLong(bitsOff + y * 8L);
            any |= layers[y] != 0L;
            full &= layers[y] == -1L;
        }

        if (!any) return null;
        if (full) return Shapes.block();

        BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(8, 8, 8);
        for (int y = 0; y < 8; y++) {
            long layer = layers[y];
            while (layer != 0L) {
                int bit = Long.numberOfTrailingZeros(layer);
                layer &= layer - 1L;
                discrete.fill(bit & 7, y, bit >>> 3);
            }
        }
        return new SkyVoxelShape(discrete);
    }
}
