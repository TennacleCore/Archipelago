package io.github.term4.shardkit.ticking;

import net.minestom.server.ServerFlag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * One ROOT's clock: a free-running virtual-thread loop at the server tick rate, parked between ticks (a blocked
 * root costs its carrier nothing). No clock waits for any other - a stall stays in its tree. Structural ops
 * ({@link #submit}) drain first each cycle; overruns drop behind, never burst.
 */
final class RootClock {

    static final long PERIOD_NANOS = 1_000_000_000L / ServerFlag.SERVER_TICKS_PER_SECOND;

    private static final Logger LOGGER = LoggerFactory.getLogger(RootClock.class);
    private static final ThreadLocal<RootClock> CURRENT = new ThreadLocal<>();

    /** The clock owning the current thread, or {@code null} (a server/foreign thread). */
    static @Nullable RootClock current() {
        return CURRENT.get();
    }

    private final Domain root;
    private final Thread thread;
    private final Queue<Runnable> ops = new ConcurrentLinkedQueue<>();

    volatile boolean running = true;
    volatile boolean quarantined;
    /** In-tick heartbeat: nanoTime at cycle start, 0 while parked - the watchdog's stall signal. */
    volatile long tickStartNanos;
    /** One watchdog interrupt per stall episode; reset when the cycle completes. */
    volatile boolean interruptFired;
    /** Tick cost gauges, single-writer on this thread (layout rule: per-clock counters live here). */
    private volatile double msptEwma;
    private volatile double msptLast;
    private volatile double msptMax;
    private volatile long deadlineMisses;

    private volatile @Nullable Runnable afterStop;
    private boolean loggedError;

    private volatile long baseNanos;
    private volatile long baseTicks;

    RootClock(Domain root) {
        this.root = root;
        this.baseNanos = System.nanoTime();
        this.thread = Thread.ofVirtual()
                .name("shard-root:" + Integer.toHexString(System.identityHashCode(root)))
                .unstarted(this::run);
        thread.start();
    }

    Domain root() {
        return root;
    }

    Thread thread() {
        return thread;
    }

    /** Runs {@code op} on this clock's thread at the next cycle, before the tree ticks - the structural seam. */
    void submit(Runnable op) {
        ops.add(op);
        LockSupport.unpark(thread);
    }

    /** Ticks this tree is behind the wall clock - the honesty/saturation metric. */
    long lag() {
        long expected = baseTicks + (System.nanoTime() - baseNanos) / PERIOD_NANOS;
        return Math.max(0, expected - root.ticks());
    }

    /** Smoothed per-tick cost in ms (~16-tick horizon); lag() is lateness, this is the saturation gauge. */
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

    /** Ends the loop; {@code then} runs on THIS thread after the final cycle - adoption hand-offs hook here. */
    void stop(@Nullable Runnable then) {
        afterStop = then;
        running = false;
        LockSupport.unpark(thread);
    }

    private void run() {
        CURRENT.set(this);
        long next = System.nanoTime() + PERIOD_NANOS;
        while (running) {
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
                Thread.interrupted(); // a late watchdog interrupt must not turn the park into a spin
                continue;
            }
            next += PERIOD_NANOS;
            if (next < System.nanoTime()) { // overran: drop behind, and lag re-bases so it reads CURRENT stall, not history
                next = System.nanoTime() + PERIOD_NANOS;
                baseNanos = System.nanoTime();
                baseTicks = root.ticks();
            }
            tickStartNanos = System.nanoTime();
            try {
                Runnable op;
                while ((op = ops.poll()) != null) safely(op);
                root.tick(System.currentTimeMillis());
            } catch (Throwable e) { // Errors included: a dead loop hangs every later relay into this tree
                logOnce(e);
            } finally {
                long costNanos = System.nanoTime() - tickStartNanos;
                double cost = costNanos / 1.0e6;
                msptLast = cost;
                if (cost > msptMax) msptMax = cost;
                msptEwma += (cost - msptEwma) / 16;
                if (costNanos > PERIOD_NANOS) deadlineMisses++;
                tickStartNanos = 0;
                interruptFired = false;
                // a watchdog interrupt that landed after the blockage freed must not poison the next park
                Thread.interrupted();
            }
        }
        // final cycle: stranded ops/relays complete before the hand-off hook; a quarantined tree keeps its
        // structural queue frozen (a stale attach would tag an entity into the abandoned tree)
        try {
            if (!quarantined) {
                Runnable op;
                while ((op = ops.poll()) != null) safely(op);
            }
            root.drainTasksTree(System.currentTimeMillis());
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
