package io.github.term4.archipelago.shard;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A shard's lighting system, installed with {@link Shard#relight(ShardLighting)}. {@code null} (the default)
 * means none - viewers keep the base instance's light, exactly like an unmanaged instance;
 * {@link Shard#relight(boolean)} installs the built-in chain-relight implementation.
 *
 * <p>The shard drives the hooks: {@link #changed} on every write to the shard's view (writer thread),
 * {@link #refreshLit} after a reset or ancestor churn, {@link #sendTo} when a viewer loads a chunk, and
 * {@link #close} on replacement, disable, or destroy - close must restore base light on every lit client.
 * The view system reads {@link #packet}, {@link #litChunks}, {@link #candidateChunks} and calls
 * {@link #markLit} to route light through block-view swaps.
 */
public interface ShardLighting {

    /** Blocks changed in {@code chunks} (chunk indexes, this shard's view). */
    void changed(Set<Long> chunks);

    /** The diff was reset (or an ancestor layer churned): previously lit chunks must re-evaluate. */
    void refreshLit();

    /** A viewer loaded {@code chunkX, chunkZ}: send this shard's light for it, if any. */
    void sendTo(Player player, int chunkX, int chunkZ);

    /** Uninstalled (swap, disable, destroy): restore base light on every lit client and stop all work. */
    void close();

    /** The current light packet for one chunk, or {@code null} where this system has none (yet). */
    @Nullable SendablePacket packet(int chunkX, int chunkZ);

    /** Chunks whose light reached at least one client - the restore set for view swaps. */
    Set<Long> litChunks();

    /** Records that {@code chunkIndex}'s light reached a client (a view swap delivered {@link #packet}). */
    void markLit(long chunkIndex);

    /** Loaded chunks where this system's light can differ from base. */
    Set<Long> candidateChunks();

    /** This system's block light at the position, or {@code -1} where it has no data (the shard reads base). */
    default int blockLight(int x, int y, int z) {
        return -1;
    }

    /** This system's sky exposure at the position, or {@code -1} where it has no data (the shard reads base). */
    default int skyLight(int x, int y, int z) {
        return -1;
    }

    /** The chunk columns the chain's diff touches (this shard + ancestors) - the natural seed candidate set. */
    static Set<Long> chainChunks(Shard shard) {
        Set<Long> chunks = new HashSet<>();
        for (Shard s = shard; s != null; s = s.parent()) chunks.addAll(s.overlayChunks());
        return chunks;
    }

    /** Sends {@code packet} to every viewer whose block view is {@code shard}. */
    static void broadcast(Shard shard, SendablePacket packet) {
        shard.sendToBlockViewers(packet);
    }

    /**
     * Reverts one chunk to base light for every block viewer - a full chunk resend, then the chain's blocks
     * re-sent over it (the full packet carries BASE blocks). The standard {@link #close()} restore; no-op for
     * unloaded chunks.
     */
    static void restoreBase(Shard shard, long chunkIndex) {
        int chunkX = CoordConversion.chunkIndexGetX(chunkIndex), chunkZ = CoordConversion.chunkIndexGetZ(chunkIndex);
        Chunk chunk = shard.instance().getChunk(chunkX, chunkZ);
        if (chunk == null) return;
        shard.sendToBlockViewers(chunk.getFullDataPacket());
        Map<Point, Block> diff = new HashMap<>();
        shard.collectChunk(chunkX, chunkZ, diff);
        diff.forEach((pos, block) -> shard.sendToBlockViewers(new BlockChangePacket(pos, block)));
    }

    /**
     * A viewer's block view swapped {@code from -> to}: the new view's light replaces the old's wherever either
     * side lit, and chunks only the old side lit fall back to the base chunk (full resend restores base light).
     * Returns the full-resent chunk indexes - those packets carry BASE blocks, so the caller must re-send the
     * destination view's diffs there afterwards.
     */
    static Set<Long> onViewSwap(Player player, @Nullable Shard from, @Nullable Shard to) {
        ShardLighting fromLight = from != null ? from.light() : null;
        ShardLighting toLight = to != null ? to.light() : null;
        if (fromLight == null && toLight == null) return Set.of();
        Set<Long> chunks = new HashSet<>();
        if (fromLight != null) chunks.addAll(fromLight.litChunks());
        if (toLight != null) chunks.addAll(toLight.candidateChunks());
        Instance base = (to != null ? to : from).instance();
        Set<Long> resent = new HashSet<>();
        for (long index : chunks) {
            int chunkX = CoordConversion.chunkIndexGetX(index), chunkZ = CoordConversion.chunkIndexGetZ(index);
            SendablePacket packet = toLight != null ? toLight.packet(chunkX, chunkZ) : null;
            if (packet != null) {
                player.sendPacket(packet);
                toLight.markLit(index);
            } else if (fromLight != null && fromLight.litChunks().contains(index)) {
                Chunk chunk = base.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    player.sendPacket(chunk.getFullDataPacket());
                    resent.add(index);
                }
            }
        }
        return resent;
    }
}
