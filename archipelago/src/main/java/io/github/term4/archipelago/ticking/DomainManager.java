package io.github.term4.archipelago.ticking;

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

/**
 * Creates and drives {@link Domain}s. Every domain owns a {@link DomainClock} - a free-running virtual-thread
 * loop - so no domain ever waits for another; the only shared resource is the JVM carrier pool
 * ({@code -Djdk.virtualThreadScheduler.parallelism}). Blocked clocks park free; only a CPU-spinner holds a carrier.
 *
 * <p>Ownership changes apply on the owning clock between its ticks; cross-domain moves hop source-then-target, so
 * nothing is ever driven by two threads (at most one tick missed mid-hop). A domain is a FAILURE DOMAIN: work never
 * reroutes to another thread or the server. The watchdog interrupts clocks stuck past {@code interruptAfterMs}
 * (second chance, same thread); past {@code quarantineAfterMs} the domain has FAILED - a crash report is written,
 * the clock retires, a waking zombie exits without ticking, {@code DomainQuarantineEvent} fires
 * ({@link #detach}/{@link #attach}/{@link #retire} still work on the frozen domain for teardown). A hung tick
 * cannot be killed on the JVM, only contained - never re-clock a failed domain, rebuild fresh.
 */
final class DomainManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainManager.class);

    final Map<Domain, DomainClock> clocks = new ConcurrentHashMap<>();
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

    /** A new domain with its own clock. */
    @NotNull Domain create() {
        Domain d = new Domain(this, false);
        grantClock(d);
        return d;
    }

    /** A clockless STASIS domain: attached entities freeze (nothing ever ticks them) until moved elsewhere. */
    @NotNull Domain stasis() {
        return new Domain(this, true);
    }

    /** {@link Domain#MOVE} destination for {@link #detach}: back to the global dispatcher. */
    static final Object SERVER = new Object();

    /**
     * Hands {@code entity} to {@code domain}: evicted from the global dispatcher, ticked on the domain's clock.
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
        } else if (!hasClock(owner)) { // a dead domain can't hand off (quarantine evacuation)
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
        if (!owner.manager().hasClock(owner)) { // a dead domain can't hand off (quarantine evacuation)
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
        claimOwnerless((Domain) destination, entity, false);
    }

    // claims an OWNERLESS entity for {@code target} on its clock, re-honoring the stamp at run time.
    // fromServer: still globally registered, so the first domain tick waits out the dispatcher's queued
    // eviction (one period; DomainEntity's ownership gate covers a server stalled past it)
    private void claimOwnerless(Domain target, Entity entity, boolean fromServer) {
        if (target.stasis) { // deliberate freeze: no clock to serialize on - direct, stamp-guarded
            if (entity.getTag(Domain.TAG) != null) return; // owned meanwhile; that clock honors the stamp
            if (entity.getTag(Domain.MOVE) != target) {
                dispatchOwnerless(entity, fromServer);
                return;
            }
            entity.setTag(Domain.TAG, target);
            entity.removeTag(Domain.MOVE);
            globalDispatcher().removeElement(entity);
            target.entities.add(entity);
            return;
        }
        boolean relayed = onClock(target, () -> {
            if (entity.getTag(Domain.TAG) != null) return; // owned meanwhile; that clock honors the stamp
            if (entity.getTag(Domain.MOVE) != target) { // the stamp moved on while this claim was queued
                dispatchOwnerless(entity, fromServer);
                return;
            }
            entity.setTag(Domain.TAG, target);
            entity.removeTag(Domain.MOVE);
            globalDispatcher().removeElement(entity);
            if (fromServer) {
                target.arrivals.add(new Domain.Arrival(entity, System.nanoTime() + DomainClock.PERIOD_NANOS));
            } else {
                target.entities.add(entity);
            }
        });
        if (!relayed) dispatchOwnerless(entity, fromServer); // target died mid-claim: never strand
    }

    // an ownerless entity goes wherever its stamp points; no live (or stasis) destination = back to the server
    private void dispatchOwnerless(Entity entity, boolean fromServer) {
        if (entity.getTag(Domain.TAG) != null) return; // claimed meanwhile
        if (entity.getTag(Domain.MOVE) instanceof Domain d && (hasClock(d) || d.stasis)) {
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

    private void grantClock(Domain node) {
        synchronized (clockLock) {
            if (!running) return; // shutdown won the race
            clocks.put(node, new DomainClock(node));
        }
    }

    /**
     * Retires {@code domain}: its entities return to server ticking and its clock ends (after the final tick).
     * Works on quarantined domains too - this is the teardown half of an evacuation.
     */
    void retire(@NotNull Domain domain) {
        DomainClock clock = clocks.remove(domain);
        if (clock != null) {
            clock.stop(() -> release(domain));
        } else {
            release(domain); // dead domain: still hand the entities back
        }
    }

    // the final tick is done: hand every entity back to the server
    private void release(Domain d) {
        for (Domain.Arrival arrival : d.arrivals) releaseToServer(d, arrival.entity());
        d.arrivals.clear();
        for (Entity e : d.entities) releaseToServer(d, e);
        d.entities.clear();
    }

    private static void releaseToServer(Domain owner, Entity entity) {
        if (entity.getTag(Domain.TAG) != owner) return; // stray set membership: the real owner keeps it
        entity.removeTag(Domain.TAG);
        entity.removeTag(Domain.MOVE); // retirement wins over any pending move
        register(entity);
    }

    /** Ends the engine: watchdog and clocks stop (domain entities are NOT re-registered; detach first). */
    void shutdown() {
        running = false;
        watchdog.wake();
        synchronized (clockLock) {
            for (Domain domain : List.copyOf(clocks.keySet())) {
                DomainClock clock = clocks.remove(domain);
                if (clock != null) clock.stop(null);
            }
        }
    }

    // ownership ops run on the owning clock's thread, between its ticks; false = no live clock (retired or
    // quarantined - a possibly-still-running zombie must not be raced), callers fall back or drop
    private boolean onClock(Domain domain, Runnable op) {
        DomainClock clock = clocks.get(domain);
        if (clock == null || clock.quarantined) return false;
        clock.submit(op);
        return true;
    }

    boolean hasClock(Domain domain) {
        return clocks.containsKey(domain);
    }

    /**
     * Carriers permanently lost to quarantined spinners, JVM-wide - the degradation signal an orchestration
     * layer polls (drain/replace policy is deliberately NOT this library's job; the server plays on).
     */
    static int zombieCarriers() {
        return Watchdog.zombies();
    }

    /**
     * Smoothed tick COST of {@code domain} in ms ({@code 0} = no clock) - the saturation gauge next to
     * lag (lateness): cost creeping toward the 50ms period predicts lag before it happens.
     */
    double mspt(@NotNull Domain domain) {
        DomainClock clock = clocks.get(domain);
        return clock == null ? 0 : clock.mspt();
    }

    /** The clock thread driving {@code domain}, or {@code "server"}. */
    @NotNull String threadName(@NotNull Domain domain) {
        DomainClock clock = clocks.get(domain);
        return clock == null ? "server" : clock.thread().getName();
    }

    private static ThreadDispatcher<Chunk, Entity> globalDispatcher() {
        return MinecraftServer.process().dispatcher();
    }
}
