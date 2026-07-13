package io.github.term4.shardkit.shard;

import io.github.term4.shardkit.world.WorldAccess;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.ExperienceOrb;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.function.Supplier;

/** The default fork cloner behind {@link Shard#copyEntity}. */
final class EntityCopy {

    private EntityCopy() {}

    static @Nullable Entity of(Entity entity) {
        Supplier<Entity> stamped = entity.getTag(WorldAccess.ENTITY_COPY);
        if (stamped != null) return stamped.get();
        Class<?> type = entity.getClass();
        if (type == Entity.class) {
            Entity copy = new Entity(entity.getEntityType());
            copyData(entity, copy);
            return copy;
        }
        if (type == LivingEntity.class) {
            LivingEntity living = (LivingEntity) entity;
            LivingEntity copy = new LivingEntity(entity.getEntityType());
            copyData(entity, copy);
            copy.setHealth(living.getHealth());
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                copy.setEquipment(slot, living.getEquipment(slot));
            }
            return copy;
        }
        if (type == ItemEntity.class) {
            ItemEntity item = (ItemEntity) entity;
            ItemEntity copy = new ItemEntity(item.getItemStack());
            copy.setPickupDelay(Duration.ofMillis(item.getPickupDelay()));
            copyData(entity, copy);
            return copy;
        }
        if (type == ExperienceOrb.class) {
            ExperienceOrb copy = new ExperienceOrb(((ExperienceOrb) entity).getExperienceCount());
            copyData(entity, copy);
            return copy;
        }
        return null;
    }

    private static void copyData(Entity from, Entity to) {
        to.setVelocity(from.getVelocity());
        to.tagHandler().updateContent(from.tagHandler().asCompound()); // serializable tags only; Transient = empty serializer
    }
}
