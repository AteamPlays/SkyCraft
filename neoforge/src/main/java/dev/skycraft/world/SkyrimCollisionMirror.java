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
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Consumes Skyrim's collision ring and mirrors it as invisible blocks in the
 * dedicated Minecraft world.
 *
 * This is intentionally a coarse first pass for Create/Sable: each 8x8x8 Skyrim
 * voxel cell becomes an invisible slab whose height is the highest occupied
 * eighth. It is stable by BlockState, which matches Sable's collider cache.
 */
public final class SkyrimCollisionMirror {
    private static final Map<Long, Integer> DESIRED = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Long> DIRTY = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();
    private static volatile Thread consumer;
    private static volatile int epoch = -1;
    private static volatile long lastCountLog;

    private SkyrimCollisionMirror() {}

    public static synchronized void start() {
        if (consumer != null) return;
        consumer = new Thread(SkyrimCollisionMirror::consumeLoop, "SkyCraft collision mirror");
        consumer.setDaemon(true);
        consumer.start();
        SkyCraft.LOG.info("SkyCraft: collision mirror consumer started");
    }

    public static int blockCount() {
        return DESIRED.size();
    }

    private static void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!drainOnce()) {
                    Thread.sleep(2L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                SkyCraft.LOG.error("SkyCraft: collision mirror consumer failed", t);
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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

        final long data = OFF_COLLISION_RING + CR_DATA;
        boolean consumed = false;

        while (tail < head) {
            long pos = tail % CR_DATA_BYTES;
            int type = s.getInt(data + pos);
            int payloadBytes = s.getInt(data + pos + 4);

            if (type == COL_PAD) {
                tail += CR_DATA_BYTES - pos;
                consumed = true;
                continue;
            }

            long payload = data + pos + 8;
            switch (type) {
                case COL_CLEAR -> clear(s.getInt(payload));
                case COL_REGION -> readRegion(s, payload);
                case COL_TRIS -> {
                    // Exact triangles are not required for the first Sable collision pass.
                }
                default -> SkyCraft.LOG.warn("SkyCraft: unknown collision message {}", type);
            }

            tail += align8(8L + Integer.toUnsignedLong(payloadBytes));
            consumed = true;
        }

        SkyLink.setCollisionTail(tail);
        return consumed;
    }

    private static long align8(long value) {
        return (value + 7L) & ~7L;
    }

    private static void clear(int newEpoch) {
        for (Long key : DESIRED.keySet()) {
            DIRTY.add(key);
        }
        DESIRED.clear();
        epoch = newEpoch;
        SkyCraft.LOG.info("SkyCraft: collision mirror cleared (epoch {})", newEpoch);
    }

    private static void readRegion(Pointer s, long p) {
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
            SkyCraft.LOG.info("SkyCraft: joined collision epoch {}", msgEpoch);
        }
        if (msgEpoch != epoch) return;

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
                    long key = BlockPos.asLong(x, y, z);
                    int next = fresh.getOrDefault(key, 0);
                    Integer before = next == 0 ? DESIRED.remove(key) : DESIRED.put(key, next);
                    int old = before == null ? 0 : before;
                    if (old != next) {
                        DIRTY.add(key);
                    }
                }
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastCountLog > 5000L) {
            lastCountLog = now;
            SkyCraft.LOG.info("SkyCraft: {} Skyrim collision proxy blocks queued/active", DESIRED.size());
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

    /**
     * Applies queued proxy changes on the integrated server thread. Real Minecraft
     * blocks always win: SkyCraft only writes into air or replaces its own proxy.
     */
    public static void flushToMirrorWorld(Minecraft minecraft) {
        if (DIRTY.isEmpty()) return;

        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || !FLUSH_SCHEDULED.compareAndSet(false, true)) return;

        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                int applied = 0;
                Long key;
                while (applied < 4096 && (key = DIRTY.poll()) != null) {
                    BlockPos pos = BlockPos.of(key);
                    int height = DESIRED.getOrDefault(key, 0);
                    BlockState current = level.getBlockState(pos);
                    boolean isProxy = current.is(SkyBlocks.SKYRIM_COLLISION.get());

                    if (height == 0) {
                        if (isProxy) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    } else if (current.isAir() || isProxy) {
                        BlockState wanted = SkyBlocks.SKYRIM_COLLISION.get().defaultBlockState()
                            .setValue(SkyrimCollisionBlock.HEIGHT, height);
                        if (current != wanted) {
                            level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
                        }
                    }
                    applied++;
                }
            } finally {
                FLUSH_SCHEDULED.set(false);
            }
        });
    }
}
