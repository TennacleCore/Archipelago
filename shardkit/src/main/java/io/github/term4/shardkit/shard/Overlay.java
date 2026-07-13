package io.github.term4.shardkit.shard;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * A shard's block diff: chunk index -> (packed local pos -> block). The chunk grouping IS the chunk-load/
 * view-swap index, so untouched-chunk reads miss in one map hop.
 */
final class Overlay {

    private final Map<Long, ConcurrentHashMap<Integer, Block>> chunks = new ConcurrentHashMap<>();

    @Nullable Block at(int x, int y, int z) {
        Map<Integer, Block> chunk = chunks.get(CoordConversion.chunkIndex(x >> 4, z >> 4));
        return chunk != null ? chunk.get(local(x, y, z)) : null;
    }

    void write(Point pos, Block block) {
        chunks.computeIfAbsent(CoordConversion.chunkIndex(pos.blockX() >> 4, pos.blockZ() >> 4), k -> new ConcurrentHashMap<>())
                .put(local(pos.blockX(), pos.blockY(), pos.blockZ()), block);
    }

    // single-writer (the owner clock); readers tolerate the empty-chunk window
    void remove(Point pos) {
        long key = CoordConversion.chunkIndex(pos.blockX() >> 4, pos.blockZ() >> 4);
        ConcurrentHashMap<Integer, Block> chunk = chunks.get(key);
        if (chunk == null) return;
        chunk.remove(local(pos.blockX(), pos.blockY(), pos.blockZ()));
        if (chunk.isEmpty()) chunks.remove(key, chunk);
    }

    void forEach(BiConsumer<Point, Block> consumer) {
        chunks.forEach((chunkKey, blocks) -> blocks.forEach((local, block) ->
                consumer.accept(unpack(chunkKey, local), block)));
    }

    void forEachInChunk(int chunkX, int chunkZ, BiConsumer<Point, Block> consumer) {
        long key = CoordConversion.chunkIndex(chunkX, chunkZ);
        Map<Integer, Block> blocks = chunks.get(key);
        if (blocks != null) blocks.forEach((local, block) -> consumer.accept(unpack(key, local), block));
    }

    int size() {
        int size = 0;
        for (Map<Integer, Block> chunk : chunks.values()) size += chunk.size();
        return size;
    }

    Set<Long> chunkKeys() {
        return chunks.keySet();
    }

    boolean touches(int chunkX, int chunkZ) {
        return chunks.containsKey(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    void clear() {
        chunks.clear();
    }

    /** The base getter with this overlay in front. */
    Block.Getter over(Block.Getter base) {
        return (x, y, z, condition) -> {
            Block b = at(x, y, z);
            return b != null ? b : base.getBlock(x, y, z, condition);
        };
    }

    // chunk-local key: x | z<<4 | y<<8 (the arithmetic unshift keeps y's sign)
    private static int local(int x, int y, int z) {
        return (x & 15) | ((z & 15) << 4) | (y << 8);
    }

    private static Point unpack(long chunkIndex, int local) {
        int cx = CoordConversion.chunkIndexGetX(chunkIndex), cz = CoordConversion.chunkIndexGetZ(chunkIndex);
        return new Vec((cx << 4) + (local & 15), local >> 8, (cz << 4) + ((local >> 4) & 15));
    }
}
