package io.github.term4.archipelago.ticking;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire parity through membership churn: a domain shard's member receives block changes, overlay re-sends and
 * entity spawns exactly like a plain shard's member - the tick engine must be invisible on the wire.
 */
class ChurnPacketParityTest extends HeadlessServerTest {

    @Test
    void churnedDomainMembersSeeTheSameWireAsPlainMembers() {
        instance.loadChunk(1400 >> 4, 1400 >> 4).join();
        ShardManager shards = new ShardManager();
        Shard plain = shards.create(instance);
        Shard domain = shards.create(instance, ExecutionDomain.create());
        FakePlayer a = FakePlayer.connect(instance, new Pos(1400.5, 66, 1400.5), "PlainMember");
        FakePlayer b = FakePlayer.connect(instance, new Pos(1400.5, 66, 1400.5), "DomainMember");
        Entity inPlain = new Entity(EntityType.ARMOR_STAND), inDomain = new Entity(EntityType.ARMOR_STAND);
        try {
            plain.join(a.player);
            domain.join(b.player);
            Point overlayPos = new Vec(1401, 65, 1401);
            plain.setBlock(overlayPos, Block.GOLD_BLOCK);
            domain.setBlock(overlayPos, Block.GOLD_BLOCK);

            for (int i = 0; i < 15; i++) {
                domain.leave(b.player);
                domain.join(b.player);
            }
            long overlayResends = b.sent(BlockChangePacket.class).stream()
                    .filter(p -> p.blockPosition().sameBlock(overlayPos) && p.blockStateId() == Block.GOLD_BLOCK.stateId())
                    .count();
            assertTrue(overlayResends >= 15, "every rejoin re-sent the overlay, got " + overlayResends);

            a.sent.clear();
            b.sent.clear();
            Point settle = new Vec(1402, 65, 1401);
            plain.setBlock(settle, Block.DIAMOND_BLOCK);
            domain.setBlock(settle, Block.DIAMOND_BLOCK);
            assertEquals(a.sent(BlockChangePacket.class), b.sent(BlockChangePacket.class),
                    "identical block wire after the churn");
            assertEquals(1, a.sent(BlockChangePacket.class).size());

            plain.spawn(inPlain, new Pos(1403.5, 66, 1400.5)).join();
            domain.spawn(inDomain, new Pos(1403.5, 66, 1400.5)).join();
            await("each member sees exactly its own shard's spawn", () ->
                    a.sent(SpawnEntityPacket.class).size() == 1 && b.sent(SpawnEntityPacket.class).size() == 1);
            assertEquals(inPlain.getEntityId(), a.sent(SpawnEntityPacket.class).get(0).entityId());
            assertEquals(inDomain.getEntityId(), b.sent(SpawnEntityPacket.class).get(0).entityId());
        } finally {
            inPlain.remove();
            inDomain.remove();
            shards.destroy(plain);
            shards.destroy(domain);
            a.player.remove();
            b.player.remove();
        }
    }
}
