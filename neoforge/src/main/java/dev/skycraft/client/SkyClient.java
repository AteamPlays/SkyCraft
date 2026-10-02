package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.mixin.OptionsAccessor;
import dev.skycraft.mixin.MinecraftAccessor;
import dev.skycraft.world.SkyrimCollisionMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Per-frame/tick glue between Minecraft 1.21.1 and Skyrim.
 *
 * Frame work (shared state, input, look, interpolation) runs from Minecraft.runTick so it tracks
 * the render frame instead of the 20 Hz client tick. Physics/world synchronization remains on
 * ClientTick.Post.
 */
public final class SkyClient {
    private static final boolean TEST_MODE = Boolean.getBoolean("skycraft.testMode");

    private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
    private static final SkyLink.McState MC = new SkyLink.McState();

    private static boolean linked;
    private static boolean mirrorReadyLogged;
    private static boolean testCollisionReadyLogged;
    private static boolean realCollisionReadyLogged;
    private static int appliedViewportW;
    private static int appliedViewportH;
    private static long frameCounter;
    private static long qpcFreq;
    private static int lastPacedSeq;
    private static boolean skyrimStalled;

    private SkyClient() {}

    public static boolean linked() {
        return linked;
    }

    public static SkyLink.SkyState sky() {
        return SKY;
    }

    /** Start of every rendered Minecraft frame. */
    public static void beginFrame() {
        SkyLink.poll();
        boolean nowLinked = SkyLink.active();

        if (nowLinked != linked) {
            linked = nowLinked;
            mirrorReadyLogged = false;
            testCollisionReadyLogged = false;
            realCollisionReadyLogged = false;
            RenderProbe.reset();

            if (!linked) {
                InputBridge.releaseAll();
            } else {
                Minecraft minecraft = Minecraft.getInstance();
                ((OptionsAccessor) (Object) minecraft.options).skycraft$setPauseOnLostFocus(false);
                minecraft.options.enableVsync().set(false);
                minecraft.options.framerateLimit().set(260);
                minecraft.options.autoJump().set(false);
                SkyCraft.LOG.info("SkyCraft: frame-synchronous background mode enabled");
            }
            SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
        }

        if (!linked || !SkyLink.readSkyState(SKY)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        applyViewportSize(minecraft);

        if (SKY.menuOpen() || SKY.loading()) {
            InputBridge.releaseAll();
        } else {
            InputBridge.drain(minecraft);
        }

        // Skyrim owns camera direction. Apply it every render frame instead of every client tick.
        LocalPlayer player = minecraft.player;
        if (player != null && minecraft.screen == null && SKY.inGame() && !SKY.loading()) {
            player.setYRot(SKY.yaw);
            player.setXRot(SKY.pitch);
            player.yRotO = SKY.yaw;
            player.xRotO = SKY.pitch;
        }
    }

    /** End of ClientTick.Post: world/physics synchronization. */
    public static void clientTick() {
        if (!linked) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        MirrorWorld.tick(minecraft, SKY);
        if (MirrorWorld.isReady(minecraft)) {
            SkyrimCollisionMirror.flushToMirrorWorld(minecraft);
        }

        LocalPlayer player = minecraft.player;
        if (player == null || !MirrorWorld.isReady(minecraft)) {
            return;
        }

        boolean collisionReady = SkyrimCollisionMirror.hasAppliedCollisionBelow(SKY.x, SKY.y, SKY.z);

        if (TEST_MODE) {
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
            boolean awaitingTeleport = MC.teleportAck != SKY.teleportSeq;
            if (awaitingTeleport) {
                syncPlayerToSkyrim(minecraft, player, !collisionReady);
                if (collisionReady) {
                    // Final snap with gravity restored, then acknowledge only after collision exists.
                    syncPlayerToSkyrim(minecraft, player, false);
                    MC.teleportAck = SKY.teleportSeq;
                    if (!realCollisionReadyLogged) {
                        realCollisionReadyLogged = true;
                        SkyCraft.LOG.info(
                            "SkyCraft: real-Skyrim floor verified; player released at ({}, {}, {}), teleportAck={}",
                            SKY.x, SKY.y, SKY.z, SKY.teleportSeq
                        );
                        if (Boolean.getBoolean("skycraft.renderProbe")) {
                            RenderProbe.send(player);
                        }
                    }
                }
            } else {
                releaseStaleNoGravity(minecraft, player);
            }
        }

        // Physics tick snapshot used by Skyrim for interpolation between Minecraft's 20 Hz ticks.
        if (qpcFreq == 0) {
            qpcFreq = SkyLink.qpcFrequency();
        }
        float tickMs = minecraft.level != null
            ? minecraft.level.tickRateManager().millisecondsPerTick()
            : 50.0F;
        float partial = ((MinecraftAccessor) (Object) minecraft).skycraft$getTimer().getGameTimeDeltaPartialTick(false);
        MC.tickQpc = SkyLink.qpc() - (long) (partial * tickMs * qpcFreq / 1000.0);
        MC.tickMs = tickMs;
        MC.prevX = player.xo;
        MC.prevY = player.yo;
        MC.prevZ = player.zo;
        MC.curX = player.getX();
        MC.curY = player.getY();
        MC.curZ = player.getZ();
        MC.eyeHeightO = player.getEyeHeight();
        MC.eyeHeightT = player.getEyeHeight();

        if (!mirrorReadyLogged) {
            mirrorReadyLogged = true;
            SkyCraft.LOG.info(
                TEST_MODE
                    ? "SkyCraft: NeoForge mirror world ready in fake-Skyrim test mode"
                    : "SkyCraft: NeoForge mirror world ready; using frame-synchronous bridge"
            );
        }
    }

    /** Immediately after GameRenderer.render and before Minecraft blits its hidden window. */
    public static void afterRender() {
        if (!linked) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        int flags = 0;

        if (player != null && minecraft.level != null && MirrorWorld.isReady(minecraft)) {
            float partial = ((MinecraftAccessor) (Object) minecraft).skycraft$getTimer().getGameTimeDeltaPartialTick(false);
            double x = player.xo + (player.getX() - player.xo) * partial;
            double y = player.yo + (player.getY() - player.yo) * partial;
            double z = player.zo + (player.getZ() - player.zo) * partial;
            Vec3 eye = player.getEyePosition(partial);

            flags |= Proto.MC_IN_WORLD;
            if (player.onGround()) flags |= Proto.MC_ON_GROUND;
            if (player.isShiftKeyDown()) flags |= Proto.MC_SNEAKING;
            if (player.isSprinting()) flags |= Proto.MC_SPRINTING;
            if (player.isDeadOrDying()) flags |= Proto.MC_DEAD;
            if (player.isSwimming()) flags |= Proto.MC_SWIMMING;
            if (player.getAbilities().flying) flags |= Proto.MC_FLYING;

            MC.x = x;
            MC.y = y;
            MC.z = z;
            MC.yaw = player.getYRot();
            MC.pitch = player.getXRot();
            MC.eyeHeight = (float) (eye.y - y);
            MC.eyeX = eye.x;
            MC.eyeY = eye.y;
            MC.eyeZ = eye.z;
            MC.sensitivity = minecraft.options.sensitivity().get().floatValue();
            MC.guiScale = (int) minecraft.getWindow().getGuiScale();
            MC.fov = minecraft.options.fov().get().floatValue();
            MC.cameraMode = minecraft.options.getCameraType().ordinal();
            MC.cameraDistance = 0.0F;
        }

        if (minecraft.screen != null) {
            flags |= Proto.MC_SCREEN_OPEN;
        }

        MC.flags = flags;
        MC.frameCounter = ++frameCounter;
        SkyLink.writeMcState(MC);
    }

    /**
     * Match original SkyCraft's cadence: don't free-run hundreds of Minecraft frames ahead of
     * Skyrim. Wait briefly for the next Skyrim state frame, but stop blocking if Skyrim stalls.
     */
    public static void paceFrame() {
        if (!linked) {
            return;
        }
        int seq = SkyLink.skyStateSeq() >>> 1;
        if (skyrimStalled && seq == lastPacedSeq) {
            return;
        }

        skyrimStalled = false;
        long deadline = System.nanoTime() + 25_000_000L;
        while ((SkyLink.skyStateSeq() >>> 1) == seq && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            if (deadline - System.nanoTime() > 2_000_000L) {
                Thread.yield();
            }
        }

        int now = SkyLink.skyStateSeq() >>> 1;
        skyrimStalled = now == seq;
        lastPacedSeq = now;
    }

    private static void applyViewportSize(Minecraft minecraft) {
        int width = Math.min(SKY.viewportW, Proto.MAX_OVERLAY_W);
        int height = Math.min(SKY.viewportH, Proto.MAX_OVERLAY_H);
        if (width <= 0 || height <= 0 || (width == appliedViewportW && height == appliedViewportH)) {
            return;
        }

        appliedViewportW = width;
        appliedViewportH = height;
        minecraft.getWindow().setWindowed(width, height);
        SkyCraft.LOG.info("SkyCraft: matching Minecraft overlay viewport to Skyrim {}x{}", width, height);
    }

    private static void syncPlayerToSkyrim(Minecraft minecraft, LocalPlayer player, boolean holdGravity) {
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
