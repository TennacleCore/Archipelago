package io.github.term4.archipelago.ticking;

import io.github.term4.archipelago.testsupport.DetectorEntity;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pull-based snapshots: domain counters are raw clock/node facts, the engine snapshot is JVM posture. */
class ExecutionDomainMetricsTest extends HeadlessServerTest {

    @Test
    void aHealthyDomainReportsItsClockAndNodeFacts() {
        ExecutionDomain domain = ExecutionDomain.create();
        DetectorEntity e = new DetectorEntity();
        try {
            Runnable stop = domain.onTick(t -> {});
            domain.attach(e);
            e.setInstance(instance, new Pos(0.5, 70, 0.5)).join();
            await("the clock runs and admits the entity", () -> e.ticks.get() >= 3);

            ExecutionDomain.Metrics m = domain.metrics();
            assertTrue(m.alive());
            assertTrue(m.ticks() >= 3);
            assertNotNull(m.threadName());
            assertTrue(m.threadName().startsWith("shard-domain:"), m.threadName());
            assertEquals(1, m.entityCount());
            assertEquals(1, m.hookCount());
            assertEquals(0, m.pendingArrivals());
            assertTrue(m.tickPeriodNanos() > 0);
            assertTrue(m.msptLast() >= 0 && m.msptEwma() >= 0);
            stop.run();
            assertEquals(0, domain.metrics().hookCount(), "the removal handle shows in the snapshot");
        } finally {
            e.remove();
            domain.retire();
        }
    }

    @Test
    void stallsSurfaceAsDeadlineMissesCostAndBacklog() throws InterruptedException {
        ExecutionDomain domain = ExecutionDomain.create();
        try {
            long missesBefore = domain.metrics().deadlineMisses();
            CountDownLatch inStall = new CountDownLatch(1);
            domain.execute(() -> {
                inStall.countDown();
                long end = System.nanoTime() + 200_000_000; // 4 periods: a deadline miss by construction
                while (System.nanoTime() < end) LockSupport.parkNanos(end - System.nanoTime());
            });
            assertTrue(inStall.await(4, TimeUnit.SECONDS));
            domain.execute(() -> {}); // queued behind the stall
            assertTrue(domain.metrics().taskBacklog() >= 1, "the queued task shows while the tick is stuck");

            await("the miss and its cost land in the counters", () -> {
                ExecutionDomain.Metrics m = domain.metrics();
                return m.deadlineMisses() > missesBefore && m.msptMax() >= 150;
            });
            await("the backlog drains with the next tick", () -> domain.metrics().taskBacklog() == 0);
        } finally {
            domain.retire();
        }
    }

    @Test
    void aRetiredDomainReadsDeadAndTheEngineCountsClocks() {
        ExecutionDomain domain = ExecutionDomain.create();
        assertTrue(ExecutionDomain.engine().clocks() >= 1);
        domain.retire();
        await("the clock ends", () -> !domain.live());

        ExecutionDomain.Metrics m = domain.metrics();
        assertFalse(m.alive());
        assertNull(m.threadName());
        assertEquals(0, m.lag(), "a dead clock reports no lag");

        ExecutionDomain.Engine engine = ExecutionDomain.engine();
        assertTrue(engine.parallelism() > 0);
        assertTrue(engine.cores() > 0);
        assertTrue(engine.zombieCarriers() >= 0 && engine.interrupts() >= 0 && engine.quarantines() >= 0);
    }
}
