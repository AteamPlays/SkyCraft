package dev.skycraft.client;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Dedicated high-bandwidth frame mapping for the Minecraft -> Skyrim compositor.
 *
 * Kept separate from Local\\SkyCraft_v1 so the stable gameplay/collision protocol does not
 * change just because graphics transport is large.
 */
public final class FrameLink {
    public static final String NAME = "Local\\SkyCraftFrame_v1";
    public static final int MAGIC = 0x46594B53; // "SKYF"
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 4096;
    public static final int SLOT_COUNT = 3;
    public static final int SLOT_DESC = 256;
    public static final int SLOT_DESC_BYTES = 128;
    public static final int MAX_W = 3840;
    public static final int MAX_H = 2160;
    public static final long LAYER_MAX = (long) MAX_W * MAX_H * 4L;
    public static final long SLOT_STRIDE = LAYER_MAX * 3L;
    public static final long MAPPING_BYTES = HEADER_BYTES + SLOT_STRIDE * SLOT_COUNT;

    private static final int PAGE_READWRITE = 0x04;
    private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
    private static final NativeLibrary KERNEL32 = NativeLibrary.getInstance("kernel32");
    private static final Function CREATE_FILE_MAPPING = KERNEL32.getFunction("CreateFileMappingW");
    private static final Function MAP_VIEW_OF_FILE = KERNEL32.getFunction("MapViewOfFile");
    private static final Function GET_CURRENT_PROCESS_ID = KERNEL32.getFunction("GetCurrentProcessId");

    private static Pointer mapping;
    private static Pointer view;
    private static int nextSlot;
    private static long frameCounter;
    private static long publishCounter;

    private FrameLink() {}

    public static synchronized boolean ensure() {
        if (view != null) return true;

        try {
            mapping = CREATE_FILE_MAPPING.invokePointer(new Object[] {
                Pointer.createConstant(-1L),
                Pointer.NULL,
                PAGE_READWRITE,
                (int) (MAPPING_BYTES >>> 32),
                (int) MAPPING_BYTES,
                new WString(NAME)
            });
            if (mapping == null) {
                dev.skycraft.SkyCraft.LOG.error(
                    "SkyCraft: CreateFileMappingW({}) failed (Windows error {})",
                    NAME, Native.getLastError()
                );
                return false;
            }

            view = MAP_VIEW_OF_FILE.invokePointer(new Object[] {
                mapping, FILE_MAP_ALL_ACCESS, 0, 0, MAPPING_BYTES
            });
            if (view == null) {
                dev.skycraft.SkyCraft.LOG.error(
                    "SkyCraft: MapViewOfFile({}) failed (Windows error {})",
                    NAME, Native.getLastError()
                );
                return false;
            }

            // Reset the small control/header area. Pixel slots do not need clearing because a
            // descriptor is invisible until its sequence is published even.
            view.clear(HEADER_BYTES);
            view.setInt(0, MAGIC);
            view.setInt(4, VERSION);
            view.setInt(8, HEADER_BYTES);
            view.setInt(12, SLOT_COUNT);
            view.setLong(16, SLOT_STRIDE);
            view.setInt(24, MAX_W);
            view.setInt(28, MAX_H);
            view.setInt(40, -1);
            view.setInt(44, GET_CURRENT_PROCESS_ID.invokeInt(new Object[0]));
            VarHandle.fullFence();

            dev.skycraft.SkyCraft.LOG.info(
                "SkyCraft: framebuffer mapping {} ready ({} MiB)",
                NAME, MAPPING_BYTES / (1024L * 1024L)
            );
            return true;
        } catch (Throwable t) {
            dev.skycraft.SkyCraft.LOG.error("SkyCraft: framebuffer mapping initialization failed", t);
            view = null;
            return false;
        }
    }

    public static synchronized Write begin(int width, int height) {
        if (!ensure() || width <= 0 || height <= 0 || width > MAX_W || height > MAX_H) {
            return null;
        }

        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % SLOT_COUNT;
        long desc = SLOT_DESC + (long) SLOT_DESC_BYTES * slot;
        long seq = view.getLong(desc);
        if ((seq & 1L) != 0L) seq++;

        view.setLong(desc, seq + 1L); // odd: writer owns this slot
        VarHandle.fullFence();

        long bytes = (long) width * height * 4L;
        return new Write(slot, desc, seq, bytes, ++frameCounter);
    }

    public static final class Write {
        private final int slot;
        private final long desc;
        private final long seq;
        private final long bytes;
        private final long frame;
        private boolean finished;

        private Write(int slot, long desc, long seq, long bytes, long frame) {
            this.slot = slot;
            this.desc = desc;
            this.seq = seq;
            this.bytes = bytes;
            this.frame = frame;
        }

        private long base() {
            return HEADER_BYTES + SLOT_STRIDE * slot;
        }

        public void putWorld(ByteBuffer src) {
            copy(src, base(), bytes);
        }

        public void putDepth(ByteBuffer src) {
            copy(src, base() + bytes, bytes);
        }

        public void finish(ByteBuffer overlay, int width, int height, float near, float far, float fovDeg,
                           double camX, double camY, double camZ, float yaw, float pitch) {
            if (finished || view == null) return;
            copy(overlay, base() + bytes * 2L, bytes);

            view.setLong(desc + 8, frame);
            view.setInt(desc + 16, width);
            view.setInt(desc + 20, height);
            view.setInt(desc + 24, 1); // rows are bottom-up (OpenGL readback)
            view.setFloat(desc + 32, near);
            view.setFloat(desc + 36, far);
            view.setFloat(desc + 40, fovDeg);
            view.setDouble(desc + 48, camX);
            view.setDouble(desc + 56, camY);
            view.setDouble(desc + 64, camZ);
            view.setFloat(desc + 72, yaw);
            view.setFloat(desc + 76, pitch);
            VarHandle.fullFence();
            view.setLong(desc, seq + 2L); // even: complete
            view.setInt(40, slot);
            VarHandle.fullFence();
            view.setLong(32, ++publishCounter);
            finished = true;
        }

        public void abort() {
            if (finished || view == null) return;
            // Make the slot stable but do not publish it as latest.
            view.setLong(desc, seq + 2L);
            finished = true;
        }

        private static void copy(ByteBuffer src, long dstOffset, long n) {
            ByteBuffer from = src.duplicate();
            from.position(0);
            from.limit(Math.toIntExact(n));
            ByteBuffer dst = view.getByteBuffer(dstOffset, n).order(ByteOrder.nativeOrder());
            dst.position(0);
            dst.put(from);
        }
    }
}
