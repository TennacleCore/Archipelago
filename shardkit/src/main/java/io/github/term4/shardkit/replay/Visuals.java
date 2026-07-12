package io.github.term4.shardkit.replay;

import io.github.term4.shardkit.polar.Nbt;
import io.github.term4.shardkit.ticking.DomainEntity;
import io.github.term4.shardkit.ticking.DomainLivingEntity;
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
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Visual-first snapshots: what an entity LOOKED like (type, metadata, equipment, velocity), captured at spawn so
 * playback renders it with no foreign library - a registered reviver upgrading the visual to a real entity is
 * optional. Metadata keeps the wire-supported value kinds only (flags, items, poses...); exotic entries drop.
 */
public final class Visuals {

    private Visuals() {}

    static CompoundBinaryTag snapshot(Entity entity) {
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder()
                .putString("type", entity.getEntityType().key().asString());
        Vec vel = entity.getVelocity();
        out.put("vel", Nbt.vec3(vel.x(), vel.y(), vel.z()));
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
        }

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
        }

        // the stock living spawn path sends this (plus equipment)
        @Override
        public EntityMetaDataPacket getMetadataPacket() {
            return restamp(this, super.getMetadataPacket(), meta);
        }
    }
}
