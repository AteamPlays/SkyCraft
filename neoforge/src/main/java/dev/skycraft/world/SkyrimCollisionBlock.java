package dev.skycraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible collision written into the mirror world from Skyrim.
 *
 * Sable/Rapier memoizes physics collision by BlockState. Therefore the state itself
 * encodes a stable, position-independent collider. For the first Aeronautics MVP
 * we use 1/8-block height steps; walls/vertical geometry naturally become full
 * blocks. Exact Skyrim triangles remain a later player-collision refinement.
 */
public final class SkyrimCollisionBlock extends Block {
    public static final IntegerProperty HEIGHT = IntegerProperty.create("height", 1, 8);
    private static final VoxelShape[] SHAPES = new VoxelShape[9];

    static {
        SHAPES[0] = Shapes.empty();
        for (int h = 1; h <= 7; h++) {
            SHAPES[h] = Shapes.box(0.0, 0.0, 0.0, 1.0, h / 8.0, 1.0);
        }
        SHAPES[8] = Shapes.block();
    }

    public SkyrimCollisionBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HEIGHT, 8));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HEIGHT);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Original SkyCraft deliberately excludes the local player from the coarse Skyrim
        // voxel/proxy collider. The local player is resolved against exact streamed triangles
        // in SkyCollider; applying both makes walls/corners sticky and causes double collision.
        // Old development builds persisted thousands of proxy blocks in the mirror save.
        // They are now legacy only: while linked, virtual SkyCollision owns collision entirely.
        // Keeping these inert prevents an old test save from reintroducing coarse wall/doorframe
        // collisions on either the client or integrated server.
        if (dev.skycraft.client.SkyClient.linked()) {
            return Shapes.empty();
        }
        return SHAPES[state.getValue(HEIGHT)];
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (dev.skycraft.client.SkyClient.linked()) {
            return Shapes.empty();
        }
        return SHAPES[state.getValue(HEIGHT)];
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
