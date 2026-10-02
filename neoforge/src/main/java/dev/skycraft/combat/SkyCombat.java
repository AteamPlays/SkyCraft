package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Bridges nearby Skyrim actors into Minecraft combat as invisible living entities.
 */
public final class SkyCombat {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, SkyCraft.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<SkyrimActorEntity>> SKYRIM_ACTOR =
        ENTITIES.register("skyrim_actor", () ->
            EntityType.Builder.<SkyrimActorEntity>of(SkyrimActorEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.8F)
                .noSave()
                .noSummon()
                .clientTrackingRange(10)
                .updateInterval(1)
                .build(SkyCraft.MOD_ID + ":skyrim_actor")
        );

    /** 15 Skyrim damage ~= 3 Minecraft damage (1.5 hearts). */
    public static final float SKYRIM_TO_MC_DAMAGE = 5.0F;

    private static final Map<Integer, SkyrimActorEntity> PROXIES = new HashMap<>();
    private static final List<SkyLink.Actor> ACTORS = new ArrayList<>();

    private SkyCombat() {}

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(SkyCombat::createAttributes);
        modBus.addListener(SkyCombat::registerRenderers);
        NeoForge.EVENT_BUS.addListener(SkyCombat::serverTick);
    }

    private static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(SKYRIM_ACTOR.get(), LivingEntity.createLivingAttributes().build());
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SKYRIM_ACTOR.get(), NoopRenderer::new);
    }

    public static SkyrimActorEntity proxy(int formId) {
        return PROXIES.get(formId);
    }

    private static void serverTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        if (!SkyLink.active() || players.isEmpty()) {
            removeAll();
            return;
        }

        ServerLevel level = players.getFirst().serverLevel();
        for (ServerPlayer player : players) {
            pickUpNearby(player);
        }

        if (SkyLink.readActors(ACTORS)) {
            sync(level);
        }

        for (SkyrimActorEntity proxy : PROXIES.values()) {
            float[] hit = proxy.takeHit();
            if (hit != null && (hit[0] > 0.0F || hit[3] > 0.0F)) {
                SkyLink.pushEvent(
                    Proto.EV_HIT_ACTOR,
                    proxy.formId(),
                    hit[0], hit[1], hit[2], hit[3],
                    Float.floatToRawIntBits(hit[4]),
                    Float.floatToRawIntBits(hit[5])
                );
                SkyCraft.LOG.info(
                    "SkyCraft: Minecraft hit Skyrim actor {} for {} (knockback {})",
                    proxy.formId(), hit[0], hit[3]
                );
            }
        }
    }

    private static void sync(ServerLevel level) {
        Map<Integer, SkyLink.Actor> live = new HashMap<>();
        for (SkyLink.Actor actor : ACTORS) {
            if (!actor.dead()) {
                live.put(actor.formId(), actor);
            }
        }

        for (Iterator<Map.Entry<Integer, SkyrimActorEntity>> it = PROXIES.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, SkyrimActorEntity> entry = it.next();
            SkyrimActorEntity proxy = entry.getValue();
            if (!live.containsKey(entry.getKey()) || proxy.isRemoved() || proxy.level() != level) {
                proxy.discard();
                it.remove();
            }
        }

        int before = PROXIES.size();
        for (SkyLink.Actor actor : live.values()) {
            SkyrimActorEntity proxy = PROXIES.get(actor.formId());
            if (proxy == null) {
                proxy = new SkyrimActorEntity(SKYRIM_ACTOR.get(), level);
                proxy.setFormId(actor.formId());
                proxy.setSize(actor.width(), actor.height());
                proxy.setPos(actor.x(), actor.y(), actor.z());
                proxy.setYRot(actor.yaw());
                proxy.setYHeadRot(actor.yaw());
                if (!actor.name().isEmpty()) {
                    proxy.setCustomName(Component.literal(actor.name()));
                }
                if (!level.addFreshEntity(proxy)) {
                    continue;
                }
                PROXIES.put(actor.formId(), proxy);
            } else {
                proxy.setSize(actor.width(), actor.height());
                proxy.setPos(actor.x(), actor.y(), actor.z());
                proxy.setYRot(actor.yaw());
                proxy.setYHeadRot(actor.yaw());
            }
            stepOnTriggers(level, proxy);
        }

        if (PROXIES.size() != before && (PROXIES.size() < 5 || PROXIES.size() % 5 == 0)) {
            SkyCraft.LOG.info("SkyCraft: {} Skyrim actors mirrored as Minecraft combat targets", PROXIES.size());
        }
    }

    private static void stepOnTriggers(ServerLevel level, SkyrimActorEntity proxy) {
        var box = proxy.getBoundingBox().deflate(1.0E-5);
        var from = net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ);
        var to = net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ);

        for (var pos : net.minecraft.core.BlockPos.betweenClosed(from, to)) {
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.TripWireBlock) {
                state.entityInside(level, pos, proxy);
            }
        }
    }

    private static void pickUpNearby(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) {
            return;
        }

        for (Entity entity : player.level().getEntities(
            player, player.getBoundingBox().inflate(1.25, 1.0, 1.25)
        )) {
            if (!entity.isRemoved()
                && (entity instanceof net.minecraft.world.entity.item.ItemEntity
                    || entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow)) {
                entity.playerTouch(player);
            }
        }
    }

    private static void removeAll() {
        if (PROXIES.isEmpty()) {
            return;
        }
        PROXIES.values().forEach(Entity::discard);
        PROXIES.clear();
    }

    /** Skyrim hit the local Minecraft player. Called on the integrated-server thread. */
    public static void hurtPlayer(
        ServerPlayer player,
        int kind,
        float skyrimDamage,
        int attackerFormId,
        int flags
    ) {
        if (!player.isAlive() || skyrimDamage <= 0.0F) {
            return;
        }

        ServerLevel level = player.serverLevel();
        SkyrimActorEntity attacker = PROXIES.get(attackerFormId);
        if (attacker != null && attacker.distanceToSqr(player) > 24.0 * 24.0) {
            attacker = null;
        }

        DamageSources sources = level.damageSources();
        DamageSource source = switch (kind) {
            case Proto.HURT_MELEE -> attacker != null ? sources.mobAttack(attacker) : sources.generic();
            case Proto.HURT_PROJECTILE -> attacker != null ? sources.mobProjectile(attacker, attacker) : sources.generic();
            case Proto.HURT_MAGIC -> attacker != null ? sources.indirectMagic(attacker, attacker) : sources.magic();
            default -> sources.generic();
        };

        float damage = skyrimDamage / SKYRIM_TO_MC_DAMAGE;
        float healthBefore = player.getHealth();
        boolean blocking = player.isBlocking();
        boolean hurt = player.hurt(source, damage);

        trainDefence(player, damage, blocking && player.getHealth() >= healthBefore - 1.0E-3F);

        if (hurt && attacker != null
            && (flags & Proto.HURT_POWER_ATTACK) != 0
            && !player.isBlocking()) {
            player.knockback(
                0.5,
                attacker.getX() - player.getX(),
                attacker.getZ() - player.getZ()
            );
        }
    }

    private static void trainDefence(ServerPlayer player, float damage, boolean blocked) {
        if (!isHost(player) || damage <= 0.0F) {
            return;
        }

        if (blocked) {
            SkyLink.pushEvent(
                Proto.EV_SKILL_USE, Proto.SKILL_BLOCK,
                damage, 0.0F, 0.0F, 0.0F, 0
            );
            return;
        }

        int light = 0;
        int heavy = 0;
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET
        }) {
            var stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;

            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (path.startsWith("iron_") || path.startsWith("diamond_") || path.startsWith("netherite_")) {
                heavy++;
            } else {
                light++;
            }
        }

        if (light + heavy > 0) {
            SkyLink.pushEvent(
                Proto.EV_SKILL_USE,
                heavy > light ? Proto.SKILL_HEAVY_ARMOR : Proto.SKILL_LIGHT_ARMOR,
                damage * (light + heavy) / 4.0F,
                0.0F, 0.0F, 0.0F, 0
            );
        }
    }

    public static boolean isHost(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && server.isSingleplayerOwner(player.getGameProfile());
    }

    public static int attackerFormId(DamageSource source) {
        return source.getEntity() instanceof SkyrimActorEntity proxy ? proxy.formId() : 0;
    }
}
