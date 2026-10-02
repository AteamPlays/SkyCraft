package dev.skycraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.skycraft.SkyCraft;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.opengl.GL11C;

/**
 * Minecraft 1.21.1 framebuffer/depth passthrough.
 *
 * At the end of the world pass (before the first-person hand), captures the fully-rendered
 * Minecraft world and depth. Then it clears the main target to real transparent black.
 * At the end of the frame, the hand/HUD/screens that rendered after that clear are captured
 * as a separate overlay. Skyrim receives all three layers and performs depth-aware compositing.
 */
public final class FrameExporter {
    private static final float NEAR = 0.05F;
    private static final long MIN_CAPTURE_NS = 66_000_000L; // ~15 fps proof path; async readback comes next

    private static ByteBuffer world;
    private static ByteBuffer depth;
    private static ByteBuffer overlay;
    private static int allocatedBytes;
    private static long lastCaptureNs;
    private static FrameLink.Write pending;
    private static int pendingW;
    private static int pendingH;
    private static float pendingFar;
    private static float pendingFov;
    private static double pendingCamX, pendingCamY, pendingCamZ;
    private static float pendingYaw, pendingPitch;
    private static boolean logged;

    private FrameExporter() {}

    /** Called from GameRenderer.renderLevel after the world/Forge-last pass and before renderHand. */
    public static void captureWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!SkyClient.linked() || minecraft.player == null || minecraft.level == null || pending != null) {
            return;
        }

        long now = System.nanoTime();
        if (now - lastCaptureNs < MIN_CAPTURE_NS) {
            return;
        }
        lastCaptureNs = now;

        RenderTarget target = minecraft.getMainRenderTarget();
        int width = target.width;
        int height = target.height;
        if (width <= 0 || height <= 0 || width > FrameLink.MAX_W || height > FrameLink.MAX_H) {
            return;
        }

        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ensureCapacity(bytes);

        FrameLink.Write write = FrameLink.begin(width, height);
        if (write == null) {
            return;
        }

        try {
            target.bindRead();
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);

            world.clear();
            world.limit(bytes);
            GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, world);

            depth.clear();
            depth.limit(bytes);
            GL11C.glReadPixels(0, 0, width, height, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, depth);
        } catch (Throwable t) {
            write.abort();
            SkyCraft.LOG.warn("SkyCraft: world/depth framebuffer readback failed", t);
            return;
        } finally {
            target.unbindRead();
        }

        // The host supplies the sky/background. Turn pixels with untouched far-plane depth
        // transparent, while every actual Minecraft surface stays opaque. The RGB remains the
        // exact result Minecraft/Flywheel rendered.
        world.order(ByteOrder.nativeOrder());
        depth.order(ByteOrder.nativeOrder());
        for (int p = 0, i = 0; i < bytes; p += 4, i += 4) {
            float d = depth.getFloat(i);
            world.put(p + 3, (byte) (d >= 0.999999F ? 0 : 0xFF));
        }

        world.position(0);
        depth.position(0);
        write.putWorld(world);
        write.putDepth(depth);

        LocalPlayer player = minecraft.player;
        var eye = player.getEyePosition();
        pending = write;
        pendingW = width;
        pendingH = height;
        pendingFar = Math.max(NEAR + 1.0F, minecraft.options.getEffectiveRenderDistance() * 16.0F * 4.0F);
        pendingFov = minecraft.options.fov().get().floatValue();
        pendingCamX = eye.x;
        pendingCamY = eye.y;
        pendingCamZ = eye.z;
        pendingYaw = player.getYRot();
        pendingPitch = player.getXRot();

        // Exactly the split used by the passthrough reference: after preserving world+depth,
        // clear to transparent so the first-person hand, screen effects, HUD and GUI become
        // their own screen-space layer. Clearing depth here is okay: vanilla does the same
        // immediately before drawing the hand.
        target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(false);
    }

    /** Called after the full GameRenderer/GUI frame. Publishes the overlay with its matching world/depth. */
    public static void captureOverlay(Minecraft minecraft) {
        FrameLink.Write write = pending;
        if (write == null) {
            return;
        }

        RenderTarget target = minecraft.getMainRenderTarget();
        int bytes = pendingW * pendingH * 4;
        if (target.width != pendingW || target.height != pendingH) {
            pending = null;
            write.abort();
            return;
        }

        try {
            target.bindRead();
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            overlay.clear();
            overlay.limit(bytes);
            GL11C.glReadPixels(0, 0, pendingW, pendingH, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, overlay);
        } catch (Throwable t) {
            write.abort();
            SkyCraft.LOG.warn("SkyCraft: hand/HUD framebuffer readback failed", t);
            pending = null;
            return;
        } finally {
            target.unbindRead();
        }

        overlay.position(0);
        write.finish(
            overlay, pendingW, pendingH, NEAR, pendingFar, pendingFov,
            pendingCamX, pendingCamY, pendingCamZ, pendingYaw, pendingPitch
        );
        pending = null;

        if (!logged) {
            logged = true;
            SkyCraft.LOG.info(
                "SkyCraft: three-layer framebuffer passthrough active ({}x{} world RGBA + depth + hand/HUD)",
                pendingW, pendingH
            );
        }
    }

    private static void ensureCapacity(int bytes) {
        if (allocatedBytes == bytes && world != null) {
            return;
        }
        world = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        depth = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        overlay = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        allocatedBytes = bytes;
        SkyCraft.LOG.info(
            "SkyCraft: allocated {:.1f} MiB three-layer framebuffer readback",
            bytes * 3.0 / 1048576.0
        );
    }
}
