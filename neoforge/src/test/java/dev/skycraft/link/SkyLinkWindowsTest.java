package dev.skycraft.link;

import static dev.skycraft.link.Proto.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import org.junit.jupiter.api.Test;

/**
 * Exercises the same named mapping and binary offsets used by the SKSE plugin.
 */
final class SkyLinkWindowsTest {
    private static final int PAGE_READWRITE = 0x04;
    private static final int FILE_MAP_ALL_ACCESS = 0xF001F;

    @Test
    void exchangesHeaderAndStateWithSkyrimLayout() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return;

        NativeLibrary kernel32 = NativeLibrary.getInstance("kernel32");
        Function create = kernel32.getFunction("CreateFileMappingW");
        Function map = kernel32.getFunction("MapViewOfFile");
        Function ticks = kernel32.getFunction("GetTickCount64");

        Pointer handle = create.invokePointer(new Object[] {
            Pointer.createConstant(-1L), Pointer.NULL, PAGE_READWRITE,
            (int) (MAPPING_BYTES >>> 32), (int) MAPPING_BYTES, new WString(MAPPING_NAME)
        });
        assertNotNull(handle, "CreateFileMappingW");

        Pointer view = map.invokePointer(new Object[] { handle, FILE_MAP_ALL_ACCESS, 0, 0, 0L });
        assertNotNull(view, "MapViewOfFile");

        int fakeSkyrimPid = 424242;
        view.setInt(OFF_HEADER + H_VERSION, VERSION);
        view.setInt(OFF_HEADER + H_SKYRIM_PID, fakeSkyrimPid);
        view.setLong(OFF_HEADER + H_SKYRIM_HEARTBEAT, ticks.invokeLong(new Object[0]));
        view.setInt(OFF_HEADER + H_MAGIC, MAGIC);

        long sb = OFF_SKY_STATE;
        view.setInt(sb + SS_SEQ, 2);
        view.setInt(sb + SS_FLAGS, SKY_IN_GAME);
        view.setInt(sb + SS_WORLD_ID, 17);
        view.setInt(sb + SS_COLLISION_EPOCH, 9);
        view.setDouble(sb + SS_POS_X, 12.25);
        view.setDouble(sb + SS_POS_Y, -3.5);
        view.setDouble(sb + SS_POS_Z, 88.75);
        view.setFloat(sb + SS_YAW, 123.0f);
        view.setFloat(sb + SS_PITCH, -14.5f);
        view.setInt(sb + SS_TELEPORT_SEQ, 6);
        view.setInt(sb + SS_VIEWPORT_W, 1920);
        view.setInt(sb + SS_VIEWPORT_H, 1080);
        view.setFloat(sb + SS_GAME_HOUR, 18.25f);

        SkyLink.poll();

        assertTrue(SkyLink.active());
        assertEquals(fakeSkyrimPid, SkyLink.skyrimPid());
        assertNotEquals(0, view.getInt(OFF_HEADER + H_MC_PID));
        assertNotEquals(0L, view.getLong(OFF_HEADER + H_MC_HEARTBEAT));

        SkyLink.SkyState sky = new SkyLink.SkyState();
        assertTrue(SkyLink.readSkyState(sky));
        assertTrue(sky.inGame());
        assertEquals(17, sky.worldId);
        assertEquals(12.25, sky.x);
        assertEquals(-3.5, sky.y);
        assertEquals(88.75, sky.z);
        assertEquals(123.0f, sky.yaw);
        assertEquals(1920, sky.viewportW);
        assertEquals(18.25f, sky.gameHour);

        SkyLink.McState mc = new SkyLink.McState();
        mc.flags = MC_IN_WORLD | MC_ON_GROUND;
        mc.x = 4.5;
        mc.y = 70.25;
        mc.z = -10.0;
        mc.yaw = 90.0f;
        mc.pitch = 5.0f;
        mc.eyeHeight = 1.62f;
        mc.frameCounter = 1234;
        mc.fov = 70.0f;
        mc.tickQpc = 99999;
        SkyLink.writeMcState(mc);

        long mb = OFF_MC_STATE;
        assertEquals(4.5, view.getDouble(mb + MS_X));
        assertEquals(70.25, view.getDouble(mb + MS_Y));
        assertEquals(-10.0, view.getDouble(mb + MS_Z));
        assertEquals(90.0f, view.getFloat(mb + MS_YAW));
        assertEquals(1234L, view.getLong(mb + MS_FRAME_COUNTER));
        assertEquals(70.0f, view.getFloat(mb + MS_FOV));
        assertEquals(99999L, view.getLong(mb + MS_TICK_QPC));
        assertEquals(0, view.getInt(mb + MS_SEQ) & 1, "seqlock must finish even");
    }
}
