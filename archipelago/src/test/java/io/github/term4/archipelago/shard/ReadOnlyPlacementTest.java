package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.play.WindowItemsPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A placement into a shard you only VIEW is swallowed. The client already predicted it - both the blocks and the
 * consumed stack - so the refusal has to resync the inventory too, or the block is gone from the hotbar.
 */
class ReadOnlyPlacementTest extends HeadlessServerTest {

    @Test
    void aSwallowedPlacementResyncsTheInventory() {
        var node = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(node);
        Shard viewed = new Shard(instance);
        FakePlayer spectator = FakePlayer.connect(instance, new Pos(2000.5, 64, 2000.5), "ReadOnlyObs");
        try {
            ShardView.set(spectator.player, ShardView.of(viewed)); // a view, not membership: read-only
            spectator.player.setItemInHand(PlayerHand.MAIN, ItemStack.of(Material.STONE, 64));
            spectator.sent.clear();

            var place = new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(2000, 63, 2000),
                    BlockFace.TOP, 0.5f, 1f, 0.5f, false, false, 1);
            var event = new PlayerPacketEvent(spectator.player, place);
            MinecraftServer.getGlobalEventHandler().call(event);

            assertTrue(event.isCancelled(), "a read-only view swallows the placement");
            assertFalse(spectator.sent(WindowItemsPacket.class).isEmpty(),
                    "the refusal must resync the inventory - the client already predicted the stack decrement");
            assertTrue(viewed.getBlock(new Vec(2000, 64, 2000)).air(), "and nothing was written");
        } finally {
            ShardView.set(spectator.player, null);
            spectator.player.remove();
            viewed.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(node);
        }
    }
}
