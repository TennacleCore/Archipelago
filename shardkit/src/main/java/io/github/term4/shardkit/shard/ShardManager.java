package io.github.term4.shardkit.shard;

import io.github.term4.shardkit.event.ShardCreateEvent;
import io.github.term4.shardkit.event.ShardDestroyEvent;
import io.github.term4.shardkit.event.ticking.DomainQuarantineEvent;
import io.github.term4.shardkit.ticking.ExecutionDomain;
import net.minestom.server.MinecraftServer;
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

/**
 * Creates, names and destroys shards. Optional - a bare {@code new Shard(instance)} works too; the manager adds
 * the registry, lifecycle events, and the FAIL-STOP fallout: shards whose domain the watchdog abandons drop
 * into STASIS (frozen; member players hand back to server ticking) - work never reroutes, and the app decides
 * to re-clock fresh, destroy, or leave them. Construct after {@code MinecraftServer.init()}.
 */
public final class ShardManager {

    private final Map<String, Shard> byName = new ConcurrentHashMap<>();
    private final Map<Shard, String> names = new ConcurrentHashMap<>();
    private final Set<Shard> shards = ConcurrentHashMap.newKeySet();

    public ShardManager() {
        // fail-stop = the DOMAIN dies; its shards drop into STASIS (frozen entities on the dead clock) - the
        // app recovers with a FRESH clock (shard.domain(...)), destroys, or leaves them. Member players alone
        // hand back to server ticking: their connections must keep living
        MinecraftServer.getGlobalEventHandler().addListener(DomainQuarantineEvent.class, e -> {
            for (Shard shard : List.copyOf(shards)) {
                ExecutionDomain domain = shard.domain();
                if (domain == null || !domain.sharesClock(e.domain())) continue;
                for (Player p : shard.members()) {
                    if (ExecutionDomain.of(p) != null) ExecutionDomain.detach(p);
                }
            }
        });
    }

    public @NotNull Shard create(@NotNull Instance base) {
        return register(new Shard(base), null);
    }

    /** {@link #create(Instance)} registered under {@code name}; throws if the name is taken. */
    public @NotNull Shard create(@NotNull Instance base, @NotNull String name) {
        return register(new Shard(base), name);
    }

    /** A shard bound to tick {@code domain} ({@link Shard#Shard(Instance, ExecutionDomain)}). */
    public @NotNull Shard create(@NotNull Instance base, @NotNull ExecutionDomain domain) {
        return register(new Shard(base, domain), null);
    }

    public @NotNull Shard create(@NotNull Instance base, @NotNull String name, @NotNull ExecutionDomain domain) {
        return register(new Shard(base, domain), name);
    }

    /** A child layer of {@code parent} ({@link Shard#Shard(Shard)}) registered under {@code name}. */
    public @NotNull Shard child(@NotNull Shard parent, @NotNull String name) {
        return register(new Shard(parent), name);
    }

    /** A child layer on its own {@code domain} ({@link Shard#Shard(Shard, ExecutionDomain)}). */
    public @NotNull Shard child(@NotNull Shard parent, @NotNull String name, @Nullable ExecutionDomain domain) {
        return register(new Shard(parent, domain), name);
    }

    /** {@link Shard#fork} registered under {@code name} (same registry + create-event semantics as {@link #create}). */
    public @NotNull Shard fork(@NotNull Shard source, @NotNull String name,
                               @Nullable Function<Entity, Entity> entityCloner) {
        return register(source.fork(entityCloner), name);
    }

    /** {@link Shard#flattenOnto} registered under {@code name}. */
    public @NotNull Shard flatten(@NotNull Shard top, @NotNull Shard bottom, @NotNull String name,
                                  @Nullable Function<Entity, Entity> cloner) {
        return register(top.flattenOnto(bottom, cloner), name);
    }

    /** {@link Shard#flatten(Function) chain flatten} registered under {@code name}. */
    public @NotNull Shard flatten(@NotNull Shard top, @NotNull String name,
                                  @Nullable Function<Entity, Entity> cloner) {
        return register(top.flatten(cloner), name);
    }

    /** Adopts an externally constructed shard (e.g. a {@code ShardFile.loadTree} layer) into the registry. */
    public @NotNull Shard register(@NotNull Shard shard, @Nullable String name) {
        if (name != null && byName.putIfAbsent(name, shard) != null) {
            shard.reset(); // a fork already spawned its clones - drop them
            throw new IllegalArgumentException("shard name already in use: " + name);
        }
        if (name != null) names.put(shard, name);
        shards.add(shard);
        EventDispatcher.call(new ShardCreateEvent(shard));
        return shard;
    }

    public @Nullable Shard byName(@NotNull String name) { return byName.get(name); }

    /** The name {@code shard} was registered under, or {@code null}. */
    public @Nullable String nameOf(@NotNull Shard shard) { return names.get(shard); }

    public @NotNull Collection<Shard> shards() { return Collections.unmodifiableSet(shards); }

    /**
     * Evicts members, strips the shard from observers' views, drops the diff + entities, retires the domain,
     * unregisters, fires {@link ShardDestroyEvent}. Cascades: children die with their parent (one tree, one
     * fate - their relative diffs are meaningless without it). Idempotent. Callable from ANY thread: a foreign
     * clock (a domain player's command runs on THEIR shard's clock) hands the destroy to the owner and returns.
     */
    public void destroy(@NotNull Shard shard) {
        ExecutionDomain domain = shard.domain();
        // a foreign-clock caller must never relay into this shard's clock (two live clocks never join)
        if (domain != null && ExecutionDomain.onClockThread() && !domain.onThread() && domain.live()) {
            domain.execute(() -> destroy(shard));
            return;
        }
        if (shard.destroyed) return;
        shard.destroyed = true;
        try {
            for (Shard child : List.copyOf(shard.children)) destroy(child);
            for (Player p : List.copyOf(shard.members())) shard.leave(p);
            for (Player o : List.copyOf(shard.observers())) {
                ShardView view = ShardView.explicit(o);
                ShardView.set(o, view != null ? view.without(shard) : null); // a view of OTHER shards survives
            }
            shard.reset();
            Shard parent = shard.parent();
            if (parent != null) parent.children.remove(shard);
            String name = names.remove(shard);
            if (name != null) byName.remove(name, shard);
            shards.remove(shard);
            // shards may share a domain (a tree on one clock): retire it only with its last registered user
            if (domain != null && shards.stream().noneMatch(s -> s.domain() != null && s.domain().sharesClock(domain))) {
                domain.retire();
            }
        } catch (Throwable t) {
            shard.destroyed = false; // a failed destroy must leave the shard destroyable
            throw t;
        }
        EventDispatcher.call(new ShardDestroyEvent(shard));
    }
}
