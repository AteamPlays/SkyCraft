package dev.skycraft.registry;

import dev.skycraft.SkyCraft;
import dev.skycraft.world.SkyrimCollisionBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Internal blocks used by the Skyrim mirror world. No item is registered for these. */
public final class SkyBlocks {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(BuiltInRegistries.BLOCK, SkyCraft.MOD_ID);

    public static final DeferredHolder<Block, SkyrimCollisionBlock> SKYRIM_COLLISION =
        BLOCKS.register("skyrim_collision", () -> new SkyrimCollisionBlock(
            BlockBehaviour.Properties.of()
                .replaceable()
                .noOcclusion()
                .noLootTable()
                .noTerrainParticles()
                .strength(-1.0F, 3_600_000.0F)
                .pushReaction(PushReaction.BLOCK)
        ));

    private SkyBlocks() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
