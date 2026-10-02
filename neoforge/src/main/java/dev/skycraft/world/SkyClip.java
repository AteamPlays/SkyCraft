package dev.skycraft.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Refines vanilla block raycasts with exact Skyrim triangles.
 *
 * This is the non-destructive core of original SkyClip. Dig-wall refinement is added with the
 * SkyDig parity pass; normal terrain, architecture, trees and static meshes work here already.
 */
public final class SkyClip {
    private SkyClip() {}

    public enum Use {
        PROJECTILE,
        PICK
    }

    private static final ThreadLocal<List<SkyTri>> SCRATCH =
        ThreadLocal.withInitial(ArrayList::new);

    public static BlockHitResult refine(Vec3 from, Vec3 to, BlockHitResult vanilla, Use use) {
        SkyRay.Hit hit = cast(from, to);
        if (hit == null) return vanilla;

        Vec3 location = new Vec3(hit.x(), hit.y(), hit.z());
        if (vanilla.getType() != HitResult.Type.MISS
            && from.distanceToSqr(vanilla.getLocation()) <= from.distanceToSqr(location)) {
            return vanilla;
        }

        Direction face = Direction.values()[SkyRay.dominantFace(hit.nx(), hit.ny(), hit.nz())];
        int[] cell = use == Use.PICK
            ? SkyRay.placementCell(hit)
            : SkyRay.surfaceCell(hit);

        return new SkyrimHitResult(
            location,
            face,
            new BlockPos(cell[0], cell[1], cell[2]),
            hit
        );
    }

    public static SkyRay.Hit cast(Vec3 from, Vec3 to) {
        List<SkyTri> tris = SCRATCH.get();
        tris.clear();
        SkyCollision.trianglesNear(new AABB(from, to).inflate(0.01), tris);
        if (tris.isEmpty()) return null;

        SkyRay.Hit hit = SkyRay.cast(
            tris,
            from.x, from.y, from.z,
            to.x, to.y, to.z
        );
        tris.clear();
        return hit;
    }

    public static final class SkyrimHitResult extends BlockHitResult {
        public final double nx, ny, nz;
        public final SkyRay.Hit hit;

        public SkyrimHitResult(
            Vec3 location,
            Direction direction,
            BlockPos pos,
            SkyRay.Hit hit
        ) {
            super(location, direction, pos, false);
            this.nx = hit.nx();
            this.ny = hit.ny();
            this.nz = hit.nz();
            this.hit = hit;
        }
    }
}
