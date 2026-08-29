package io.github.term4.archipelago.ticking;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A domain created at its own tps drives its whole timeline at that cadence. */
class DomainRateTest extends HeadlessServerTest {

    @Test
    void aFastDomainOutpacesASlowOne() throws InterruptedException {
        ExecutionDomain fast = ExecutionDomain.create(200);
        ExecutionDomain slow = ExecutionDomain.create(5);
        try {
            // deadline-wait, not a fixed sleep: suite CPU load starves the clocks and undercuts a timed count
            long deadline = System.currentTimeMillis() + 5000;
            while (fast.ticks() < 60 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            long fastTicks = fast.ticks();
            long slowTicks = slow.ticks();
            assertTrue(fastTicks >= 60, "fast domain barely ticked: " + fastTicks);
            assertTrue(fastTicks > slowTicks * 5, fastTicks + " vs " + slowTicks);
        } finally {
            fast.retire();
            slow.retire();
        }
    }
}
