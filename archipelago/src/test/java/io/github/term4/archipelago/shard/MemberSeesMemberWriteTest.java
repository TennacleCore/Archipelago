package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.BlockWire;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shard's own writes are what its members are there to see. Reported from duels: a player could not see the
 * blocks another player placed or broke, and a spectator saw none of them.
 */
class MemberSeesMemberWriteTest extends HeadlessServerTest {

    @Test
    void aMemberSeesAnotherMembersPlacement() {
        Shard shard = new Shard(instance);
        Point at = new Vec(2401, 70, 2400);
        FakePlayer placer = FakePlayer.connect(instance, new Pos(2400.5, 71, 2400.5), "SeeMePlacer");
        FakePlayer peer = FakePlayer.connect(instance, new Pos(2402.5, 71, 2400.5), "SeeMePeer");
        try {
            shard.join(placer.player);
            shard.join(peer.player);
            placer.sent.clear();
            peer.sent.clear();

            shard.setBlock(at, Block.DIAMOND_BLOCK);

            assertEquals(Block.DIAMOND_BLOCK.stateId(), BlockWire.statesSentTo(peer).get(new Vec(2401, 70, 2400)),
                    "the peer is told about it: " + BlockWire.statesSentTo(peer));
            assertEquals(Block.DIAMOND_BLOCK.stateId(), BlockWire.statesSentTo(placer).get(new Vec(2401, 70, 2400)),
                    "and so is the player who did it");
        } finally {
            placer.player.remove();
            peer.player.remove();
        }
    }

    @Test
    void aMemberSeesAnotherMembersBreak() {
        Shard shard = new Shard(instance);
        Point at = new Vec(2411, 70, 2410);
        instance.setBlock(at, Block.STONE);
        FakePlayer breaker = FakePlayer.connect(instance, new Pos(2410.5, 71, 2410.5), "SeeMeBreaker");
        FakePlayer peer = FakePlayer.connect(instance, new Pos(2412.5, 71, 2410.5), "SeeMeBreakPeer");
        try {
            shard.join(breaker.player);
            shard.join(peer.player);
            peer.sent.clear();

            shard.setBlock(at, Block.AIR);

            assertEquals(Block.AIR.stateId(), BlockWire.statesSentTo(peer).get(new Vec(2411, 70, 2410)),
                    "the break reaches the peer: " + BlockWire.statesSentTo(peer));
        } finally {
            instance.setBlock(at, Block.AIR);
            breaker.player.remove();
            peer.player.remove();
        }
    }

    /**
     * The real wire: a player's sendPacket only QUEUES, so the out event fires later on that connection's write
     * thread. Anything the sender marked about itself is gone by then. This dispatches the shard's own packet
     * exactly as the wire would - no sender mark at all - and it must still reach the member.
     */
    @Test
    void theShardsOwnWriteSurvivesTheSendQueue() {
        Shard shard = new Shard(instance);
        Point at = new Vec(2431, 70, 2430);
        FakePlayer member = FakePlayer.connect(instance, new Pos(2430.5, 71, 2430.5), "QueueHopMember");
        try {
            shard.join(member.player);
            shard.setBlock(at, Block.DIAMOND_BLOCK);

            // what the write thread does with the queued packet, on a thread that never saw the send
            PlayerPacketOutEvent out = new PlayerPacketOutEvent(member.player,
                    new BlockChangePacket(at, Block.DIAMOND_BLOCK));
            net.minestom.server.event.EventDispatcher.call(out);
            assertFalse(out.isCancelled(), "the shard's own value must not be filtered as if it were the base's");

            PlayerPacketOutEvent stale = new PlayerPacketOutEvent(member.player,
                    new BlockChangePacket(at, Block.STONE));
            net.minestom.server.event.EventDispatcher.call(stale);
            assertTrue(stale.isCancelled(), "but a value that contradicts the chain is still the base painting over");
        } finally {
            member.player.remove();
        }
    }

    /** A spectator watches through the same chain, so every write a member makes has to reach them too. */
    @Test
    void aSpectatorSeesTheGamesWrites() {
        Shard shard = new Shard(instance);
        Point at = new Vec(2421, 70, 2420);
        FakePlayer member = FakePlayer.connect(instance, new Pos(2420.5, 71, 2420.5), "SpecGameMember");
        FakePlayer watcher = FakePlayer.connect(instance, new Pos(2422.5, 71, 2420.5), "SpecGameWatcher");
        try {
            shard.join(member.player);
            ShardView.set(watcher.player, ShardView.of(shard));
            watcher.sent.clear();

            shard.setBlock(at, Block.DIAMOND_BLOCK);

            assertTrue(BlockWire.statesSentTo(watcher).containsKey(new Vec(2421, 70, 2420)),
                    "the watcher sees it: " + BlockWire.statesSentTo(watcher));
        } finally {
            ShardView.set(watcher.player, null);
            member.player.remove();
            watcher.player.remove();
        }
    }
}
