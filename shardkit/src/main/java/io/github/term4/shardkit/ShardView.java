package io.github.term4.shardkit;

import io.github.term4.worldaccess.WorldAccess;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a player SEES of the sharded world, independent of game membership: exactly one block view (a shard's
 * overlay or the base map - overlays don't blend) plus the set of shards whose entities are visible. Purely
 * observational - mechanics targeting, broadcasts and block editing stay bound to {@link Shard#join membership};
 * a non-member's block view is read-only.
 *
 * <p>Immutable; compose from a preset: {@code ShardView.of(shard)} (spectate a game), {@code ShardView.base()
 * .withEntities(shard)} (staff: the game's players over the plain map), {@code ShardView.base().withAllEntities()}
 * (every player everywhere). Apply with {@link #set}; members get {@code of(their shard)} implicitly and
 * {@link Shard#join} clears any explicit view.
 */
public final class ShardView {

    private static final Tag<ShardView> TAG = Tag.Transient("shardkit:view");
    /** Observers whose view has {@code allEntities} - watchers of every shard. */
    private static final Set<Player> ALL_SEEING = ConcurrentHashMap.newKeySet();

    private static final ShardView BASE = new ShardView(null, false, true, Set.of());

    private final @Nullable Shard blocks;
    private final boolean allEntities;
    private final boolean baseEntities;
    private final Set<Shard> entityShards;

    private ShardView(@Nullable Shard blocks, boolean allEntities, boolean baseEntities, Set<Shard> entityShards) {
        this.blocks = blocks;
        this.allEntities = allEntities;
        this.baseEntities = baseEntities;
        this.entityShards = entityShards;
    }

    /** The plain map + unbound (main-world) entities - the view of a player in no shard. */
    public static @NotNull ShardView base() { return BASE; }

    /** {@code shard}'s world as its members see it: overlay blocks + that shard's entities only. */
    public static @NotNull ShardView of(@NotNull Shard shard) { return shard.memberView(); }

    static ShardView newMemberView(Shard shard) { return new ShardView(shard, false, false, Set.of(shard)); }

    public @NotNull ShardView withBlocks(@Nullable Shard shard) {
        return new ShardView(shard, allEntities, baseEntities, entityShards);
    }

    /** Adds {@code shards}' entities to the visible set. */
    public @NotNull ShardView withEntities(@NotNull Shard... shards) {
        Set<Shard> set = new HashSet<>(entityShards);
        for (Shard s : shards) set.add(s);
        return new ShardView(blocks, allEntities, baseEntities, Set.copyOf(set));
    }

    public @NotNull ShardView withBaseEntities(boolean visible) {
        return new ShardView(blocks, allEntities, visible, entityShards);
    }

    public @NotNull ShardView withAllEntities() {
        return new ShardView(blocks, true, baseEntities, entityShards);
    }

    /** The shard whose overlay this player sees, or {@code null} for the base map. */
    public @Nullable Shard blocks() { return blocks; }

    /** Whether entities bound to {@code shard} ({@code null}/non-diff = main world) are visible. */
    public boolean seesEntitiesOf(@Nullable WorldAccess shard) {
        if (allEntities) return true;
        return shard instanceof Shard d ? entityShards.contains(d) : baseEntities;
    }

    /** Whether this view shows any aspect of {@code shard} (its blocks or its entities) - the FX audience test. */
    public boolean sees(@NotNull Shard shard) {
        return blocks == shard || seesEntitiesOf(shard);
    }

    // --- per-player application ---

    /** The player's effective view: their explicit view, else the default for their shard binding. */
    public static @NotNull ShardView of(@NotNull Player player) {
        ShardView explicit = player.getTag(TAG);
        if (explicit != null) return explicit;
        return player.getTag(WorldAccess.ENTITY_TAG) instanceof Shard d ? d.memberView() : BASE;
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

    /** Visibility between two entities; the single rule every viewable/viewer predicate delegates to. */
    public static boolean sees(@NotNull Player viewer, @NotNull Entity subject) {
        return of(viewer).seesEntitiesOf(subject.getTag(WorldAccess.ENTITY_TAG));
    }

    /** This view with every reference to {@code shard} dropped; {@code null} when nothing shard-specific remains. */
    @Nullable ShardView without(Shard shard) {
        Shard newBlocks = blocks == shard ? null : blocks;
        Set<Shard> rest = new HashSet<>(entityShards);
        rest.remove(shard);
        if (newBlocks == null && rest.isEmpty() && !allEntities) return null;
        return new ShardView(newBlocks, allEntities, baseEntities, Set.copyOf(rest));
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
        for (Shard s : entityShards) s.observers.add(player);
        if (allEntities) ALL_SEEING.add(player);
    }

    private void unregister(Player player) {
        if (blocks != null) blocks.observers.remove(player);
        for (Shard s : entityShards) s.observers.remove(player);
        ALL_SEEING.remove(player);
    }

    /** After the player's binding or explicit view changed; {@code old} = the effective view before. */
    static void applyChange(Player player, ShardView old) {
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
        if (to == null) Shard.sendBaseSky(player);
        else if (player.getInstance() == to.instance()) to.sendSky(player);
    }

    /** Stamps + re-evaluates both visibility directions for {@code player} (rules read tags live, so re-stamping is idempotent). */
    static void applyRules(Player player) {
        player.updateViewableRule(v -> sees(v, player));
        player.updateViewerRule(e -> sees(player, e));
    }

    /** Re-sends every position either overlay touches, as the new view sees it (loaded chunks only - chunk load re-applies). */
    private static void sendBlockSwap(Player player, @Nullable Shard from, @Nullable Shard to) {
        if (!player.isOnline()) return;
        var in = player.getInstance();
        // each side only if the player is in that shard's world (a swap can cross instances)
        if (from != null && in == from.instance()) {
            Shard newView = to != null && in == to.instance() ? to : null;
            from.forEachDiff((pos, block) -> sendViewAt(player, newView, pos));
        }
        if (to != null && in == to.instance()) to.forEachDiff((pos, block) -> sendViewAt(player, to, pos));
    }

    private static void sendViewAt(Player player, @Nullable Shard view, Point pos) {
        if (!player.getInstance().isChunkLoaded(pos)) return;
        Block block = view != null ? view.getBlock(pos) : player.getInstance().getBlock(pos);
        player.sendPacket(new BlockChangePacket(pos, block));
    }
}
