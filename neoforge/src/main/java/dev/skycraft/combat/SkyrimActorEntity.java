package dev.skycraft.combat;

import dev.skycraft.link.Proto;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Invisible Minecraft combat target for one Skyrim actor.
 * Skyrim owns position/health; Minecraft owns hit detection, weapon damage, crits and knockback.
 */
public final class SkyrimActorEntity extends LivingEntity {
    private static final EntityDataAccessor<Integer> FORM_ID =
        SynchedEntityData.defineId(SkyrimActorEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> WIDTH =
        SynchedEntityData.defineId(SkyrimActorEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT =
        SynchedEntityData.defineId(SkyrimActorEntity.class, EntityDataSerializers.FLOAT);

    private float pendingDamage;
    private int pendingFlags;
    private int pendingWeapon;
    private double pushX;
    private double pushZ;
    private float pushStrength;
    private boolean hitThisTick;

    public SkyrimActorEntity(EntityType<? extends SkyrimActorEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
        setInvisible(true);
        setSilent(true);
    }

    public int formId() {
        return entityData.get(FORM_ID);
    }

    public void setFormId(int formId) {
        entityData.set(FORM_ID, formId);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FORM_ID, 0);
        builder.define(WIDTH, 0.6F);
        builder.define(HEIGHT, 1.8F);
    }

    public void setSize(float width, float height) {
        if (Math.abs(entityData.get(WIDTH) - width) > 0.01F
            || Math.abs(entityData.get(HEIGHT) - height) > 0.01F) {
            entityData.set(WIDTH, width);
            entityData.set(HEIGHT, height);
            refreshDimensions();
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (WIDTH.equals(accessor) || HEIGHT.equals(accessor)) {
            refreshDimensions();
        }
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return EntityDimensions.scalable(entityData.get(WIDTH), entityData.get(HEIGHT));
    }

    @Override
    protected void actuallyHurt(DamageSource source, float damage) {
        if (isInvulnerableTo(source) || damage <= 0.0F) {
            return;
        }

        // 1.21.1 calls actuallyHurt before applying armor/magic reductions. The stand-in wears
        // no armor, so weapon/enchantment damage is already represented by this value.
        pendingDamage += damage;
        if (source.getDirectEntity() instanceof Projectile) {
            pendingFlags |= Proto.HIT_PROJECTILE;
        }
        if (source.is(DamageTypeTags.IS_FIRE)) {
            pendingFlags |= Proto.HIT_FIRE;
        }
        pendingWeapon = weaponClass(source);
        hitThisTick = true;
        getCombatTracker().recordDamage(source, damage);
    }

    @Override
    public void knockback(double strength, double x, double z) {
        double len = Math.sqrt(x * x + z * z);
        if (len > 1.0E-6 && strength > pushStrength) {
            pushStrength = (float)strength;
            pushX = -x / len;
            pushZ = -z / len;
        }
        hitThisTick = true;
    }

    public void markCritical() {
        pendingFlags |= Proto.HIT_CRITICAL;
    }

    public void markSweep() {
        pendingFlags |= Proto.HIT_SWEEP;
    }

    private static int weaponClass(DamageSource source) {
        if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.ThrownTrident) {
            return Proto.WEAPON_PIERCE;
        }
        if (source.getDirectEntity() instanceof Projectile) {
            return Proto.WEAPON_ARROW;
        }

        ItemStack weapon = source.getWeaponItem();
        if ((weapon == null || weapon.isEmpty()) && source.getEntity() instanceof LivingEntity attacker) {
            weapon = attacker.getMainHandItem();
        }
        if (weapon == null || weapon.isEmpty()) return Proto.WEAPON_UNARMED;
        if (weapon.is(ItemTags.SWORDS)) return Proto.WEAPON_BLADE;
        if (weapon.is(ItemTags.AXES)) return Proto.WEAPON_AXE;
        if (weapon.is(Items.TRIDENT)) return Proto.WEAPON_PIERCE;
        return Proto.WEAPON_BLUNT;
    }

    /** damage, pushX, pushZ, strength, flags-as-float-bits, weapon-as-float-bits */
    public float[] takeHit() {
        if (!hitThisTick) {
            return null;
        }

        float[] hit = {
            pendingDamage,
            (float)pushX,
            (float)pushZ,
            pushStrength,
            Float.intBitsToFloat(pendingFlags),
            Float.intBitsToFloat(pendingWeapon)
        };

        pendingDamage = 0.0F;
        pendingFlags = 0;
        pendingWeapon = 0;
        pushX = pushZ = 0.0;
        pushStrength = 0.0F;
        hitThisTick = false;
        return hit;
    }

    @Override
    public void tick() {
        // Position is overwritten from Skyrim; only vanilla timers/effects should advance.
        baseTick();
        setHealth(getMaxHealth());
        setDeltaMovement(0.0, 0.0, 0.0);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return null;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return null;
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return java.util.List.of();
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        // Skyrim owns the real actor's equipment; the proxy only exists for hit detection.
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }
}
