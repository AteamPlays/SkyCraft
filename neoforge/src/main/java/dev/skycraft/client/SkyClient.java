package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Safe player-state bridge for the 1.21.1 backport.
 *
 * Until Skyrim collision has been ported, MC_IN_WORLD intentionally stays clear.
 * That keeps Skyrim authoritative and prevents an incomplete Minecraft client from
 * moving the Skyrim player.
 */
public final class SkyClient {
    private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
    private static final SkyLink.McState MC = new SkyLink.McState();

    private static boolean linked;
    private static boolean mirrorReadyLogged;
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
            SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
        }

        if (!linked || !SkyLink.readSkyState(SKY)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        MirrorWorld.tick(minecraft, SKY);

        LocalPlayer player = minecraft.player;
        MC.flags = 0; // Do not hand movement authority to Minecraft yet.
        MC.frameCounter = ++frameCounter;

        if (player != null && MirrorWorld.isReady(minecraft)) {
            // During bring-up, Skyrim owns look direction too. This proves coordinate/state
            // exchange while leaving all Skyrim movement safely untouched.
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
                    "SkyCraft: NeoForge mirror world ready; Minecraft authority remains disabled until collision is ported"
                );
            }
        }

        SkyLink.writeMcState(MC);
    }
}
