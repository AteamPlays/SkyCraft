package dev.skycraft.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {
    @Invoker("onPress")
    void skycraft$onPress(long window, int button, int action, int modifiers);

    @Invoker("onScroll")
    void skycraft$onScroll(long window, double horizontal, double vertical);

    @Invoker("onMove")
    void skycraft$onMove(long window, double x, double y);
}
