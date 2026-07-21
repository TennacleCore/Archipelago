package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.world.WorldAccess;
import io.github.term4.archipelago.world.WorldPolicy;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@link WorldPolicy} whose entity- and block-view tests consult {@link ShardView}. Installed automatically by
 * {@link Shard#blockRouting()} when the policy is still the plain default; extend THIS (not {@code SAME_WORLD})
 * when overriding with shards in play.
 */
public interface ShardPolicy extends WorldPolicy {

    ShardPolicy DEFAULT = new ShardPolicy() {};

    @Override
    default boolean sees(@NotNull Player viewer, @NotNull Entity subject) {
        return ShardView.of(viewer).seesEntitiesOf(subject.getTag(WorldAccess.ENTITY_TAG));
    }

    @Override
    default boolean seesBlocksOf(@NotNull Player viewer, @NotNull WorldAccess world) {
        Shard worldBlocks = world instanceof Shard s ? s : null;
        return ShardView.of(viewer).blocks() == worldBlocks && viewer.getInstance() == world.instance();
    }
}
