package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.mixin.MinecraftAccessor;
import dev.skycraft.mixin.OptionsAccessor;
import dev.skycraft.world.SkyCollision;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Per-frame/tick glue between Minecraft 1.21.1 and Skyrim.
 *
 * This follows original SkyCraft's ownership model: Skyrim supplies camera/teleport state,
 * Minecraft owns player physics once streamed collision is ready, and transitions are held
 * until the new Skyrim collision epoch around the player is usable.
 */
public final class SkyClient {
    private static final boolean TEST_MODE = Boolean.getBoolean("skycraft.testMode");

    private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
    private static final SkyLink.McState MC = new SkyLink.McState();

    private static volatile boolean linked;
    private static boolean tookOver;
    private static boolean mirrorReadyLogged;
    private static boolean testCollisionReadyLogged;
    private static boolean realCollisionReadyLogged;

    private static int appliedViewportW;
    private static int appliedViewportH;
    private static long frameCounter;
    private static long qpcFreq;
    private static int lastPacedSeq;
    private static boolean skyrimStalled;

    // Original SkyCraft teleport/load hold state.
    private static int lastTeleportSeq = Integer.MIN_VALUE;
    private static int teleportAck;
    private static boolean teleportPending = true;
    private static LocalPlayer lastPlayer;
    private static Vec3 holdPos;
    private static Vec3 unlinkedHold;
    private static long holdSince;

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
        Minecraft minecraft = Minecraft.getInstance();
        boolean nowLinked = SkyLink.active();

        if (nowLinked != linked) {
            linked = nowLinked;
            mirrorReadyLogged = false;
            testCollisionReadyLogged = false;
            realCollisionReadyLogged = false;
            RenderProbe.reset();

            if (linked) {
                tookOver = true;
                unlinkedHold = null;
                teleportPending = true;
                ((OptionsAccessor)(Object)minecraft.options).skycraft$setPauseOnLostFocus(false);
                minecraft.options.enableVsync().set(false);
                minecraft.options.framerateLimit().set(260);
                minecraft.options.autoJump().set(false);
                SkyCraft.LOG.info("SkyCraft: frame-synchronous background mode enabled");
            } else {
                InputBridge.releaseAll();
                LocalPlayer player = minecraft.player;
                unlinkedHold = player != null ? player.position() : null;
            }

            SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
        }

        if (!linked) {
            freezeWhileUnlinked(minecraft);
            return;
        }

        if (!SkyLink.readSkyState(SKY)) {
            return;
        }

        applyViewportSize(minecraft);

        if (SKY.menuOpen() || SKY.loading()) {
            InputBridge.releaseAll();
        } else {
            InputBridge.drain(minecraft);
        }

        LocalPlayer player = minecraft.player;
        if (player == null) {
            lastPlayer = null;
            return;
        }

        // Joining/respawning always re-enters the Skyrim-controlled teleport handshake.
        if (player != lastPlayer) {
            lastPlayer = player;
            teleportPending = true;
            holdPos = null;
            holdSince = 0L;
        }

        if (SKY.teleportSeq != lastTeleportSeq) {
            lastTeleportSeq = SKY.teleportSeq;
            teleportPending = true;
            holdPos = null;
            holdSince = 0L;
            realCollisionReadyLogged = false;
        }

        // A Skyrim loading screen/world transition invalidates nearby collision. Park Minecraft
        // until the new state and new collision epoch are present.
        if (!SKY.inGame() || SKY.loading()) {
            if (holdPos == null) {
                holdPos = player.position();
            }
            teleportPending = true;
        }

        // Skyrim owns look direction every render frame for zero-latency camera alignment.
        if (minecraft.screen == null && SKY.inGame() && !SKY.loading()) {
            player.setYRot(SKY.yaw);
            player.setXRot(SKY.pitch);
            player.yRotO = SKY.yaw;
            player.xRotO = SKY.pitch;
        }
    }

    /** End of ClientTick.Post: world/physics synchronization. */
    public static void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();

        if (!linked) {
            freezeWhileUnlinked(minecraft);
            return;
        }

        MirrorWorld.tick(minecraft, SKY);
        SkyDigClient.tick(minecraft);

        LocalPlayer player = minecraft.player;
        if (player == null || !MirrorWorld.isReady(minecraft)) {
            return;
        }

        if (TEST_MODE) {
            boolean ready = SkyCollision.readyAround(SKY.x, SKY.y, SKY.z);
            pinPlayerAndServer(minecraft, player, new Vec3(SKY.x, SKY.y, SKY.z), true);

            if (ready && !testCollisionReadyLogged) {
                testCollisionReadyLogged = true;
                SkyCraft.LOG.info(
                    "SkyCraft: fake-Skyrim floor verified under player; keeping harness pinned at ({}, {}, {})",
                    SKY.x, SKY.y, SKY.z
                );
            }

            if (ready) {
                teleportAck = SKY.teleportSeq;
            }
        } else {
            holdUntilReady(minecraft, player);
        }

        publishTick(minecraft, player);

        if (!mirrorReadyLogged) {
            mirrorReadyLogged = true;
            SkyCraft.LOG.info(
                TEST_MODE
                    ? "SkyCraft: NeoForge mirror world ready in fake-Skyrim test mode"
                    : "SkyCraft: NeoForge mirror world ready; original-style collision/teleport hold active"
            );
        }
    }

    /**
     * Freeze the player while Skyrim is temporarily silent. Interior transitions can clear the
     * collision stream, so allowing even a few gravity ticks here can put Minecraft inside the
     * next room's floor/wall before Skyrim reconnects.
     */
    private static void freezeWhileUnlinked(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (linked || !tookOver || player == null) {
            return;
        }

        if (unlinkedHold == null) {
            unlinkedHold = player.position();
        }

        pinLocalPlayer(player, unlinkedHold);
    }

    /** Original SkyCraft load/teleport handshake, adapted to the 1.21.1 virtual collision store. */
    private static void holdUntilReady(Minecraft minecraft, LocalPlayer player) {
        if (!SKY.inGame() || SKY.loading()) {
            if (holdPos == null) {
                holdPos = player.position();
            }
            teleportPending = true;
            pinPlayerAndServer(minecraft, player, holdPos, true);
            return;
        }

        if (teleportPending) {
            // Once Skyrim is back from a door/load, its coordinates are authoritative.
            holdPos = new Vec3(SKY.x, SKY.y, SKY.z);
            teleportPending = false;
            holdSince = 0L;
        }

        if (holdPos == null) {
            releaseStaleNoGravity(minecraft, player);
            return;
        }

        if (holdSince == 0L) {
            holdSince = System.currentTimeMillis();
        }

        int bx = (int)Math.floor(holdPos.x);
        int by = (int)Math.floor(holdPos.y);
        int bz = (int)Math.floor(holdPos.z);

        boolean known =
            SkyCollision.isKnown(bx, by - 1, bz)
                && SkyCollision.isKnown(bx, by, bz)
                && SkyCollision.isKnown(bx, by - SkyCollision.REGION_SIZE, bz);

        // Original SkyCraft waits for actual support, but intentionally releases after six seconds
        // so a legitimate mid-air Skyrim teleport does not deadlock forever.
        boolean ready =
            known
                && (SkyCollision.hasSolidBelow(bx, by, bz, 12)
                    || System.currentTimeMillis() - holdSince > 6000L);

        if (!ready) {
            pinPlayerAndServer(minecraft, player, holdPos, true);
            return;
        }

        Vec3 safe = liftOutOfGeometry(holdPos);
        pinPlayerAndServer(minecraft, player, safe, false);

        teleportAck = SKY.teleportSeq;
        holdPos = null;
        holdSince = 0L;

        if (!realCollisionReadyLogged) {
            realCollisionReadyLogged = true;
            SkyCraft.LOG.info(
                "SkyCraft: real-Skyrim collision ready; player released at ({}, {}, {}), teleportAck={}",
                safe.x, safe.y, safe.z, teleportAck
            );
            if (Boolean.getBoolean("skycraft.renderProbe")) {
                RenderProbe.send(player);
            }
        }
    }

    private static Vec3 liftOutOfGeometry(Vec3 pos) {
        double ground = SkyCollider.groundAt(pos.x, pos.y, pos.z, 2.5);
        if (!Double.isNaN(ground) && ground > pos.y) {
            SkyCraft.LOG.info(
                "SkyCraft: lifted player {} blocks out of Skyrim geometry",
                String.format("%.3f", ground - pos.y)
            );
            return new Vec3(pos.x, ground, pos.z);
        }
        return pos;
    }

    private static void pinLocalPlayer(LocalPlayer player, Vec3 pos) {
        player.setDeltaMovement(Vec3.ZERO);
        player.setPos(pos.x, pos.y, pos.z);
        player.xo = pos.x;
        player.yo = pos.y;
        player.zo = pos.z;
        player.resetFallDistance();
    }

    private static void pinPlayerAndServer(
        Minecraft minecraft,
        LocalPlayer player,
        Vec3 pos,
        boolean holdGravity
    ) {
        pinLocalPlayer(player, pos);
        player.setYRot(SKY.yaw);
        player.setXRot(SKY.pitch);

        var server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }

        var uuid = player.getUUID();
        double x = pos.x, y = pos.y, z = pos.z;
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

    private static void publishTick(Minecraft minecraft, LocalPlayer player) {
        if (qpcFreq == 0L) {
            qpcFreq = SkyLink.qpcFrequency();
        }

        float tickMs = minecraft.level != null
            ? minecraft.level.tickRateManager().millisecondsPerTick()
            : 50.0F;

        float partial = ((MinecraftAccessor)(Object)minecraft)
            .skycraft$getTimer()
            .getGameTimeDeltaPartialTick(false);

        MC.tickQpc = SkyLink.qpc() - (long)(partial * tickMs * qpcFreq / 1000.0);
        MC.tickMs = tickMs;
        MC.prevX = player.xo;
        MC.prevY = player.yo;
        MC.prevZ = player.zo;
        MC.curX = player.getX();
        MC.curY = player.getY();
        MC.curZ = player.getZ();
        MC.eyeHeightO = player.getEyeHeight();
        MC.eyeHeightT = player.getEyeHeight();
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
            float partial = ((MinecraftAccessor)(Object)minecraft)
                .skycraft$getTimer()
                .getGameTimeDeltaPartialTick(false);

            double x = player.xo + (player.getX() - player.xo) * partial;
            double y = player.yo + (player.getY() - player.yo) * partial;
            double z = player.zo + (player.getZ() - player.zo) * partial;
            Vec3 eye = player.getEyePosition(partial);
            Camera camera = minecraft.gameRenderer.getMainCamera();

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
            MC.eyeHeight = (float)(eye.y - y);
            MC.eyeX = eye.x;
            MC.eyeY = eye.y;
            MC.eyeZ = eye.z;
            MC.sensitivity = minecraft.options.sensitivity().get().floatValue();
            MC.guiScale = (int)minecraft.getWindow().getGuiScale();
            MC.fov = minecraft.options.fov().get().floatValue();
            MC.cameraMode = minecraft.options.getCameraType().ordinal();
            MC.cameraDistance = camera.isDetached()
                ? (float) camera.getPosition().distanceTo(eye)
                : 0.0F;
        }

        if (minecraft.screen != null) {
            flags |= Proto.MC_SCREEN_OPEN;
        }

        MC.flags = flags;
        MC.teleportAck = holdPos == null ? teleportAck : teleportAck - 1;
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

        if (width <= 0 || height <= 0
            || (width == appliedViewportW && height == appliedViewportH)) {
            return;
        }

        appliedViewportW = width;
        appliedViewportH = height;
        minecraft.getWindow().setWindowed(width, height);
        SkyCraft.LOG.info(
            "SkyCraft: matching Minecraft overlay viewport to Skyrim {}x{}",
            width, height
        );
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
