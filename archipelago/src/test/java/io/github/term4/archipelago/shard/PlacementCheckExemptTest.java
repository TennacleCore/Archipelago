package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.8 runs no placement entity check for blocks with no collision box: an exempt block places into ANY body -
 * the placer's, another member's - while a non-exempt block still respects the overlap check.
 */
class PlacementCheckExemptTest extends HeadlessServerTest {

    private static ClientPlayerBlockPlacementPacket placeOnTopOf(int x, int y, int z) {
        return new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(x, y, z), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 1);
    }

    private static void place(FakePlayer placer, Material material, int x, int y, int z) {
        placer.player.setItemInHand(PlayerHand.MAIN, ItemStack.of(material, 64));
        MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(placer.player, placeOnTopOf(x, y, z)));
    }

    @Test
    void exemptBlocksSkipTheEntityCheckForEveryone() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        Shard shard = new Shard(instance);
        instance.loadChunk(2100 >> 4, 2100 >> 4).join();
        FakePlayer placer = FakePlayer.connect(instance, new Pos(2098.5, 64, 2100.5), "ExemptPlacer");
        FakePlayer victim = FakePlayer.connect(instance, new Pos(2100.5, 64, 2100.5), "ExemptVictim");
        FakePlayer victim2 = FakePlayer.connect(instance, new Pos(2102.5, 64, 2100.5), "ExemptVictim2");
        Shard.placementCheckExempt(b -> !b.blocksMotion());
        try {
            shard.join(placer.player);
            shard.join(victim.player);
            shard.join(victim2.player);

            place(placer, Material.LADDER, 2100, 63, 2100); // into the victim's feet cell
            assertTrue(shard.getBlock(new Vec(2100, 64, 2100)).compare(Block.LADDER),
                    "exempt block places through the overlapping member");

            place(placer, Material.STONE, 2102, 63, 2100); // the second victim's feet cell
            assertTrue(shard.getBlock(new Vec(2102, 64, 2100)).air(),
                    "a motion-blocking block still respects the overlap");
        } finally {
            Shard.placementCheckExempt(b -> false);
            placer.player.remove();
            victim.player.remove();
            victim2.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }
}
