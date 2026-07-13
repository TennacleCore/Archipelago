package io.github.term4.shardkit.storage;

import io.github.term4.shardkit.storage.DataKey;
import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bare-minimum format: diff + optional before column + DataKey user data, gzip'd NBT. */
class ShardFileTest extends HeadlessServerTest {

    private static final DataKey<Integer> ROUND = new DataKey<>("test:round", Codec.INT);

    @Test
    void roundTripsDiffUserDataAndBefore() {
        instance.loadChunk(3300 >> 4, 3300 >> 4).join();
        Shard source = new Shard(instance);
        Point buried = new Vec(3301, 30, 3300), aired = new Vec(3302, 70, 3300);
        source.setBlock(buried, Block.GOLD_BLOCK);   // over base stone
        source.setBlock(aired, Block.DIAMOND_BLOCK); // over base air

        byte[] file = ShardFile.save(source, w -> w.put(ROUND, 7), true);

        Shard target = new Shard(instance);
        ShardFile.UserDataView view = ShardFile.load(target, file);
        assertEquals(Block.GOLD_BLOCK, target.getBlock(buried));
        assertEquals(Block.DIAMOND_BLOCK, target.getBlock(aired));
        assertEquals(7, view.get(ROUND));

        Map<Point, Block> before = ShardFile.before(file);
        assertEquals(Block.STONE, before.get(buried), "the before column carries what the diff covered");
        assertTrue(before.get(aired).isAir());
    }

    @Test
    void withoutBeforeTheColumnIsAbsent() {
        Shard source = new Shard(instance);
        source.setBlock(new Vec(3310, 70, 3300), Block.EMERALD_BLOCK);
        assertTrue(ShardFile.before(ShardFile.save(source)).isEmpty());
    }

    @Test
    void aCorruptFileFailsLoudAndTouchesNothing() {
        Shard target = new Shard(instance);
        assertThrows(UncheckedIOException.class, () -> ShardFile.load(target, new byte[]{1, 2, 3}));
        assertEquals(0, target.overlaySize());
    }
}
