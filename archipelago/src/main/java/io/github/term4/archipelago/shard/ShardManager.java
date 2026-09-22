package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.event.ShardCreateEvent;
import io.github.term4.archipelago.event.ShardDestroyEvent;
import io.github.term4.archipelago.event.ticking.DomainQuarantineEvent;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.WeakHashMap;
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

    // ONE process-wide quarantine listener over weakly-held managers - a per-manager global listener would leak
    // every manager (and its shards) for the JVM's life
    private static final Set<ShardManager> MANAGERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean listenerInstalled;

    private final Map<String, Shard> byName = new ConcurrentHashMap<>();
    private final Map<Shard, String> names = new ConcurrentHashMap<>();
    private final Set<Shard> shards = ConcurrentHashMap.newKeySet();

    public ShardManager() {
        synchronized (ShardManager.class) {
            if (!listenerInstalled) {
                listenerInstalled = true;
                EventNode<Event> node = EventNode.all("archipelago:shard-manager");
                node.addListener(DomainQuarantineEvent.class, ShardManager::onQuarantine);
                MinecraftServer.getGlobalEventHandler().addChild(node);
            }
            MANAGERS.add(this);
        }
    }

    // the shards stay frozen on the dead clock; only the players hand back to server ticking, members and
    // watchers alike, since their connections must keep living
    private static void onQuarantine(DomainQuarantineEvent e) {
        List<ShardManager> live;
        synchronized (ShardManager.class) {
            live = List.copyOf(MANAGERS);
        }
        for (ShardManager manager : live) {
            for (Shard shard : List.copyOf(manager.shards)) {
                ExecutionDomain domain = shard.domain();
                if (domain == null || !domain.sharesClock(e.domain())) continue;
                for (Player p : shard.members()) {
                    if (ExecutionDomain.of(p) != null) ExecutionDomain.detach(p);
                }
                for (Player o : shard.observers()) {
                    if (ShardView.ticksOn(o, shard) && ExecutionDomain.of(o) != null) ExecutionDomain.detach(o);
                }
            }
        }
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

    /** {@link #create(Instance)} seeded with copies of the base instance's entities ({@link Shard#seedFromBase}). */
    public @NotNull Shard create(@NotNull Instance base, @Nullable Function<Entity, Entity> entityCloner) {
        return register(new Shard(base).seedFromBase(entityCloner), null);
    }

    public @NotNull Shard create(@NotNull Instance base, @NotNull String name,
                                 @Nullable Function<Entity, Entity> entityCloner) {
        return register(new Shard(base).seedFromBase(entityCloner), name);
    }

    public @NotNull Shard create(@NotNull Instance base, @NotNull String name, @NotNull ExecutionDomain domain,
                                 @Nullable Function<Entity, Entity> entityCloner) {
        return register(new Shard(base, domain).seedFromBase(entityCloner), name);
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
            discard(shard);
            throw new IllegalArgumentException("shard name already in use: " + name);
        }
        if (name != null) names.put(shard, name);
        shards.add(shard);
        // a throwing create listener can't unwind this: Minestom routes listener throwables to the exception manager
        EventDispatcher.call(new ShardCreateEvent(shard));
        return shard;
    }

    /** Unwinds a never-registered shard: fork clones dropped, the parent link severed (the ctor attached it). */
    private static void discard(Shard shard) {
        shard.reset();
        Shard parent = shard.parent();
        if (parent != null) parent.children.remove(shard);
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
        synchronized (shard) { // atomic claim: concurrent destroys race the flag, only one tears down
            if (shard.destroyed) return;
            shard.destroyed = true;
        }
        try {
            shard.relight(false); // the light flush hook would otherwise keep the dead shard ticking
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
            // shards may share a domain (a tree on one clock): the domain counts its own holders, so an
            // unregistered shard or a bare fork on the same clock keeps it alive too
            if (domain != null && domain.release()) domain.retire();
        } catch (Throwable t) {
            shard.destroyed = false; // a failed destroy must leave the shard destroyable
            throw t;
        }
        EventDispatcher.call(new ShardDestroyEvent(shard));
    }
}
