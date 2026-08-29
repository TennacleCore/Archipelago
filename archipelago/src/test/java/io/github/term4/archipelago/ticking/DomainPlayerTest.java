package io.github.term4.archipelago.ticking;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The zero-dependency player seam: a {@link DomainPlayer} provider gives an app player domain ticking with no
 * bridge, and the ownership gate no-ops foreign drivers.
 */
class DomainPlayerTest extends HeadlessServerTest {

    public static class CountingPlayer extends DomainPlayer {
        final AtomicLong ticks = new AtomicLong();
        final AtomicLong gated = new AtomicLong();
        volatile String thread = "";

        public CountingPlayer(PlayerConnection connection, GameProfile profile) {
            super(connection, profile);
        }

        @Override public void tick(long time) {
            if (foreignDriver()) {
                gated.incrementAndGet();
                return;
            }
            thread = Thread.currentThread().getName();
            ticks.incrementAndGet();
            super.tick(time);
        }
    }

    @Test
    void domainPlayersTickOnTheirShardsClockWithNoBridge() {
        MinecraftServer.getConnectionManager().setPlayerProvider(CountingPlayer::new);
        ExecutionDomain domain = ExecutionDomain.create();
        try {
            CountingPlayer player = (CountingPlayer) FakePlayer.connect(instance,
                    new Pos(0.5, 70, 0.5), "DomainAlex").player;

            Shard shard = new Shard(instance, domain);
            shard.join(player);
            await("the shard's clock drives the player",
                    () -> player.ticks.get() >= 3 && player.thread.startsWith("shard-domain:"));

            long gated = player.gated.get();
            player.tick(System.nanoTime() / 1_000_000); // a stale driver (this thread): the gate must no-op it
            assertEquals(gated + 1, player.gated.get(), "a foreign driver's call never runs the tick body");

            shard.leave(player);
            await("leave hands the player back to the server", () -> ExecutionDomain.of(player) == null);
            assertNull(player.getTag(MechanicsWorld.ENTITY_TAG));
        } finally {
            MinecraftServer.getConnectionManager().setPlayerProvider(null);
            domain.retire();
        }
    }
}
