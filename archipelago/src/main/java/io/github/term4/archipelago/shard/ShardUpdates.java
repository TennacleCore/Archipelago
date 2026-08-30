package io.github.term4.archipelago.shard;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import org.slf4j.LoggerFactory;

/**
 * 1.8 {@code applyPhysics} over a shard: a change notifies its six neighbors, and every neighbor a rule
 * actually changes becomes a fresh epicenter - no distance budget (the base pipeline's capped chain is not
 * vanilla; 1.8 chains are bounded only by changes drying up).
 */
final class ShardUpdates {

    /** 1.8 notify order ({@code World.applyPhysics}): west, east, down, up, north, south. */
    private static final BlockFace[] ORDER = {
            BlockFace.WEST, BlockFace.EAST, BlockFace.BOTTOM, BlockFace.TOP, BlockFace.NORTH, BlockFace.SOUTH
    };
    /** Vanilla's runaway backstop ({@code max-chained-neighbor-updates}): a rule cycle trips it instead of the stack. */
    private static final int CHANGE_CAP = 1_000_000;

    private ShardUpdates() {}

    static void applyPhysics(Shard shard, Point origin) {
        int[] budget = {CHANGE_CAP};
        notifyNeighbors(shard, origin, budget);
        if (budget[0] < 0) LoggerFactory.getLogger(ShardUpdates.class)
                .warn("block update cascade from {} exceeded {} changes - rule cycle?", origin, CHANGE_CAP);
    }

    private static void notifyNeighbors(Shard shard, Point pos, int[] budget) {
        BlockManager manager = MinecraftServer.getBlockManager();
        for (BlockFace face : ORDER) {
            var direction = face.toDirection();
            Point neighborPos = pos.add(direction.normalX(), direction.normalY(), direction.normalZ());
            if (!shard.instance().isChunkLoaded(neighborPos)) continue;
            Block neighbor = shard.getBlock(neighborPos);
            if (neighbor.air()) continue;
            BlockPlacementRule rule = manager.getBlockPlacementRule(neighbor);
            if (rule == null) continue;
            Block updated = rule.blockUpdate(new BlockPlacementRule.UpdateState(
                    new ShardBlockView(shard), neighborPos, neighbor, face.getOppositeFace()));
            if (updated == neighbor) continue; // upstream contract: same instance = unchanged
            if (--budget[0] < 0) return;
            shard.setBlock(neighborPos, updated);
            notifyNeighbors(shard, neighborPos, budget);
        }
    }
}
