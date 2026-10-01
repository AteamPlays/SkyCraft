package dev.skycraft.link;

import static dev.skycraft.link.Proto.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import org.junit.jupiter.api.Test;

/**
 * Proves the Java 21/JNA bridge can open the exact named mapping the SKSE plugin
 * creates and can exchange the header heartbeat fields.
 */
final class SkyLinkWindowsTest {
    private static final int PAGE_READWRITE = 0x04;
    private static final int FILE_MAP_ALL_ACCESS = 0xF001F;

    @Test
    void opensSkyrimMappingAndPublishesMinecraftHeartbeat() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return;
        }

        NativeLibrary kernel32 = NativeLibrary.getInstance("kernel32");
        Function create = kernel32.getFunction("CreateFileMappingW");
        Function map = kernel32.getFunction("MapViewOfFile");
        Function ticks = kernel32.getFunction("GetTickCount64");

        Pointer invalidFile = Pointer.createConstant(-1L);
        Pointer handle = create.invokePointer(new Object[] {
            invalidFile,
            Pointer.NULL,
            PAGE_READWRITE,
            (int) (MAPPING_BYTES >>> 32),
            (int) MAPPING_BYTES,
            new WString(MAPPING_NAME)
        });
        assertNotNull(handle, "CreateFileMappingW");

        Pointer view = map.invokePointer(new Object[] {
            handle, FILE_MAP_ALL_ACCESS, 0, 0, 0L
        });
        assertNotNull(view, "MapViewOfFile");

        int fakeSkyrimPid = 424242;
        view.setInt(OFF_HEADER + H_VERSION, VERSION);
        view.setInt(OFF_HEADER + H_SKYRIM_PID, fakeSkyrimPid);
        view.setLong(OFF_HEADER + H_SKYRIM_HEARTBEAT, ticks.invokeLong(new Object[0]));
        // Magic is written last in the real SKSE plugin.
        view.setInt(OFF_HEADER + H_MAGIC, MAGIC);

        SkyLink.poll();

        assertTrue(SkyLink.active(), "Minecraft should see Skyrim's live heartbeat");
        assertEquals(fakeSkyrimPid, SkyLink.skyrimPid());
        assertNotEquals(0, view.getInt(OFF_HEADER + H_MC_PID), "Minecraft PID should be published");
        assertNotEquals(0L, view.getLong(OFF_HEADER + H_MC_HEARTBEAT), "Minecraft heartbeat should be published");
    }
}
