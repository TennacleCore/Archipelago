package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A multi-block placement rule (a bed's head, a door's top) writes its other half as it answers, and both paths
 * ask it for the shape before the body check and the event. Those writes are staged: they land with the
 * placement, never before it - and a rule asked twice must not trip over the half its own first answer wrote.
 */
class StagedPlacementTest extends HeadlessServerTest {

    private static final Block PAIRED = Block.RED_BED;

    @BeforeAll
    static void rule() {
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new BlockPlacementRule(PAIRED) {
            @Override
            public Block blockPlace(PlacementState state) {
                Point other = state.placePosition().add(1, 0, 0);
                if (!state.instance().getBlock(other).registry().isReplaceable()) return null;
                ((Block.Setter) state.instance()).setBlock(other, PAIRED.withProperty("part", "head"));
                return PAIRED.withProperty("part", "foot");
            }
        });
    }

    private static ClientPlayerBlockPlacementPacket onTopOf(int x, int y, int z) {
        return new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(x, y, z), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 1);
    }

    // the stock listener slot, where a player bound to no shard lands
    private static void placeOnBase(FakePlayer placer, int x, int y, int z) {
        placer.player.setItemInHand(PlayerHand.MAIN, ItemStack.of(Material.RED_BED, 64));
        Shard.placementListener(onTopOf(x, y, z), placer.player);
    }

    private static void placeInShard(FakePlayer placer, int x, int y, int z) {
        placer.player.setItemInHand(PlayerHand.MAIN, ItemStack.of(Material.RED_BED, 64));
        MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(placer.player, onTopOf(x, y, z)));
    }

    @Test
    void bothHalvesLandTogether() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        instance.loadChunk(2300 >> 4, 2300 >> 4).join();
        Shard shard = new Shard(instance);
        FakePlayer base = FakePlayer.connect(instance, new Pos(2290.5, 64, 2300.5), "StagedBase");
        FakePlayer bound = FakePlayer.connect(instance, new Pos(2291.5, 64, 2300.5), "StagedShard");
        LivingEntity body = new LivingEntity(EntityType.ZOMBIE);
        try {
            placeOnBase(base, 2300, 63, 2300); // the base pipeline: placeBlock runs the rule for real
            assertTrue(instance.getBlock(2300, 64, 2300).compare(PAIRED), "the foot landed");
            assertTrue(instance.getBlock(2301, 64, 2300).compare(PAIRED), "and the rule's own half with it");

            body.setInstance(instance, new Pos(2304.5, 64, 2300.5)).join();
            placeOnBase(base, 2304, 63, 2300); // refused by the body in the target cell
            assertTrue(instance.getBlock(2304, 64, 2300).air(), "the body refused it");
            assertTrue(instance.getBlock(2305, 64, 2300).air(), "and the rule's half never landed either");

            shard.join(bound.player);
            placeInShard(bound, 2308, 63, 2300); // the shard pipeline
            assertTrue(shard.getBlock(new Vec(2308, 64, 2300)).compare(PAIRED), "the foot landed in the shard");
            assertTrue(shard.getBlock(new Vec(2309, 64, 2300)).compare(PAIRED), "and its half beside it");
            assertTrue(instance.getBlock(2309, 64, 2300).air(), "neither half leaked into the base map");
        } finally {
            body.remove();
            base.player.remove();
            bound.player.remove();
            shard.reset();
            for (int x = 2300; x <= 2310; x++) instance.setBlock(x, 64, 2300, Block.AIR);
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }
}
