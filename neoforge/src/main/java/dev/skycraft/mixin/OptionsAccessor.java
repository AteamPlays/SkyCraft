package dev.skycraft.mixin;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Options.class)
public interface OptionsAccessor {
    @Accessor("pauseOnLostFocus")
    void skycraft$setPauseOnLostFocus(boolean value);
}
