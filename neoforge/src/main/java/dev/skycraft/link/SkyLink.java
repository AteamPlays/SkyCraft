package dev.skycraft.link;

import static dev.skycraft.link.Proto.*;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import dev.skycraft.SkyCraft;

/**
 * Java 21 version of SkyCraft's Windows shared-memory bridge.
 *
 * The original 26.3 client uses Java 25's Foreign Function & Memory API. Minecraft
 * 1.21.1 runs Java 21, so this port talks to the same SKSE mapping through JNA.
 * The binary protocol and offsets are unchanged.
 */
public final class SkyLink {
    private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
    private static final long HEARTBEAT_TIMEOUT_MS = 8000L;

    private static final NativeLibrary KERNEL32 = NativeLibrary.getInstance("kernel32");
    private static final Function OPEN_FILE_MAPPING = KERNEL32.getFunction("OpenFileMappingW");
    private static final Function MAP_VIEW_OF_FILE = KERNEL32.getFunction("MapViewOfFile");
    private static final Function GET_TICK_COUNT64 = KERNEL32.getFunction("GetTickCount64");
    private static final Function GET_CURRENT_PROCESS_ID = KERNEL32.getFunction("GetCurrentProcessId");
    private static final Function CREATE_MUTEX = KERNEL32.getFunction("CreateMutexW");

    private static Pointer runningMutex;
    private static volatile Pointer shm;
    private static long lastOpenAttempt;
    private static int skyrimPid;
    private static int generation;
    private static int lastOpenError = -1;

    private SkyLink() {}

    /** Hold a named mutex while Minecraft is alive so the SKSE side can detect us. */
    public static synchronized void announceRunning() {
        if (runningMutex != null) {
            return;
        }
        try {
            runningMutex = CREATE_MUTEX.invokePointer(new Object[] {
                Pointer.NULL, 0, new WString(MAPPING_NAME + "_minecraft")
            });
            if (runningMutex == null) {
                SkyCraft.LOG.warn("SkyCraft: couldn't create the running-Minecraft mutex (Windows error {})", Native.getLastError());
            }
        } catch (Throwable t) {
            SkyCraft.LOG.warn("SkyCraft: couldn't create the running-Minecraft mutex", t);
        }
    }

    /** True when a live Skyrim process is updating the other end of the mapping. */
    public static boolean active() {
        Pointer s = shm;
        if (s == null) {
            return false;
        }
        long beat = s.getLong(OFF_HEADER + H_SKYRIM_HEARTBEAT);
        return tickCount() - beat < HEARTBEAT_TIMEOUT_MS;
    }

    public static int generation() {
        return generation;
    }

    public static int skyrimPid() {
        return skyrimPid;
    }

    /**
     * Try to attach to Skyrim's mapping. Once attached, refresh our heartbeat on
     * every client tick.
     */
    public static void poll() {
        Pointer s = shm;
        if (s != null) {
            s.setLong(OFF_HEADER + H_MC_HEARTBEAT, tickCount());
            int pid = s.getInt(OFF_HEADER + H_SKYRIM_PID);
            if (pid != skyrimPid) {
                skyrimPid = pid;
                generation++;
                SkyCraft.LOG.info("SkyCraft: Skyrim instance changed (pid {})", pid);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastOpenAttempt < 1000L) {
            return;
        }
        lastOpenAttempt = now;

        try {
            Pointer handle = OPEN_FILE_MAPPING.invokePointer(new Object[] {
                FILE_MAP_ALL_ACCESS, 0, new WString(MAPPING_NAME)
            });
            if (handle == null) {
                int error = Native.getLastError();
                if (error != lastOpenError) {
                    lastOpenError = error;
                    SkyCraft.LOG.info(
                        "SkyCraft: can't open Skyrim's shared memory yet (Windows error {}{})",
                        error,
                        error == 2 ? ": Skyrim hasn't created it yet"
                            : error == 5 ? ": access denied; is Skyrim elevated?"
                            : ""
                    );
                }
                return;
            }

            Pointer view = MAP_VIEW_OF_FILE.invokePointer(new Object[] {
                handle, FILE_MAP_ALL_ACCESS, 0, 0, 0L
            });
            if (view == null) {
                SkyCraft.LOG.error("SkyCraft: MapViewOfFile failed (Windows error {})", Native.getLastError());
                return;
            }

            int magic = view.getInt(OFF_HEADER + H_MAGIC);
            int version = view.getInt(OFF_HEADER + H_VERSION);
            if (magic != MAGIC || version != VERSION) {
                SkyCraft.LOG.error(
                    "SkyCraft: protocol mismatch (magic {} version {}); expected magic {} version {}",
                    Integer.toHexString(magic), version, Integer.toHexString(MAGIC), VERSION
                );
                return;
            }

            view.setInt(OFF_HEADER + H_MC_PID, GET_CURRENT_PROCESS_ID.invokeInt(new Object[0]));
            view.setLong(OFF_HEADER + H_MC_HEARTBEAT, tickCount());
            skyrimPid = view.getInt(OFF_HEADER + H_SKYRIM_PID);
            generation++;
            shm = view;
            lastOpenError = -1;
            SkyCraft.LOG.info("SkyCraft: linked to Skyrim (pid {})", skyrimPid);
        } catch (Throwable t) {
            SkyCraft.LOG.error("SkyCraft: failed to open Skyrim shared memory", t);
        }
    }

    public static long tickCount() {
        return GET_TICK_COUNT64.invokeLong(new Object[0]);
    }
}
