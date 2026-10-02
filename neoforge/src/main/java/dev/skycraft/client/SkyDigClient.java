package dev.skycraft.client;

import static dev.skycraft.link.Proto.DIG_STONE;

import dev.skycraft.SkyCraft;
import dev.skycraft.world.SkyClip;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyDig;
import dev.skycraft.world.SkyRay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client half of digging into Skyrim geometry.
 *
 * This is the original SkyCraft gameplay model adapted to the current singleplayer NeoForge port:
 * mine an exact Skyrim surface at the material's Minecraft break speed, persist the opened cell on
 * the integrated server, then reveal enclosed neighboring material as ordinary Minecraft blocks.
 */
public final class SkyDigClient {
    private static final int REVEAL_TRIES = 60;

    private static BlockPos mining;
    private static float progress;
    private static int miningTick;
    private static int ticks;

    private static final Map<Long, Integer> REVEAL = new HashMap<>();
    private static boolean warnedNoServer;

    private SkyDigClient() {}

    public static int world() {
        return SkyClient.sky().worldId;
    }

    /**
     * Attack/hold on Skyrim geometry. Returns true when the normal Minecraft miss/block attack
     * should be considered handled by SkyCraft.
     */
    public static boolean attack(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof SkyClip.SkyrimHitResult result)
            || minecraft.level == null
            || minecraft.player == null
            || minecraft.gameMode == null) {
            return false;
        }

        GameType mode = minecraft.gameMode.getPlayerMode();
        if (mode == GameType.ADVENTURE || mode == GameType.SPECTATOR || !SkyDig.destruction) {
            return false;
        }

        SkyRay.Hit hit = result.hit;
        if (hit.tri() == null || !hit.tri().diggable) {
            return false;
        }

        int[] cell = SkyRay.surfaceCell(hit);
        BlockPos pos = new BlockPos(cell[0], cell[1], cell[2]);
        BlockState here = minecraft.level.getBlockState(pos);
        if ((!here.isAir() && !here.canBeReplaced()) || SkyDig.isDug(minecraft.level, world(), pos)) {
            return false;
        }

        if (pos.equals(mining) && miningTick == ticks) {
            return true;
        }
        if (!pos.equals(mining) || ticks - miningTick > 2) {
            mining = pos;
            progress = 0.0F;
        }
        miningTick = ticks;

        int material = hit.tri().material == 0 ? DIG_STONE : hit.tri().material;
        BlockState state = SkyDig.materialState(material);
        progress += minecraft.player.getAbilities().instabuild
            ? 1.0F
            : state.getDestroyProgress(minecraft.player, minecraft.level, pos);

        if (progress >= 1.0F) {
            openOnServer(minecraft, world(), pos, material);
            REVEAL.put(pos.asLong(), REVEAL_TRIES);
            mining = null;
            progress = 0.0F;
        }
        return true;
    }

    /** A real Minecraft block that was exposed inside a dug hole broke: expose what is around it. */
    public static void blockChanged(ClientLevel level, BlockPos pos, BlockState before, BlockState after) {
        if (before.isAir() || !(after.isAir() || after.canBeReplaced())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.blockPosition().distSqr(pos) > 24 * 24) {
            return;
        }

        if (SkyDig.isDug(level, world(), pos) && SkyDig.destruction) {
            REVEAL.put(pos.asLong(), REVEAL_TRIES);
        }
    }

    public static void tick(Minecraft minecraft) {
        ticks++;
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            REVEAL.clear();
            SkyDig.clientDug = null;
            return;
        }

        int world = world();
        SkyDig.clientWorld = world;
        SkyDig.clientDug = (x, y, z) -> SkyDig.isDug(level, world, new BlockPos(x, y, z));

        reveal(minecraft, level);
        DigExporter.tick(minecraft);

        SkyCollision.takeChangedRegions(key -> {
            int rx = BlockPos.getX(key);
            int ry = BlockPos.getY(key);
            int rz = BlockPos.getZ(key);
            SkyDig.wallsChanged(rx, ry, rz, rx + 7, ry + 7, rz + 7);
        });
    }

    public static void resendAll() {
        DigExporter.resendAll();
    }

    private static void reveal(Minecraft minecraft, ClientLevel level) {
        if (REVEAL.isEmpty()) {
            return;
        }

        int world = world();
        List<BlockPos> cells = new ArrayList<>();
        List<Integer> materials = new ArrayList<>();
        Set<Long> queued = new HashSet<>();

        long[] keys = REVEAL.keySet().stream().mapToLong(Long::longValue).toArray();
        for (long key : keys) {
            BlockPos pos = BlockPos.of(key);
            boolean waiting = false;

            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (queued.contains(n.asLong()) || SkyDig.isDug(level, world, n)) {
                    continue;
                }
                if (!SkyCollision.isKnown(n.getX(), n.getY(), n.getZ())) {
                    waiting = true;
                    continue;
                }

                int material = SkyDig.classify(n.getX(), n.getY(), n.getZ());
                if (material <= SkyDig.AIR) {
                    continue;
                }

                BlockState state = level.getBlockState(n);
                if (!state.isAir() && !state.canBeReplaced()) {
                    material = 0;
                }

                queued.add(n.asLong());
                cells.add(n.immutable());
                materials.add(material);
            }

            int tries = REVEAL.getOrDefault(key, 0) - 1;
            if (waiting && tries > 0) {
                REVEAL.put(key, tries);
            } else {
                REVEAL.remove(key);
            }
        }

        if (!cells.isEmpty()) {
            revealOnServer(minecraft, world, cells, materials);
        }
    }

    private static void openOnServer(Minecraft minecraft, int world, BlockPos pos, int material) {
        var server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            warnNoServer();
            return;
        }

        var uuid = minecraft.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                SkyDig.open(player, world, pos, material);
            }
        });
    }

    private static void revealOnServer(
        Minecraft minecraft,
        int world,
        List<BlockPos> cells,
        List<Integer> materials
    ) {
        var server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            warnNoServer();
            return;
        }

        var uuid = minecraft.player.getUUID();
        List<BlockPos> safeCells = List.copyOf(cells);
        int[] safeMaterials = materials.stream().mapToInt(Integer::intValue).toArray();

        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                SkyDig.reveal(player, world, safeCells, safeMaterials);
            }
        });
    }

    private static void warnNoServer() {
        if (!warnedNoServer) {
            warnedNoServer = true;
            SkyCraft.LOG.warn("SkyCraft: remote-server dig networking is not ported yet; local Skyrim digging requires the integrated server");
        }
    }
}
