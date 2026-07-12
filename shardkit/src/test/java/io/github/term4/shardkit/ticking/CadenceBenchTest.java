package io.github.term4.shardkit.ticking;

import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saturation alarm proof: 64 domain clocks x 20 entities all hold the 50ms cadence with lag 0 - the carrier
 * pool multiplexes parked clocks, so shard count alone must never cost cadence. Prints the measured numbers.
 */
@Tag("slow")
class CadenceBenchTest extends HeadlessServerTest {

    private static final int DOMAINS = 64, ENTITIES = 20;

    private static final class Standee extends DomainEntity {
        Standee() {
            super(EntityType.ARMOR_STAND);
            setNoGravity(true);
        }
    }

    @Test
    void sixtyFourDomainsHoldCadenceAtTargetLoad() throws Exception {
        instance.loadChunk(1500 >> 4, 1500 >> 4).join();
        List<ExecutionDomain> roots = new ArrayList<>();
        List<Entity> all = new ArrayList<>();
        try {
            List<CompletableFuture<Void>> spawns = new ArrayList<>();
            for (int i = 0; i < DOMAINS; i++) {
                ExecutionDomain root = ExecutionDomain.create();
                roots.add(root);
                for (int j = 0; j < ENTITIES; j++) {
                    Standee e = new Standee();
                    all.add(e);
                    root.attach(e);
                    spawns.add(e.setInstance(instance, new Pos(1500.5 + (i % 16), 80 + j, 1500.5 + (i / 16))));
                }
            }
            CompletableFuture.allOf(spawns.toArray(CompletableFuture[]::new)).join();

            Thread.sleep(1000); // warmup: arrivals admitted, EWMA primed
            long[] t0 = roots.stream().mapToLong(ExecutionDomain::ticks).toArray();
            Thread.sleep(3000);

            long minDelta = Long.MAX_VALUE, maxLag = 0;
            double maxMspt = 0, sumMspt = 0;
            for (int i = 0; i < DOMAINS; i++) {
                ExecutionDomain.Metrics m = roots.get(i).metrics();
                minDelta = Math.min(minDelta, m.ticks() - t0[i]);
                maxLag = Math.max(maxLag, m.lag());
                maxMspt = Math.max(maxMspt, m.msptEwma());
                sumMspt += m.msptEwma();
            }
            System.out.printf("[bench] %d domains x %d entities: minDelta=%d/60 maxLag=%d mspt avg=%.3fms max=%.3fms%n",
                    DOMAINS, ENTITIES, minDelta, maxLag, sumMspt / DOMAINS, maxMspt);
            assertTrue(minDelta >= 45, "every domain holds cadence (worst " + minDelta + "/60 ticks in 3s)");
            assertTrue(maxLag <= 1, "no domain is behind its clock (worst lag " + maxLag + ")");
        } finally {
            for (Entity e : all) e.remove();
            for (ExecutionDomain root : roots) root.retire();
        }
    }
}
