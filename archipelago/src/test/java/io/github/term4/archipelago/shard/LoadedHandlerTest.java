package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A block loaded from disk carries a Dummy for its block entity; the rule registered for the block still runs. */
class LoadedHandlerTest extends HeadlessServerTest {

    private static final BlockHandler CONSUMES = new BlockHandler() {
        @Override public Key getKey() { return Block.LECTERN.key(); }
        @Override public boolean onInteract(Interaction interaction) { return false; }
    };

    @BeforeAll
    static void rule() {
        MinecraftServer.getBlockManager().registerHandler(Block.LECTERN.key().asString(), () -> CONSUMES);
    }

    @Test
    void registeredRuleBeatsTheDummy() {
        assertSame(CONSUMES, BlockRouting.handlerOf(Block.LECTERN.withHandler(BlockHandler.Dummy.get("minecraft:lectern"))));
        assertSame(CONSUMES, BlockRouting.handlerOf(Block.LECTERN), "an overlay write attaches nothing");
        BlockHandler own = new BlockHandler() {
            @Override public Key getKey() { return Block.LECTERN.key(); }
        };
        assertSame(own, BlockRouting.handlerOf(Block.LECTERN.withHandler(own)), "a real attached handler stands");
        Block dummyOnly = Block.STONE.withHandler(BlockHandler.Dummy.get("minecraft:stone"));
        assertSame(dummyOnly.handler(), BlockRouting.handlerOf(dummyOnly), "no rule: the dummy stays");
    }

    @Test
    void aLoadedBlockStillConsumes() {
        instance.loadChunk(2500 >> 4, 2500 >> 4).join();
        FakePlayer placer = FakePlayer.connect(instance, new Pos(2500.5, 64, 2502.5), "LecternClicker");
        try {
            instance.setBlock(2500, 64, 2500, Block.LECTERN.withHandler(BlockHandler.Dummy.get("minecraft:lectern")));
            placer.player.setItemInMainHand(ItemStack.of(Material.STONE, 64));
            Shard.placementListener(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(2500, 64, 2500),
                    BlockFace.NORTH, 0.5f, 0.5f, 0f, false, false, 1), placer.player);
            assertTrue(instance.getBlock(2500, 64, 2499).air(), "the click was the block's use, not a placement");
        } finally {
            instance.setBlock(2500, 64, 2500, Block.AIR);
            placer.player.remove();
        }
    }
}
