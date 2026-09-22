package io.github.term4.archipelago.shard;

import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * A shard's diff and what a write to it does. Reads fall through the chain to the base; writes land in this
 * layer's overlay and fan to everyone who can see the position.
 *
 * <p>The fan-out is the part worth reading. A shard write has no native broadcast, so it reaches this shard's
 * block viewers AND every descendant's - pruned wherever a descendant's own diff already masks the position,
 * which cuts off that whole subtree. That recursion is why {@link #fanBlock} and {@link #fanBatch} reach into
 * a child's overlay directly rather than going through its public reads.
 */
final class ShardBlocks {

    private final Shard shard;
    final Overlay overlay = new Overlay();
    private final List<Shard.BlockChangeHook> hooks = new CopyOnWriteArrayList<>();

    ShardBlocks(Shard shard) {
        this.shard = shard;
    }

    Runnable onChange(@NotNull Shard.BlockChangeHook hook) {
        hooks.add(hook);
        return () -> hooks.remove(hook);
    }

    private @Nullable Block preWrite(Point pos) {
        return hooks.isEmpty() ? null : getBlock(pos);
    }

    private void fireChange(Point pos, @Nullable Block previous, Block block) {
        if (previous == null || previous.equals(block)) return; // nobody listened, or a view no-op
        for (Shard.BlockChangeHook hook : hooks) hook.changed(pos, previous, block);
    }

    int size() {
        return overlay.size();
    }

    Set<Long> chunkKeys() {
        return overlay.chunkKeys();
    }

    boolean touches(int chunkX, int chunkZ) {
        return overlay.touches(chunkX, chunkZ);
    }

    void forEachDiff(@NotNull BiConsumer<Point, Block> consumer) {
        overlay.forEach(consumer);
    }

    @NotNull Block getBlock(int x, int y, int z) {
        Block b = overlay.at(x, y, z);
        if (b != null) return b;
        return shard.parent != null ? shard.parent.getBlock(x, y, z) : shard.base.getBlock(x, y, z);
    }

    @NotNull Block getBlock(int x, int y, int z, Block.Getter.@NotNull Condition condition) {
        Block b = overlay.at(x, y, z);
        if (b != null) return b;
        return shard.parent != null
                ? shard.parent.getBlock(x, y, z, condition) : shard.base.getBlock(x, y, z, condition);
    }

    @NotNull Block getBlock(@NotNull Point pos) {
        return getBlock(pos.blockX(), pos.blockY(), pos.blockZ());
    }

    /** This chain's own entry at the position, or {@code null} when the chain reads straight through to the base. */
    @Nullable Block overlaidAt(int x, int y, int z) {
        for (Shard s = shard; s != null; s = s.parent) {
            Block block = s.blocks.overlay.at(x, y, z);
            if (block != null) return block;
        }
        return null;
    }

    /** This chain's overlays in front of {@code bottom}, child-first. */
    Block.Getter overlaid(Block.Getter bottom) {
        return overlay.over(shard.parent != null ? shard.parent.blocks.overlaid(bottom) : bottom);
    }

    void setBlock(@NotNull Point pos, @NotNull Block block) {
        if (revertsToParent(pos, block)) {
            if (overlay.at(pos.blockX(), pos.blockY(), pos.blockZ()) == null) return; // total no-op
            Block previous = preWrite(pos);
            overlay.remove(pos);
            changed(pos, block);
            fireChange(pos, previous, block);
            return;
        }
        pinBlock(pos, block);
    }

    void clearBlock(@NotNull Point pos) {
        if (shard.base.isChunkLoaded(pos)) {
            shard.setBlock(pos, shard.parent != null ? shard.parent.getBlock(pos) : shard.base.getBlock(pos));
            return;
        }
        overlay.remove(pos);
    }

    void pinBlock(@NotNull Point pos, @NotNull Block block) {
        Block previous = preWrite(pos);
        boolean shown = shard.base.isChunkLoaded(pos) && block.equals(previous != null ? previous : getBlock(pos));
        overlay.write(pos, block);
        if (shown) return; // the pin lands, the wire stays quiet: a replay refold re-pins whole diffs
        changed(pos, block);
        fireChange(pos, previous, block);
    }

    void setBlocks(@NotNull Map<Point, Block> blocks) {
        shard.relay(() -> {
            Map<Point, Block> changed = new HashMap<>();
            blocks.forEach((pos, block) -> {
                boolean reverts = revertsToParent(pos, block);
                if (reverts && overlay.at(pos.blockX(), pos.blockY(), pos.blockZ()) == null) return; // total no-op
                Block previous = preWrite(pos);
                if (reverts) overlay.remove(pos);
                else overlay.write(pos, block);
                changed.put(pos, block);
                fireChange(pos, previous, block);
            });
            fanBatch(changed);
            Set<Long> sections = new HashSet<>();
            for (Point pos : changed.keySet()) {
                sections.add(ShardLighting.sectionKey(pos.blockX() >> 4, pos.blockY() >> 4, pos.blockZ() >> 4));
            }
            if (!sections.isEmpty()) shard.lightChanged(sections);
            return null;
        });
    }

    /** Reverts every overlaid position to the parent view and drops the diff. The caller owns the relay. */
    void resetDiff() {
        forEachDiff((pos, block) -> {
            if (!shard.base.isChunkLoaded(pos)) return; // never reached a client; nothing to revert
            Block revert = shard.parent != null ? shard.parent.getBlock(pos) : shard.base.getBlock(pos);
            // descendants saw this layer too, where unmasked
            BaseBlockFilter.ownSend(() -> fanBlock(pos, new BlockChangePacket(pos, revert)));
            fireChange(pos, hooks.isEmpty() ? null : block, revert); // the entry IS the pre-reset view
        });
        overlay.clear();
    }

    /** Drops entries the chain now reads through to anyway - what a commit to the base leaves behind. */
    void dropWhereBaseMatches(@NotNull Map<Point, Block> committed) {
        committed.forEach((pos, block) -> {
            if (revertsToParent(pos, block)) overlay.remove(pos);
        });
    }

    private void changed(Point pos, Block block) {
        BaseBlockFilter.ownSend(() -> fanBlock(pos, new BlockChangePacket(pos, block)));
        // no lighting anywhere below: skip the per-block section set and the descent
        if (shard.light == null && shard.children.isEmpty()) return;
        shard.lightChanged(Set.of(ShardLighting.sectionKey(pos.blockX() >> 4, pos.blockY() >> 4, pos.blockZ() >> 4)));
    }

    // a write equal to the parent view isn't a diff: place-then-break ends with an EMPTY overlay, not a
    // redundant explicit-air entry (unloaded chunks can't be compared - keep the entry)
    private boolean revertsToParent(Point pos, Block block) {
        if (!shard.base.isChunkLoaded(pos)) return false;
        return block.equals(shard.parent != null ? shard.parent.getBlock(pos) : shard.base.getBlock(pos));
    }

    // a shard write has no native broadcast: it reaches this shard's block viewers plus every descendant's,
    // pruned wherever a descendant's own diff masks the position (masking cuts off that whole subtree)
    private void fanBlock(Point pos, BlockChangePacket packet) {
        shard.sendToBlockViewers(packet);
        for (Shard child : shard.children) {
            if (child.blocks.overlay.at(pos.blockX(), pos.blockY(), pos.blockZ()) == null) {
                child.blocks.fanBlock(pos, packet);
            }
        }
    }

    // each level builds its own section packets (only if it has viewers) from the positions its diff doesn't mask
    private void fanBatch(Map<Point, Block> blocks) {
        if (blocks.isEmpty()) return;
        if (shard.hasBlockViewers()) sendSections(blocks);
        for (Shard child : shard.children) {
            Map<Point, Block> unmasked = new HashMap<>();
            blocks.forEach((pos, block) -> {
                if (child.blocks.overlay.at(pos.blockX(), pos.blockY(), pos.blockZ()) == null) unmasked.put(pos, block);
            });
            child.blocks.fanBatch(unmasked);
        }
    }

    private void sendSections(Map<Point, Block> blocks) {
        BaseBlockFilter.ownSend(() -> {
            for (MultiBlockChangePacket packet : ShardViewers.sectionPackets(blocks)) {
                shard.sendToBlockViewers(packet);
            }
        });
    }

    void collectChunk(int chunkX, int chunkZ, Map<Point, Block> out) {
        if (shard.parent != null) shard.parent.blocks.collectChunk(chunkX, chunkZ, out);
        overlay.forEachInChunk(chunkX, chunkZ, out::put);
    }

    /** This chain's overlay entries in the chunk as one effective view (child wins) - the chunk-load re-send. */
    void sendChunkOverlay(net.minestom.server.entity.Player player, int chunkX, int chunkZ) {
        Map<Point, Block> effective = new HashMap<>();
        if (shard.parent == null) overlay.forEachInChunk(chunkX, chunkZ, effective::put);
        else collectChunk(chunkX, chunkZ, effective);
        BaseBlockFilter.ownSend(() -> {
            for (MultiBlockChangePacket packet : ShardViewers.sectionPackets(effective)) player.sendPacket(packet);
        });
        ShardLighting l = shard.light;
        if (l != null) l.sendTo(player, chunkX, chunkZ);
    }
}
