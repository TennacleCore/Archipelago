package io.github.term4.shardkit.ticking;

import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.event.ticking.DomainQuarantineEvent;
import io.github.term4.shardkit.testsupport.DetectorEntity;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-root-clock engine: independent roots (a stalled or hung root never slows a sibling), children inline
 * parent-first, reparent/promote moving execution, and the watchdog's interrupt + fail-stop quarantine.
 */
class DomainManagerTest {

    private static InstanceContainer instance;

    @BeforeAll
    static void boot() throws IOException {
        if (System.getProperty("shardkit.crash-dir") == null) {
            System.setProperty("shardkit.crash-dir", Files.createTempDirectory("shardkit-crash-test").toString());
        }
        if (instance != null) return;
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
            MinecraftServer.process().dispatcher().start();
        }
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 64, Block.STONE));
        instance.loadChunk(0, 0).join();
    }

    private static void await(String what, Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 4000;
        while (!done.get() && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertTrue(done.get(), what);
    }

    /** Spins {@code root} until {@code released} and waits for the watchdog to quarantine its tree. */
    private static void quarantine(Domain root, AtomicBoolean released) {
        CompletableFuture<ExecutionDomain> quarantined = new CompletableFuture<>();
        EventNode<Event> node = EventNode.all("test:quarantine");
        node.addListener(DomainQuarantineEvent.class, e -> quarantined.complete(e.domain()));
        MinecraftServer.getGlobalEventHandler().addChild(node);
        try {
            root.execute(() -> {
                while (!released.get()) LockSupport.parkNanos(1_000_000);
            });
            assertEquals(root.handle, quarantined.orTimeout(4, TimeUnit.SECONDS).join(), "the watchdog reported the tree");
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(node);
        }
    }

    @Test
    void rootsTickIndependentlyOnTheirOwnThreads() {
        DomainManager domains = new DomainManager();
        DetectorEntity e0 = new DetectorEntity(), e1 = new DetectorEntity();
        try {
            Domain r0 = domains.root(), r1 = domains.root();
            domains.attach(r0, e0);
            domains.attach(r1, e1);
            e0.setInstance(instance, new Pos(0.5, 70, 0.5)).join();
            e1.setInstance(instance, new Pos(2.5, 70, 2.5)).join();
            await("both roots tick", () -> e0.ticks.get() >= 5 && e1.ticks.get() >= 5);
            assertTrue(e0.thread.startsWith("shard-root:"), e0.thread);
            assertTrue(e1.thread.startsWith("shard-root:"), e1.thread);
            assertNotEquals(e0.thread, e1.thread, "each root has its own clock thread");
        } finally {
            e0.remove();
            e1.remove();
            domains.shutdown();
        }
    }

    @Test
    void aHungRootNeverSlowsItsSiblings() {
        DomainManager domains = new DomainManager();
        DetectorEntity ea = new DetectorEntity(), eb = new DetectorEntity();
        try {
            Domain a = domains.root(), b = domains.root();
            domains.attach(a, ea);
            domains.attach(b, eb);
            ea.setInstance(instance, new Pos(0.5, 70, 0.5)).join();
            eb.setInstance(instance, new Pos(2.5, 70, 2.5)).join();
            await("both roots tick", () -> ea.ticks.get() >= 2 && eb.ticks.get() >= 2);

            CompletableFuture<Void> stallOver = new CompletableFuture<>();
            a.execute(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                stallOver.complete(null);
            });
            long a0 = ea.ticks.get(), b0 = eb.ticks.get();
            await("b keeps FULL rate while a hangs", () -> eb.ticks.get() >= b0 + 6);
            assertFalse(stallOver.isDone(), "a is still inside its stall");
            assertTrue(ea.ticks.get() <= a0 + 1, "a froze (at most the tick the stall started on)");
            stallOver.orTimeout(4, TimeUnit.SECONDS).join();
        } finally {
            ea.remove();
            eb.remove();
            domains.shutdown();
        }
    }

    @Test
    void childrenTickInlineAfterTheirParentOnTheRootsThread() {
        DomainManager domains = new DomainManager();
        try {
            Domain root = domains.root();
            Domain child = domains.child(root);
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            String[] threads = new String[2];
            root.onTick(t -> {
                order.add("root");
                threads[0] = Thread.currentThread().getName();
            });
            child.onTick(t -> {
                order.add("child");
                threads[1] = Thread.currentThread().getName();
            });
            await("both hooks run", () -> order.size() >= 6);

            assertEquals(threads[0], threads[1], "the child shares its root's thread");
            List<String> snapshot;
            synchronized (order) {
                snapshot = new ArrayList<>(order.subList(0, 6));
            }
            for (int i = 0; i + 1 < 6; i += 2) {
                assertEquals("root", snapshot.get(i), "parent strictly before children");
                assertEquals("child", snapshot.get(i + 1));
            }
        } finally {
            domains.shutdown();
        }
    }

    @Test
    void reparentMovesExecutionAndPromoteGrantsAClock() {
        DomainManager domains = new DomainManager();
        try {
            Domain r0 = domains.root(), r1 = domains.root();
            Domain child = domains.child(r0);
            String[] childThread = new String[1];
            String[] rootThreads = new String[2];
            child.onTick(t -> childThread[0] = Thread.currentThread().getName());
            r0.onTick(t -> rootThreads[0] = Thread.currentThread().getName());
            r1.onTick(t -> rootThreads[1] = Thread.currentThread().getName());
            await("all tick", () -> childThread[0] != null && rootThreads[0] != null && rootThreads[1] != null);
            assertEquals(rootThreads[0], childThread[0], "starts on r0's thread");

            domains.reparent(child, r1);
            await("execution followed the new root", () -> rootThreads[1].equals(childThread[0]));
            assertEquals(r1, child.root());

            domains.promote(child);
            await("a promoted root ticks on its own fresh clock",
                    () -> child.isRoot() && childThread[0].startsWith("shard-root:")
                            && !childThread[0].equals(rootThreads[0]) && !childThread[0].equals(rootThreads[1]));
            assertNull(child.parent());
        } finally {
            domains.shutdown();
        }
    }

    @Test
    void watchdogInterruptFreesABlockedRoot() {
        DomainManager domains = new DomainManager(200, 10_000);
        DetectorEntity e = new DetectorEntity();
        try {
            Domain root = domains.root();
            domains.attach(root, e);
            e.setInstance(instance, new Pos(0.5, 70, 0.5)).join();
            await("root ticks", () -> e.ticks.get() >= 2);

            root.execute(() -> {
                try {
                    Thread.sleep(60_000); // an interruptible hang: deadlockable lock, stuck future, sleep...
                } catch (InterruptedException freedByWatchdog) {
                }
            });
            long before = e.ticks.get();
            await("the watchdog interrupt freed the root and ticking resumed", () -> e.ticks.get() >= before + 3);
        } finally {
            e.remove();
            domains.shutdown();
        }
    }

    @Test
    void adoptingALiveGloballyRegisteredEntityNeverDoubleTicks() throws InterruptedException {
        var dispatcher = MinecraftServer.process().dispatcher();
        DomainManager domains = new DomainManager();
        // slow tick widens any overlap window; concurrent entry = the double-tick the epic must never allow
        DetectorEntity e = new DetectorEntity(10_000_000);
        AtomicBoolean serve = new AtomicBoolean(true);
        Thread server = new Thread(() -> { // headless stand-in for serverTick's updateAndAwait cadence
            while (serve.get()) {
                dispatcher.updateAndAwait(System.currentTimeMillis());
                LockSupport.parkNanos(2_000_000);
            }
        });
        try {
            e.setInstance(instance, new Pos(4.5, 70, 4.5)).join();
            server.start();
            await("the server drives it", () -> e.ticks.get() >= 3);
            Domain d = domains.root();
            for (int round = 0; round < 12; round++) {
                domains.attach(d, e);
                long taken = e.ticks.get();
                await("the domain takes over", () -> e.ticks.get() > taken && e.thread.startsWith("shard-root:"));
                DomainManager.detach(e);
                long back = e.ticks.get();
                await("the server takes back", () -> e.ticks.get() > back && !e.thread.startsWith("shard-root:"));
            }
            assertEquals(0, e.overlaps.get(), "an adopted entity was ticked by two threads at once");
        } finally {
            serve.set(false);
            server.join(4000);
            e.remove();
            domains.shutdown();
        }
    }

    @Test
    void pingPongTransfersNeverOverlapTicks() throws InterruptedException {
        var dispatcher = MinecraftServer.process().dispatcher();
        DomainManager domains = new DomainManager();
        // slow tick widens the overlap window; a hop whose release ran on a STALE clock would land mid-body
        DetectorEntity e = new DetectorEntity(2_000_000);
        AtomicBoolean serve = new AtomicBoolean(true);
        Thread server = new Thread(() -> {
            while (serve.get()) {
                dispatcher.updateAndAwait(System.currentTimeMillis());
                LockSupport.parkNanos(2_000_000);
            }
        });
        try {
            e.setInstance(instance, new Pos(6.5, 70, 6.5)).join();
            server.start();
            Domain[] targets = {domains.root(), domains.root(), domains.root()};
            // re-transfers issued faster than the clocks drain their hops: every release must chase the
            // CURRENT owner or it races the real owner's in-flight tick
            for (int i = 0; i < 900; i++) domains.attach(targets[i % 3], e);
            await("the hops settle on a live owner that really ticks it", () -> {
                Domain owner = Domain.of(e);
                if (owner == null) return false;
                long before = e.ticks.get();
                LockSupport.parkNanos(120_000_000);
                return e.ticks.get() > before;
            });
            assertEquals(0, e.overlaps.get(), "a stale hop released a foreign owner mid-tick");
        } finally {
            serve.set(false);
            server.join(4000);
            e.remove();
            domains.shutdown();
        }
    }

    @Test
    void quarantinedTreesStillReleaseTheirEntities() {
        DomainManager domains = new DomainManager(150, 600);
        DetectorEntity evacuee = new DetectorEntity(), rebound = new DetectorEntity();
        try {
            Domain root = domains.root(), refuge = domains.root();
            domains.attach(root, evacuee);
            domains.attach(root, rebound);
            evacuee.setInstance(instance, new Pos(0.5, 70, 0.5)).join();
            rebound.setInstance(instance, new Pos(2.5, 70, 2.5)).join();
            await("ticking", () -> evacuee.ticks.get() >= 2 && rebound.ticks.get() >= 2);

            AtomicBoolean released = new AtomicBoolean();
            quarantine(root, released);

            DomainManager.detach(evacuee); // the quarantine evacuation path - dropping it would freeze the entity forever
            assertNull(Domain.of(evacuee), "detach must work on a quarantined tree");
            long before = evacuee.ticks.get();
            evacuee.tick(System.currentTimeMillis()); // server-tickable again (headless stand-in)
            assertEquals(before + 1, evacuee.ticks.get());

            domains.attach(refuge, rebound); // ...and so must moving survivors to a live tree
            await("the refuge clock drives the survivor",
                    () -> Domain.of(rebound) == refuge && rebound.thread.startsWith("shard-root:"));
            released.set(true);
        } finally {
            evacuee.remove();
            rebound.remove();
            domains.shutdown();
        }
    }

    @Test
    void queuedTasksAreNeverLostWhenTheirDomainDies() throws InterruptedException {
        DomainManager domains = new DomainManager();
        try {
            Domain d = domains.root();
            CountDownLatch inStall = new CountDownLatch(1);
            d.execute(() -> {
                inStall.countDown();
                LockSupport.parkNanos(300_000_000);
            });
            assertTrue(inStall.await(4, TimeUnit.SECONDS));
            CompletableFuture<Void> ran = new CompletableFuture<>();
            d.execute(() -> ran.complete(null)); // queued behind the stall
            domains.retire(d); // mid-stall: the task is still queued when the clock is told to end
            ran.orTimeout(4, TimeUnit.SECONDS).join(); // a lost task here = a forever-hung onDomain join
        } finally {
            domains.shutdown();
        }
    }

    @Test
    void relayEscapesAQuarantinedSpinner() {
        DomainManager domains = new DomainManager(150, 600);
        try {
            Domain d = domains.root();
            AtomicBoolean released = new AtomicBoolean();
            quarantine(d, released);
            // the zombie never drains its queue; the relay must reclaim its task instead of hanging the caller
            assertEquals("ran", d.relay(() -> "ran"));
            released.set(true);
        } finally {
            domains.shutdown();
        }
    }

    @Test
    void attachRightAfterChildCreationLands() {
        DomainManager domains = new DomainManager();
        DetectorEntity e = new DetectorEntity();
        try {
            Domain parent = domains.root();
            Domain sub = domains.child(parent);
            domains.attach(sub, e); // before the parent's clock ever cycled
            e.setInstance(instance, new Pos(0.5, 70, 0.5)).join();
            await("the parent's clock ticks the sub-domain entity",
                    () -> e.ticks.get() >= 2 && e.thread.startsWith("shard-root:"));
        } finally {
            e.remove();
            domains.shutdown();
        }
    }

    @Test
    void aQueuedHopSurvivesTheTargetRetiring() throws InterruptedException {
        DomainManager domains = new DomainManager();
        DetectorEntity e = new DetectorEntity();
        try {
            Domain a = domains.root(), b = domains.root();
            domains.attach(a, e);
            e.setInstance(instance, new Pos(0.5, 70, 0.5)).join();
            await("a drives it", () -> e.ticks.get() >= 2);

            CountDownLatch inStall = new CountDownLatch(1);
            b.execute(() -> {
                inStall.countDown();
                LockSupport.parkNanos(500_000_000);
            });
            assertTrue(inStall.await(4, TimeUnit.SECONDS));
            domains.attach(b, e); // hop 2 queues behind b's stall
            Thread.sleep(150); // let hop 1 (a's clock) run and submit hop 2
            domains.retire(b);
            // whichever side of retire the hop landed on, the entity must end server-owned - REALLY registered,
            // not just untagged (a dropped hop leaves it out of every dispatcher)
            await("the entity is handed back to the server", () -> Domain.of(e) == null);
            long before = e.ticks.get();
            await("the global dispatcher really ticks it again", () -> {
                MinecraftServer.process().dispatcher().updateAndAwait(System.currentTimeMillis());
                return e.ticks.get() > before;
            });
        } finally {
            e.remove();
            domains.shutdown();
        }
    }

    @Test
    void aDomainNeverTicksAnEntityTaggedElsewhere() {
        DomainManager domains = new DomainManager();
        DetectorEntity stray = new DetectorEntity();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        try {
            Domain a = domains.root(), b = domains.root();
            domains.attach(b, stray);
            stray.setInstance(instance, new Pos(0.5, 70, 0.5)).join();
            await("b drives it", () -> stray.ticks.get() >= 2);
            String bThread = stray.thread;

            a.entities.add(stray); // the double-entry a lost hand-off race would leave behind
            await("a self-cleans the stray instead of double-ticking it", () -> !a.entities.contains(stray));
            long settle = stray.ticks.get();
            await("still ticking", () -> stray.ticks.get() >= settle + 3);
            threads.add(stray.thread);
            assertEquals(Set.of(bThread), threads, "only b's clock ever ticked it");
        } finally {
            stray.remove();
            domains.shutdown();
        }
    }

    @Test
    void aTickCountAlwaysSpansItsWallTimeAndStallsNeverBurst() throws InterruptedException {
        DomainManager domains = new DomainManager();
        try {
            Domain d = domains.root();
            long start = d.ticks();
            long t0 = System.nanoTime();
            await("40 ticks pass", () -> d.ticks() >= start + 40);
            long elapsed = System.nanoTime() - t0;
            assertTrue(elapsed >= 38 * RootClock.PERIOD_NANOS,
                    "40 ticks must span >= their wall time, took " + elapsed / 1_000_000 + "ms");

            CompletableFuture<Void> stallOver = new CompletableFuture<>();
            d.execute(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                stallOver.complete(null);
            });
            stallOver.orTimeout(4, TimeUnit.SECONDS).join();
            long resumed = d.ticks();
            Thread.sleep(1000); // ~20 honest ticks; a catch-up burst would replay the ~10 the stall dropped
            long gained = d.ticks() - resumed;
            assertTrue(gained <= 24, "dropped ticks must never burst back: +" + gained + " in 1s");
            assertTrue(gained >= 5, "the clock resumed after the stall");
        } finally {
            domains.shutdown();
        }
    }

    @Test
    void msptMeasuresPerDomainTickCost() {
        DomainManager domains = new DomainManager();
        try {
            Domain idle = domains.root(), busy = domains.root();
            busy.onTick(t -> LockSupport.parkNanos(10_000_000)); // ~10ms of tick cost
            await("the busy tree's cost registers", () -> domains.mspt(busy) >= 5.0);
            assertTrue(domains.mspt(idle) < domains.mspt(busy),
                    "cost is per-clock: idle=" + domains.mspt(idle) + " busy=" + domains.mspt(busy));
        } finally {
            domains.shutdown();
        }
    }

    @Test
    void relayRunsInlineOnItsOwnClockAndFailsLoudFromAForeignOne() {
        DomainManager domains = new DomainManager();
        try {
            Domain a = domains.root(), b = domains.root();
            CompletableFuture<Object> outcome = new CompletableFuture<>();
            a.onTick(t -> {
                if (outcome.isDone()) return;
                String inline = a.relay(() -> "inline"); // own clock: no queue, no self-deadlock
                try {
                    outcome.complete(inline + "|" + b.relay(() -> "joined"));
                } catch (IllegalStateException deadlockGuard) {
                    outcome.complete(deadlockGuard);
                }
            });
            Object result = outcome.orTimeout(4, TimeUnit.SECONDS).join();
            assertTrue(result instanceof IllegalStateException,
                    "two live clocks joining each other would deadlock, got: " + result);
        } finally {
            domains.shutdown();
        }
    }

    @Test
    void anErrorInATickNeverKillsTheClock() {
        DomainManager domains = new DomainManager();
        try {
            Domain d = domains.root();
            AtomicLong ticks = new AtomicLong();
            d.onTick(t -> {
                if (ticks.incrementAndGet() == 2) throw new AssertionError("entity code gone wrong");
            });
            await("the clock survives the Error and keeps ticking", () -> ticks.get() >= 5);
            CompletableFuture<Void> alive = new CompletableFuture<>();
            d.execute(() -> alive.complete(null));
            alive.orTimeout(2, TimeUnit.SECONDS).join(); // and still drains relays
        } finally {
            domains.shutdown();
        }
    }

    @Test
    void watchdogQuarantinesAnUninterruptibleRootAndWritesACrashReport() throws IOException {
        DomainManager domains = new DomainManager(150, 600);
        try {
            Domain root = domains.root();
            String clockThread = domains.threadName(root);
            AtomicBoolean released = new AtomicBoolean();
            AtomicLong hookTicks = new AtomicLong();
            root.onTick(t -> hookTicks.incrementAndGet());
            quarantine(root, released); // the park loop ignores interrupts: a true hang

            Path dir = Path.of(System.getProperty("shardkit.crash-dir"));
            try (var files = Files.list(dir)) {
                String report = null;
                for (Path p : files.toList()) {
                    String content = Files.readString(p);
                    if (content.contains(clockThread)) report = content;
                }
                assertNotNull(report, "a crash report names the failed clock");
                assertTrue(report.contains("at "), "with the stuck tick's stack");
                assertTrue(report.contains("fingerprint: "), "with the poison's stack fingerprint");
                assertTrue(report.contains("never re-clock"), "and the rebuild-fresh doctrine");
            }
            assertTrue(DomainManager.zombieCarriers() >= 1, "the JVM-degradation counter ticked");

            released.set(true); // the zombie wakes, finishes its cycle, and must exit without ticking again
            await("the zombie clock went quiet", () -> {
                long before = hookTicks.get();
                LockSupport.parkNanos(150_000_000);
                return hookTicks.get() == before;
            });
        } finally {
            domains.shutdown();
        }
    }
}
