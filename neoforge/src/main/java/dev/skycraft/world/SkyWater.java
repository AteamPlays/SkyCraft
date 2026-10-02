package dev.skycraft.world;

import dev.skycraft.link.SkyLink;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Skyrim's lakes, rivers and sea as Minecraft water. The shared WaterGrid provides the exact
 * surface over nearby columns; entity physics sees water there without placing water blocks.
 */
public final class SkyWater {
    private record Grid(int originX, int originZ, int size, float[] surface) {}

    private static volatile Grid grid;

    private SkyWater() {}

    public static void refresh() {
        SkyLink.WaterGrid read = SkyLink.readWaterGrid();
        if (read != null) {
            grid = new Grid(read.originX, read.originZ, read.size, read.surface);
        }
    }

    public static void clear() {
        grid = null;
    }

    public static boolean active() {
        return grid != null;
    }

    public static double surfaceAt(int x, int z) {
        Grid g = grid;
        if (g == null) return Double.NaN;
        int dx = x - g.originX(), dz = z - g.originZ();
        if (dx < 0 || dz < 0 || dx >= g.size() || dz >= g.size()) return Double.NaN;
        float s = g.surface()[dz * g.size() + dx];
        return s < -1.0e20F ? Double.NaN : s;
    }

    public static float depthIn(BlockPos pos) {
        double s = surfaceAt(pos.getX(), pos.getZ());
        if (Double.isNaN(s)) return 0.0F;
        double h = s - pos.getY();
        return h < 0.02 ? 0.0F : (float)Math.min(1.0, h);
    }

    public static FluidState fluidAt(BlockGetter level, BlockPos pos) {
        if (depthIn(pos) <= 0.0F || !level.getBlockState(pos).isAir()) return null;
        return Fluids.WATER.getSource(false);
    }

    public static float substitutedHeight(BlockGetter level, BlockPos pos) {
        float depth = depthIn(pos);
        if (depth <= 0.0F || !level.getFluidState(pos).isEmpty() || !level.getBlockState(pos).isAir()) {
            return -1.0F;
        }
        return depth;
    }
}
