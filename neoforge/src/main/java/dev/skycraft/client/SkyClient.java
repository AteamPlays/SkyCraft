package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyrimCollisionMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Safe player-state bridge for the 1.21.1 backport.
 *
 * Normal development keeps Skyrim authoritative until the collision/player-control
 * port is complete. The fake-Skyrim harness opts into a controlled test mode so
 * we can prove spawn, collision and the handshake without launching the real game.
 */
public final class SkyClient {
    private static final boolean TEST_MODE = Boolean.getBoolean("skycraft.testMode");

    private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
    private static final SkyLink.McState MC = new SkyLink.McState();

    private static boolean linked;
    private static boolean mirrorReadyLogged;
    private static boolean testCollisionReadyLogged;
    private static boolean realCollisionReadyLogged;
    private static long frameCounter;

    private SkyClient() {}

    public static boolean linked() {
        return linked;
    }

    public static SkyLink.SkyState sky() {
        return SKY;
    }

    public static void clientTick() {
        SkyLink.poll();
        boolean nowLinked = SkyLink.active();

        if (nowLinked != linked) {
            linked = nowLinked;
            mirrorReadyLogged = false;
            testCollisionReadyLogged = false;
            realCollisionReadyLogged = false;
            SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
        }

        if (!linked || !SkyLink.readSkyState(SKY)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        MirrorWorld.tick(minecraft, SKY);
        if (MirrorWorld.isReady(minecraft)) {
            SkyrimCollisionMirror.flushToMirrorWorld(minecraft);
        }

        LocalPlayer player = minecraft.player;
        MC.flags = 0;
        MC.frameCounter = ++frameCounter;

        if (player != null && MirrorWorld.isReady(minecraft)) {
            boolean collisionReady = SkyrimCollisionMirror.hasAppliedCollisionBelow(SKY.x, SKY.y, SKY.z);

            if (TEST_MODE) {
                // Keep the fake harness pinned forever. It proves the shared-memory/collision
                // handshake without introducing movement authority into that deterministic test.
                syncPlayerToSkyrim(minecraft, player, true);

                if (collisionReady && !testCollisionReadyLogged) {
                    testCollisionReadyLogged = true;
                    SkyCraft.LOG.info(
                        "SkyCraft: fake-Skyrim floor verified under player; keeping harness pinned at ({}, {}, {})",
                        SKY.x, SKY.y, SKY.z
                    );
                }

                if (collisionReady) {
                    MC.flags |= Proto.MC_IN_WORLD;
                    MC.teleportAck = SKY.teleportSeq;
                }
            } else {
                // A real Skyrim run starts from whatever position/noGravity state the persistent
                // mirror save last had (the fake harness deliberately leaves noGravity enabled).
                // Whenever Skyrim requests a teleport, pin Minecraft at Skyrim's live position
                // until the streamed proxy floor is actually present. Then perform one final snap,
                // restore gravity, acknowledge the teleport, and let vanilla/exact collision take over.
                boolean awaitingTeleport = MC.teleportAck != SKY.teleportSeq;

                if (awaitingTeleport) {
                    syncPlayerToSkyrim(minecraft, player, !collisionReady);

                    if (collisionReady) {
                        MC.teleportAck = SKY.teleportSeq;
                        MC.flags |= Proto.MC_IN_WORLD;

                        if (!realCollisionReadyLogged) {
                            realCollisionReadyLogged = true;
                            SkyCraft.LOG.info(
                                "SkyCraft: real-Skyrim floor verified; player released at ({}, {}, {}), teleportAck={}",
                                SKY.x, SKY.y, SKY.z, SKY.teleportSeq
                            );
                        }
                    }
                } else {
                    // Keep advertising authority after the initial handshake. Also clear any
                    // stale noGravity bit left in the integrated-player save by an older test.
                    MC.flags |= Proto.MC_IN_WORLD;
                    releaseStaleNoGravity(minecraft, player);
                }
            }

            if (minecraft.screen == null && SKY.inGame() && !SKY.loading()) {
                player.setYRot(SKY.yaw);
                player.setXRot(SKY.pitch);
                player.yRotO = SKY.yaw;
                player.xRotO = SKY.pitch;
            }

            Vec3 eye = player.getEyePosition();
            MC.x = player.getX();
            MC.y = player.getY();
            MC.z = player.getZ();
            MC.yaw = player.getYRot();
            MC.pitch = player.getXRot();
            MC.eyeHeight = player.getEyeHeight();
            MC.eyeX = eye.x;
            MC.eyeY = eye.y;
            MC.eyeZ = eye.z;
            MC.sensitivity = minecraft.options.sensitivity().get().floatValue();
            MC.guiScale = (int) minecraft.getWindow().getGuiScale();
            MC.fov = minecraft.options.fov().get().floatValue();
            MC.tickQpc = SkyLink.qpc();
            MC.prevX = player.xo;
            MC.prevY = player.yo;
            MC.prevZ = player.zo;
            MC.curX = player.getX();
            MC.curY = player.getY();
            MC.curZ = player.getZ();
            MC.eyeHeightO = MC.eyeHeight;
            MC.eyeHeightT = MC.eyeHeight;
            MC.tickMs = 50.0F;
            MC.cameraMode = minecraft.options.getCameraType().ordinal();
            MC.cameraDistance = 0.0F;

            if (!mirrorReadyLogged) {
                mirrorReadyLogged = true;
                SkyCraft.LOG.info(
                    TEST_MODE
                        ? "SkyCraft: NeoForge mirror world ready in fake-Skyrim test mode"
                        : "SkyCraft: NeoForge mirror world ready; waiting for real Skyrim floor sync"
                );
            }
        }

        SkyLink.writeMcState(MC);
    }

    private static void syncPlayerToSkyrim(Minecraft minecraft, LocalPlayer player, boolean holdGravity) {
        // Snap client-side immediately so the camera never spends a frame at a stale saved position.
        player.setDeltaMovement(Vec3.ZERO);
        player.setPos(SKY.x, SKY.y, SKY.z);
        player.xo = SKY.x;
        player.yo = SKY.y;
        player.zo = SKY.z;
        player.setYRot(SKY.yaw);
        player.setXRot(SKY.pitch);
        player.resetFallDistance();

        var server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }

        var uuid = player.getUUID();
        double x = SKY.x, y = SKY.y, z = SKY.z;
        float yaw = SKY.yaw, pitch = SKY.pitch;

        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
            if (serverPlayer == null) {
                return;
            }

            serverPlayer.setNoGravity(holdGravity);
            serverPlayer.setDeltaMovement(Vec3.ZERO);
            serverPlayer.teleportTo(x, y, z);
            serverPlayer.setYRot(yaw);
            serverPlayer.setXRot(pitch);
            serverPlayer.resetFallDistance();
        });
    }

    private static void releaseStaleNoGravity(Minecraft minecraft, LocalPlayer player) {
        var server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }

        var uuid = player.getUUID();
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
            if (serverPlayer != null && serverPlayer.isNoGravity()) {
                serverPlayer.setNoGravity(false);
                serverPlayer.resetFallDistance();
            }
        });
    }
}
