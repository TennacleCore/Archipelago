package io.github.term4.archipelago.storage;

import io.github.term4.archipelago.shard.Shard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.term4.archipelago.ticking.DomainEntity;
import io.github.term4.archipelago.ticking.DomainLivingEntity;
import io.github.term4.archipelago.ticking.DomainTicked;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ExperienceOrb;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Entity {@code <->} save descriptor ({@code {id, pos, data}} compounds in the world user-data):
 * {@link MechanicsWorld#ENTITY_SAVE} stamps first, registered describers next, generic tiers for the plain
 * classes; load revives per registered {@link #reviveEntity reviver}, unknown ids pass through untouched.
 */
public final class EntityDescriptors {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityDescriptors.class);

    private EntityDescriptors() {}

    private static final Map<String, Function<CompoundBinaryTag, Entity>> REVIVERS = new ConcurrentHashMap<>();

    // built-in revivers construct domain-capable classes: a revival into a domain shard ticks on ITS clock
    static {
        REVIVERS.put("archipelago:entity", data ->
                stampCopy(readData(data, new DomainEntity(EntityType.fromKey(data.getString("type"))))));
        REVIVERS.put("archipelago:living", data -> {
            LivingEntity living = new RevivedLiving(EntityType.fromKey(data.getString("type")));
            readData(data, living);
            living.setHealth(data.getFloat("health"));
            Nbt.applyEquipment(living, data.getCompound("equipment"));
            return stampCopy(living);
        });
        REVIVERS.put("archipelago:item", data -> {
            ItemEntity item = new RevivedItem(Nbt.item(data.get("item")));
            item.setPickupDelay(Duration.ofMillis(data.getLong("delayMs")));
            return stampCopy(readData(data, item));
        });
        REVIVERS.put("archipelago:xp_orb", data -> stampCopy(readData(data, new RevivedOrb(data.getShort("xp")))));
    }

    // Shard.copyEntity's exact-class tiers can't see these classes; the copy stamp round-trips through the
    // descriptor instead, so forking a shard with revived entities still reproduces them
    private static Entity stampCopy(Entity entity) {
        entity.setTag(MechanicsWorld.ENTITY_COPY, () -> reviveDescriptor(descriptor(entity)));
        return entity;
    }

    /** Registers the load-side factory for {@code id} descriptors ({@link MechanicsWorld#ENTITY_SAVE} stamps); replacement WARNs. Returns the removal handle. */
    public static @NotNull Runnable reviveEntity(@NotNull String id, @NotNull Function<CompoundBinaryTag, Entity> reviver) {
        if (REVIVERS.put(id, reviver) != null) LOGGER.warn("entity reviver '{}' replaced", id);
        return () -> REVIVERS.remove(id, reviver);
    }

    private static final List<Function<Entity, CompoundBinaryTag>> DESCRIBERS = new CopyOnWriteArrayList<>();

    /**
     * Save-side twin of {@link #reviveEntity}: maps another library's {@code ENTITY_SAVE} mirror into saves.
     * First non-null wins, after the native stamp, before the generic tiers.
     */
    public static @NotNull Runnable describeEntity(@NotNull Function<Entity, @Nullable CompoundBinaryTag> describer) {
        DESCRIBERS.add(describer);
        return () -> DESCRIBERS.remove(describer);
    }

    /** Revives ONE saved entity descriptor through the registered revivers (replay/playback systems), or {@code null}. */
    public static @Nullable Entity reviveDescriptor(@NotNull CompoundBinaryTag descriptor) {
        Function<CompoundBinaryTag, Entity> reviver = REVIVERS.get(descriptor.getString("id"));
        return reviver != null ? reviver.apply(descriptor) : null;
    }

    /** Descriptors whose id had no reviver at load, re-emitted on save so this app never DROPS a foreign app's entities. */
    private static final Tag<List<CompoundBinaryTag>> UNREVIVED = Tag.Transient("archipelago:unrevived");

    /** Every owned entity's descriptor + position, ready for any container ({@link #read} is the load half). */
    public static ListBinaryTag write(Shard shard) {
        ListBinaryTag.Builder<CompoundBinaryTag> out = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (Entity entity : shard.entities()) {
            if (entity instanceof Player || entity.isRemoved()) continue;
            CompoundBinaryTag desc = descriptor(entity);
            if (desc == null) continue; // no stamp, no generic tier: not persistable (like the fork cloner's skip)
            Pos pos = entity.getPosition();
            out.add(CompoundBinaryTag.builder().put(desc)
                    .put("pos", Nbt.vec3(pos.x(), pos.y(), pos.z())).build());
        }
        List<CompoundBinaryTag> unrevived = shard.getTag(UNREVIVED);
        if (unrevived != null) unrevived.forEach(out::add);
        return out.build();
    }

    /** {@code entity}'s save descriptor - its stamp, a registered describer's, or a generic tier - or {@code null}. */
    public static @Nullable CompoundBinaryTag descriptor(@NotNull Entity entity) {
        Supplier<CompoundBinaryTag> stamped = entity.getTag(MechanicsWorld.ENTITY_SAVE);
        if (stamped != null) return stamped.get();
        for (Function<Entity, CompoundBinaryTag> describer : DESCRIBERS) {
            CompoundBinaryTag desc = describer.apply(entity);
            if (desc != null) return desc;
        }
        Class<?> type = entity.getClass();
        // exact classes only (a subclass's behavior isn't data) - plus our own revived twins, which are pure data
        if (type == Entity.class || type == DomainEntity.class) return generic("archipelago:entity", entity).build();
        if (type == LivingEntity.class || type == RevivedLiving.class) {
            LivingEntity living = (LivingEntity) entity;
            return generic("archipelago:living", entity)
                    .putFloat("health", living.getHealth()).put("equipment", Nbt.equipment(living)).build();
        }
        if (type == ItemEntity.class || type == RevivedItem.class) {
            ItemEntity item = (ItemEntity) entity;
            return generic("archipelago:item", entity)
                    .put("item", Nbt.item(item.getItemStack())).putLong("delayMs", item.getPickupDelay()).build();
        }
        if (type == ExperienceOrb.class || type == RevivedOrb.class) {
            return generic("archipelago:xp_orb", entity).putShort("xp", ((ExperienceOrb) entity).getExperienceCount()).build();
        }
        return null;
    }

    private static final class RevivedLiving extends DomainLivingEntity {
        RevivedLiving(EntityType type) { super(type); }
    }

    private static final class RevivedItem extends ItemEntity implements DomainTicked {
        RevivedItem(ItemStack stack) { super(stack); }

        @Override protected void refreshCurrentChunk(@NotNull Chunk chunk) {
            if (ExecutionDomain.of(this) != null) { currentChunk = chunk; return; }
            super.refreshCurrentChunk(chunk);
        }

        @Override public void tick(long time) {
            if (DomainTicked.foreignDriver(this)) return;
            super.tick(time);
        }
    }

    private static final class RevivedOrb extends ExperienceOrb implements DomainTicked {
        RevivedOrb(short xp) { super(xp); }

        @Override protected void refreshCurrentChunk(@NotNull Chunk chunk) {
            if (ExecutionDomain.of(this) != null) { currentChunk = chunk; return; }
            super.refreshCurrentChunk(chunk);
        }

        @Override public void tick(long time) {
            if (DomainTicked.foreignDriver(this)) return;
            super.tick(time);
        }
    }

    private static CompoundBinaryTag.Builder generic(String id, Entity entity) {
        Vec vel = entity.getVelocity();
        return CompoundBinaryTag.builder().putString("id", id).putString("type", entity.getEntityType().key().asString())
                .put("vel", Nbt.vec3(vel.x(), vel.y(), vel.z()))
                .put("tags", entity.tagHandler().asCompound()); // serializable tags only, like the fork cloner
    }

    private static Entity readData(CompoundBinaryTag data, Entity entity) {
        ListBinaryTag vel = data.getList("vel", BinaryTagTypes.DOUBLE);
        if (vel.size() == 3) entity.setVelocity(new Vec(vel.getDouble(0), vel.getDouble(1), vel.getDouble(2)));
        entity.tagHandler().updateContent(data.getCompound("tags"));
        return entity;
    }

    /** Revives {@link #write}'s descriptors into {@code shard}; unrevivable ones stash for lossless resave.
     *  Spawns settle ASYNC (chunk loads); a failed spawn logs instead of vanishing silently. */
    public static void read(Shard shard, ListBinaryTag entities) {
        List<CompoundBinaryTag> unrevived = new ArrayList<>();
        for (BinaryTag tag : entities) {
            CompoundBinaryTag desc = (CompoundBinaryTag) tag;
            Function<CompoundBinaryTag, Entity> reviver = REVIVERS.get(desc.getString("id"));
            Entity entity = reviver != null ? reviver.apply(desc) : null;
            if (entity == null) {
                unrevived.add(desc); // no reviver here: everything else loads, and a resave keeps the descriptor
                continue;
            }
            ListBinaryTag pos = desc.getList("pos", BinaryTagTypes.DOUBLE);
            if (pos.size() == 3) {
                shard.spawn(entity, new Pos(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)))
                        .exceptionally(t -> {
                            LOGGER.warn("revived entity failed to spawn ({})", desc.getString("id"), t);
                            return null;
                        });
            }
        }
        if (unrevived.isEmpty()) shard.removeTag(UNREVIVED);
        else shard.setTag(UNREVIVED, List.copyOf(unrevived));
    }
}
