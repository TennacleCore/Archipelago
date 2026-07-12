package io.github.term4.shardkit.ticking;

import net.minestom.server.entity.Entity;
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
 * One execution node and tree member: owned entities, per-tick hooks and a task queue, all ticked on the ROOT's
 * thread - a root owns a {@link RootClock}, children tick inline after their parent. Structure changes go through
 * the {@link DomainManager}, applied between that tree's ticks, never mid-subtree. Public face:
 * {@link ExecutionDomain}, one canonical handle per node.
 */
final class Domain {

    static final Tag<Domain> TAG = Tag.Transient("shardkit:domain");
    /** Where the entity should move next (a Domain, or DomainManager.SERVER); the latest stamp wins. */
    static final Tag<Object> MOVE = Tag.Transient("shardkit:move-to");
    private static final Logger LOGGER = LoggerFactory.getLogger(Domain.class);

    /** Adopted from the global dispatcher: not ticked until the dispatcher's queued eviction had a tick to drain. */
    record Arrival(Entity entity, long readyNanos) {}

    final ExecutionDomain handle = new ExecutionDomain(this);
    private final DomainManager manager;
    volatile @Nullable Domain parent;
    final List<Domain> children = new CopyOnWriteArrayList<>();
    final Set<Entity> entities = ConcurrentHashMap.newKeySet();
    final Queue<Arrival> arrivals = new ConcurrentLinkedQueue<>();
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final List<LongConsumer> tickHooks = new CopyOnWriteArrayList<>();
    private volatile long ticks;
    private boolean loggedError;
    private boolean loggedBacklog;

    Domain(DomainManager manager) {
        this.manager = manager;
    }

    /** The domain owning {@code entity}, or {@code null} (server-ticked). */
    static @Nullable Domain of(@NotNull Entity entity) {
        return entity.getTag(TAG);
    }

    boolean isRoot() {
        return parent == null;
    }

    @Nullable Domain parent() {
        return parent;
    }

    /** The root whose thread ticks this domain (itself when a root). */
    @NotNull Domain root() {
        Domain d = this;
        for (Domain p = parent; p != null; p = p.parent) d = p;
        return d;
    }

    @NotNull DomainManager manager() {
        return manager;
    }

    /**
     * This domain's clock: +1 at the START of each tick, one fixed phase - a count always spans at least its
     * wall time (overruns drop behind, never burst). Stamp AND judge on the SAME domain's clock; never mix
     * clocks, and re-base tick-stamped state when an entity changes domains.
     */
    long ticks() {
        return ticks;
    }

    /** Whether the current thread is this domain's root clock thread. */
    boolean onThread() {
        RootClock clock = RootClock.current();
        return clock != null && clock.root() == root();
    }

    /** Whether the current thread is ANY domain clock thread (relay / join-deadlock guards). */
    static boolean onClockThread() {
        return RootClock.current() != null;
    }

    /** Whether this domain's tree has a live clock (false once retired or quarantined). */
    boolean live() {
        return manager.hasClock(root());
    }

    /**
     * Runs {@code task} on this domain's next tick (its root's thread) - the cross-domain hand-off. Runs inline
     * when the tree has no live clock. The queue is unbounded by design: it drains fully each tick, a stalled
     * tree's growth window ends at quarantine (tasks then run inline), and a producer outpacing the drain gets
     * one WARN - bounding would mean dropping or rerouting shard work, both off the table.
     */
    void execute(@NotNull Runnable task) {
        tasks.add(task);
        // claimed back atomically: a dead tree's queue is never drained, and a caller may be waiting on this
        if (!live() && tasks.remove(task)) task.run();
    }

    /**
     * Runs {@code task} on this domain's thread and waits - the synchronous snapshot/batch relay. Runs inline
     * when already on the owner or the tree has no live clock; throws from any OTHER clock thread (two live
     * clocks joining each other would deadlock). If the clock dies with the relay still queued, the task is
     * reclaimed and runs on the caller instead of hanging it.
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

    /** Registers a per-tick hook (block ticking, samplers...), run before the entities with {@link #ticks()}. Returns its removal handle. */
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

    /** Clock entry (roots only): this domain, then children, inline on the root's thread. */
    void tick(long time) {
        long now = ++ticks;
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
        for (Domain child : children) child.tick(time);
    }

    // bounded drain: a task that re-executes itself runs NEXT tick, not forever inside this one
    private void drainTasks(long time) {
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

    /** Retire/shutdown final cycle: relayed work must complete even while the tree is not ticking. */
    void drainTasksTree(long time) {
        drainTasks(time);
        for (Domain child : children) child.drainTasksTree(time);
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

    // one bad entity/task must never take the whole tree's tick with it - Errors included
    private void safely(Runnable op, long time) {
        try {
            op.run();
        } catch (Throwable e) {
            if (!loggedError) {
                loggedError = true;
                LOGGER.warn("domain tick failed at {} (logged once)", time, e);
            }
        }
    }
}
