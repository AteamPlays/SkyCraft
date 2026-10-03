package dev.skycraft.link;

import static dev.skycraft.link.Proto.*;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java 21/JNA implementation of SkyCraft's Windows shared-memory protocol.
 *
 * The Fabric 26.3 implementation uses Java 25's FFM API. This class preserves the
 * exact same binary layout while staying compatible with Minecraft 1.21.1 / Java 21.
 */
public final class SkyLink {
    private static final Logger LOG = LoggerFactory.getLogger("skycraft-link");
    private static final VarHandle INT_VIEW =
        MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
    private static final long HEARTBEAT_TIMEOUT_MS = 8000L;

    private static final NativeLibrary KERNEL32 = NativeLibrary.getInstance("kernel32");
    private static final Function OPEN_FILE_MAPPING = KERNEL32.getFunction("OpenFileMappingW");
    private static final Function MAP_VIEW_OF_FILE = KERNEL32.getFunction("MapViewOfFile");
    private static final Function GET_TICK_COUNT64 = KERNEL32.getFunction("GetTickCount64");
    private static final Function GET_CURRENT_PROCESS_ID = KERNEL32.getFunction("GetCurrentProcessId");
    private static final Function QUERY_PERFORMANCE_COUNTER = KERNEL32.getFunction("QueryPerformanceCounter");
    private static final Function QUERY_PERFORMANCE_FREQUENCY = KERNEL32.getFunction("QueryPerformanceFrequency");
    private static final Function CREATE_MUTEX = KERNEL32.getFunction("CreateMutexW");

    private static Pointer runningMutex;
    private static volatile Pointer shm;
    private static long lastOpenAttempt;
    private static int skyrimPid;
    private static volatile int generation;
    private static int lastOpenError = -1;
    private static int overlayBack = 1;

    private SkyLink() {}

    public static synchronized void announceRunning() {
        if (runningMutex != null) return;
        try {
            runningMutex = CREATE_MUTEX.invokePointer(new Object[] {
                Pointer.NULL, 0, new WString(MAPPING_NAME + "_minecraft")
            });
            if (runningMutex == null) {
                LOG.warn("SkyCraft: couldn't create running-Minecraft mutex (Windows error {})", Native.getLastError());
            }
        } catch (Throwable t) {
            LOG.warn("SkyCraft: couldn't create running-Minecraft mutex", t);
        }
    }

    public static boolean active() {
        Pointer s = shm;
        if (s == null) return false;
        VarHandle.loadLoadFence();
        long beat = s.getLong(OFF_HEADER + H_SKYRIM_HEARTBEAT);
        return tickCount() - beat < HEARTBEAT_TIMEOUT_MS;
    }

    public static int generation() { return generation; }
    public static int skyrimPid() { return skyrimPid; }
    public static Pointer segment() { return shm; }

    public static void poll() {
        Pointer s = shm;
        if (s != null) {
            s.setLong(OFF_HEADER + H_MC_HEARTBEAT, tickCount());
            VarHandle.storeStoreFence();
            int pid = s.getInt(OFF_HEADER + H_SKYRIM_PID);
            if (pid != skyrimPid) {
                skyrimPid = pid;
                overlayBack = 1;
                generation++;
                LOG.info("SkyCraft: Skyrim instance changed (pid {})", pid);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastOpenAttempt < 1000L) return;
        lastOpenAttempt = now;

        try {
            Pointer handle = OPEN_FILE_MAPPING.invokePointer(new Object[] {
                FILE_MAP_ALL_ACCESS, 0, new WString(MAPPING_NAME)
            });
            if (handle == null) {
                int error = Native.getLastError();
                if (error != lastOpenError) {
                    lastOpenError = error;
                    LOG.info("SkyCraft: can't open Skyrim shared memory yet (Windows error {}{})",
                        error,
                        error == 2 ? ": Skyrim hasn't created it yet"
                            : error == 5 ? ": access denied; is Skyrim elevated?" : "");
                }
                return;
            }

            Pointer view = MAP_VIEW_OF_FILE.invokePointer(new Object[] {
                handle, FILE_MAP_ALL_ACCESS, 0, 0, 0L
            });
            if (view == null) {
                LOG.error("SkyCraft: MapViewOfFile failed (Windows error {})", Native.getLastError());
                return;
            }

            int magic = view.getInt(OFF_HEADER + H_MAGIC);
            int version = view.getInt(OFF_HEADER + H_VERSION);
            if (magic != MAGIC || version != VERSION) {
                LOG.error("SkyCraft: protocol mismatch (magic {} version {}); expected magic {} version {}",
                    Integer.toHexString(magic), version, Integer.toHexString(MAGIC), VERSION);
                return;
            }

            view.setInt(OFF_HEADER + H_MC_PID, GET_CURRENT_PROCESS_ID.invokeInt(new Object[0]));
            view.setLong(OFF_HEADER + H_MC_HEARTBEAT, tickCount());
            VarHandle.storeStoreFence();
            skyrimPid = view.getInt(OFF_HEADER + H_SKYRIM_PID);
            generation++;
            shm = view;
            lastOpenError = -1;
            LOG.info("SkyCraft: linked to Skyrim (pid {})", skyrimPid);
        } catch (Throwable t) {
            LOG.error("SkyCraft: failed to open Skyrim shared memory", t);
        }
    }

    public static long tickCount() {
        return GET_TICK_COUNT64.invokeLong(new Object[0]);
    }

    public static synchronized long qpc() {
        com.sun.jna.Memory out = new com.sun.jna.Memory(8);
        int ok = QUERY_PERFORMANCE_COUNTER.invokeInt(new Object[] { out });
        if (ok == 0) throw new IllegalStateException("QueryPerformanceCounter failed");
        return out.getLong(0);
    }

    public static synchronized long qpcFrequency() {
        com.sun.jna.Memory out = new com.sun.jna.Memory(8);
        int ok = QUERY_PERFORMANCE_FREQUENCY.invokeInt(new Object[] { out });
        if (ok == 0) throw new IllegalStateException("QueryPerformanceFrequency failed");
        return out.getLong(0);
    }

    // ---- Skyrim state ---------------------------------------------------------------------

    public static final class SkyState {
        public int seq, flags, worldId, collisionEpoch;
        public double x, y, z;
        public float yaw, pitch;
        public int teleportSeq, viewportW, viewportH;
        public float gameHour;

        public boolean inGame() { return (flags & SKY_IN_GAME) != 0; }
        public boolean menuOpen() { return (flags & SKY_MENU_OPEN) != 0; }
        public boolean loading() { return (flags & SKY_LOADING) != 0; }
    }

    public static final class WaterGrid {
        public int originX, originZ, worldId;
        public final int size = WATER_GRID_SIZE;
        public final float[] surface = new float[WATER_GRID_SIZE * WATER_GRID_SIZE];
    }

    public static boolean readSkyState(SkyState out) {
        Pointer s = shm;
        if (s == null) return false;
        long b = OFF_SKY_STATE;
        for (int attempt = 0; attempt < 1000; attempt++) {
            int seq1 = s.getInt(b + SS_SEQ);
            VarHandle.loadLoadFence();
            if ((seq1 & 1) != 0) {
                if (attempt > 100) Thread.yield(); else Thread.onSpinWait();
                continue;
            }
            out.flags = s.getInt(b + SS_FLAGS);
            out.worldId = s.getInt(b + SS_WORLD_ID);
            out.collisionEpoch = s.getInt(b + SS_COLLISION_EPOCH);
            out.x = s.getDouble(b + SS_POS_X);
            out.y = s.getDouble(b + SS_POS_Y);
            out.z = s.getDouble(b + SS_POS_Z);
            out.yaw = s.getFloat(b + SS_YAW);
            out.pitch = s.getFloat(b + SS_PITCH);
            out.teleportSeq = s.getInt(b + SS_TELEPORT_SEQ);
            out.viewportW = s.getInt(b + SS_VIEWPORT_W);
            out.viewportH = s.getInt(b + SS_VIEWPORT_H);
            out.gameHour = s.getFloat(b + SS_GAME_HOUR);
            VarHandle.loadLoadFence();
            int seq2 = s.getInt(b + SS_SEQ);
            if (seq1 == seq2) {
                out.seq = seq1;
                return true;
            }
        }
        return false;
    }

    public static WaterGrid readWaterGrid() {
        Pointer s = shm;
        if (s == null) return null;
        long b = OFF_WATER_GRID;
        WaterGrid out = new WaterGrid();
        for (int attempt = 0; attempt < 100; attempt++) {
            int seq1 = s.getInt(b + WG_SEQ);
            VarHandle.loadLoadFence();
            if ((seq1 & 1) != 0 || seq1 == 0) {
                if (seq1 == 0) return null;
                Thread.onSpinWait();
                continue;
            }
            out.originX = s.getInt(b + WG_ORIGIN_X);
            out.originZ = s.getInt(b + WG_ORIGIN_Z);
            out.worldId = s.getInt(b + WG_WORLD_ID);
            for (int i = 0; i < out.surface.length; i++) {
                out.surface[i] = s.getFloat(b + WG_SURFACE + i * 4L);
            }
            VarHandle.loadLoadFence();
            if (s.getInt(b + WG_SEQ) == seq1) return out;
        }
        return null;
    }

    public static int skyStateSeq() {
        Pointer s = shm;
        if (s == null) return 0;
        VarHandle.loadLoadFence();
        return s.getInt(OFF_SKY_STATE + SS_SEQ);
    }

    // ---- Minecraft state ------------------------------------------------------------------

    public static final class McState {
        public int flags;
        public double x, y, z;
        public float yaw, pitch, eyeHeight, sensitivity;
        public int teleportAck, guiScale;
        public long frameCounter;
        public float fov, bobPhase, bobAmount;
        public double eyeX, eyeY, eyeZ;
        public long tickQpc;
        public double prevX, prevY, prevZ, curX, curY, curZ;
        public float eyeHeightO, eyeHeightT, walkDistO, walkDist, bobO, bob;
        public float tickMs = 50.0F;
        public int cameraMode;
        public float cameraDistance;
    }

    public static synchronized void writeMcState(McState st) {
        Pointer s = shm;
        if (s == null) return;
        long b = OFF_MC_STATE;
        int seq = s.getInt(b + MS_SEQ);
        s.setInt(b + MS_SEQ, seq + 1);
        VarHandle.storeStoreFence();
        s.setInt(b + MS_FLAGS, st.flags);
        s.setDouble(b + MS_X, st.x);
        s.setDouble(b + MS_Y, st.y);
        s.setDouble(b + MS_Z, st.z);
        s.setFloat(b + MS_YAW, st.yaw);
        s.setFloat(b + MS_PITCH, st.pitch);
        s.setFloat(b + MS_EYE_HEIGHT, st.eyeHeight);
        s.setFloat(b + MS_SENSITIVITY, st.sensitivity);
        s.setInt(b + MS_TELEPORT_ACK, st.teleportAck);
        s.setInt(b + MS_GUI_SCALE, st.guiScale);
        s.setLong(b + MS_FRAME_COUNTER, st.frameCounter);
        s.setFloat(b + MS_FOV, st.fov);
        s.setFloat(b + MS_BOB_PHASE, st.bobPhase);
        s.setFloat(b + MS_BOB_AMOUNT, st.bobAmount);
        s.setDouble(b + MS_EYE_X, st.eyeX);
        s.setDouble(b + MS_EYE_Y, st.eyeY);
        s.setDouble(b + MS_EYE_Z, st.eyeZ);
        s.setLong(b + MS_TICK_QPC, st.tickQpc);
        s.setDouble(b + MS_PREV_X, st.prevX);
        s.setDouble(b + MS_PREV_X + 8, st.prevY);
        s.setDouble(b + MS_PREV_X + 16, st.prevZ);
        s.setDouble(b + MS_CUR_X, st.curX);
        s.setDouble(b + MS_CUR_X + 8, st.curY);
        s.setDouble(b + MS_CUR_X + 16, st.curZ);
        s.setFloat(b + MS_EYE_HEIGHT_O, st.eyeHeightO);
        s.setFloat(b + MS_EYE_HEIGHT_T, st.eyeHeightT);
        s.setFloat(b + MS_WALK_O, st.walkDistO);
        s.setFloat(b + MS_WALK, st.walkDist);
        s.setFloat(b + MS_BOB_O, st.bobO);
        s.setFloat(b + MS_BOB, st.bob);
        s.setFloat(b + MS_TICK_MS, st.tickMs);
        s.setInt(b + MS_CAMERA_MODE, st.cameraMode);
        s.setFloat(b + MS_CAMERA_DISTANCE, st.cameraDistance);
        VarHandle.storeStoreFence();
        s.setInt(b + MS_SEQ, seq + 2);
    }

    // ---- input ring -----------------------------------------------------------------------

    @FunctionalInterface
    public interface InputSink {
        void accept(int type, int code, int a, int b, int c);
    }

    public static void drainInput(InputSink sink) {
        Pointer s = shm;
        if (s == null) return;
        long base = OFF_INPUT_RING;
        VarHandle.loadLoadFence();
        long head = s.getLong(base + IR_HEAD);
        long tail = s.getLong(base + IR_TAIL);
        if (head - tail > INPUT_RING_ENTRIES) tail = head - INPUT_RING_ENTRIES;
        while (tail < head) {
            long e = base + IR_DATA + (tail & (INPUT_RING_ENTRIES - 1)) * 16L;
            int type = Short.toUnsignedInt(s.getShort(e));
            int code = Short.toUnsignedInt(s.getShort(e + 2));
            int a = s.getInt(e + 4);
            int b = s.getInt(e + 8);
            int c = s.getInt(e + 12);
            tail++;
            sink.accept(type, code, a, b, c);
        }
        s.setLong(base + IR_TAIL, tail);
        VarHandle.storeStoreFence();
    }

    // ---- actors ---------------------------------------------------------------------------

    public record Actor(int formId, int flags, float x, float y, float z, float yaw,
                        float width, float height, float healthFrac, int level, String name) {
        public boolean dead() { return (flags & ACTOR_DEAD) != 0; }
        public boolean hostile() { return (flags & ACTOR_HOSTILE) != 0; }
    }

    public static int actorSeq() {
        Pointer s = shm;
        if (s == null) return -1;
        VarHandle.loadLoadFence();
        return s.getInt(OFF_ACTOR_TABLE + AT_SEQ);
    }

    public static boolean readActors(List<Actor> out) {
        out.clear();
        Pointer s = shm;
        if (s == null) return false;
        long b = OFF_ACTOR_TABLE;
        for (int attempt = 0; attempt < 16; attempt++) {
            int seq1 = s.getInt(b + AT_SEQ);
            VarHandle.loadLoadFence();
            if ((seq1 & 1) != 0) {
                Thread.onSpinWait();
                continue;
            }
            int count = Math.min(s.getInt(b + AT_COUNT), MAX_ACTORS);
            for (int i = 0; i < count; i++) {
                long r = b + AT_RECORDS + i * ACTOR_RECORD_BYTES;
                out.add(new Actor(
                    s.getInt(r), s.getInt(r + 4),
                    s.getFloat(r + 8), s.getFloat(r + 12), s.getFloat(r + 16),
                    s.getFloat(r + 20), s.getFloat(r + 24), s.getFloat(r + 28),
                    s.getFloat(r + 32), Short.toUnsignedInt(s.getShort(r + 36)),
                    readName(s, r + 40, 24)
                ));
            }
            VarHandle.loadLoadFence();
            if (s.getInt(b + AT_SEQ) == seq1) return true;
            out.clear();
        }
        return false;
    }

    private static String readName(Pointer s, long off, int max) {
        byte[] bytes = s.getByteArray(off, max);
        int n = 0;
        while (n < bytes.length && bytes[n] != 0) n++;
        return new String(bytes, 0, n, StandardCharsets.UTF_8);
    }

    // ---- events ---------------------------------------------------------------------------

    public static void pushEvent(int type, int formId, float a, float b, float c, float d, int flags) {
        pushEvent(type, formId, a, b, c, d, flags, 0);
    }

    public static synchronized void pushEvent(int type, int formId, float a, float b, float c, float d, int flags, int weapon) {
        Pointer s = shm;
        if (s == null) return;
        long base = OFF_EVENT_RING;
        long head = s.getLong(base + ER_HEAD);
        VarHandle.loadLoadFence();
        long tail = s.getLong(base + ER_TAIL);
        if (head - tail >= EVENT_RING_ENTRIES) return;
        long e = base + ER_DATA + (head & (EVENT_RING_ENTRIES - 1)) * EVENT_BYTES;
        s.setInt(e, type);
        s.setInt(e + 4, formId);
        s.setFloat(e + 8, a);
        s.setFloat(e + 12, b);
        s.setFloat(e + 16, c);
        s.setFloat(e + 20, d);
        s.setInt(e + 24, flags);
        s.setInt(e + 28, weapon);
        VarHandle.storeStoreFence();
        s.setLong(base + ER_HEAD, head + 1);
    }

    // ---- world entities -------------------------------------------------------------------

    public record WorldEntity(int kind, int id, float x, float y, float z, float yaw,
                              float pitch, float scale, float[] ext, float[] uv, int tint) {}

    public static synchronized void writeWorldEntities(List<WorldEntity> entities, float[] selection) {
        Pointer s = shm;
        if (s == null) return;
        long b = OFF_WORLD_ENTITIES;
        int seq = s.getInt(b + WE_SEQ);
        s.setInt(b + WE_SEQ, seq + 1);
        VarHandle.storeStoreFence();
        int count = Math.min(entities.size(), MAX_WORLD_ENTITIES);
        s.setInt(b + WE_COUNT, count);
        s.setInt(b + WE_HAS_SELECTION, selection != null ? 1 : 0);
        if (selection != null) {
            for (int i = 0; i < 6; i++) s.setFloat(b + WE_SEL_MIN + i * 4L, selection[i]);
        }
        for (int i = 0; i < count; i++) {
            WorldEntity w = entities.get(i);
            long r = b + WE_RECORDS + i * WORLD_ENTITY_BYTES;
            s.setInt(r, w.kind());
            s.setInt(r + 4, w.id());
            s.setFloat(r + 8, w.x());
            s.setFloat(r + 12, w.y());
            s.setFloat(r + 16, w.z());
            s.setFloat(r + 20, w.yaw());
            s.setFloat(r + 24, w.pitch());
            s.setFloat(r + 28, w.scale());
            for (int k = 0; k < 3; k++) s.setFloat(r + 32 + k * 4L, w.ext() != null ? w.ext()[k] : 0.0F);
            for (int k = 0; k < 12; k++) s.setFloat(r + 44 + k * 4L, w.uv() != null && k < w.uv().length ? w.uv()[k] : 0.0F);
            s.setInt(r + 92, w.tint());
        }
        VarHandle.storeStoreFence();
        s.setInt(b + WE_SEQ, seq + 2);
    }

    // ---- render ring ----------------------------------------------------------------------

    public static boolean writeRender(int type, ByteBuffer header, ByteBuffer body) {
        return writeRender(type, header, body, 500);
    }

    public static boolean tryWriteRender(int type, ByteBuffer header, ByteBuffer body) {
        return writeRender(type, header, body, 1);
    }

    private static synchronized boolean writeRender(int type, ByteBuffer header, ByteBuffer body, int attempts) {
        Pointer s = shm;
        if (s == null) return false;
        int headerLen = header.remaining();
        int bodyLen = body != null ? body.remaining() : 0;
        int payload = headerLen + bodyLen;
        long msgBytes = (8L + payload + 7L) & ~7L;
        if (msgBytes > RR_DATA_BYTES / 2) {
            LOG.warn("SkyCraft: render message too large ({} bytes)", msgBytes);
            return false;
        }
        long base = OFF_RENDER_RING;
        for (int attempt = 0; attempt < attempts; attempt++) {
            long head = s.getLong(base + RR_HEAD);
            VarHandle.loadLoadFence();
            long tail = s.getLong(base + RR_TAIL);
            long pos = head % RR_DATA_BYTES;
            long pad = pos + msgBytes > RR_DATA_BYTES ? RR_DATA_BYTES - pos : 0;
            if (RR_DATA_BYTES - (head - tail) < msgBytes + pad) {
                if (attempt + 1 >= attempts) break;
                try { Thread.sleep(2); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                continue;
            }
            if (pad > 0) {
                s.setInt(base + RR_DATA + pos, REN_PAD);
                s.setInt(base + RR_DATA + pos + 4, 0);
                head += pad;
                pos = 0;
            }
            long at = base + RR_DATA + pos;
            s.setInt(at, type);
            s.setInt(at + 4, payload);
            writeBuffer(s, at + 8, header);
            if (body != null && bodyLen > 0) writeBuffer(s, at + 8 + headerLen, body);
            VarHandle.storeStoreFence();
            s.setLong(base + RR_HEAD, head + msgBytes);
            return true;
        }
        return false;
    }

    private static void writeBuffer(Pointer dst, long offset, ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        dst.write(offset, data, 0, data.length);
    }

    // ---- overlay --------------------------------------------------------------------------

    public static long overlayBackSlotOffset() {
        return OFF_OVERLAY_PIXELS + overlayBack * OVERLAY_SLOT_BYTES;
    }

    /**
     * Publish metadata after callers have copied pixels into {@link #overlayBackSlotOffset()}.
     * The final exchange is revisited when the 1.21.1 renderer is ported; one writer is serialized here.
     */
    public static synchronized void publishOverlay(int width, int height, boolean bottomUp, long frameId) {
        Pointer s = shm;
        if (s == null) return;
        long hdr = OFF_OVERLAY_SLOT_HDR + overlayBack * SLOT_HDR_SIZE;
        s.setInt(hdr + SH_WIDTH, width);
        s.setInt(hdr + SH_HEIGHT, height);
        s.setInt(hdr + SH_FLAGS, bottomUp ? 1 : 0);
        s.setLong(hdr + SH_FRAME_ID, frameId);
        VarHandle.storeStoreFence();
        ByteBuffer state = s.getByteBuffer(OFF_OVERLAY_CTL + OC_STATE, Integer.BYTES);
        int old = (int) INT_VIEW.getAndSet(state, 0, overlayBack | OVERLAY_DIRTY);
        overlayBack = old & 3;
        s.setLong(OFF_OVERLAY_CTL + OC_FRAMES_PUBLISHED,
            s.getLong(OFF_OVERLAY_CTL + OC_FRAMES_PUBLISHED) + 1L);
    }

    // ---- collision ring -------------------------------------------------------------------

    public static long collisionHead() {
        Pointer s = shm;
        if (s == null) return 0;
        VarHandle.loadLoadFence();
        return s.getLong(OFF_COLLISION_RING + CR_HEAD);
    }

    public static long collisionTail() {
        Pointer s = shm;
        return s == null ? 0 : s.getLong(OFF_COLLISION_RING + CR_TAIL);
    }

    public static void setCollisionTail(long tail) {
        Pointer s = shm;
        if (s != null) {
            s.setLong(OFF_COLLISION_RING + CR_TAIL, tail);
            VarHandle.storeStoreFence();
        }
    }
}
