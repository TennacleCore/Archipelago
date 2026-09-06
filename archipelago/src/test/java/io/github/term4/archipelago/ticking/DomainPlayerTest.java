package io.github.term4.archipelago.ticking;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    /** A watcher rides the clock of what they watch: the shard's own, not the server thread. */
    @Test
    void watchersRideTheClockOfWhatTheyWatch() {
        MinecraftServer.getConnectionManager().setPlayerProvider(CountingPlayer::new);
        ExecutionDomain stageClock = ExecutionDomain.create();
        ExecutionDomain seatClock = ExecutionDomain.create();
        try {
            Player watcher = FakePlayer.connect(instance, new Pos(40.5, 70, 40.5), "DomainWatcher").player;
            Player seated = FakePlayer.connect(instance, new Pos(42.5, 70, 42.5), "DomainSeated").player;
            Shard stage = new Shard(instance, stageClock);
            Shard elsewhere = new Shard(instance, seatClock);
            elsewhere.join(seated);
            await("a member rides the shard they belong to", () -> ExecutionDomain.of(seated) == seatClock);

            ShardView.set(watcher, ShardView.of(stage));
            await("and a watcher the one they watch", () -> ExecutionDomain.of(watcher) == stageClock);

            ShardView.set(seated, ShardView.of(stage)); // watching without leaving their seat
            assertSame(seatClock, ExecutionDomain.of(seated), "membership keeps the clock it gave them");

            ShardView.set(watcher, null);
            await("a watcher who stops watching goes back to the server", () -> ExecutionDomain.of(watcher) == null);
            ShardView.set(seated, null);
            elsewhere.leave(seated);
        } finally {
            MinecraftServer.getConnectionManager().setPlayerProvider(null);
            stageClock.retire();
            seatClock.retire();
        }
    }

    /** A clock that stops never freezes a watcher's connection, the promise a member already had. */
    @Test
    void aParkedShardHandsBackItsWatchers() {
        MinecraftServer.getConnectionManager().setPlayerProvider(CountingPlayer::new);
        ExecutionDomain domain = ExecutionDomain.create();
        try {
            Player watcher = FakePlayer.connect(instance, new Pos(44.5, 70, 44.5), "ParkedWatcher").player;
            Shard stage = new Shard(instance, domain);
            ShardView.set(watcher, ShardView.of(stage));
            await("watching on its clock", () -> ExecutionDomain.of(watcher) == domain);

            stage.domain(null); // stasis: nothing on it ticks, and a live connection must not ride it
            await("a parked shard hands its watchers back", () -> ExecutionDomain.of(watcher) == null);

            stage.domain(domain);
            await("and takes them again once it runs", () -> ExecutionDomain.of(watcher) == domain);
            ShardView.set(watcher, null);
        } finally {
            MinecraftServer.getConnectionManager().setPlayerProvider(null);
            domain.retire();
        }
    }
}
