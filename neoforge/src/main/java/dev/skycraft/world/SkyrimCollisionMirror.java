package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;

import com.sun.jna.Pointer;
import dev.skycraft.SkyCraft;
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
 * Coarse Skyrim collision mirrored as invisible Minecraft blocks for Sable/Create.
 *
 * Important: this class does NOT consume the shared-memory collision ring itself.
 * SkyCollision is the single owner of that ring and forwards COL_CLEAR/COL_REGION
 * messages here. This avoids two threads racing the same tail pointer.
 */
public final class SkyrimCollisionMirror {
    private static final Map<Long, Integer> DESIRED = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> APPLIED = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Long> DIRTY = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();

    private static volatile int epoch = -1;
    private static volatile long lastCountLog;

    private SkyrimCollisionMirror() {}

    public static int blockCount() {
        return DESIRED.size();
    }

    /** Called only by SkyCollision, the single shared-ring consumer. */
    static void acceptClear(int newEpoch) {
        for (Long key : DESIRED.keySet()) {
            DIRTY.add(key);
        }
        DESIRED.clear();
        APPLIED.clear();
        epoch = newEpoch;
        SkyCraft.LOG.info("SkyCraft: collision proxy mirror cleared (epoch {})", newEpoch);
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
            SkyCraft.LOG.info("SkyCraft: collision proxy mirror joined epoch {}", msgEpoch);
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
        if (now - lastCountLog > 3000L) {
            lastCountLog = now;
            SkyCraft.LOG.info(
                "SkyCraft: {} Skyrim collision proxy blocks received ({} materialized)",
                DESIRED.size(), APPLIED.size()
            );
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
     * True only after a proxy block near the player's feet has actually been written
     * into the integrated-server world, not merely received from shared memory.
     */
    public static boolean hasAppliedCollisionBelow(double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);

        // The normal case is by-1 (feet at Y=100, floor block at Y=99).
        // Check a tiny vertical neighborhood to tolerate fractional Skyrim surfaces.
        for (int yy = by; yy >= by - 2; yy--) {
            if (APPLIED.containsKey(BlockPos.asLong(bx, yy, bz))) {
                return true;
            }
        }
        return false;
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
                int appliedThisPass = 0;
                Long key;

                while (appliedThisPass < 4096 && (key = DIRTY.poll()) != null) {
                    BlockPos pos = BlockPos.of(key);
                    int height = DESIRED.getOrDefault(key, 0);
                    BlockState current = level.getBlockState(pos);
                    boolean isProxy = current.is(SkyBlocks.SKYRIM_COLLISION.get());

                    if (height == 0) {
                        if (isProxy) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                        APPLIED.remove(key);
                    } else if (current.isAir() || isProxy) {
                        BlockState wanted = SkyBlocks.SKYRIM_COLLISION.get().defaultBlockState()
                            .setValue(SkyrimCollisionBlock.HEIGHT, height);

                        if (!current.equals(wanted)) {
                            level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
                        }
                        APPLIED.put(key, height);
                    }

                    appliedThisPass++;
                }
            } finally {
                FLUSH_SCHEDULED.set(false);
            }
        });
    }
}
