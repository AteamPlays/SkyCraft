package dev.skycraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.skycraft.SkyCraft;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;

/**
 * Asynchronous Minecraft 1.21.1 framebuffer/depth passthrough.
 *
 * World colour + depth are queued into PBOs at the world/hand boundary. The target is then
 * cleared transparent and hand/HUD/screens render normally. Their layer is queued into a third
 * PBO at the end of GameRenderer.render. A three-frame ring publishes only completed GPU copies,
 * so readback never intentionally stalls Minecraft's render thread.
 */
public final class FrameExporter {
    private static final float NEAR = 0.05F;
    private static final int RING_SIZE = 3;
    private static final Capture[] RING = new Capture[RING_SIZE];

    private static int ringNext;
    private static Capture current;
    private static ByteBuffer overlayScratch;
    private static int scratchBytes;
    private static boolean logged;
    private static boolean loggedDrop;

    private FrameExporter() {}

    private static final class Capture {
        int worldPbo;
        int depthPbo;
        int overlayPbo;
        int width;
        int height;
        int bytes;
        long fence;
        boolean busy;

        float far;
        float fov;
        double camX, camY, camZ;
        float yaw, pitch;

        void ensure(int w, int h) {
            int n = Math.multiplyExact(Math.multiplyExact(w, h), 4);
            if (worldPbo == 0) {
                worldPbo = GL15C.glGenBuffers();
                depthPbo = GL15C.glGenBuffers();
                overlayPbo = GL15C.glGenBuffers();
            }
            if (bytes == n && width == w && height == h) {
                return;
            }

            width = w;
            height = h;
            bytes = n;
            allocate(worldPbo, n);
            allocate(depthPbo, n);
            allocate(overlayPbo, n);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
        }

        private static void allocate(int pbo, int bytes) {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pbo);
            GL15C.glBufferData(GL21C.GL_PIXEL_PACK_BUFFER, bytes, GL15C.GL_STREAM_READ);
        }
    }

    /** Called after Minecraft/NeoForge finish the 3D world and immediately before the hand pass. */
    public static void captureWorld() {
        publishReady();

        Minecraft minecraft = Minecraft.getInstance();
        if (!SkyClient.linked() || minecraft.player == null || minecraft.level == null || current != null) {
            return;
        }

        RenderTarget target = minecraft.getMainRenderTarget();
        int width = target.width;
        int height = target.height;
        if (width <= 0 || height <= 0 || width > FrameLink.MAX_W || height > FrameLink.MAX_H) {
            return;
        }

        Capture capture = RING[ringNext];
        if (capture == null) {
            capture = RING[ringNext] = new Capture();
        }

        // Never wait for the GPU. If all three ring entries are still in flight, drop this visual
        // frame and let gameplay/rendering continue.
        if (capture.busy && !tryPublish(capture)) {
            if (!loggedDrop) {
                loggedDrop = true;
                SkyCraft.LOG.info("SkyCraft: async framebuffer ring saturated; dropping visual frames instead of stalling gameplay");
            }
            return;
        }

        capture.ensure(width, height);

        try {
            target.bindRead();
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);

            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, capture.worldPbo);
            GL11C.glReadPixels(
                0, 0, width, height,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L
            );

            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, capture.depthPbo);
            GL11C.glReadPixels(
                0, 0, width, height,
                GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, 0L
            );
        } catch (Throwable t) {
            SkyCraft.LOG.warn("SkyCraft: async world/depth readback queue failed", t);
            return;
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            target.unbindRead();
        }

        // Use the camera that actually rendered this frame. This matters for F5, Create seats,
        // contraption-mounted cameras and future Aeronautics cockpits: player eye metadata is not
        // necessarily the view that produced the captured depth buffer.
        Camera camera = minecraft.gameRenderer.getMainCamera();
        var cameraPos = camera.getPosition();
        capture.far = Math.max(NEAR + 1.0F, minecraft.options.getEffectiveRenderDistance() * 16.0F * 4.0F);
        capture.fov = minecraft.options.fov().get().floatValue();
        capture.camX = cameraPos.x;
        capture.camY = cameraPos.y;
        capture.camZ = cameraPos.z;
        capture.yaw = camera.getYRot();
        capture.pitch = camera.getXRot();

        // Split the frame exactly like the passthrough reference: world/depth are already queued;
        // everything rendered after this clear becomes the screen-space hand/HUD layer.
        target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(false);

        current = capture;
    }

    /** Called immediately after GameRenderer.render and before Minecraft blits its own window. */
    public static void captureOverlay(Minecraft minecraft) {
        Capture capture = current;
        current = null;

        if (capture == null) {
            publishReady();
            return;
        }

        RenderTarget target = minecraft.getMainRenderTarget();
        if (target.width != capture.width || target.height != capture.height) {
            return;
        }

        try {
            target.bindRead();
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, capture.overlayPbo);
            GL11C.glReadPixels(
                0, 0, capture.width, capture.height,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L
            );
        } catch (Throwable t) {
            SkyCraft.LOG.warn("SkyCraft: async hand/HUD readback queue failed", t);
            return;
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            target.unbindRead();
        }

        capture.fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        capture.busy = true;
        ringNext = (ringNext + 1) % RING_SIZE;

        // Usually publishes a frame from 1-2 render frames ago.
        publishReady();
    }

    private static void publishReady() {
        for (Capture capture : RING) {
            if (capture != null && capture.busy) {
                tryPublish(capture);
            }
        }
    }

    private static boolean tryPublish(Capture capture) {
        if (!capture.busy || capture.fence == 0L) {
            return !capture.busy;
        }

        int result = GL32C.glClientWaitSync(capture.fence, 0, 0L);
        if (result != GL32C.GL_ALREADY_SIGNALED && result != GL32C.GL_CONDITION_SATISFIED) {
            return false;
        }

        FrameLink.Write write = FrameLink.begin(capture.width, capture.height);
        if (write == null) {
            finishCapture(capture);
            return true;
        }

        try {
            ByteBuffer world = mapRead(capture.worldPbo, capture.bytes);
            if (world == null) throw new IllegalStateException("world PBO map failed");
            write.putWorld(world);
            unmap();

            ByteBuffer depth = mapRead(capture.depthPbo, capture.bytes);
            if (depth == null) throw new IllegalStateException("depth PBO map failed");
            write.putDepth(depth);
            unmap();

            ByteBuffer mappedOverlay = mapRead(capture.overlayPbo, capture.bytes);
            if (mappedOverlay == null) throw new IllegalStateException("overlay PBO map failed");
            ByteBuffer repaired = overlayScratch(capture.bytes);
            repaired.clear();
            repaired.limit(capture.bytes);
            repaired.put(mappedOverlay.duplicate().position(0).limit(capture.bytes));
            repaired.flip();
            unmap();

            int cleared = repairOverlayAlpha(repaired, capture.bytes);
            repaired.position(0);
            write.finish(
                repaired,
                capture.width, capture.height,
                NEAR, capture.far, capture.fov,
                capture.camX, capture.camY, capture.camZ,
                capture.yaw, capture.pitch
            );

            if (!logged) {
                logged = true;
                SkyCraft.LOG.info(
                    "SkyCraft: async three-layer passthrough active at {}x{}; overlay alpha repaired on {} pixels",
                    capture.width, capture.height, cleared
                );
            }
        } catch (Throwable t) {
            try {
                unmapIfMapped();
            } catch (Throwable ignored) {
            }
            write.abort();
            SkyCraft.LOG.warn("SkyCraft: async framebuffer publish failed", t);
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            finishCapture(capture);
        }
        return true;
    }

    private static ByteBuffer mapRead(int pbo, int bytes) {
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pbo);
        return GL30C.glMapBufferRange(
            GL21C.GL_PIXEL_PACK_BUFFER,
            0L,
            bytes,
            GL30C.GL_MAP_READ_BIT
        );
    }

    private static void unmap() {
        GL15C.glUnmapBuffer(GL21C.GL_PIXEL_PACK_BUFFER);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
    }

    private static void unmapIfMapped() {
        GL15C.glUnmapBuffer(GL21C.GL_PIXEL_PACK_BUFFER);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
    }

    private static void finishCapture(Capture capture) {
        if (capture.fence != 0L) {
            GL32C.glDeleteSync(capture.fence);
            capture.fence = 0L;
        }
        capture.busy = false;
    }

    private static ByteBuffer overlayScratch(int bytes) {
        if (overlayScratch == null || scratchBytes != bytes) {
            overlayScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            scratchBytes = bytes;
            SkyCraft.LOG.info(
                "SkyCraft: async overlay staging buffer {} MiB",
                String.format("%.1f", bytes / 1048576.0)
            );
        }
        return overlayScratch;
    }

    private static int repairOverlayAlpha(ByteBuffer rgba, int bytes) {
        int cleared = 0;
        for (int i = 0; i < bytes; i += 4) {
            int r = rgba.get(i) & 0xFF;
            int g = rgba.get(i + 1) & 0xFF;
            int b = rgba.get(i + 2) & 0xFF;
            if (r <= 2 && g <= 2 && b <= 2) {
                rgba.put(i + 3, (byte) 0);
                cleared++;
            }
        }
        return cleared;
    }
}
