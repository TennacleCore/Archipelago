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
 * The per-body placement check is the app's: a substituted rule can exempt a block for every body (1.8
 * ladders) or trim the tested volume per body (1.8 stairs: blocked only by a body covering the target
 * cell's center - a centered self-place is refused, an edge overlap passes).
 */
class PlacementBodyCheckTest extends HeadlessServerTest {

    private static ClientPlayerBlockPlacementPacket placeOnTopOf(int x, int y, int z) {
        return new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(x, y, z), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 1);
    }

    private static void place(FakePlayer placer, Material material, int x, int y, int z) {
        placer.player.setItemInHand(PlayerHand.MAIN, ItemStack.of(material, 64));
        MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(placer.player, placeOnTopOf(x, y, z)));
    }

    @Test
    void bodyCheckGovernsExemptionsAndTheStairsCenterRule() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        Shard shard = new Shard(instance);
        instance.loadChunk(2100 >> 4, 2100 >> 4).join();
        FakePlayer placer = FakePlayer.connect(instance, new Pos(2098.5, 64, 2100.5), "BodyPlacer");
        FakePlayer victim = FakePlayer.connect(instance, new Pos(2100.5, 64, 2100.5), "BodyVictim");
        FakePlayer victim2 = FakePlayer.connect(instance, new Pos(2102.5, 64, 2100.5), "BodyVictim2");
        FakePlayer edger = FakePlayer.connect(instance, new Pos(2104.8, 64, 2100.5), "BodyEdger");
        Shard.placementBodyCheck((block, rel, box) -> {
            if (!block.blocksMotion()) return false; // 1.8: no collision box, no check - for anyone
            if (block.name().endsWith("_stairs")) {
                return rel.x() + box.minX() < 0.5 && rel.x() + box.maxX() > 0.5
                        && rel.y() + box.minY() < 0.5 && rel.y() + box.maxY() > 0.5
                        && rel.z() + box.minZ() < 0.5 && rel.z() + box.maxZ() > 0.5;
            }
            return block.collisionShape().intersectBox(rel, box);
        });
        try {
            shard.join(placer.player);
            shard.join(victim.player);
            shard.join(victim2.player);
            shard.join(edger.player);

            place(placer, Material.LADDER, 2100, 63, 2100); // into the victim's feet cell
            assertTrue(shard.getBlock(new Vec(2100, 64, 2100)).compare(Block.LADDER),
                    "exempt block places through the overlapping member");

            place(placer, Material.STONE, 2102, 63, 2100); // the second victim's feet cell
            assertTrue(shard.getBlock(new Vec(2102, 64, 2100)).air(),
                    "a motion-blocking block still respects the overlap");

            place(placer, Material.OAK_STAIRS, 2098, 63, 2100); // the placer's OWN centered feet cell
            assertTrue(shard.getBlock(new Vec(2098, 64, 2100)).air(),
                    "a centered self-place covers the center - refused");

            place(placer, Material.OAK_STAIRS, 2102, 63, 2100); // the centered second victim
            assertTrue(shard.getBlock(new Vec(2102, 64, 2100)).air(),
                    "a centered other body covers the center - refused");

            place(placer, Material.OAK_STAIRS, 2105, 63, 2100); // the edger clips 0.1 into this cell
            assertTrue(shard.getBlock(new Vec(2105, 64, 2100)).compare(Block.OAK_STAIRS),
                    "an edge overlap leaves the center free - the 1.8 clutch placement");
        } finally {
            Shard.placementBodyCheck((block, rel, box) -> block.collisionShape().intersectBox(rel, box));
            placer.player.remove();
            victim.player.remove();
            victim2.player.remove();
            edger.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }
}
