package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base instance broadcasts its writes to every chunk viewer, a shard's members included. A position the
 * viewer's chain masks must not be repainted by it: the mask is what the shard is for.
 */
class BaseWriteMaskTest extends HeadlessServerTest {

    private static boolean reaches(FakePlayer viewer, Point pos, Block block) {
        PlayerPacketOutEvent out = new PlayerPacketOutEvent(viewer.player, new BlockChangePacket(pos, block));
        EventDispatcher.call(out);
        return !out.isCancelled();
    }

    @Test
    void aBaseWriteDoesNotPaintOverAMask() {
        Shard shard = new Shard(instance);
        Point broken = new Vec(1901, 70, 1900);
        Point open = new Vec(1902, 70, 1900);
        FakePlayer member = FakePlayer.connect(instance, new Pos(1900.5, 71, 1900.5), "MaskMember");
        FakePlayer bystander = FakePlayer.connect(instance, new Pos(1900.5, 71, 1902.5), "MaskBystander");
        try {
            instance.setBlock(broken, Block.STONE);
            shard.join(member.player);
            shard.setBlock(broken, Block.AIR); // this shard broke it; the base still has it

            assertFalse(reaches(member, broken, Block.DIAMOND_BLOCK), "a base write under the mask is dropped");
            assertTrue(reaches(member, open, Block.DIAMOND_BLOCK), "an unmasked one still reaches them");
            assertTrue(reaches(bystander, broken, Block.DIAMOND_BLOCK), "a base viewer sees the base");
            assertTrue(shard.getBlock(broken).isAir(), "and the chain still reads broken");
        } finally {
            instance.setBlock(broken, Block.AIR);
            member.player.remove();
            bystander.player.remove();
        }
    }
}
