package dev.skycraft.mixin;

import dev.skycraft.combat.SkyCombat;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Crafting Minecraft equipment trains the linked Skyrim character's Smithing skill. */
@Mixin(ItemStack.class)
public abstract class SmithingMixin {
    @Inject(method = "onCraftedBy", at = @At("HEAD"))
    private void skycraft$trainSmithing(
        Level level,
        Player player,
        int craftCount,
        CallbackInfo ci
    ) {
        ItemStack stack = (ItemStack)(Object)this;
        if (!(player instanceof ServerPlayer serverPlayer)
            || !SkyCombat.isHost(serverPlayer)
            || craftCount <= 0
            || !SkyLink.active()) {
            return;
        }

        var item = stack.getItem();
        if (!(item instanceof ArmorItem)
            && !(item instanceof DiggerItem)
            && !(item instanceof SwordItem)
            && !(item instanceof BowItem)
            && !(item instanceof CrossbowItem)
            && !(item instanceof TridentItem)
            && !(item instanceof ShieldItem)) {
            return;
        }

        SkyLink.pushEvent(
            Proto.EV_SKILL_USE,
            Proto.SKILL_SMITHING,
            skycraft$worth(stack) * craftCount,
            0.0F, 0.0F, 0.0F, 0
        );
    }

    @Unique
    private static float skycraft$worth(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.startsWith("netherite_")) return 150.0F;
        if (path.startsWith("diamond_")) return 80.0F;
        if (path.startsWith("iron_")) return 40.0F;
        if (path.startsWith("golden_") || path.startsWith("chainmail_")) return 30.0F;
        if (path.startsWith("copper_")) return 25.0F;
        if (path.startsWith("wooden_")) return 15.0F;
        return 20.0F;
    }
}
