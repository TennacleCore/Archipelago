package io.github.term4.archipelago.shard;

import io.github.term4.polyp.world.MechanicsWorld;
import java.util.function.Predicate;
import java.util.HashMap;
import java.util.Map;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a player SEES of the sharded world: one block view (a shard's overlay or the base map) plus the set of
 * shards whose entities are visible. Purely observational - targeting, broadcasts and editing follow
 * {@link Shard#join membership}. Immutable; compose from {@link #base()}/{@link #of(Shard)} and apply with
 * {@link #set}; members get their shard's view implicitly.
 */
public final class ShardView {

    private static final Tag<ShardView> TAG = Tag.Transient("archipelago:view");
    /** Observers whose view has {@code allEntities} - watchers of every shard. */
    private static final Set<Player> ALL_SEEING = ConcurrentHashMap.newKeySet();

    /** The default entity filter: everything the shard holds. */
    public static final Predicate<Entity> ALL_ENTITIES = e -> true;

    private static final ShardView BASE = new ShardView(null, false, ALL_ENTITIES, Map.of());

    private final @Nullable Shard blocks;
    private final boolean allEntities;
    // which unbound (main-world) entities; null = none. A spectator's co-watchers and the dead ride here
    private final @Nullable Predicate<Entity> baseEntities;
    // which shards' entities, and WHICH of them: a spectator can watch only the mobs, or only the players
    private final Map<Shard, Predicate<Entity>> entityShards;

    private ShardView(@Nullable Shard blocks, boolean allEntities, @Nullable Predicate<Entity> baseEntities,
                      Map<Shard, Predicate<Entity>> entityShards) {
        this.blocks = blocks;
        this.allEntities = allEntities;
        this.baseEntities = baseEntities;
        this.entityShards = entityShards;
    }

    /** The plain map + unbound (main-world) entities - the view of a player in no shard. */
    public static @NotNull ShardView base() { return BASE; }

    /** {@code shard}'s world as its members see it: overlay blocks + that shard's entities only. */
    public static @NotNull ShardView of(@NotNull Shard shard) { return shard.memberView(); }

    static ShardView newMemberView(Shard shard) { return new ShardView(shard, false, null, Map.of(shard, ALL_ENTITIES)); }

    public @NotNull ShardView withBlocks(@Nullable Shard shard) {
        return new ShardView(shard, allEntities, baseEntities, entityShards);
    }

    /** Adds {@code shards}' entities to the visible set - all of them; a repeat call for a shard replaces its filter. */
    public @NotNull ShardView withEntities(@NotNull Shard... shards) {
        return withEntities(ALL_ENTITIES, shards);
    }

    /** Sees only the entities of {@code shards} that {@code filter} accepts - {@code e -> !(e instanceof Player)} is "the mobs, not the players". */
    public @NotNull ShardView withEntities(@NotNull Predicate<Entity> filter, @NotNull Shard... shards) {
        Map<Shard, Predicate<Entity>> map = new HashMap<>(entityShards);
        for (Shard s : shards) map.put(s, filter);
        return new ShardView(blocks, allEntities, baseEntities, Map.copyOf(map));
    }

    public @NotNull ShardView withBaseEntities(boolean visible) {
        return new ShardView(blocks, allEntities, visible ? ALL_ENTITIES : null, entityShards);
    }

    /** Sees only the unbound entities {@code filter} accepts. */
    public @NotNull ShardView withBaseEntities(@NotNull Predicate<Entity> filter) {
        return new ShardView(blocks, allEntities, filter, entityShards);
    }

    public @NotNull ShardView withAllEntities() {
        return new ShardView(blocks, true, baseEntities, entityShards);
    }

    /**
     * The shard whose overlay this player SEES (and so stands on client-side), or {@code null} for the base map. It may
     * be a shard the player is NOT a member of (an observer set via {@link #set}): the client collides with the diff
     * blocks regardless of ownership, so a server-side anticheat must resolve a viewer's collidable blocks through THIS
     * view, not their {@link Shard#of membership} or the base instance (which differ for an observer), or a diff block
     * reads as air - a false flag.
     */
    public @Nullable Shard blocks() { return blocks; }

    /** Whether ANY entity of {@code shard} is in view - the coarse check; {@link #seesEntity} decides per entity. */
    public boolean seesEntitiesOf(@Nullable MechanicsWorld shard) {
        if (allEntities) return true;
        return shard instanceof Shard d ? entityShards.containsKey(d) : baseEntities != null;
    }

    /** Whether {@code subject} itself is in view: its shard must be watched, and its shard's filter must accept it. */
    public boolean seesEntity(@NotNull Entity subject) {
        if (allEntities) return true;
        Object bound = subject.getTag(MechanicsWorld.ENTITY_TAG);
        if (!(bound instanceof Shard d)) return baseEntities != null && baseEntities.test(subject);
        Predicate<Entity> filter = entityShards.get(d);
        return filter != null && filter.test(subject);
    }

    /** Whether this view shows any aspect of {@code shard} (its blocks or its entities) - the FX audience test. */
    public boolean sees(@NotNull Shard shard) {
        return blocks == shard || seesEntitiesOf(shard);
    }

    /** The player's effective view: their explicit view, else the default for their shard binding. */
    public static @NotNull ShardView of(@NotNull Player player) {
        ShardView explicit = player.getTag(TAG);
        if (explicit != null) return explicit;
        return player.getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard d ? d.memberView() : BASE;
    }

    /**
     * Sets ({@code null} clears) the player's explicit view: swaps the visible overlay client-side and re-evaluates
     * entity visibility. Overridden by the member preset on the next {@link Shard#join}.
     */
    public static void set(@NotNull Player player, @Nullable ShardView view) {
        ShardView old = of(player);
        ShardView explicitOld = player.getTag(TAG);
        if (explicitOld != null) explicitOld.unregister(player);
        if (view == null) player.removeTag(TAG);
        else {
            player.setTag(TAG, view);
            view.register(player);
        }
        applyChange(player, old);
    }

    /** Visibility between two entities; every viewable/viewer predicate delegates here, through the policy. */
    public static boolean sees(@NotNull Player viewer, @NotNull Entity subject) {
        return WorldPolicy.canSee(viewer, subject);
    }

    /** This view with every reference to {@code shard} dropped; {@code null} when nothing shard-specific remains. */
    @Nullable ShardView without(Shard shard) {
        Shard newBlocks = blocks == shard ? null : blocks;
        Map<Shard, Predicate<Entity>> rest = new HashMap<>(entityShards);
        rest.remove(shard);
        if (newBlocks == null && rest.isEmpty() && !allEntities) return null;
        return new ShardView(newBlocks, allEntities, baseEntities, Map.copyOf(rest));
    }

    static @Nullable ShardView explicit(Player player) { return player.getTag(TAG); }

    static Set<Player> allSeeing() { return ALL_SEEING; }

    static void forget(Player player) {
        ShardView explicit = player.getTag(TAG);
        if (explicit != null) explicit.unregister(player);
        player.removeTag(TAG);
    }

    private void register(Player player) {
        if (blocks != null) blocks.observers.add(player);
        for (Shard s : entityShards.keySet()) s.observers.add(player);
        if (allEntities) ALL_SEEING.add(player);
    }

    private void unregister(Player player) {
        if (blocks != null) blocks.observers.remove(player);
        for (Shard s : entityShards.keySet()) s.observers.remove(player);
        ALL_SEEING.remove(player);
    }

    /** A feature's per-player hook on any view/binding change (the tab-list scoper re-syncs here). Fires
     *  BEFORE the viewable rules: a modern client DROPS a player spawn whose info entry hasn't arrived yet,
     *  so tab adds must precede the spawns the rule update sends. */
    static volatile Consumer<Player> viewChangeHook = p -> {};

    /** After the player's binding or explicit view changed; {@code old} = the effective view before. */
    static void applyChange(Player player, ShardView old) {
        viewChangeHook.accept(player);
        ShardView now = of(player);
        if (old.blocks != now.blocks) {
            sendBlockSwap(player, old.blocks, now.blocks);
            sendSkySwap(player, now.blocks);
        }
        applyRules(player);
    }

    /** The sky follows the block view: the new view's weather/time, or the base's when leaving. */
    private static void sendSkySwap(Player player, @Nullable Shard to) {
        if (!player.isOnline()) return;
        if (to == null) ShardSky.sendBase(player);
        else if (player.getInstance() == to.instance()) to.sendSky(player);
    }

    // Minestom's Option.updateRule holds the view's own mutex across its pair-locking callbacks - an
    // unordered outer lock, so two concurrent rule updates deadlock AB-BA (soak-reproduced on 26.1.2).
    // This outer lock restores a total order for the lib's own updates; a rule update racing a
    // chunk-crossing's pair ops (inside Entity.tick) is the documented residual only Minestom can close.
    // The helpers below are the only door - never call a rule update bare.
    private static final Object RULE_LOCK = new Object();

    static void applyViewableRule(Entity entity) {
        synchronized (RULE_LOCK) {
            entity.updateViewableRule(v -> sees(v, entity));
        }
    }

    static void refireViewableRule(Entity entity) {
        synchronized (RULE_LOCK) {
            entity.updateViewableRule();
        }
    }

    // the plain default can't know about views; don't clobber a custom policy
    static void installDefaultPolicy() {
        if (WorldPolicy.get() == WorldPolicy.SAME_WORLD) WorldPolicy.set(ShardPolicy.DEFAULT);
    }

    /** Stamps + re-evaluates both visibility directions for {@code player} (rules read tags live, so re-stamping is idempotent). */
    static void applyRules(Player player) {
        installDefaultPolicy();
        synchronized (RULE_LOCK) {
            player.updateViewableRule(v -> sees(v, player));
            player.updateViewerRule(e -> sees(player, e));
        }
    }

    /**
     * Re-sends every position where the two views can diverge, as the new view sees it (loaded chunks only -
     * chunk load re-applies). Chains diverge only below their lowest common ancestor - shared layers render
     * identically - so a sibling layer switch re-sends just the diverging diffs.
     */
    private static void sendBlockSwap(Player player, @Nullable Shard from, @Nullable Shard to) {
        if (!player.isOnline()) return;
        var in = player.getInstance();
        Shard lca = lowestCommonAncestor(from, to);
        // each side only if the player is in that shard's world (a swap can cross instances)
        Shard oldView = from != null && in == from.instance() ? from : null;
        Shard newView = to != null && in == to.instance() ? to : null;
        Set<Point> positions = new HashSet<>();
        if (oldView != null) collectChainDiff(oldView, lca, positions);
        if (newView != null) collectChainDiff(newView, lca, positions);
        // light first: its unlit fallback re-sends BASE chunks whole, which would clobber the block sends below
        Set<Long> resent = ShardLighting.onViewSwap(player, oldView, newView);
        if (newView != null && !resent.isEmpty()) {
            // a full base resend reverts the WHOLE chain's overlay in that chunk - shared-ancestor diffs the
            // LCA cut excluded included - so recover every chain position living in a resent chunk
            Set<Point> chain = new HashSet<>();
            collectChainDiff(newView, null, chain);
            for (Point pos : chain) {
                if (resent.contains(CoordConversion.chunkIndex(pos.chunkX(), pos.chunkZ()))) positions.add(pos);
            }
        }
        for (Point pos : positions) sendViewAt(player, newView, pos);
    }

    private static void collectChainDiff(Shard shard, @Nullable Shard stop, Set<Point> out) {
        for (Shard s = shard; s != null && s != stop; s = s.parent()) s.forEachDiff((pos, block) -> out.add(pos));
    }

    private static @Nullable Shard lowestCommonAncestor(@Nullable Shard a, @Nullable Shard b) {
        for (Shard s = a; s != null; s = s.parent()) {
            for (Shard t = b; t != null; t = t.parent()) {
                if (s == t) return s;
            }
        }
        return null;
    }

    private static void sendViewAt(Player player, @Nullable Shard view, Point pos) {
        if (!player.getInstance().isChunkLoaded(pos)) return;
        Block block = view != null ? view.getBlock(pos) : player.getInstance().getBlock(pos);
        player.sendPacket(new BlockChangePacket(pos, block));
    }
}
