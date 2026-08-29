package io.github.term4.archipelago.ticking;

import net.minestom.server.ServerFlag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * One domain's clock: a free-running virtual-thread loop at the server tick rate, parked between ticks. No clock
 * waits for any other. Ownership ops ({@link #submit}) drain first each cycle; overruns drop behind, never burst.
 */
final class DomainClock {

    static final long PERIOD_NANOS = 1_000_000_000L / ServerFlag.SERVER_TICKS_PER_SECOND;

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainClock.class);
    private static final ThreadLocal<DomainClock> CURRENT = new ThreadLocal<>();

    /** The clock owning the current thread, or {@code null} (a server/foreign thread). */
    static @Nullable DomainClock current() {
        return CURRENT.get();
    }

    private final Domain domain;
    private final Thread thread;
    private final Queue<Runnable> ops = new ConcurrentLinkedQueue<>();

    volatile boolean running = true;
    volatile boolean quarantined;
    /** In-tick heartbeat: nanoTime at cycle start, 0 while parked - the watchdog's stall signal. */
    volatile long tickStartNanos;
    /** One watchdog interrupt per stall episode; reset when the cycle completes. */
    volatile boolean interruptFired;
    /** Single-writer on this thread. */
    private volatile double msptEwma;
    private volatile double msptLast;
    private volatile double msptMax;
    private volatile long deadlineMisses;

    private volatile @Nullable Runnable afterStop;
    private boolean loggedError;

    private volatile long baseNanos;
    private volatile long baseTicks;

    /** This clock's cadence; {@link #PERIOD_NANOS} unless the domain was created at its own rate. */
    private final long periodNanos;

    DomainClock(Domain domain, long periodNanos) {
        this.domain = domain;
        this.periodNanos = periodNanos;
        this.baseNanos = System.nanoTime();
        this.thread = Thread.ofVirtual()
                .name("shard-domain:" + Integer.toHexString(System.identityHashCode(domain)))
                .unstarted(this::run);
        thread.start();
    }

    Domain domain() {
        return domain;
    }

    Thread thread() {
        return thread;
    }

    /** Runs {@code op} on this clock's thread at the next cycle, before the domain ticks. */
    void submit(Runnable op) {
        ops.add(op);
        LockSupport.unpark(thread);
    }

    /** Ticks this domain is behind the wall clock. */
    long lag() {
        long expected = baseTicks + (System.nanoTime() - baseNanos) / periodNanos;
        return Math.max(0, expected - domain.ticks());
    }

    long periodNanos() {
        return periodNanos;
    }

    /** Smoothed per-tick cost in ms (~16-tick horizon). */
    double mspt() {
        return msptEwma;
    }

    double msptLast() {
        return msptLast;
    }

    double msptMax() {
        return msptMax;
    }

    /** Ticks whose cost exceeded the period (monotonic). */
    long deadlineMisses() {
        return deadlineMisses;
    }

    /** Ends the loop; {@code then} runs on THIS thread after the final cycle. */
    void stop(@Nullable Runnable then) {
        afterStop = then;
        running = false;
        LockSupport.unpark(thread);
    }

    private void run() {
        CURRENT.set(this);
        long next = System.nanoTime() + periodNanos;
        while (running) {
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
                Thread.interrupted(); // a late watchdog interrupt must not turn the park into a spin
                continue;
            }
            next += periodNanos;
            if (next < System.nanoTime()) { // overran: drop behind, and lag re-bases so it reads CURRENT stall, not history
                next = System.nanoTime() + periodNanos;
                baseNanos = System.nanoTime();
                baseTicks = domain.ticks();
            }
            tickStartNanos = System.nanoTime();
            try {
                Runnable op;
                while ((op = ops.poll()) != null) safely(op);
                // MONOTONIC millis, matching the server dispatcher's TickThread.tick (nanoTime -> toMillis) -
                // not epoch millis, not raw nanos. Minestom stamps this value into Entity cooldowns (item
                // pickup, xp) and judges them wherever the entity ticks next; a stamp in any larger unit reads
                // as the far future on the server side and the cooldown never clears again
                domain.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            } catch (Throwable e) { // Errors included: a dead loop hangs every later relay into this domain
                logOnce(e);
            } finally {
                long costNanos = System.nanoTime() - tickStartNanos;
                double cost = costNanos / 1.0e6;
                msptLast = cost;
                if (cost > msptMax) msptMax = cost;
                msptEwma += (cost - msptEwma) / 16;
                if (costNanos > periodNanos) deadlineMisses++;
                tickStartNanos = 0;
                interruptFired = false;
                Thread.interrupted(); // a late watchdog interrupt must not poison the next park
            }
        }
        // final cycle: stranded ops/relays complete before the hand-off hook; a quarantined domain keeps its
        // ownership queue frozen (a stale attach would tag an entity into the abandoned domain)
        try {
            if (!quarantined) {
                Runnable op;
                while ((op = ops.poll()) != null) safely(op);
            }
            domain.drainTasks(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        } catch (Throwable e) {
            logOnce(e);
        }
        Runnable then = afterStop;
        if (then != null) safely(then);
    }

    private void safely(Runnable op) {
        try {
            op.run();
        } catch (Throwable e) {
            logOnce(e);
        }
    }

    private void logOnce(Throwable e) {
        if (!loggedError) {
            loggedError = true;
            LOGGER.warn("{} cycle failed (logged once)", thread.getName(), e);
        }
    }
}
