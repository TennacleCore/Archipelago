package io.github.term4.archipelago.ticking;

import io.github.term4.archipelago.Storage;
import io.github.term4.archipelago.event.ticking.DomainQuarantineEvent;
import net.minestom.server.event.EventDispatcher;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** The manager's stall monitor: interrupt past {@code interruptAfterMs}, fail-stop past {@code quarantineAfterMs}. */
final class Watchdog {

    private static final Logger LOGGER = LoggerFactory.getLogger(Watchdog.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    /** Zombie clock threads this JVM: each holds one carrier until process restart (a spinning tick cannot be killed). */
    private static final AtomicInteger ZOMBIES = new AtomicInteger();
    /** Stack fingerprint -> failures this JVM: a repeat = deterministic poison, not a one-off. */
    private static final Map<String, Integer> FINGERPRINTS = new ConcurrentHashMap<>();
    private static final AtomicLong INTERRUPTS = new AtomicLong();
    private static final AtomicLong QUARANTINES = new AtomicLong();

    static int zombies() {
        return ZOMBIES.get();
    }

    static long interrupts() {
        return INTERRUPTS.get();
    }

    static long quarantines() {
        return QUARANTINES.get();
    }

    private final DomainManager manager;
    private final long interruptAfterMs;
    private final long quarantineAfterMs;
    private final Thread thread;

    Watchdog(DomainManager manager, long interruptAfterMs, long quarantineAfterMs) {
        this.manager = manager;
        this.interruptAfterMs = interruptAfterMs;
        this.quarantineAfterMs = quarantineAfterMs;
        this.thread = Thread.ofVirtual().name("archipelago-watchdog").unstarted(this::watch);
        thread.start();
    }

    void wake() {
        LockSupport.unpark(thread);
    }

    private void watch() {
        long intervalNanos = Math.max(50, interruptAfterMs / 4) * 1_000_000;
        while (manager.running) {
            LockSupport.parkNanos(intervalNanos);
            try {
                long now = System.nanoTime();
                for (DomainClock clock : manager.clocks.values()) {
                    long start = clock.tickStartNanos;
                    if (start == 0 || clock.quarantined) continue;
                    long stuckMs = (now - start) / 1_000_000;
                    if (stuckMs >= quarantineAfterMs) {
                        quarantine(clock, stuckMs);
                    } else if (stuckMs >= interruptAfterMs && !clock.interruptFired) {
                        clock.interruptFired = true;
                        INTERRUPTS.incrementAndGet();
                        LOGGER.warn("{} stuck {}ms - interrupting\n{}", clock.thread().getName(), stuckMs,
                                stack(clock.thread().getStackTrace()));
                        clock.thread().interrupt();
                    }
                }
            } catch (Throwable e) { // the watchdog must outlive anything a hook or log call throws
                LOGGER.warn("watchdog pass failed", e);
            }
        }
    }

    private void quarantine(DomainClock clock, long stuckMs) {
        clock.quarantined = true;
        clock.running = false; // a late-waking zombie exits without ever ticking again
        manager.clocks.remove(clock.domain());
        QUARANTINES.incrementAndGet();
        StackTraceElement[] frames = clock.thread().getStackTrace();
        String fingerprint = fingerprint(frames);
        int seen = FINGERPRINTS.merge(fingerprint, 1, Integer::sum);
        int zombies = ZOMBIES.incrementAndGet();
        Path report = writeCrashReport(clock, stuckMs, zombies, frames, fingerprint, seen);
        LOGGER.error("{} FAILED after {}ms - domain abandoned, {} zombie carrier(s) held until JVM restart ({})\n{}",
                clock.thread().getName(), stuckMs, zombies,
                report != null ? "crash report: " + report : "crash report write FAILED", stack(frames));
        if (seen > 1) {
            LOGGER.error("failure fingerprint {} seen {}x this JVM - DETERMINISTIC POISON: every shard running "
                    + "this code path will hang; disable the source (stack in the crash report)", fingerprint, seen);
        }
        try {
            EventDispatcher.call(new DomainQuarantineEvent(clock.domain().handle));
        } catch (Throwable e) {
            LOGGER.warn("quarantine listener failed", e);
        }
        // hand-offs stranded between their live() check and the removal above; off-thread so a blocking task
        // can't stall the watchdog loop
        Thread.startVirtualThread(clock.domain()::drainAbandoned);
    }

    /** Post-mortem file per failed domain, under {@link Storage} key {@code crash-dir} (default {@code archipelago-crashes/}). */
    private @Nullable Path writeCrashReport(DomainClock clock, long stuckMs, int zombies,
                                            StackTraceElement[] frames, String fingerprint, int seen) {
        try {
            Path dir = Storage.dir("crash-dir", "archipelago-crashes");
            Files.createDirectories(dir);
            Path file = dir.resolve("domain-" + LocalDateTime.now().format(STAMP) + "-"
                    + clock.thread().getName().replace(':', '_') + ".log");
            StringBuilder out = new StringBuilder("archipelago domain FAILED (fail-stop)\n");
            out.append("thread: ").append(clock.thread().getName()).append('\n')
                    .append("stuck: ").append(stuckMs).append("ms (interrupt at ").append(interruptAfterMs)
                    .append("ms, fail at ").append(quarantineAfterMs).append("ms)\n")
                    .append("zombie carriers this JVM: ").append(zombies)
                    .append(" (a hung tick cannot be killed; each holds one carrier until restart)\n")
                    .append("fingerprint: ").append(fingerprint)
                    .append(seen > 1 ? " (seen " + seen + "x this JVM - deterministic poison)" : " (first this JVM)")
                    .append('\n')
                    .append("domain:\n");
            describe(clock.domain(), out);
            out.append("stack (the tick it died in):\n").append(stack(frames))
                    .append("never re-clock this domain (a waking zombie would double-tick it) - rebuild fresh;")
                    .append(" the stack above names the poison\n");
            Files.writeString(file, out.toString());
            return file;
        } catch (Exception e) {
            LOGGER.warn("crash report write failed", e);
            return null;
        }
    }

    // top frames only: the spin site is stable for a deterministic bug, deeper frames vary by entry path
    private static String fingerprint(StackTraceElement[] frames) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < Math.min(5, frames.length); i++) key.append(frames[i]).append('\n');
        return Integer.toHexString(key.toString().hashCode());
    }

    private static void describe(Domain d, StringBuilder out) {
        out.append("  - domain@").append(Integer.toHexString(System.identityHashCode(d)))
                .append(" ticks=").append(d.ticks()).append(" entities=").append(d.entities.size())
                .append(" arrivals=").append(d.arrivals.size()).append('\n');
    }

    private static String stack(StackTraceElement[] frames) {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : frames) out.append("  at ").append(frame).append('\n');
        return out.toString();
    }
}
