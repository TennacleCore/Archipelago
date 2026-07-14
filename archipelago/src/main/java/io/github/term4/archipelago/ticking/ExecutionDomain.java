package io.github.term4.archipelago.ticking;

import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * The clock a shard runs on: a free-running thread ticking attached entities and hooks at the server tick rate,
 * independent of every other domain - one stalled game never slows another. Create one per game and bind shards
 * to it ({@link io.github.term4.archipelago.shard.Shard#Shard(net.minestom.server.instance.Instance, ExecutionDomain)
 * Shard(Instance, ExecutionDomain)}); {@link io.github.term4.archipelago.shard.ShardManager#destroy ShardManager.destroy}
 * retires it with the shard.
 *
 * <p>A domain is a FAILURE DOMAIN: its work never reroutes to another thread. A tick hung past the watchdog's
 * quarantine threshold FAILS the domain (crash report on disk, {@code DomainQuarantineEvent}, shards destroyed) -
 * rebuild fresh, never reuse it.
 */
public final class ExecutionDomain {

    private static final Object ENGINE_LOCK = new Object();
    private static volatile DomainManager engineManager;
    private static long watchdogInterruptMs = 5_000;
    private static long watchdogQuarantineMs = 15_000;

    final Domain node;

    ExecutionDomain(Domain node) {
        this.node = node;
    }

    /** A new domain with its own clock, on the engine. */
    public static @NotNull ExecutionDomain create() {
        return engine0().create().handle;
    }

    /**
     * A domain with NO clock: attaching an entity FREEZES it (nothing ever ticks it) until it moves to a live
     * domain or {@link #detach detaches}. {@code Shard.domain(null)} parks a shard here - stasis, not a silent
     * fallback to the server clock.
     */
    public static @NotNull ExecutionDomain stasis() {
        return engine0().stasis().handle;
    }

    private static DomainManager engine0() {
        DomainManager engine = engineManager;
        if (engine == null) {
            synchronized (ENGINE_LOCK) {
                if (engineManager == null) {
                    engineManager = new DomainManager(watchdogInterruptMs, watchdogQuarantineMs);
                }
                engine = engineManager;
            }
        }
        return engine;
    }

    /** Engine watchdog thresholds (defaults 5s interrupt, 15s fail-stop); call before the first {@link #create}. */
    public static void watchdog(long interruptAfterMs, long quarantineAfterMs) {
        synchronized (ENGINE_LOCK) {
            if (engineManager != null) {
                throw new IllegalStateException("the engine is already running - configure before the first create()");
            }
            watchdogInterruptMs = interruptAfterMs;
            watchdogQuarantineMs = quarantineAfterMs;
        }
    }

    /** The domain owning {@code entity}, or {@code null} (server-ticked). */
    public static @Nullable ExecutionDomain of(@NotNull Entity entity) {
        Domain owner = Domain.of(entity);
        return owner != null ? owner.handle : null;
    }

    /** Returns {@code entity} to server ticking; same mailbox semantics as {@link #attach} (an in-flight claim re-honors the stamp). */
    public static void detach(@NotNull Entity entity) {
        DomainManager.detach(entity);
    }

    /**
     * Hands {@code entity} to this domain: evicted from the global dispatcher, ticked on this domain's thread.
     * Transfers are a mailbox - the latest stamp wins, and only the current owner's clock hands the entity
     * away, so a hand-off can never race an in-flight tick.
     *
     * @throws IllegalArgumentException when {@code entity}'s class is not {@link DomainTicked opt-out covered} -
     *                                  it would re-register on its next chunk crossing and double-tick
     */
    public void attach(@NotNull Entity entity) {
        node.manager().attach(node, entity);
    }

    /**
     * Runs {@code task} on this domain's next tick - the cross-domain hand-off. Runs inline when the domain has
     * no live clock. The queue is unbounded by design (bounding would drop or reroute shard work); a producer
     * outpacing the drain gets one WARN.
     */
    public void execute(@NotNull Runnable task) {
        node.execute(task);
    }

    /**
     * Runs {@code task} on this domain's thread and waits - the synchronous snapshot/batch relay. Runs inline
     * when already on the owner or the domain has no live clock; throws from any OTHER clock thread (two live
     * clocks joining each other would deadlock). If the clock dies mid-wait the task is reclaimed and runs on
     * the caller instead of hanging it.
     */
    public <T> T relay(@NotNull Supplier<T> task) {
        return node.relay(task);
    }

    /** Registers a per-tick hook (block ticking, samplers...), run before the entities with {@link #ticks()}. Returns its removal handle. */
    public @NotNull Runnable onTick(@NotNull LongConsumer hook) {
        return node.onTick(hook);
    }

    /**
     * This domain's clock: +1 at the START of each tick, one fixed phase - a count always spans at least its
     * wall time (overruns drop behind, never burst). Stamp AND judge tick-stamped state on the SAME domain's
     * clock; re-base when an entity changes domains.
     */
    public long ticks() {
        return node.ticks();
    }

    /** Whether the current thread is this domain's clock thread. */
    public boolean onThread() {
        return node.onThread();
    }

    /**
     * Whether the current thread is ANY domain clock thread. A domain player's packets (and so their commands)
     * process on their owning clock - code targeting ANOTHER live domain from here must {@link #execute
     * hand off} instead of {@link #relay relaying} (two live clocks never join each other).
     */
    public static boolean onClockThread() {
        return Domain.onClockThread();
    }

    /** Whether this domain and {@code other} tick on the same clock (domains are flat: the same domain). */
    public boolean sharesClock(@NotNull ExecutionDomain other) {
        return node == other.node;
    }

    /** Whether this domain has a live clock (false once retired or failed). */
    public boolean live() {
        return node.live();
    }

    /** Ends this domain after its final tick: attached entities return to server ticking, the clock stops. */
    public void retire() {
        node.manager().retire(node);
    }

    /** One pull of this domain's raw counters. */
    public @NotNull Metrics metrics() {
        DomainClock clock = node.manager().clocks.get(node);
        return new Metrics(node.ticks(),
                clock == null ? 0 : clock.lag(),
                clock == null ? 0 : clock.msptLast(),
                clock == null ? 0 : clock.mspt(),
                clock == null ? 0 : clock.msptMax(),
                clock == null ? 0 : clock.deadlineMisses(),
                node.taskBacklog(), node.arrivals.size(), node.entities.size(), node.hookCount(),
                clock == null ? null : clock.thread().getName(), clock != null, DomainClock.PERIOD_NANOS);
    }

    /** One pull of the engine's raw counters. */
    public static @NotNull Engine engine() {
        DomainManager engine = engineManager;
        int cores = Runtime.getRuntime().availableProcessors();
        return new Engine(engine == null ? 0 : engine.clocks.size(), Watchdog.zombies(),
                Integer.getInteger("jdk.virtualThreadScheduler.parallelism", cores), cores,
                Watchdog.interrupts(), Watchdog.quarantines());
    }

    /**
     * Raw domain facts, no opinions. {@code lag} = ticks behind wall time (the one failure signal - a clock
     * never runs ahead); {@code mspt*} = tick cost in ms (cost creeping toward the period predicts lag);
     * {@code deadlineMisses} = ticks whose cost exceeded the period (monotonic). {@code threadName} is null and
     * {@code alive} false once the clock retired or failed.
     */
    public record Metrics(long ticks, long lag, double msptLast, double msptEwma, double msptMax,
                          long deadlineMisses, int taskBacklog, int pendingArrivals, int entityCount,
                          int hookCount, @Nullable String threadName, boolean alive, long tickPeriodNanos) {}

    /**
     * Raw engine facts. {@code parallelism} = the configured carrier cap
     * ({@code -Djdk.virtualThreadScheduler.parallelism}, default = cores); the ACTIVE carrier count is not
     * observable via supported JDK API. {@code zombieCarriers}/{@code interrupts}/{@code quarantines} are
     * JVM-wide watchdog counters; {@code clocks} counts the engine's live clocks.
     */
    public record Engine(int clocks, int zombieCarriers, int parallelism, int cores,
                         long interrupts, long quarantines) {}
}
