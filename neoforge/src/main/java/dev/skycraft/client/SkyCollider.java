package dev.skycraft.client;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyTri;
import dev.skycraft.world.TriCollider;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Feeds the local player's movement through Skyrim's exact collision triangles. */
public final class SkyCollider {
    private SkyCollider() {}

    public static Vec3 collide(LocalPlayer player, Vec3 move) {
        AABB box = player.getBoundingBox();
        double step = player.maxUpStep();
        List<SkyTri> tris = new ArrayList<>();
        SkyCollision.trianglesNear(
            box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0),
            tris
        );
        if (tris.isEmpty()) return move;

        double[] resolved = TriCollider.resolve(
            tris,
            (box.minX + box.maxX) * 0.5,
            box.minY,
            (box.minZ + box.maxZ) * 0.5,
            box.getXsize() * 0.5,
            box.getYsize(),
            step,
            player.onGround(),
            move.x, move.y, move.z
        );

        if (resolved[0] == move.x && resolved[1] == move.y && resolved[2] == move.z) return move;

        return Entity.collideBoundingBox(
            player,
            new Vec3(resolved[0], resolved[1], resolved[2]),
            box,
            player.level(),
            List.of()
        );
    }

    /** Highest exact Skyrim surface near the feet, matching original SkyCraft spawn recovery. */
    public static double groundAt(double x, double y, double z, double maxAbove) {
        List<SkyTri> tris = new ArrayList<>();
        SkyCollision.trianglesNear(
            new AABB(x - 1.0, y - 4.0, z - 1.0, x + 1.0, y + maxAbove + 1.0, z + 1.0),
            tris
        );
        return TriCollider.groundAt(tris, x, y, z, maxAbove);
    }
}
