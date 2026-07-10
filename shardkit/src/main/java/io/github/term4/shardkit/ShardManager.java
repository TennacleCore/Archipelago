package io.github.term4.shardkit;

import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Creates, names and destroys shards. Optional - a bare {@code new Shard(instance)} works too; the manager adds the registry + lifecycle events. */
public final class ShardManager {

    private final Map<String, Shard> byName = new ConcurrentHashMap<>();
    private final Set<Shard> shards = ConcurrentHashMap.newKeySet();

    public @NotNull Shard create(@NotNull Instance base) {
        return register(new Shard(base), null);
    }

    /** {@link #create(Instance)} registered under {@code name}; throws if the name is taken. */
    public @NotNull Shard create(@NotNull Instance base, @NotNull String name) {
        return register(new Shard(base), name);
    }

    /** {@link Shard#fork} registered under {@code name} (same registry + create-event semantics as {@link #create}). */
    public @NotNull Shard fork(@NotNull Shard source, @NotNull String name,
                               @Nullable Function<Entity, Entity> entityCloner) {
        return register(source.fork(entityCloner), name);
    }

    private Shard register(Shard shard, @Nullable String name) {
        if (name != null && byName.putIfAbsent(name, shard) != null) {
            shard.reset(); // a fork already spawned its clones - drop them
            throw new IllegalArgumentException("shard name already in use: " + name);
        }
        shards.add(shard);
        EventDispatcher.call(new ShardCreateEvent(shard));
        return shard;
    }

    public @Nullable Shard byName(@NotNull String name) { return byName.get(name); }

    public @NotNull Collection<Shard> shards() { return Collections.unmodifiableSet(shards); }

    /** Evicts members, strips the shard from observers' views, drops the diff + entities, unregisters, fires {@link ShardDestroyEvent}. */
    public void destroy(@NotNull Shard shard) {
        for (Player p : List.copyOf(shard.members())) shard.leave(p);
        for (Player o : List.copyOf(shard.observers())) {
            ShardView view = ShardView.explicit(o);
            ShardView.set(o, view != null ? view.without(shard) : null); // a view of OTHER shards survives
        }
        shard.reset();
        byName.values().remove(shard);
        shards.remove(shard);
        EventDispatcher.call(new ShardDestroyEvent(shard));
    }
}
