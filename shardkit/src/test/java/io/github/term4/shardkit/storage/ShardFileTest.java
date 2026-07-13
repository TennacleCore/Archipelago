package io.github.term4.shardkit.storage;

import io.github.term4.shardkit.storage.DataKey;
import io.github.term4.shardkit.storage.polar.PolarShardCodec;
import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void pathSavesAtomically(@TempDir Path dir) throws IOException {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(3330, 70, 3300), Block.STONE);
        Path file = dir.resolve("game.shard");

        ShardFile.save(original, file);
        original.setBlock(new Vec(3331, 70, 3300), Block.OAK_PLANKS);
        ShardFile.save(original, file); // overwrite goes through the tmp+move path

        Shard restored = new Shard(instance);
        ShardFile.load(restored, file);
        assertEquals(original.diff(), restored.diff());
        assertTrue(Files.notExists(dir.resolve("game.shard.tmp")), "no tmp file left behind");
    }

    @Test
    void nbtAndHandlerBlocksRoundTrip() {
        CompoundBinaryTag nbt = CompoundBinaryTag.builder().putString("test:marker", "kept").build();
        BlockHandler handler = MinecraftServer.getBlockManager().getHandlerOrDummy("test:chest");
        Shard source = new Shard(instance);
        source.setBlock(new Vec(3320, 70, 3300), Block.CHEST.withNbt(nbt).withHandler(handler));
        source.setBlock(new Vec(3321, 70, 3300), Block.CHEST); // nbt-less twin shares the palette entry

        Shard restored = new Shard(instance);
        ShardFile.load(restored, ShardFile.save(source));

        assertEquals(nbt, restored.getBlock(3320, 70, 3300).nbt(), "block NBT rides the sparse detail section");
        assertEquals(handler, restored.getBlock(3320, 70, 3300).handler());
        assertNull(restored.getBlock(3321, 70, 3300).nbt(), "the plain twin stays plain");
        assertEquals(source.diff(), restored.diff());
    }

    @Test
    void skyOverridesRoundTrip() {
        Shard source = new Shard(instance);
        source.setTime(6000);
        source.setWeather(new Weather(0.6f, 0.2f));

        Shard restored = new Shard(instance);
        ShardFile.load(restored, ShardFile.save(source));

        assertEquals(6000, restored.timeOverride());
        assertEquals(new Weather(0.6f, 0.2f), restored.weatherOverride());
    }

    @Test
    void treeSaveLoadRoundTrips() {
        Shard root = new Shard(instance);
        Point a = new Vec(4000, 70, 4000), b = new Vec(4001, 70, 4000), c = new Vec(4002, 70, 4000);
        root.setBlock(a, Block.GOLD_BLOCK);
        root.setTime(1000);
        Shard mid = new Shard(root);
        mid.setBlock(a, Block.AIR); // explicit-air mask of the root block
        mid.setBlock(b, Block.EMERALD_BLOCK);
        mid.setWeather(new Weather(0.8f, 0.3f));
        Shard leaf = new Shard(mid);
        leaf.setBlock(c, Block.DIAMOND_BLOCK);

        byte[] tree = ShardFile.saveTree(root,
                s -> s == root ? "base" : s == mid ? "mid" : "leaf",
                (s, w) -> w.put(ROUND, s == root ? 0 : s == mid ? 1 : 2));

        List<ShardFile.TreeLayer> loaded = ShardFile.loadTree(instance, tree);
        assertEquals(3, loaded.size());
        Shard r2 = loaded.get(0).shard(), m2 = loaded.get(1).shard(), l2 = loaded.get(2).shard();
        assertSame(r2, m2.parent());
        assertSame(m2, l2.parent());
        assertEquals(root.diff(), r2.diff());
        assertEquals(mid.diff(), m2.diff(), "the explicit-air mask survives");
        assertTrue(m2.getBlock(a).isAir(), "the mask still masks the ancestor block");
        assertEquals(Block.GOLD_BLOCK, r2.getBlock(a));
        assertEquals(leaf.diff(), l2.diff());
        assertEquals(1000, r2.timeOverride());
        assertEquals(new Weather(0.8f, 0.3f), m2.weatherOverride());
        assertEquals(-1, m2.timeOverride(), "no phantom overrides");
        assertEquals(1, loaded.get(1).data().get(ROUND));
        assertEquals("mid", loaded.get(1).data().get(ShardFile.NAME));
    }

    @Test
    void splitJoinRoundTripsByteExact() {
        Shard root = new Shard(instance);
        root.setBlock(new Vec(4010, 70, 4000), Block.STONE);
        Shard child = new Shard(root);
        child.setBlock(new Vec(4011, 70, 4000), Block.OAK_PLANKS);
        byte[] tree = ShardFile.saveTree(root, s -> s == root ? "base" : "game", null);

        List<byte[]> parts = ShardFile.split(tree);
        assertEquals(2, parts.size());
        // a child file alone loads under its (freshly loaded) parent - the chain recipe
        Shard rootTwin = new Shard(instance);
        ShardFile.load(rootTwin, parts.get(0));
        Shard childTwin = new Shard(rootTwin);
        ShardFile.UserDataView view = ShardFile.load(childTwin, parts.get(1));
        assertEquals(child.diff(), childTwin.diff());
        assertEquals("base", view.get(ShardFile.PARENT), "split filled the parent hint");
        assertArrayEquals(tree, ShardFile.join(parts), "join is split's byte-exact inverse");

        // file order (and file NAMES, which never enter the format) don't matter - hints resolve the tree
        List<ShardFile.TreeLayer> shuffled = ShardFile.loadTree(instance,
                ShardFile.join(List.of(parts.get(1), parts.get(0))));
        assertEquals(2, shuffled.size());
        assertEquals("base", shuffled.get(0).data().get(ShardFile.NAME), "the hintless file is the root");
        assertEquals(child.diff(), shuffled.get(1).shard().diff());
    }

    @Test
    void offlinePipelineMatchesTheRuntimeFlatten() {
        Shard root = new Shard(instance);
        Point a = new Vec(4020, 70, 4000), b = new Vec(4021, 70, 4000);
        root.setBlock(a, Block.GOLD_BLOCK);
        root.setBlock(b, Block.STONE);
        root.setTime(2000);
        Shard mid = new Shard(root);
        mid.setBlock(a, Block.AIR);
        Shard leaf = new Shard(mid);
        leaf.setBlock(b, Block.DIAMOND_BLOCK);
        leaf.setTime(9000);
        byte[] tree = ShardFile.saveTree(root, s -> s == root ? "base" : s == mid ? "mid" : "leaf",
                (s, w) -> { if (s == root) w.put(ROUND, 0); if (s == leaf) w.put(ROUND, 2); });

        // the offline pipeline: polar(tree) -> shard(tree) -> flatten -> flat shard -> polar
        byte[] shardTree = PolarShardCodec.toShardFile(PolarShardCodec.fromShardFile(tree));
        assertArrayEquals(tree, shardTree, "the tree section tunnels through polar byte-exact");
        byte[] flat = ShardFile.flattenChain(shardTree);
        PolarShardCodec.fromShardFile(flat); // the last hop parses clean

        Shard offline = new Shard(instance);
        ShardFile.UserDataView user = ShardFile.load(offline, flat);
        Shard runtime = leaf.flatten();
        assertEquals(runtime.diff(), offline.diff(), "file-level flatten equals the runtime flatten's diff");
        assertEquals(runtime.timeOverride(), offline.timeOverride());
        assertEquals(2, user.get(ROUND), "user sections fold leaf-wins-per-key");
        assertEquals("leaf", user.get(ShardFile.NAME));
    }

    @Test
    void rollbackChainUndoesACommitRunAsOneOverlay() {
        instance.loadChunk(3300 >> 4, 3300 >> 4).join();
        Point built = new Vec(3304, 70, 3300), carved = new Vec(3305, 63, 3300), extra = new Vec(3306, 70, 3300);
        // commit 1: build over air, carve a base block - then land it on the world
        Shard c1 = new Shard(instance);
        c1.setBlock(built, Block.GOLD_BLOCK);
        c1.setBlock(carved, Block.AIR);
        byte[] f1 = ShardFile.save(c1, null, true);
        instance.setBlock(built, Block.GOLD_BLOCK);
        instance.setBlock(carved, Block.AIR);
        // commit 2: overwrite commit 1's build, add more
        Shard c2 = new Shard(instance);
        c2.setBlock(built, Block.EMERALD_BLOCK);
        c2.setBlock(extra, Block.IRON_BLOCK);
        byte[] f2 = ShardFile.save(c2, null, true);
        instance.setBlock(built, Block.EMERALD_BLOCK);
        instance.setBlock(extra, Block.IRON_BLOCK);

        Shard view = new Shard(instance);
        ShardFile.load(view, ShardFile.rollbackChain(List.of(f1, f2)));
        assertTrue(view.getBlock(built).isAir(), "the OLDEST before wins - the pre-run state");
        assertEquals(Block.STONE, view.getBlock(carved), "the carved base block restores");
        assertTrue(view.getBlock(extra).isAir());
        assertEquals(3, view.overlaySize(), "ten commits view as ONE overlay, not ten layers");
    }

    @Test
    void branchingTreesFlattenPerLeaf() {
        Shard root = new Shard(instance);
        Point p = new Vec(4030, 70, 4000);
        root.setBlock(p, Block.STONE);
        Shard left = new Shard(root);
        left.setBlock(p, Block.EMERALD_BLOCK);
        Shard right = new Shard(root);
        right.setBlock(p, Block.DIAMOND_BLOCK);
        byte[] tree = ShardFile.saveTree(root, null, null);

        assertThrows(IllegalArgumentException.class, () -> ShardFile.flattenChain(tree), "branches never merge");
        Shard flatLeft = new Shard(instance);
        ShardFile.load(flatLeft, ShardFile.flattenChain(tree, 1));
        assertEquals(Block.EMERALD_BLOCK, flatLeft.getBlock(p));
    }
}
