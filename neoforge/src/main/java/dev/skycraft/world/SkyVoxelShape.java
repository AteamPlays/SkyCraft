package dev.skycraft.world;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 1.21.1 equivalent of CubeVoxelShape for SkyCraft's fixed 8x8x8 Skyrim collision cells.
 * CubeVoxelShape's constructor is protected in this version, so keep the exact same coordinate
 * semantics in our own subclass instead of using thousands of physical proxy blocks.
 */
public final class SkyVoxelShape extends VoxelShape {
    private static final DoubleList EIGHTHS = DoubleArrayList.wrap(new double[] {
        0.0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0
    });

    public SkyVoxelShape(DiscreteVoxelShape shape) {
        super(shape);
    }

    @Override
    public DoubleList getCoords(Direction.Axis axis) {
        return EIGHTHS;
    }
}
