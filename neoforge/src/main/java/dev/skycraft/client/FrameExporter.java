package dev.skycraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.sun.jna.Pointer;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11C;
import net.minecraft.client.Minecraft;

/**
 * NeoForge 1.21.1 port of the original SkyCraft overlay exporter.
 *
 * While linked, LevelRendererMixin removes Minecraft's level from the main target, leaving
 * first-person hand + HUD + screens on a transparent background. This class reads that RGBA
 * target after GameRenderer finishes and publishes it through SkyCraft's existing overlay
 * triple-buffer for the SKSE plugin to composite over Skyrim.
 *
 * This first port intentionally uses a synchronous glReadPixels path. It is simple and proves
 * correctness; once the visual path is green we can replace it with asynchronous PBO readback.
 */
public final class FrameExporter {
    private static ByteBuffer pixels;
    private static byte[] copy;
    private static int bufferBytes;
    private static long nextFrameId = 1L;
    private static long lastCaptureNs;
    private static boolean logged;

    // Keep the synchronous proof-of-life path from stalling either game excessively.
    private static final long MIN_CAPTURE_NS = 33_000_000L; // ~30 fps

    private FrameExporter() {}

    public static void capture(Minecraft minecraft) {
        if (!SkyClient.linked() || minecraft.player == null || minecraft.level == null) {
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
        if (width <= 0 || height <= 0 || width > Proto.MAX_OVERLAY_W || height > Proto.MAX_OVERLAY_H) {
            return;
        }

        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ensureCapacity(bytes);

        target.bindRead();
        try {
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            pixels.clear();
            pixels.limit(bytes);
            GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
        } finally {
            target.unbindRead();
        }

        pixels.position(0);
        pixels.limit(bytes);
        pixels.get(copy, 0, bytes);

        Pointer shm = SkyLink.segment();
        if (shm == null) {
            return;
        }
        shm.write(SkyLink.overlayBackSlotOffset(), copy, 0, bytes);
        long frameId = nextFrameId++;
        SkyLink.publishOverlay(width, height, true, frameId);

        if (!logged) {
            logged = true;
            SkyCraft.LOG.info(
                "SkyCraft: NeoForge hand/HUD overlay capture active ({}x{}, synchronous RGBA readback)",
                width, height
            );
        }
    }

    private static void ensureCapacity(int bytes) {
        if (pixels != null && bufferBytes == bytes) {
            return;
        }
        pixels = ByteBuffer.allocateDirect(bytes);
        copy = new byte[bytes];
        bufferBytes = bytes;
        SkyCraft.LOG.info("SkyCraft: allocated {} MiB hand/HUD overlay buffer", String.format("%.1f", bytes / 1048576.0));
    }
}
