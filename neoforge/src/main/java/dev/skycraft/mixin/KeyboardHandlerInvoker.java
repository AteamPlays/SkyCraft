package dev.skycraft.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("keyPress")
    void skycraft$keyPress(long window, int key, int scanCode, int action, int modifiers);

    @Invoker("charTyped")
    void skycraft$charTyped(long window, int codePoint, int modifiers);
}
