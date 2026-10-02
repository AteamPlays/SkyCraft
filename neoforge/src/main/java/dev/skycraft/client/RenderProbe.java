package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.player.LocalPlayer;

/**
 * First real-Skyrim visual smoke test.
 *
 * Sends a tiny white atlas and one multicoloured cube through the exact render ring consumed by
 * SkyCraft.dll. The cube is placed three blocks in front of the Minecraft camera at eye height.
 * Once this is visible in Skyrim, the native render path is proven and this class can be replaced
 * by the full 1.21.1 world/Flywheel framebuffer exporter.
 */
public final class RenderProbe {
    private static final int FLAG_UNTEXTURED = 1 << 2;
    private static final int FLAG_NORMAL_FROM_FACES = 7 << 4;
    private static final int FULL_SKY_LIGHT = 15 << 8;
    private static boolean sent;

    private RenderProbe() {}

    public static void reset() {
        sent = false;
    }

    public static void send(LocalPlayer player) {
        if (sent || !SkyLink.active()) {
            return;
        }

        float yaw = (float) Math.toRadians(player.getYRot());
        double cx = player.getX() + Math.sin(yaw) * 3.0;
        double cy = player.getEyeY();
        double cz = player.getZ() + Math.cos(yaw) * 3.0;

        double minX = cx - 0.5;
        double minY = cy - 0.5;
        double minZ = cz - 0.5;

        int sx = Math.floorDiv((int) Math.floor(minX), 16);
        int sy = Math.floorDiv((int) Math.floor(minY), 16);
        int sz = Math.floorDiv((int) Math.floor(minZ), 16);

        float x0 = (float) (minX - sx * 16.0);
        float y0 = (float) (minY - sy * 16.0);
        float z0 = (float) (minZ - sz * 16.0);
        float x1 = x0 + 1.0F;
        float y1 = y0 + 1.0F;
        float z1 = z0 + 1.0F;

        // The Skyrim renderer requires an atlas even for untextured probe geometry.
        ByteBuffer atlasHeader = le(8).putInt(1).putInt(1).flip();
        ByteBuffer atlasPixel = ByteBuffer.allocate(4).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();

        // Six faces, two triangles per face, six vertices per face.
        ByteBuffer verts = le(36 * Proto.REN_VERTEX_BYTES);
        int flags = FLAG_UNTEXTURED | FLAG_NORMAL_FROM_FACES;

        // RGBA8 packed with red in the low byte, matching SkyCraft's RenVertex colour format.
        face(verts, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1, 0xFF0000FF, flags); // south: red
        face(verts, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0, 0xFF00FF00, flags); // north: green
        face(verts, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1, 0xFFFF0000, flags); // east: blue
        face(verts, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0, 0xFF00FFFF, flags); // west: yellow
        face(verts, x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0, 0xFFFF00FF, flags); // top: magenta
        face(verts, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1, 0xFFFFFF00, flags); // bottom: cyan
        verts.flip();

        ByteBuffer sectionHeader = le(16).putInt(sx).putInt(sy).putInt(sz).putInt(36).flip();

        boolean clearOk = SkyLink.writeRender(Proto.REN_CLEAR_ALL, le(0), null);
        boolean atlasOk = SkyLink.writeRender(Proto.REN_ATLAS, atlasHeader, atlasPixel);
        boolean sectionOk = SkyLink.writeRender(Proto.REN_SECTION, sectionHeader, verts);

        sent = atlasOk && sectionOk;
        SkyCraft.LOG.info(
            "SkyCraft: visual render probe {} at ({}, {}, {}) [section {} {} {}, clear={}, atlas={}, mesh={}]",
            sent ? "sent" : "FAILED",
            cx, cy, cz, sx, sy, sz, clearOk, atlasOk, sectionOk
        );
    }

    private static ByteBuffer le(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void face(
        ByteBuffer out,
        float ax, float ay, float az,
        float bx, float by, float bz,
        float cx, float cy, float cz,
        float dx, float dy, float dz,
        int color,
        int flags
    ) {
        vertex(out, ax,ay,az, 0,0, color, flags);
        vertex(out, bx,by,bz, 1,0, color, flags);
        vertex(out, cx,cy,cz, 1,1, color, flags);
        vertex(out, ax,ay,az, 0,0, color, flags);
        vertex(out, cx,cy,cz, 1,1, color, flags);
        vertex(out, dx,dy,dz, 0,1, color, flags);
    }

    private static void vertex(ByteBuffer out, float x, float y, float z, float u, float v, int color, int flags) {
        out.putFloat(x).putFloat(y).putFloat(z);
        out.putFloat(u).putFloat(v);
        out.putInt(color);
        out.putInt(FULL_SKY_LIGHT);
        out.putInt(flags);
    }
}
