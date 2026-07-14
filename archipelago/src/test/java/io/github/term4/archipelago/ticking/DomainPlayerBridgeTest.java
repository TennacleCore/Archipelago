package io.github.term4.archipelago.ticking;

import io.github.term4.minestommechanics.platform.player.OptimizedPlayer;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.world.WorldAccess;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.junit.jupiter.api.Test;
import test.ShardBridge;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-repo player seam with the REAL mechanics class: an {@code OptimizedPlayer} joining a domain-bound
 * shard ticks (packets and all) on the shard's clock thread; leaving hands it back to the server.
 */
class DomainPlayerBridgeTest extends HeadlessServerTest {

    public static class CountingPlayer extends OptimizedPlayer {
        final AtomicLong ticks = new AtomicLong();
        final AtomicLong gated = new AtomicLong();
        volatile String thread = "";

        public CountingPlayer(PlayerConnection connection, GameProfile profile) {
            super(connection, profile);
        }

        @Override public void tick(long time) {
            if (!MechanicsWorld.ownsCurrentTick(this)) { // only EFFECTIVE ticks count, like the sk detectors
                gated.incrementAndGet();
                return;
            }
            thread = Thread.currentThread().getName();
            ticks.incrementAndGet();
            super.tick(time);
        }
    }

    @Test
    void playersTickOnTheirShardsClockAndReleaseOnLeave() {
        ShardBridge.installDomainTicking();
        MinecraftServer.getConnectionManager().setPlayerProvider(CountingPlayer::new);
        ExecutionDomain domain = ExecutionDomain.create();
        try {
            CountingPlayer player = (CountingPlayer) FakePlayer.connect(instance,
                    new Pos(0.5, 70, 0.5), "DomainSteve").player;
            assertInstanceOf(OptimizedPlayer.class, player);

            Shard shard = new Shard(instance, domain);
            shard.join(player);
            await("the shard's clock drives the player",
                    () -> player.ticks.get() >= 3 && player.thread.startsWith("shard-domain:"));
            long before = player.ticks.get();
            await("and keeps driving it", () -> player.ticks.get() >= before + 3);

            long gated = player.gated.get();
            player.tick(System.currentTimeMillis()); // a stale driver (this thread): the mm gate must no-op it
            assertEquals(gated + 1, player.gated.get(), "the ownership gate mirrors into mm's covered classes");

            shard.leave(player);
            await("leave hands the player back to the server", () -> ExecutionDomain.of(player) == null);
            long released = player.ticks.get();
            LockSupport.parkNanos(200_000_000);
            assertTrue(player.ticks.get() <= released + 1, "no clock drives it anymore (headless)");
            assertNull(player.getTag(WorldAccess.ENTITY_TAG));
        } finally {
            MinecraftServer.getConnectionManager().setPlayerProvider(null);
            domain.retire();
        }
    }
}
