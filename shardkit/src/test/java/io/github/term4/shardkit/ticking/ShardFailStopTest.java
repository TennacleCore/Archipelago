package io.github.term4.shardkit.ticking;

import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.ShardManager;
import io.github.term4.shardkit.testsupport.DetectorEntity;
import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import io.github.term4.shardkit.world.WorldAccess;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-stop through the manager: a quarantined domain destroys its shards, work never reroutes. */
class ShardFailStopTest extends HeadlessServerTest {

    @Test
    void aQuarantinedShardFailStops() {
        DomainManager domains = new DomainManager(150, 600); // internal: short watchdog thresholds
        ShardManager manager = new ShardManager();
        FakePlayer member = FakePlayer.connect(instance, new Pos(30.5, 70, 30.5), "DoomedSteve");
        DetectorEntity e = new DetectorEntity();
        AtomicBoolean released = new AtomicBoolean();
        try {
            Shard shard = manager.create(instance, "doomed", domains.root().handle);
            shard.join(member.player);
            shard.spawn(e, new Pos(31.5, 70, 31.5)).join();
            await("ticking", () -> e.ticks.get() >= 2);

            shard.domain().execute(() -> { // uninterruptible: the shard FAILS, it never reroutes
                while (!released.get()) LockSupport.parkNanos(1_000_000);
            });
            await("fail-stop destroyed the shard", () -> manager.byName("doomed") == null);
            assertNull(member.player.getTag(WorldAccess.ENTITY_TAG), "members evicted to the base world");
            assertNull(ExecutionDomain.of(member.player), "member ticking handed back to the server");
            assertTrue(e.isRemoved(), "shard entities die with the shard");
        } finally {
            released.set(true);
            member.player.remove();
            domains.shutdown();
        }
    }
}
