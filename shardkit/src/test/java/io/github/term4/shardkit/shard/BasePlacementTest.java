package io.github.term4.shardkit.shard;

import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world-scoped base placement listener: a co-located entity from another world is invisible to the placer
 * and never blocks their placement; a same-world entity still does (with the stock targeted rollback).
 */
class BasePlacementTest extends HeadlessServerTest {

    private static ClientPlayerBlockPlacementPacket placeOnTopOf(int x, int y, int z) {
        return new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(x, y, z), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 1);
    }

    @Test
    void coLocatedOtherWorldEntitiesNeverBlockAPlacersBlock() {
        instance.loadChunk(1900 >> 4, 1900 >> 4).join();
        Shard shard = new Shard(instance);
        LivingEntity ghost = new LivingEntity(EntityType.ZOMBIE);
        LivingEntity local = new LivingEntity(EntityType.ZOMBIE);
        FakePlayer placer = FakePlayer.connect(instance, new Pos(1908.5, 64, 1900.5), "BasePlacer");
        try {
            placer.player.setItemInMainHand(ItemStack.of(Material.STONE, 64));
            shard.spawn(ghost, new Pos(1900.5, 64, 1900.5)).join(); // another world, same coordinates

            Shard.placementListener(placeOnTopOf(1900, 63, 1900), placer.player);
            assertEquals(Block.STONE, instance.getBlock(1900, 64, 1900),
                    "an entity the placer's world can't affect never blocks the placement");

            local.setInstance(instance, new Pos(1902.5, 64, 1900.5)).join(); // the placer's own (base) world
            placer.sent.clear();
            Shard.placementListener(placeOnTopOf(1902, 63, 1900), placer.player);
            assertTrue(instance.getBlock(1902, 64, 1900).isAir(), "a same-world entity still blocks it");
            assertTrue(placer.sent(BlockChangePacket.class).stream()
                            .anyMatch(p -> p.blockPosition().sameBlock(new Vec(1902, 64, 1900))),
                    "with the stock targeted rollback, not a chunk resend");
        } finally {
            ghost.remove();
            local.remove();
            placer.player.remove();
        }
    }
}
