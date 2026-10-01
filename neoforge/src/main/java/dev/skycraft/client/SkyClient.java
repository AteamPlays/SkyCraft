package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * First playable slice of the 1.21.1 port: exchange Skyrim and Minecraft player
 * state without depending on the 26.3 renderer/collision APIs.
 */
public final class SkyClient {
    private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
    private static final SkyLink.McState MC = new SkyLink.McState();

    private static boolean linked;
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
            SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
        }

        if (!linked || !SkyLink.readSkyState(SKY)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        // Keep Minecraft's look direction aligned with Skyrim while normal gameplay has focus.
        if (minecraft.screen == null && SKY.inGame() && !SKY.loading()) {
            player.setYRot(SKY.yaw);
            player.setXRot(SKY.pitch);
            player.yRotO = SKY.yaw;
            player.xRotO = SKY.pitch;
        }

        int flags = Proto.MC_IN_WORLD;
        if (player.onGround()) flags |= Proto.MC_ON_GROUND;
        if (player.isShiftKeyDown()) flags |= Proto.MC_SNEAKING;
        if (player.isSprinting()) flags |= Proto.MC_SPRINTING;
        if (player.isDeadOrDying()) flags |= Proto.MC_DEAD;
        if (player.isSwimming()) flags |= Proto.MC_SWIMMING;
        if (player.getAbilities().flying) flags |= Proto.MC_FLYING;
        if (minecraft.screen != null) flags |= Proto.MC_SCREEN_OPEN;

        Vec3 eye = player.getEyePosition();

        MC.flags = flags;
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
        MC.frameCounter = ++frameCounter;
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

        SkyLink.writeMcState(MC);
    }
}
