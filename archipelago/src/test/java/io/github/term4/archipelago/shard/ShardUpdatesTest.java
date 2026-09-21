package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
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
 * Neighbor rules run over the shard view and every change cascades fresh: a column taller than the base
 * pipeline's capped chain still collapses whole.
 */
class ShardUpdatesTest extends HeadlessServerTest {

    /** Mini-1.8 cactus: pops without cactus/sand below or beside any solid horizontal neighbor. */
    private static final class PillarRule extends BlockPlacementRule {
        PillarRule() { super(Block.CACTUS); }

        @Override public Block blockPlace(PlacementState state) { return state.block(); }

        @Override
        public Block blockUpdate(UpdateState state) {
            Point pos = state.blockPosition();
            Block below = state.instance().getBlock(pos.add(0, -1, 0));
            if (!below.compare(Block.CACTUS) && !below.compare(Block.SAND)) return Block.AIR;
            for (BlockFace face : new BlockFace[]{BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH}) {
                var d = face.toDirection();
                if (state.instance().getBlock(pos.add(d.normalX(), 0, d.normalZ())).isSolid()) return Block.AIR;
            }
            return state.currentBlock();
        }
    }

    @BeforeAll
    static void registerRule() {
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new PillarRule());
    }

    @Test
    void aTallColumnCollapsesWhole() {
        instance.loadChunk(2210 >> 4, 2210 >> 4).join();
        Shard shard = new Shard(instance);
        try {
            shard.setBlock(new Vec(2210, 63, 2210), Block.SAND);
            for (int y = 64; y < 79; y++) shard.setBlock(new Vec(2210, y, 2210), Block.CACTUS); // 15 > the base cap of 10

            shard.setBlock(new Vec(2210, 63, 2210), Block.AIR);
            shard.applyPhysics(new Vec(2210, 63, 2210));

            for (int y = 64; y < 79; y++) {
                assertTrue(shard.getBlock(new Vec(2210, y, 2210)).air(), "column still standing at y=" + y);
            }
        } finally {
            shard.reset();
        }
    }

    @Test
    void aRoutedPlacementFiresNeighborPhysics() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        instance.loadChunk(2220 >> 4, 2220 >> 4).join();
        Shard shard = new Shard(instance);
        FakePlayer placer = FakePlayer.connect(instance, new Pos(2217.5, 64, 2220.5), "PhysicsPlacer");
        try {
            shard.join(placer.player);
            shard.setBlock(new Vec(2220, 63, 2220), Block.SAND);
            shard.setBlock(new Vec(2220, 64, 2220), Block.CACTUS);

            placer.player.setItemInHand(PlayerHand.MAIN, ItemStack.of(Material.STONE, 64));
            var place = new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(2221, 63, 2220),
                    BlockFace.TOP, 0.5f, 1f, 0.5f, false, false, 1);
            MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(placer.player, place));

            assertTrue(shard.getBlock(new Vec(2221, 64, 2220)).compare(Block.STONE), "the placement landed");
            assertTrue(shard.getBlock(new Vec(2220, 64, 2220)).air(), "the new solid neighbor popped the pillar");
        } finally {
            placer.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }
}
