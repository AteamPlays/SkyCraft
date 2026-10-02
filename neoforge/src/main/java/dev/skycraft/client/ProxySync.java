package dev.skycraft.client;

import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Keeps client copies of Skyrim combat proxies at the latest Skyrim positions every frame so
 * crosshair/melee targeting does not lag one or two server ticks behind what the player sees.
 */
public final class ProxySync {
    private static final List<SkyLink.Actor> ACTORS = new ArrayList<>();
    private static final Map<Integer, SkyLink.Actor> BY_ID = new HashMap<>();

    private ProxySync() {}

    public static void frame(Minecraft minecraft) {
        if (minecraft.level == null || !SkyLink.readActors(ACTORS)) {
            return;
        }

        BY_ID.clear();
        for (SkyLink.Actor actor : ACTORS) {
            BY_ID.put(actor.formId(), actor);
        }

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof SkyrimActorEntity proxy)) {
                continue;
            }

            SkyLink.Actor actor = BY_ID.get(proxy.formId());
            if (actor == null) {
                continue;
            }

            proxy.setSize(actor.width(), actor.height());
            proxy.setPos(actor.x(), actor.y(), actor.z());
            proxy.xo = actor.x();
            proxy.yo = actor.y();
            proxy.zo = actor.z();
            proxy.setYRot(actor.yaw());
            proxy.yRotO = actor.yaw();
            proxy.setYHeadRot(actor.yaw());
        }
    }
}
