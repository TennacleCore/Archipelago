package io.github.term4.shardkit.ticking;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.instance.Chunk;
import net.minestom.server.thread.ThreadDispatcher;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Creates and drives {@link Domain} trees. Every ROOT owns a {@link RootClock} - a free-running virtual-thread loop -
 * so no tree ever waits for another; the only shared resource is the JVM carrier pool
 * ({@code -Djdk.virtualThreadScheduler.parallelism}). Blocked roots park free; only a CPU-spinner holds a carrier.
 *
 * <p>Structural changes apply on the owning clock between its ticks; cross-tree moves hop source-then-target, so
 * nothing is ever driven by two threads (at most one tick missed mid-hop). A tree is a FAILURE DOMAIN: work never
 * reroutes to another thread or the server. The watchdog interrupts roots stuck past {@code interruptAfterMs}
 * (second chance, same thread); past {@code quarantineAfterMs} the tree has FAILED - a crash report is written,
 * the clock retires, a waking zombie exits without ticking, {@code DomainQuarantineEvent} fires
 * ({@link #detach}/{@link #attach}/{@link #retire} still work on the frozen tree for teardown). A hung tick
 * cannot be killed on the JVM, only contained - never re-clock a failed tree, rebuild fresh.
 */
final class DomainManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainManager.class);

    final Map<Domain, RootClock> clocks = new ConcurrentHashMap<>();
    // clock creation and shutdown serialize here, so a clock is never granted after the manager ended
    private final Object clockLock = new Object();
    private final Watchdog watchdog;
    volatile boolean running = true;

    DomainManager() {
        this(5_000, 15_000);
    }

    DomainManager(long interruptAfterMs, long quarantineAfterMs) {
        this.watchdog = new Watchdog(this, interruptAfterMs, quarantineAfterMs);
    }

    /** A new root domain with its own clock. */
    @NotNull Domain root() {
        Domain d = new Domain(this);
        grantClock(d);
        return d;
    }

    /** A new child of {@code parent}: a full domain (entities/hooks/tasks), ticked inline by {@code parent}'s root. */
    @NotNull Domain child(@NotNull Domain parent) {
        Domain d = new Domain(this);
        d.parent = parent; // eager: root() resolves immediately, later ops serialize behind the link op below
        if (!onRoot(parent, () -> parent.children.add(d))) {
            LOGGER.warn("child created under a dead tree: {} will never tick", d);
        }
        return d;
    }

    /** {@link Domain#MOVE} destination for {@link #detach}: back to the global dispatcher. */
    static final Object SERVER = new Object();

    /**
     * Hands {@code entity} to {@code domain}: evicted from the global dispatcher, ticked by the domain's root.
     * Transfers are a mailbox - this stamps the destination (the latest stamp wins) and only the CURRENT
     * owner's clock hands the entity away, so a hand-off can never race the owner's in-flight tick.
     *
     * @throws IllegalArgumentException when {@code entity}'s class is not {@link DomainTicked opt-out covered} -
     *                                  it would re-register on its next chunk crossing and double-tick
     */
    void attach(@NotNull Domain domain, @NotNull Entity entity) {
        if (!DomainTicked.covers(entity)) {
            throw new IllegalArgumentException("not opt-out covered (would double-tick): "
                    + entity.getClass().getName() + " - extend DomainEntity/DomainLivingEntity/DomainPlayer"
                    + " or register DomainTicked.cover");
        }
        entity.setTag(Domain.MOVE, domain);
        Domain owner = entity.getTag(Domain.TAG);
        if (owner == null) {
            claimOwnerless(domain, entity, true);
        } else if (!hasClock(owner.root())) { // a dead tree can't hand off (quarantine evacuation)
            disown(owner, entity);
            claimOwnerless(domain, entity, false);
        }
        // live owner: its clock notices the stamp between its own ticks
    }

    /** Returns {@code entity} to the global dispatcher (server ticking); same mailbox semantics as {@link #attach}. */
    static void detach(@NotNull Entity entity) {
        entity.setTag(Domain.MOVE, SERVER);
        Domain owner = entity.getTag(Domain.TAG);
        if (owner == null) return; // server-side already; an in-flight claim re-honors the stamp
        if (!owner.manager().hasClock(owner.root())) { // a dead tree can't hand off (quarantine evacuation)
            disown(owner, entity);
            entity.removeTag(Domain.MOVE);
            register(entity);
        }
    }

    // on the owner's clock (called from its tick loop): the disown is safe by construction
    void moveOut(Domain owner, Entity entity, Object destination) {
        disown(owner, entity);
        if (destination == SERVER) {
            entity.removeTag(Domain.MOVE);
            register(entity);
            return;
        }
        Domain target = (Domain) destination;
        if (target.root() == owner.root()) { // same tree: this thread is the target's clock too
            entity.setTag(Domain.TAG, target);
            entity.removeTag(Domain.MOVE);
            target.entities.add(entity);
            return;
        }
        claimOwnerless(target, entity, false);
    }

    // claims an OWNERLESS entity for {@code target} on its clock, re-honoring the stamp at run time.
    // fromServer: still globally registered, so the first domain tick waits out the dispatcher's queued
    // eviction (one period; DomainEntity's ownership gate covers a server stalled past it)
    private void claimOwnerless(Domain target, Entity entity, boolean fromServer) {
        boolean relayed = onRoot(target, () -> {
            if (entity.getTag(Domain.TAG) != null) return; // owned meanwhile; that clock honors the stamp
            if (entity.getTag(Domain.MOVE) != target) { // the stamp moved on while this claim was queued
                dispatchOwnerless(entity, fromServer);
                return;
            }
            entity.setTag(Domain.TAG, target);
            entity.removeTag(Domain.MOVE);
            globalDispatcher().removeElement(entity);
            if (fromServer) {
                target.arrivals.add(new Domain.Arrival(entity, System.nanoTime() + RootClock.PERIOD_NANOS));
            } else {
                target.entities.add(entity);
            }
        });
        if (!relayed) dispatchOwnerless(entity, fromServer); // target tree died mid-claim: never strand
    }

    // an ownerless entity goes wherever its stamp points; no live destination = back to the server
    private void dispatchOwnerless(Entity entity, boolean fromServer) {
        if (entity.getTag(Domain.TAG) != null) return; // claimed meanwhile
        if (entity.getTag(Domain.MOVE) instanceof Domain d && hasClock(d.root())) {
            claimOwnerless(d, entity, fromServer);
            return;
        }
        entity.removeTag(Domain.MOVE);
        register(entity);
    }

    // drops the entity from the domain's bookkeeping and unstamps ownership
    private static void disown(Domain domain, Entity entity) {
        domain.arrivals.removeIf(arrival -> arrival.entity() == entity);
        domain.entities.remove(entity);
        // a dead-tree fallback may run stale: never clobber a live owner's tag
        if (entity.getTag(Domain.TAG) == domain) entity.removeTag(Domain.TAG);
    }

    private static void register(Entity entity) {
        Chunk chunk = entity.getChunk();
        if (chunk != null && !entity.isRemoved()) globalDispatcher().updateElement(entity, chunk);
    }

    /**
     * Moves {@code node} and its whole subtree under {@code newParent}; execution follows the new root. A root
     * being demoted retires its clock first - adoption runs only after its final tick completed.
     */
    void reparent(@NotNull Domain node, @NotNull Domain newParent) {
        for (Domain d = newParent; d != null; d = d.parent) {
            if (d == node) throw new IllegalArgumentException("target is inside the moved subtree");
        }
        RootClock clock = clocks.remove(node);
        if (clock != null) {
            clock.stop(() -> adoptSubtree(node, newParent));
        } else if (!onRoot(node, () -> {
            Domain parent = node.parent;
            if (parent != null) parent.children.remove(node);
            node.parent = null;
            adoptSubtree(node, newParent);
        })) {
            LOGGER.warn("reparent dropped: {} has no live clock", node);
        }
    }

    private void adoptSubtree(Domain node, Domain newParent) {
        boolean relayed = onRoot(newParent, () -> {
            for (Domain d = newParent; d != null; d = d.parent) {
                if (d == node) {
                    LOGGER.warn("reparent aborted mid-hop (target moved inside the subtree); {} re-promoted", node);
                    grantClock(node);
                    return;
                }
            }
            node.parent = newParent;
            newParent.children.add(node);
        });
        if (!relayed) { // target died mid-hop: the subtree must not strand - it becomes its own root
            LOGGER.warn("reparent target lost its clock mid-hop; {} promoted instead", node);
            grantClock(node);
        }
    }

    /** Makes child {@code node} a root: its subtree gets its own clock. */
    void promote(@NotNull Domain node) {
        if (!onRoot(node, () -> {
            Domain parent = node.parent;
            if (parent == null) return;
            parent.children.remove(node); // this cycle no longer ticks it; the fresh clock is the only driver
            node.parent = null;
            grantClock(node);
        })) {
            LOGGER.warn("promote dropped: {} has no live clock", node);
        }
    }

    private void grantClock(Domain node) {
        synchronized (clockLock) {
            if (!running) return; // shutdown won the race
            clocks.put(node, new RootClock(node));
        }
    }

    /**
     * Retires {@code domain}: its whole subtree's entities return to server ticking and a root's clock ends
     * (after its final tick). Reparent children out first if they should survive. Works on quarantined trees
     * too - this is the teardown half of an evacuation.
     */
    void retire(@NotNull Domain domain) {
        RootClock clock = clocks.remove(domain);
        if (clock != null) {
            clock.stop(() -> releaseSubtree(domain));
        } else if (!onRoot(domain, () -> {
            Domain parent = domain.parent;
            if (parent != null) parent.children.remove(domain);
            domain.parent = null;
            releaseSubtree(domain);
        })) {
            releaseSubtree(domain); // dead tree: still hand the entities back
        }
    }

    // the final tick is done: hand every entity in the subtree back to the server
    private void releaseSubtree(Domain domain) {
        walk(domain, d -> {
            for (Domain.Arrival arrival : d.arrivals) releaseToServer(d, arrival.entity());
            d.arrivals.clear();
            for (Entity e : d.entities) releaseToServer(d, e);
            d.entities.clear();
        });
    }

    private static void releaseToServer(Domain owner, Entity entity) {
        if (entity.getTag(Domain.TAG) != owner) return; // stray set membership: the real owner keeps it
        entity.removeTag(Domain.TAG);
        entity.removeTag(Domain.MOVE); // retirement wins over any pending move
        register(entity);
    }

    /** Ends the forest: watchdog and clocks stop (domain entities are NOT re-registered; detach first). */
    void shutdown() {
        running = false;
        watchdog.wake();
        synchronized (clockLock) {
            for (Domain root : List.copyOf(clocks.keySet())) {
                RootClock clock = clocks.remove(root);
                if (clock != null) clock.stop(null);
            }
        }
    }

    // structural ops run on the owning clock's thread, between its ticks; false = no live clock (retired or
    // quarantined - a possibly-still-running zombie must not be raced), callers fall back or drop
    private boolean onRoot(Domain domain, Runnable op) {
        RootClock clock = clocks.get(domain.root());
        if (clock == null || clock.quarantined) return false;
        clock.submit(op);
        return true;
    }

    boolean hasClock(Domain root) {
        return clocks.containsKey(root);
    }

    /**
     * Carriers permanently lost to quarantined spinners, JVM-wide - the degradation signal an orchestration
     * layer polls (drain/replace policy is deliberately NOT this library's job; the server plays on).
     */
    static int zombieCarriers() {
        return Watchdog.zombies();
    }

    /**
     * Ticks {@code domain}'s tree is behind the wall clock ({@code 0} = healthy, or no clock). A domain clock
     * can never run AHEAD of wall time, so lag is the one failure signal: clock-honesty bugs and carrier
     * saturation both surface here.
     */
    long lag(@NotNull Domain domain) {
        RootClock clock = clocks.get(domain.root());
        return clock == null ? 0 : clock.lag();
    }

    /**
     * Smoothed tick COST of {@code domain}'s tree in ms ({@code 0} = no clock) - the saturation gauge next to
     * {@link #lag} (lateness): cost creeping toward the 50ms period predicts lag before it happens.
     */
    double mspt(@NotNull Domain domain) {
        RootClock clock = clocks.get(domain.root());
        return clock == null ? 0 : clock.mspt();
    }

    /** The clock thread driving {@code domain}, or {@code "server"}. */
    @NotNull String threadName(@NotNull Domain domain) {
        RootClock clock = clocks.get(domain.root());
        return clock == null ? "server" : clock.thread().getName();
    }

    private void walk(Domain domain, Consumer<Domain> visit) {
        visit.accept(domain);
        for (Domain child : domain.children) walk(child, visit);
    }

    private static ThreadDispatcher<Chunk, Entity> globalDispatcher() {
        return MinecraftServer.process().dispatcher();
    }
}
