package io.github.term4.archipelago.ticking;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.DetectorEntity;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-stop through the manager: a failed DOMAIN parks its shards in stasis; the app recovers or destroys. */
class ShardFailStopTest extends HeadlessServerTest {

    @Test
    void aQuarantinedShardEntersStasisAndRecoversOnAFreshClock() {
        DomainManager domains = new DomainManager(150, 600); // internal: short watchdog thresholds
        ShardManager manager = new ShardManager();
        FakePlayer member = FakePlayer.connect(instance, new Pos(30.5, 70, 30.5), "DoomedSteve");
        DetectorEntity e = new DetectorEntity();
        AtomicBoolean released = new AtomicBoolean();
        try {
            Shard shard = manager.create(instance, "doomed", domains.create().handle);
            shard.join(member.player);
            shard.spawn(e, new Pos(31.5, 70, 31.5)).join();
            await("ticking", () -> e.ticks.get() >= 2);

            shard.domain().execute(() -> { // uninterruptible: the DOMAIN fails, work never reroutes
                while (!released.get()) LockSupport.parkNanos(1_000_000);
            });
            await("the member's connection handed back to the server", () -> ExecutionDomain.of(member.player) == null);
            assertEquals(shard, manager.byName("doomed"), "the shard survives its domain in stasis");
            assertTrue(shard.members().contains(member.player), "membership survives stasis");
            long frozen = e.ticks.get();
            LockSupport.parkNanos(200_000_000);
            assertEquals(frozen, e.ticks.get(), "stasis entities are FROZEN, not rerouted");

            ExecutionDomain fresh = ExecutionDomain.create(); // recovery is the app's call: a FRESH clock
            shard.domain(fresh);
            await("the fresh clock revives the frozen entity",
                    () -> e.ticks.get() > frozen && e.thread.startsWith("shard-domain:"));

            manager.destroy(shard);
            await("destroyed on demand", () -> manager.byName("doomed") == null);
            assertNull(member.player.getTag(MechanicsWorld.ENTITY_TAG));
            assertTrue(e.isRemoved());
        } finally {
            released.set(true);
            member.player.remove();
            domains.shutdown();
        }
    }
}
