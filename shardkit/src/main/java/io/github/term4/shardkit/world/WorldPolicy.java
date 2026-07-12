package io.github.term4.shardkit.world;

import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Cross-world gameplay rules, globally pluggable. The default gates everything to the actor's own world;
 * override to open specific reaches (staff hitting/building into an observed game). Views govern what a player
 * SEES; this governs what they DO.
 */
public interface WorldPolicy {

    WorldPolicy SAME_WORLD = new WorldPolicy() {};

    /**
     * Whether {@code actor}'s gameplay effects reach {@code target} (melee, projectiles, splash, pushes, pickup,
     * merging). {@code actor} = the acting ENTITY, not its shooter; an actor may legitimately be world-less.
     */
    default boolean affects(@NotNull Entity actor, @NotNull Entity target) {
        return actor.getTag(WorldAccess.ENTITY_TAG) == target.getTag(WorldAccess.ENTITY_TAG);
    }

    /** Whether {@code viewer}'s client renders {@code subject} - the per-viewer entity filter (view layers re-point it). */
    default boolean sees(@NotNull Player viewer, @NotNull Entity subject) {
        return viewer.getTag(WorldAccess.ENTITY_TAG) == subject.getTag(WorldAccess.ENTITY_TAG);
    }

    /** Whether {@code player} may edit blocks of a world they view without belonging to it (staff build mode). */
    default boolean edits(@NotNull Player player, @NotNull WorldAccess viewed) {
        return false;
    }

    /** Whether {@code viewer}'s client renders {@code world}'s BLOCKS - the filter for block-anchored FX. */
    default boolean seesBlocksOf(@NotNull Player viewer, @NotNull WorldAccess world) {
        return viewer.getTag(WorldAccess.ENTITY_TAG) == null && viewer.getInstance() == world.instance();
    }

    static boolean canAffect(@NotNull Entity actor, @NotNull Entity target) {
        return Holder.POLICY.affects(actor, target);
    }

    static boolean canSee(@NotNull Player viewer, @NotNull Entity subject) {
        return Holder.POLICY.sees(viewer, subject);
    }

    static boolean canEdit(@NotNull Player player, @NotNull WorldAccess viewed) {
        return Holder.POLICY.edits(player, viewed);
    }

    static boolean seesBlocks(@NotNull Player viewer, @NotNull WorldAccess world) {
        return Holder.POLICY.seesBlocksOf(viewer, world);
    }

    static @NotNull WorldPolicy get() { return Holder.POLICY; }

    /** Replaces the rules server-wide. */
    static void set(@NotNull WorldPolicy policy) { Holder.POLICY = policy; }

    final class Holder {
        private static volatile WorldPolicy POLICY = SAME_WORLD;
        private Holder() {}
    }
}
