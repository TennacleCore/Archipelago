package io.github.term4.shardkit;

import io.github.term4.worldaccess.WorldAccess;
import io.github.term4.worldaccess.WorldPolicy;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@link WorldPolicy} whose block-view test consults {@link ShardView}. Installed automatically by
 * {@link Shard#blockRouting()} when the policy is still the plain default; extend THIS (not {@code SAME_WORLD})
 * when overriding with shards in play.
 */
public interface ShardPolicy extends WorldPolicy {

    ShardPolicy DEFAULT = new ShardPolicy() {};

    @Override
    default boolean seesBlocksOf(@NotNull Player viewer, @NotNull WorldAccess world) {
        Shard worldBlocks = world instanceof Shard s ? s : null;
        return ShardView.of(viewer).blocks() == worldBlocks && viewer.getInstance() == world.instance();
    }
}
