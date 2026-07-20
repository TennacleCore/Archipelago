package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.ticking.DomainEntity;
import io.github.term4.archipelago.ticking.DomainLivingEntity;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.metadata.projectile.AbstractArrowMeta;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Visual-first snapshots: what an entity LOOKED like (type, metadata, equipment), captured at spawn so
 * playback renders it with no foreign library. Metadata keeps the wire-supported value kinds only
 * (flags, items, poses...); exotic entries drop.
 */
public final class Visuals {

    private Visuals() {}

    static CompoundBinaryTag snapshot(Entity entity) {
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder()
                .putString("type", entity.getEntityType().key().asString());
        ListBinaryTag.Builder<CompoundBinaryTag> meta = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        entity.getMetadataPacket().entries().forEach((index, entry) -> {
            CompoundBinaryTag encoded = encodeEntry(entry);
            if (encoded != null) meta.add(CompoundBinaryTag.builder().putInt("i", index).put(encoded).build());
        });
        out.put("meta", meta.build());
        if (entity instanceof LivingEntity living) {
            CompoundBinaryTag.Builder equip = CompoundBinaryTag.builder();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack item = living.getEquipment(slot);
                if (!item.isAir()) equip.put(slot.name(), Nbt.item(item));
            }
            out.put("equip", equip.build());
        }
        return out.build();
    }

    /**
     * The snapshot as a live stand-in, or {@code null} (unknown type here). Stand-ins are track-driven at BOTH
     * ends: zero server velocity (self-flight rubber-bands against corrections) and zero WIRE velocity (modern
     * clients simulate an advertised velocity ahead of the track - the rod's "spawns far then snaps").
     */
    public static @Nullable Entity entity(@NotNull CompoundBinaryTag snapshot, @Nullable Ghost owner) {
        EntityType type = EntityType.fromKey(snapshot.getString("type"));
        if (type == null) return null;
        Map<Integer, Metadata.Entry<?>> meta = new HashMap<>();
        for (BinaryTag tag : snapshot.getList("meta", BinaryTagTypes.COMPOUND)) {
            CompoundBinaryTag entry = (CompoundBinaryTag) tag;
            Metadata.Entry<?> decoded = decodeEntry(entry);
            if (decoded != null) meta.put(entry.getInt("i"), decoded);
        }
        CompoundBinaryTag equip = snapshot.getCompound("equip");
        if (!equip.keySet().isEmpty()) {
            VisualLiving living = new VisualLiving(type, meta);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                BinaryTag item = equip.get(slot.name());
                if (item != null) living.setEquipment(slot, Nbt.item(item));
            }
            return living;
        }
        return new VisualEntity(type, owner == null ? 0 : owner.getEntityId(), meta);
    }

    /** Whether {@code entity} is one of these stand-ins (no physics of its own); revived twins self-simulate. */
    public static boolean standIn(@NotNull Entity entity) {
        return entity instanceof VisualEntity || entity instanceof VisualLiving;
    }

    // Minestom seeds a first scheduled sync ~tick 20 that setSynchronizationTicks does NOT reset - on a
    // track-driven twin that absolute lands mid-leg as a periodic catch-up snap
    private static void clearSeededSync(Entity e) {
        try {
            var latch = Entity.class.getDeclaredField("nextSynchronizationTick");
            latch.setAccessible(true);
            latch.setLong(e, Long.MAX_VALUE);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static @Nullable CompoundBinaryTag encodeEntry(Metadata.Entry<?> entry) {
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
        int type = entry.type();
        Object value = entry.value();
        if (type == Metadata.TYPE_BYTE) return out.putString("k", "byte").putByte("v", (Byte) value).build();
        if (type == Metadata.TYPE_VARINT) return out.putString("k", "int").putInt("v", (Integer) value).build();
        if (type == Metadata.TYPE_LONG) return out.putString("k", "long").putLong("v", (Long) value).build();
        if (type == Metadata.TYPE_FLOAT) return out.putString("k", "float").putFloat("v", (Float) value).build();
        if (type == Metadata.TYPE_STRING) return out.putString("k", "string").putString("v", (String) value).build();
        if (type == Metadata.TYPE_BOOLEAN) return out.putString("k", "boolean").putBoolean("v", (Boolean) value).build();
        if (type == Metadata.TYPE_POSE) return out.putString("k", "pose").putString("v", ((EntityPose) value).name()).build();
        if (type == Metadata.TYPE_ITEM_STACK) {
            return out.putString("k", "item").put("v", Nbt.item((ItemStack) value)).build();
        }
        return null;
    }

    private static @Nullable Metadata.Entry<?> decodeEntry(CompoundBinaryTag entry) {
        return switch (entry.getString("k")) {
            case "byte" -> Metadata.Byte(entry.getByte("v"));
            case "int" -> Metadata.VarInt(entry.getInt("v"));
            case "long" -> Metadata.VarLong(entry.getLong("v"));
            case "float" -> Metadata.Float(entry.getFloat("v"));
            case "string" -> Metadata.String(entry.getString("v"));
            case "boolean" -> Metadata.Boolean(entry.getBoolean("v"));
            case "pose" -> Metadata.Pose(EntityPose.valueOf(entry.getString("v")));
            case "item" -> Metadata.ItemStack(Nbt.item(entry.get("v")));
            default -> null;
        };
    }

    // recorded entries layered over the live holder's (the item stack, arrow flags... ride metadata)
    private static EntityMetaDataPacket restamp(Entity entity, EntityMetaDataPacket live,
                                                Map<Integer, Metadata.Entry<?>> recorded) {
        Map<Integer, Metadata.Entry<?>> merged = new HashMap<>(live.entries());
        merged.putAll(recorded);
        return new EntityMetaDataPacket(entity.getEntityId(), merged);
    }

    /** Track-driven stand-in whose wire spawn names {@code ownerId}: clients DISCARD ownerless bobbers. */
    private static final class VisualEntity extends DomainEntity {
        private final int ownerId;
        private final Map<Integer, Metadata.Entry<?>> meta;

        VisualEntity(EntityType type, int ownerId, Map<Integer, Metadata.Entry<?>> meta) {
            super(type);
            this.ownerId = ownerId;
            this.meta = meta;
            setNoGravity(true); // the recorded position track drives it, not physics
            clearSeededSync(this);
        }

        // inert: the applier advances the server along its legs; the base tick would integrate the leg
        // velocity AND broadcast it - on top of the client's own integration, doubled motion
        @Override
        public void movementTick() {}

        @Override
        public EntityMetaDataPacket getMetadataPacket() {
            return restamp(this, super.getMetadataPacket(), meta);
        }

        @Override
        public void updateNewViewer(Player player) {
            Pos pos = getPosition();
            player.sendPacket(new SpawnEntityPacket(getEntityId(), getUuid(), getEntityType(), pos, pos.yaw(),
                    ownerId, Vec.ZERO));
            player.sendPacket(getMetadataPacket());
        }
    }

    private static final class VisualLiving extends DomainLivingEntity {
        private final Map<Integer, Metadata.Entry<?>> meta;

        VisualLiving(EntityType type, Map<Integer, Metadata.Entry<?>> meta) {
            super(type);
            this.meta = meta;
            setNoGravity(true);
            clearSeededSync(this);
        }

        // the stock living spawn path sends this (plus equipment)
        @Override
        public EntityMetaDataPacket getMetadataPacket() {
            return restamp(this, super.getMetadataPacket(), meta);
        }
    }
    /** Client sims that FIGHT external velocity (26.1 source): hurting projectiles re-accelerate along their
     *  own motion every tick ((v + v-hat x 0.1) x 0.95, terminal ~2 b/t), the bobber's gravity ignores
     *  NO_GRAVITY (drag 0.92). Zero velocity is their only inert state - their twins glide. */
    private static final Set<EntityType> OWN_SIM = Set.of(EntityType.FISHING_BOBBER, EntityType.FIREBALL,
            EntityType.SMALL_FIREBALL, EntityType.DRAGON_FIREBALL, EntityType.WITHER_SKULL,
            EntityType.WIND_CHARGE, EntityType.BREEZE_WIND_CHARGE, EntityType.SHULKER_BULLET);

    /** Whether the CLIENT integrates this entity's velocity linearly under NO_GRAVITY (snowballs, arrows,
     *  items, TNT: inertia ~0.99) - the leg-flight family. Living entities are interpolation-driven
     *  remotely; {@link #OWN_SIM} clients fight external velocity - both glide on relative moves instead. */
    public static boolean velocityFlier(@NotNull Entity entity) {
        return !(entity instanceof LivingEntity) && !OWN_SIM.contains(entity.getEntityType());
    }

    /** The bobber sinks client-side even parked (its gravity ignores NO_GRAVITY). */
    public static boolean clientRestless(@NotNull Entity entity) {
        return entity.getEntityType() == EntityType.FISHING_BOBBER;
    }

    /** Both generations turn this family's velocity into client-side acceleration (1.8 setVelocity
     *  seeds accel = v x 0.1; 26.1 adds accelerationPower 0.1 along motion per tick) - a slow-motion
     *  aim outruns its leg immediately. */
    public static boolean clientAccelerates(@NotNull Entity entity) {
        return entity.getEntityType() != EntityType.FISHING_BOBBER
                && OWN_SIM.contains(entity.getEntityType());
    }

    /** A stuck arrow's synced IN_GROUND freezes the client's own sim: velocity packets do nothing there. */
    public static boolean clientFrozen(@NotNull Entity entity) {
        return entity.getEntityMeta() instanceof AbstractArrowMeta arrow && arrow.isInGround();
    }


    /** The TNT client mangles grounded velocity itself (x0.7/-0.5/x0.7 per tick on ground): leg velocities
     *  need re-asserting EVERY tick or the landing/bounce fight renders as a stall-then-hop. */
    public static boolean clientMeddles(@NotNull Entity entity) {
        return entity.getEntityType() == EntityType.TNT;
    }

    /** A resting NON-stuck arrow faces yaw0/pitch0 on modern clients (they rotate arrows toward velocity;
     *  atan2 of zero is zero - the fully-sideways look): rest advertises IN_GROUND, legs clear it. */
    public static void freezeArrow(@NotNull Entity entity, boolean frozen) {
        if (entity.getEntityMeta() instanceof AbstractArrowMeta arrow && arrow.isInGround() != frozen) {
            arrow.setInGround(frozen);
        }
    }
}
