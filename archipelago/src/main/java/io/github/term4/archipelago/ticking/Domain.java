package io.github.term4.archipelago.ticking;

import io.github.term4.archipelago.event.ticking.DomainTickEvent;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.ListenerHandle;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * One execution node: owned entities, per-tick hooks and a task queue, ticked by its own {@link DomainClock}.
 * Flat by design - hierarchy is a SHARD concept (shards share a domain handle); domains only own entities and
 * hand them off. Ownership changes go through the {@link DomainManager}, applied between the owning clock's
 * ticks. Public face: {@link ExecutionDomain}, one canonical handle per node.
 */
final class Domain {

    static final Tag<Domain> TAG = Tag.Transient("archipelago:domain");
    /** Where the entity should move next (a Domain, or DomainManager.SERVER); the latest stamp wins. */
    static final Tag<Object> MOVE = Tag.Transient("archipelago:move-to");
    private static final Logger LOGGER = LoggerFactory.getLogger(Domain.class);

    /** Adopted from the global dispatcher: not ticked until the dispatcher's queued eviction had a tick to drain. */
    record Arrival(Entity entity, long readyNanos) {}

    private static final ListenerHandle<DomainTickEvent> TICK_EVENT =
            MinecraftServer.getGlobalEventHandler().getHandle(DomainTickEvent.class);

    final ExecutionDomain handle = new ExecutionDomain(this);
    private final DomainManager manager;
    /** A deliberately clockless domain: attached entities freeze (stasis) instead of falling back to the server. */
    final boolean stasis;
    final Set<Entity> entities = ConcurrentHashMap.newKeySet();
    final Queue<Arrival> arrivals = new ConcurrentLinkedQueue<>();
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final List<LongConsumer> tickHooks = new CopyOnWriteArrayList<>();
    private volatile long ticks;
    private static final long ERROR_QUIET_NANOS = 60_000_000_000L;
    private long lastErrorAt = Long.MIN_VALUE;
    private boolean loggedBacklog;

    Domain(DomainManager manager, boolean stasis) {
        this.manager = manager;
        this.stasis = stasis;
    }

    /** The domain owning {@code entity}, or {@code null} (server-ticked). */
    static @Nullable Domain of(@NotNull Entity entity) {
        return entity.getTag(TAG);
    }

    @NotNull DomainManager manager() {
        return manager;
    }

    /** +1 at the START of each tick; overruns drop behind, never burst. */
    long ticks() {
        return ticks;
    }

    boolean onThread() {
        DomainClock clock = DomainClock.current();
        return clock != null && clock.domain() == this;
    }

    static boolean onClockThread() {
        return DomainClock.current() != null;
    }

    /** False once retired or quarantined. */
    boolean live() {
        return manager.hasClock(this);
    }

    /**
     * Runs {@code task} on this domain's next tick, or inline when the domain has no live clock. Post-death
     * execution (inline here, or the quarantine drain) runs OFF the owner thread; a stuck zombie may still sit
     * inside its final tick, so consistency then is best-effort.
     */
    void execute(@NotNull Runnable task) {
        tasks.add(task);
        // claimed back atomically: a dead domain's queue is never drained, and a caller may be waiting on this
        if (!live() && tasks.remove(task)) task.run();
    }

    /** Runs what the quarantine window stranded: tasks queued while the clock was alive that it never drained. */
    void drainAbandoned() {
        for (Runnable task; (task = tasks.poll()) != null; ) {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.warn("abandoned-domain task failed", t);
            }
        }
    }

    /**
     * Runs {@code task} on this domain's thread and waits. Inline when already on the owner or the domain has no
     * live clock; throws from any OTHER clock thread (two live clocks joining each other would deadlock). If the
     * clock dies with the relay still queued, the task is reclaimed and runs on the caller instead of hanging it.
     */
    <T> T relay(@NotNull Supplier<T> task) {
        if (onThread() || !live()) return task.get();
        if (onClockThread()) {
            throw new IllegalStateException("cross-domain relay from a clock thread - hand off via execute");
        }
        CompletableFuture<T> out = new CompletableFuture<>();
        Runnable wrapper = () -> {
            try {
                out.complete(task.get());
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        };
        execute(wrapper);
        while (true) {
            try {
                return out.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting domain relay", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                if (e.getCause() instanceof Error err) throw err;
                throw new IllegalStateException(e.getCause());
            } catch (TimeoutException e) {
                if (!live() && tasks.remove(wrapper)) wrapper.run();
            }
        }
    }

    /** Runs before the entities, given {@link #ticks()}. Returns its removal handle. */
    @NotNull Runnable onTick(@NotNull LongConsumer hook) {
        tickHooks.add(hook);
        return () -> tickHooks.remove(hook);
    }

    int taskBacklog() {
        return tasks.size();
    }

    int hookCount() {
        return tickHooks.size();
    }

    /** Clock entry: hooks, then owned entities, on this domain's thread. {@code time} feeds {@code Entity#tick}
     *  and must be the dispatcher's unit - monotonic millis, {@code TickThread}'s {@code toMillis(nanoTime)}.
     *  Epoch millis or raw nanos stamp Minestom's cooldowns (item/xp pickup) with the far future, and they
     *  never clear once the entity returns to server ticking. */
    void tick(long time) {
        long now = ++ticks;
        if (TICK_EVENT.hasListener()) safely(() -> TICK_EVENT.call(new DomainTickEvent(handle, now)), time);
        drainTasks(time);
        for (LongConsumer hook : tickHooks) safely(() -> hook.accept(now), time);
        admitArrivals();
        for (Entity e : entities) {
            if (e.getTag(TAG) != this) { // lost a racing hand-off; not ours to tick
                entities.remove(e);
                continue;
            }
            if (e.isRemoved()) { // Entity.remove talks to the global dispatcher only; our set self-cleans here
                entities.remove(e);
                e.removeTag(TAG);
                continue;
            }
            Object destination = e.getTag(MOVE);
            if (destination != null) {
                if (destination == this) {
                    e.removeTag(MOVE); // arrived
                } else {
                    manager.moveOut(this, e, destination); // only the owner gives an entity away
                    continue;
                }
            }
            if (e.getInstance() == null) continue; // async spawn not landed yet
            safely(() -> e.tick(time), time);
        }
    }

    // bounded drain: a task that re-executes itself runs NEXT tick, not forever inside this one.
    // also the retire/shutdown final cycle: relayed work must complete even while the domain is not ticking
    void drainTasks(long time) {
        int pending = tasks.size();
        if (pending >= 10_000 && !loggedBacklog) {
            loggedBacklog = true;
            LOGGER.warn("domain task backlog: {} queued - producers outpacing the tick (logged once)", pending);
        }
        for (int i = 0; i < pending; i++) {
            Runnable task = tasks.poll();
            if (task == null) break;
            safely(task, time);
        }
    }

    private void admitArrivals() {
        if (arrivals.isEmpty()) return;
        long now = System.nanoTime();
        for (Iterator<Arrival> it = arrivals.iterator(); it.hasNext(); ) {
            Arrival arrival = it.next();
            if (arrival.readyNanos() - now > 0) break; // FIFO by ready time: same defer for all
            it.remove();
            entities.add(arrival.entity());
        }
    }

    // one bad entity/task must never take the whole domain's tick with it - Errors included
    private void safely(Runnable op, long time) {
        try {
            op.run();
        } catch (Throwable e) {
            // rate-limited, not once-only: a domain that survives one bad tick must still report the next
            long now = System.nanoTime();
            if (now - lastErrorAt > ERROR_QUIET_NANOS) {
                lastErrorAt = now;
                LOGGER.warn("domain tick failed at {} (further failures quiet for a minute)", time, e);
            }
        }
    }
}
