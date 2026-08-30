package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-body placement check is the app's: with a Paper-1.8-style rule the placer's own body never blocks
 * (a self-overlapping stair lands), no-collision-box blocks skip every body, and other bodies stay precise.
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
    void placerExclusionAndPassableSkipRideTheBodyCheck() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        Shard shard = new Shard(instance);
        instance.loadChunk(2100 >> 4, 2100 >> 4).join();
        FakePlayer placer = FakePlayer.connect(instance, new Pos(2098.5, 64, 2100.5), "BodyPlacer");
        FakePlayer victim = FakePlayer.connect(instance, new Pos(2100.5, 64, 2100.5), "BodyVictim");
        FakePlayer victim2 = FakePlayer.connect(instance, new Pos(2102.5, 64, 2100.5), "BodyVictim2");
        // the app-policy layer: a Hypixel-style anticheat is a plain PlayerBlockPlaceEvent cancel
        EventNode<Event> veto = EventNode.all("test:self-place-veto");
        veto.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (e.getBlock().collisionShape().intersectBox(
                    e.getPlayer().getPosition().sub(e.getBlockPosition()), e.getPlayer().getBoundingBox())) {
                e.setCancelled(true);
            }
        });
        Shard.placementBodyCheck((actor, body, block, rel, box) -> {
            if (body == actor) return false;        // Paper 1.8: the placer is excluded outright
            if (!block.blocksMotion()) return false; // no collision box: no check for anyone
            return block.collisionShape().intersectBox(rel, box);
        });
        try {
            shard.join(placer.player);
            shard.join(victim.player);
            shard.join(victim2.player);

            place(placer, Material.OAK_STAIRS, 2098, 63, 2100); // the placer's OWN feet cell
            assertTrue(shard.getBlock(new Vec(2098, 64, 2100)).compare(Block.OAK_STAIRS),
                    "the placer's own body never blocks - the 1.8 self-place");

            place(placer, Material.LADDER, 2100, 63, 2100); // into the victim's feet cell
            assertTrue(shard.getBlock(new Vec(2100, 64, 2100)).compare(Block.LADDER),
                    "no-collision-box block places through the overlapping member");

            place(placer, Material.STONE, 2102, 63, 2100); // the second victim's feet cell
            assertTrue(shard.getBlock(new Vec(2102, 64, 2100)).air(),
                    "another body still blocks a motion-blocking placement");

            MinecraftServer.getGlobalEventHandler().addChild(veto);
            place(placer, Material.OAK_STAIRS, 2098, 64, 2100); // clicked = the placed stair, target = the placer's face
            assertTrue(shard.getBlock(new Vec(2098, 65, 2100)).air(),
                    "the app's event veto refuses what the mechanic allows");
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(veto);
            Shard.placementBodyCheck((actor, body, block, rel, box) -> block.collisionShape().intersectBox(rel, box));
            placer.player.remove();
            victim.player.remove();
            victim2.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }
}
