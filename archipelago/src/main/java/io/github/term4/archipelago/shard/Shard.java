package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.event.ShardJoinEvent;
import net.minestom.server.instance.EntityTracker;
import io.github.term4.archipelago.event.ShardLeaveEvent;
import io.github.term4.archipelago.event.ShardReclockEvent;
import io.github.term4.archipelago.event.ShardTransferEvent;
import io.github.term4.archipelago.ticking.DomainTicked;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.polyp.world.InstanceWorld;
import io.github.term4.polyp.world.MechanicsWorld;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.collision.EntityCollisionResult;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.collision.PhysicsUtils;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.WorldBorder;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import net.minestom.server.tag.TagHandler;
import net.minestom.server.world.DimensionType;
import net.kyori.adventure.sound.Sound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * A game world as a diff over a shared base map: block writes land in an overlay (the base instance is never
 * written), entities and viewers are shard-owned, N shards share one loaded map at the same coordinates.
 * Gameplay reaches a shard through the {@link MechanicsWorld#ENTITY_TAG} binding ({@link #join}/{@link #spawn}/
 * {@link #transfer} stamp it); {@link #players()} = members, {@link #watchers()} adds observers and all-seeing
 * viewers standing on the base instance.
 *
 * <p>Shards chain: a {@link #Shard(Shard) child} layer's diff is RELATIVE to its parent - block reads fall
 * through child -> parent -> base, an explicit-air entry masks the ancestor block, and ancestor writes stay
 * live for every descendant's viewers where unmasked. Run one tree on one clock (the one-arg child ctor's
 * default) unless you know what crossing clocks costs.
 *
 * <p>Threading: block writes run on the CALLER's thread - gameplay writes belong on the shard's clock (domain
 * hooks and entity ticks already land there). Snapshots and batch writes relay between domain ticks, and that
 * relay is the only cross-thread write consistency the shard guarantees.
 */
public final class Shard implements MechanicsWorld {

    final Instance base;
    final @Nullable Shard parent;
    final List<Shard> children = new CopyOnWriteArrayList<>();
    final ShardBlocks blocks = new ShardBlocks(this);
    final ShardSky sky = new ShardSky();
    final Set<Player> members = ConcurrentHashMap.newKeySet();
    final Set<Player> membersView = Collections.unmodifiableSet(members);
    /** Non-members with an explicit {@link ShardView} referencing this shard; maintained by ShardView. */
    final Set<Player> observers = ConcurrentHashMap.newKeySet();
    private final Set<Player> observersView = Collections.unmodifiableSet(observers);
    final Set<Entity> entities = ConcurrentHashMap.newKeySet();
    private final ShardView memberView = ShardView.newMemberView(this);
    private final TagHandler tags = TagHandler.newHandler();
    volatile @Nullable ExecutionDomain domain;

    private final ShardClock clock = new ShardClock(this);
    private final ShardViewers viewers = new ShardViewers(this);
    volatile @Nullable ShardLighting light;
    private volatile ShardTabList.TabScope tabScope = ShardTabList.TabScope.WORLD;
    /** ShardManager.destroy guard: the cascade and the quarantine sweep may both reach a shard. */
    volatile boolean destroyed;

    public Shard(@NotNull Instance base) {
        this(base, null, null);
    }

    /**
     * Binds this shard to a tick {@code domain}: {@link DomainTicked}-covered entities spawned/transferred here
     * tick on its thread; others stay server-ticked. Domains may be shared across shards;
     * {@link ShardManager#destroy} retires one with its last registered user.
     */
    public Shard(@NotNull Instance base, @Nullable ExecutionDomain domain) {
        this(base, null, domain);
    }

    /** A child layer on the parent's own domain: {@link #Shard(Shard, ExecutionDomain)} with {@code parent.domain()}. */
    public Shard(@NotNull Shard parent) {
        this(parent.base, parent, parent.domain);
        parent.children.add(this);
    }

    /**
     * A child layer of {@code parent}: same base map, own relative diff/entities/members, RELATIVE block reads
     * (child -> parent -> base). Shards and domains are separate: {@code domain} may be the parent's, another,
     * or {@code null} (server-ticked). Chain reads are safe from any thread, but cross-layer write/read
     * ordering is only tick-atomic within ONE clock - splitting a tree across clocks is on the caller.
     */
    public Shard(@NotNull Shard parent, @Nullable ExecutionDomain domain) {
        this(parent.base, parent, domain);
        parent.children.add(this);
    }

    private Shard(Instance base, @Nullable Shard parent, @Nullable ExecutionDomain domain) {
        BaseBlockFilter.install(); // a base write must never paint over a viewer's mask
        this.base = base;
        this.parent = parent;
        this.domain = domain;
        if (domain != null) {
            domain.claim();
            onDomainBound.accept(this);
        }
    }

    /** This shard's tick domain, or {@code null} (server-ticked). */
    public @Nullable ExecutionDomain domain() {
        return domain;
    }

    /**
     * Re-clocks this shard: covered members/entities hand off to {@code domain} through the mailbox,
     * {@link #onTick} hooks migrate, and {@link ShardReclockEvent} fires (combat-state bridges re-base their
     * stamps there). {@code null} = STASIS ({@link ExecutionDomain#stasis()}): removing the executor freezes
     * the shard - clock, hooks and entities stop dead - it never silently reroutes work to the server; member
     * PLAYERS alone fall back to server ticking (a frozen connection is a dead client). Same for a domain that
     * retired or FAILED: the shard sits in stasis until re-clocked here or destroyed - never re-clock onto a
     * failed domain, bind a fresh one. Serialize calls per shard (like {@link #transfer}).
     */
    /** Runs on every domain (re)binding: the world pass follows the clock, registered or not. */
    static volatile java.util.function.Consumer<Shard> onDomainBound = shard -> {};

    public void domain(@Nullable ExecutionDomain domain) {
        ExecutionDomain target = domain != null ? domain : ExecutionDomain.stasis();
        ExecutionDomain old;
        synchronized (this) {
            old = this.domain;
            if (old == target) return;
            target.claim();
            this.domain = target;
            clock.rebind();
        }
        for (Player p : members) adoptTicking(p);
        onDomainBound.accept(this); // the world pass follows the clock, registered with a manager or not
        for (Player o : observers) adoptObserver(o);
        for (Entity e : entities) adoptTicking(e);
        if (old != null) old.release(); // a re-clock moves the count; only a destroy ends a clock
        EventDispatcher.call(new ShardReclockEvent(this, old, target));
    }

    /** The layer this shard's diff is relative to, or {@code null} (a root over the base map). */
    public @Nullable Shard parent() {
        return parent;
    }

    /** This shard's direct child layers; copy-on-write, so iterating is a snapshot without copying. */
    /** Unlinks this shard from its parent: a reset husk kept here is walked by every later fan-out and light pass. */
    public void detach() {
        if (parent != null) parent.children.remove(this);
    }

    /** Drops removed entities and offline members. The lifecycle listeners do this on the events;
     *  {@link #entities()} also runs it, so a caller that installed neither is not left with corpses. */
    public void sweep() {
        entities();
    }

    public @NotNull List<Shard> children() {
        return Collections.unmodifiableList(children);
    }

    /**
     * Recomputes chain-effective light for this shard's viewers (sent as follow-up UpdateLight overlays,
     * Minestom's own engine on a scratch neighborhood). Off by default: base light usually reads fine, and 1.8
     * clients relight locally from block data - enable on shards whose diffs reshape lighting for modern clients.
     */
    public void relight(boolean enabled) {
        relight(enabled ? new ShardLight(this) : null);
    }

    /**
     * Installs a custom {@link ShardLighting} system ({@code null} = none, the default: viewers keep the base
     * instance's light untouched, like an unmanaged instance). The previous system is {@link ShardLighting#close()
     * closed} first - it restores base light on its lit clients before the replacement takes over.
     */
    public void relight(@Nullable ShardLighting lighting) {
        ShardLighting old = this.light;
        if (old != null) old.close();
        this.light = lighting;
    }

    @Nullable ShardLighting light() {
        return light;
    }

    /** What this shard's members list in tab, once {@link ShardTabList#install()} ran; default {@link ShardTabList.TabScope#WORLD}. */
    public void tabScope(ShardTabList.@NotNull TabScope scope) {
        this.tabScope = scope;
        ShardTabList.refreshShard(this);
    }

    public ShardTabList.@NotNull TabScope tabScope() {
        return tabScope;
    }

    // no mask pruning: shards without light cost nothing, and light bleeds past exact positions anyway
    void lightChanged(Set<Long> sections) {
        ShardLighting l = light;
        if (l != null) l.changed(sections);
        for (Shard child : children) child.lightChanged(sections);
    }

    Set<Long> overlayChunks() {
        return blocks.chunkKeys();
    }

    boolean overlayTouches(int chunkX, int chunkZ) {
        return blocks.touches(chunkX, chunkZ);
    }

    /**
     * THIS shard's clock: its domain's tick, or the base instance's age when server-ticked. Stamp AND judge
     * tick-stamped state against this one clock; never compare across shards, and re-base stamps on
     * {@link #domain(ExecutionDomain) re-clock} ({@link ShardReclockEvent}).
     */
    public long ticks() {
        return clock.ticks();
    }

    /** One view change through this shard's write API; {@code previous} = the pre-write view. */
    @FunctionalInterface
    public interface BlockChangeHook {
        void changed(@NotNull Point pos, @NotNull Block previous, @NotNull Block block);
    }

    /**
     * Fires when a write changes this shard's view of a position - setBlock/setBlocks/pinBlock/reset; view
     * no-ops and base/parent edits don't fire. Runs on the writing thread. Returns the removal handle.
     * Replay recordings ride this seam.
     */
    public @NotNull Runnable onBlockChange(@NotNull BlockChangeHook hook) {
        return blocks.onChange(hook);
    }

    /**
     * Per-tick hook on this shard's clock, called with {@link #ticks()} - a domain shard's own thread, a plain
     * shard's server scheduler. Survives a {@link #domain(ExecutionDomain) re-clock}. Returns the removal handle.
     */
    public @NotNull Runnable onTick(@NotNull LongConsumer hook) {
        return clock.onTick(hook);
    }

    /** Admits {@code player}: binds them to this shard, drops any explicit {@link ShardView}, applies the member view. */
    public void join(@NotNull Player player) {
        if (destroyed) throw new IllegalStateException("shard is destroyed"); // nothing would ever evict them
        // one-pass retag: leave()-then-join briefly unbound the player, flashing them and base players at each other
        Shard previous = player.getTag(ENTITY_TAG) instanceof Shard s && s != this ? s : null;
        ShardView old = ShardView.of(player);
        boolean left = previous != null && previous.members.remove(player);
        boolean added = members.add(player);
        player.setTag(ENTITY_TAG, this);
        ShardView.forget(player);
        ShardView.applyChange(player, old);
        adoptTicking(player);
        if (left) EventDispatcher.call(new ShardLeaveEvent(player, previous));
        if (added) EventDispatcher.call(new ShardJoinEvent(player, this));
    }

    /** Removes {@code player}: unbinds, reverts to their explicit view or the base map, rescopes visibility. */
    public void leave(@NotNull Player player) {
        if (!members.remove(player)) return;
        ShardView old = ShardView.of(player);
        player.removeTag(ENTITY_TAG);
        ShardView.applyChange(player, old);
        if (ExecutionDomain.of(player) != null) ExecutionDomain.detach(player); // unbound = server-ticked again
        EventDispatcher.call(new ShardLeaveEvent(player, this));
    }

    /** Drops the diff: every overlaid position reverts to the PARENT view (the base map for a root shard). */
    public void reset() {
        relay(() -> {
            blocks.resetDiff();
            refreshLight(); // recomputed without the diff = parent-view light; untouched chunks restore base
            for (Entity e : entities) e.remove();
            entities.clear();
            return null;
        });
    }

    private void refreshLight() {
        ShardLighting l = light;
        if (l != null) l.refreshLit();
        for (Shard child : children) child.refreshLight();
    }

    public @NotNull Set<Player> members() { return membersView; }

    /** Players with an explicit {@link ShardView} showing this shard (spectators/staff; not members). */
    public @NotNull Set<Player> observers() { return observersView; }

    public int overlaySize() {
        return blocks.size();
    }

    /** A tick-consistent snapshot of the diff - the EXPORT half; load one back with {@link #setBlocks}. */
    public @NotNull Map<Point, Block> diff() {
        return relay(() -> {
            Map<Point, Block> out = new HashMap<>();
            forEachDiff(out::put);
            return out;
        });
    }

    /** Copy-free diff read (persistence/replay recorders). */
    public void forEachDiff(@NotNull BiConsumer<Point, Block> consumer) {
        blocks.forEachDiff(consumer);
    }

    /**
     * {@link #diff()} with the chain collapsed (upper layers win) - the read half for custom fork/flatten
     * recipes; {@link #setBlocks} is the write half.
     */
    public @NotNull Map<Point, Block> effectiveDiff() {
        return relay(() -> {
            Map<Point, Block> out = new HashMap<>();
            effectiveDiff(out);
            return out;
        });
    }

    ShardView memberView() { return memberView; }

    @Override public @NotNull Instance instance() { return base; }

    @Override public @NotNull TagHandler tagHandler() { return tags; }

    @Override public @NotNull Block getBlock(@NotNull Point pos) { return getBlock(pos.blockX(), pos.blockY(), pos.blockZ()); }

    @Override public @NotNull Block getBlock(int x, int y, int z) {
        return blocks.getBlock(x, y, z);
    }

    @Override public @NotNull Block getBlock(@NotNull Point pos, Block.Getter.@NotNull Condition condition) {
        return getBlock(pos.blockX(), pos.blockY(), pos.blockZ(), condition);
    }

    @Override public @NotNull Block getBlock(int x, int y, int z, Block.Getter.@NotNull Condition condition) {
        return blocks.getBlock(x, y, z, condition);
    }

    /** This chain's own entry at the position, or {@code null} when the chain reads straight through to the base. */
    @Nullable Block overlaidAt(int x, int y, int z) {
        return blocks.overlaidAt(x, y, z);
    }

    /** This chain's overlays in front of {@code bottom}, child-first. */
    private Block.Getter overlaid(Block.Getter bottom) {
        return blocks.overlaid(bottom);
    }

    @Override public void setBlock(@NotNull Point pos, @NotNull Block block) {
        blocks.setBlock(pos, block);
    }

    /**
     * Drops this layer's diff entry at {@code pos} - the position reverts to the parent view. Works under an
     * unloaded chunk too (no viewer holds it; chunk load shows the parent view), where {@link #setBlock} would
     * have to read an unloadable parent block.
     */
    public void clearBlock(@NotNull Point pos) {
        blocks.clearBlock(pos);
    }

    /**
     * {@link #setBlock} that always keeps the diff entry: a write equal to the parent view PINS the position
     * (later parent/base changes no longer show through) instead of clearing it. Replay theaters use it -
     * recorded layer state must reproduce verbatim whatever order the ops land in.
     */
    public void pinBlock(@NotNull Point pos, @NotNull Block block) {
        blocks.pinBlock(pos, block);
    }

    /** One overlay write pass, then one {@link MultiBlockChangePacket} per touched section to each block viewer. */
    @Override public void setBlocks(@NotNull Map<Point, Block> blocks) {
        this.blocks.setBlocks(blocks);
    }

    @Override public boolean isChunkLoaded(@NotNull Point pos) { return base.isChunkLoaded(pos); }

    @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return base.isChunkLoaded(chunkX, chunkZ); }

    @Override public @NotNull DimensionType dimension() { return base.getCachedDimensionType(); }

    /** The chain's computed light where the installed {@link ShardLighting} has it, else base. Without a
     *  lighting system the diff is NOT reflected - a roofed-in area still reads base sky. */
    @Override
    public int getBlockLight(int x, int y, int z) {
        ShardLighting l = light;
        if (l != null) {
            int level = l.blockLight(x, y, z);
            if (level >= 0) return level;
        }
        return base.getBlockLight(x, y, z);
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        ShardLighting l = light;
        if (l != null) {
            int level = l.skyLight(x, y, z);
            if (level >= 0) return level;
        }
        return base.getSkyLight(x, y, z);
    }

    @Override
    public PhysicsResult sweep(@NotNull BoundingBox box, @NotNull Pos position, @NotNull Vec velocity,
                               @Nullable PhysicsResult last, boolean singleCollision) {
        return CollisionUtils.handlePhysics(overlaid(InstanceWorld.solidGetter(base, position)),
                box, position, velocity, last, singleCollision);
    }

    @Override
    public PhysicsResult sweepLoaded(@NotNull BoundingBox box, @NotNull Pos position, @NotNull Vec velocity,
                                     @Nullable PhysicsResult last, boolean singleCollision) {
        return CollisionUtils.handlePhysics(overlaid(InstanceWorld.airGetter(base, position)),
                box, position, velocity, last, singleCollision);
    }

    @Override
    public Collection<EntityCollisionResult> sweepEntities(@NotNull BoundingBox box, @NotNull Pos position, @NotNull Vec velocity,
                                                           double extendRadius, @NotNull Function<Entity, Boolean> filter,
                                                           @Nullable PhysicsResult physics) {
        // base sweep sees every entity in the world; contains() narrows it
        return CollisionUtils.checkEntityCollisions(base, box, position, velocity, extendRadius,
                e -> contains(e) && filter.apply(e), physics);
    }

    @Override
    public PhysicsResult simulateMovement(@NotNull Pos position, @NotNull Vec velocityPerTick, @NotNull BoundingBox box,
                                          @NotNull Aerodynamics aerodynamics, boolean noGravity, boolean hasPhysics,
                                          boolean onGround, @Nullable PhysicsResult last) {
        return PhysicsUtils.simulateMovement(position, velocityPerTick, box, base.getWorldBorder(),
                overlaid(InstanceWorld.solidGetter(base, position)), aerodynamics, noGravity, hasPhysics, onGround, false, last);
    }

    /**
     * The shard {@code entity} belongs to, or {@code null} (a plain instance). O(1); a child-layer entity reports
     * that exact layer (walk {@link #parent()} for the root). Independent of the clock it ticks on -
     * {@link ExecutionDomain#of(Entity)} can differ during stasis/re-clock windows.
     */
    public static @Nullable Shard of(@NotNull Entity entity) {
        return entity.getTag(ENTITY_TAG) instanceof Shard s ? s : null;
    }

    /** Whether {@code entity} belongs to this shard (member player or shard-spawned entity). */
    public boolean contains(Entity entity) {
        return entity.getTag(ENTITY_TAG) == this;
    }

    @Override public @NotNull WorldBorder worldBorder() { return base.getWorldBorder(); }

    @Override public boolean isInVoid(@NotNull Point pos) { return base.isInVoid(pos); }

    @Override public @NotNull Collection<@NotNull Entity> entities() {
        // cleanup fused into the copy; the despawn listener is the primary one
        List<Entity> all = new ArrayList<>(entities.size() + members.size());
        for (Entity e : entities) {
            if (e.isRemoved()) entities.remove(e);
            else all.add(e);
        }
        for (Player m : members) {
            if (!m.isOnline()) members.remove(m);
            else all.add(m);
        }
        return all;
    }

    @Override public @NotNull Collection<@NotNull Entity> nearbyEntities(@NotNull Point point, double range) {
        double rangeSq = range * range;
        List<Entity> found = new ArrayList<>();
        for (Entity e : entities) {
            if (!e.isRemoved() && e.getPosition().distanceSquared(point) <= rangeSq) found.add(e);
        }
        for (Player m : members) {
            if (m.isOnline() && m.getPosition().distanceSquared(point) <= rangeSq) found.add(m);
        }
        return found;
    }


    @Override public @NotNull CompletableFuture<Void> spawn(@NotNull Entity entity, @NotNull Pos position) {
        entity.setTag(ENTITY_TAG, this);
        ShardView.applyViewableRule(entity);
        entities.add(entity);
        adoptTicking(entity);
        // re-fire the rule once tracked - a rule set before setInstance isn't applied to the initial viewer add
        return entity.setInstance(base, position).thenRun(() -> ShardView.refireViewableRule(entity));
    }

    /**
     * Runs {@code op} on this shard's clock and waits: snapshots and batch writes land between its ticks, so a
     * fork/save can never capture half a {@link #setBlocks} batch. Inline on the owner (and on a shard without
     * a live clock); NEVER relay from a foreign clock - two live clocks joining each other deadlock, so
     * {@link ExecutionDomain#relay} throws there - {@link #onOwner} hands off instead.
     */
    public <T> T relay(@NotNull Supplier<T> op) {
        return clock.relay(op);
    }

    /** {@link #relay} without waiting: {@code op} runs inline when already on this shard's clock, else on its
     *  next tick. The safe direction from another clock. */
    public void onOwner(@NotNull Runnable op) {
        clock.onOwner(op);
    }

    /**
     * Ticks an observer of this shard on its clock, so a watcher runs with what they are watching rather than
     * against the server thread. A member already rides the shard they belong to, and a viewer of this shard
     * who stands in another rides that one, so both are left where they are.
     */
    void adoptObserver(@NotNull Player player) {
        if (of(player) != null || !ShardView.ticksOn(player, this)) return;
        adoptTicking(player);
    }

    /** Hands an observer back to server ticking; a member keeps the clock their own shard gave them. */
    void releaseObserver(@NotNull Player player) {
        if (of(player) == null && ExecutionDomain.of(player) != null) ExecutionDomain.detach(player);
    }

    // only opt-out-covered classes move to the domain; anything else would double-tick after a chunk crossing.
    // players never attach to a clock that can't tick them (stasis/failed) - a frozen connection is a dead client
    private void adoptTicking(Entity entity) {
        if (!DomainTicked.covers(entity)) return;
        ExecutionDomain d = domain;
        if (d != null && (d.live() || !(entity instanceof Player))) {
            d.attach(entity);
        } else if (ExecutionDomain.of(entity) != null) {
            ExecutionDomain.detach(entity);
        }
    }

    /**
     * An independent copy over the same base: diff + sky, plus owned entities reproduced by {@code entityCloner}
     * (players never copy - membership is session state; a {@code null} clone skips that entity). Copies are
     * server-ticked - execution is not data; bind a domain at creation instead. A child forks into a SIBLING
     * under the same parent - its relative diff is meaningless alone; {@link #flattenOnto flatten} to detach
     * from the chain.
     */
    public @NotNull Shard fork(@Nullable Function<Entity, Entity> entityCloner) {
        return fork((pos, block) -> block, entityCloner);
    }

    /** {@link #fork(Function)} without entities: blocks + sky only. */
    public @NotNull Shard fork() {
        return fork(null);
    }

    /**
     * {@link #fork(Function)} with a per-entry block rule: each own-diff entry passes through {@code blocks} -
     * return the block to carry (possibly changed) or {@code null} to drop it (region-scoped copies are a
     * position test away).
     */
    public @NotNull Shard fork(@NotNull BiFunction<Point, Block, @Nullable Block> blocks,
                               @Nullable Function<Entity, Entity> entityCloner) {
        return ShardForks.fork(this, blocks, entityCloner);
    }

    /**
     * Seeds this shard with copies of the base instance's OWN entities ({@link #fork} semantics: a {@code null}
     * clone skips that entity). Shard-owned entities and players never copy. A shard over a base otherwise
     * starts empty - base entities are not shard-visible, so nothing carries over without this.
     */
    public @NotNull Shard seedFromBase(@Nullable Function<Entity, Entity> cloner) {
        return ShardForks.seedFromBase(this, cloner);
    }

    /** Per-position flatten rule: the block the combined shard keeps ({@code null} = none); a side absent at the position is {@code null}. */
    @FunctionalInterface
    public interface BlockMerge {
        @Nullable Block merge(@NotNull Point pos, @Nullable Block bottom, @Nullable Block top);
    }

    /** {@link #flattenOnto(Shard, BlockMerge, Function)} with the default rule: the top side wins where both touch. */
    public @NotNull Shard flattenOnto(@NotNull Shard bottom, @Nullable Function<Entity, Entity> cloner) {
        return flattenOnto(bottom, (pos, b, t) -> t != null ? t : b, cloner);
    }

    /** {@link #flattenOnto(Shard, Function)} without entities: blocks + sky only. */
    public @NotNull Shard flattenOnto(@NotNull Shard bottom) {
        return flattenOnto(bottom, null);
    }

    /**
     * A new INDEPENDENT shard over the same base: both sides' effective chains combined per position by
     * {@code merger}, the nearest sky override (this chain first), and both sides' entities via {@code cloner}
     * ({@link #fork} semantics; a side's origin is readable off the entity's world binding). Sources are
     * untouched - to commit a child layer down IN PLACE, {@code parent.setBlocks(child.diff())} is the one-call
     * recipe (the fan-out repaints viewers). Each side snapshots tick-atomically; two shards on one clock
     * flatten as a single snapshot.
     */
    public @NotNull Shard flattenOnto(@NotNull Shard bottom, @NotNull BlockMerge merger,
                                      @Nullable Function<Entity, Entity> cloner) {
        return ShardForks.flattenOnto(this, bottom, merger, cloner);
    }

    /**
     * This shard's whole CHAIN collapsed into one independent shard over the base: effective blocks, effective
     * sky, and every layer's own entities via {@code cloner} ({@link #fork} semantics; root layer's clones
     * spawn first). Sources untouched. BRANCHES never merge - siblings are alternative realities of the same
     * coordinates; flatten each leaf's chain. One tick-atomic snapshot on this shard's clock (a cross-clock
     * ancestor's entities read best-effort - one tree one clock is the recommended default).
     */
    public @NotNull Shard flatten(@Nullable Function<Entity, Entity> cloner) {
        return ShardForks.flatten(this, cloner);
    }

    /** {@link #flatten(Function)} without entities: blocks + sky only. */
    public @NotNull Shard flatten() {
        return flatten(null);
    }

    // root-most first: upper layers overwrite
    void effectiveDiff(Map<Point, Block> out) {
        if (parent != null) parent.effectiveDiff(out);
        forEachDiff(out::put);
    }

    long effectiveTimeOverride() {
        long own = sky.timeOverride();
        if (own >= 0) return own;
        return parent != null ? parent.effectiveTimeOverride() : -1;
    }

    @Nullable Weather effectiveWeatherOverride() {
        Weather own = sky.weatherOverride();
        if (own != null) return own;
        return parent != null ? parent.effectiveWeatherOverride() : null;
    }

    /**
     * The default fork cloner: {@link MechanicsWorld#ENTITY_COPY} stamps win; exact-class {@code Entity}/
     * {@code LivingEntity}/{@code ItemEntity}/{@code ExperienceOrb} get a data copy (velocity, serializable tags,
     * health/equipment/stack/xp). Unknown subclasses return {@code null} - behavior isn't data; stamp them, or
     * compose: {@code e -> e instanceof MyNpc n ? n.copy() : Shard.copyEntity(e)}.
     */
    public static @Nullable Entity copyEntity(@NotNull Entity entity) {
        return EntityCopy.of(entity);
    }

    /**
     * Writes this shard's OWN diff into the base instance - the "winner's build lands on the real map" op.
     * The lib never writes the base by itself; this is the one explicit exception, on the caller's order.
     * Call from the base's ticking context (the server thread - base writes aren't clock-thread-safe). A
     * child commits its RELATIVE diff; {@link #flatten} first to commit a chain's effective view. Entries
     * the commit made redundant clear (a root shard's diff empties; unloaded-chunk entries stay - they
     * couldn't commit); co-located shards see the base change wherever their own diffs don't mask it.
     */
    public void commitToBase() {
        Map<Point, Block> committed = new HashMap<>();
        diff().forEach((pos, block) -> {
            if (!base.isChunkLoaded(pos)) return;
            base.setBlock(pos, block);
            committed.put(pos, block);
        });
        relay(() -> {
            // view unchanged where the chain now matches; a child's masked entries stay load-bearing
            blocks.dropWhereBaseMatches(committed);
            return null;
        });
    }

    /** Takes ownership of {@code entity} (already in the base instance) from its current world; players = {@link #join}. */
    public void transfer(@NotNull Entity entity) {
        if (entity instanceof Player p) {
            join(p);
            return;
        }
        Shard from = of(entity);
        if (from == this) return;
        if (from != null) from.entities.remove(entity);
        entity.setTag(ENTITY_TAG, this);
        entities.add(entity);
        ShardView.applyViewableRule(entity);
        adoptTicking(entity);
        EventDispatcher.call(new ShardTransferEvent(entity, from, this));
    }

    @Override public @NotNull Collection<@NotNull Player> players() { return viewers.players(); }

    @Override public @NotNull Collection<@NotNull Player> watchers() { return viewers.watchers(); }

    @Override public void forEachMemberWithin(@NotNull Point point, double range, @NotNull Consumer<Player> action) {
        viewers.forEachMemberWithin(point, range, action);
    }

    /** {@link #watchers()} without the snapshot - what every send iterates. */
    @Override public void forEachWatcher(@NotNull Consumer<Player> action) {
        viewers.forEachWatcher(action);
    }

    @Override public void broadcast(@NotNull ServerPacket packet) {
        viewers.broadcast(packet);
    }

    @Override public void playSound(@NotNull Sound sound, @NotNull Point pos) {
        viewers.playSound(sound, pos);
    }

    void sendToBlockViewers(SendablePacket packet) {
        viewers.sendToBlockViewers(packet);
    }

    boolean hasBlockViewers() {
        return viewers.hasBlockViewers();
    }

    /** This chain's overlay entries in {@code (chunkX, chunkZ)} as one effective view (child wins) - the chunk-load re-send. */
    void sendChunkOverlay(Player player, int chunkX, int chunkZ) {
        blocks.sendChunkOverlay(player, chunkX, chunkZ);
    }

    void collectChunk(int chunkX, int chunkZ, Map<Point, Block> out) {
        blocks.collectChunk(chunkX, chunkZ, out);
    }

    @Override public @NotNull Weather weather() {
        Weather effective = effectiveWeatherOverride();
        return effective != null ? effective : base.getWeather();
    }

    @Override public void setWeather(@NotNull Weather weather) {
        sky.setWeather(weather);
        ShardSky.sendWeather(weather, p -> fanSky(p, false));
    }

    @Override public long time() {
        long effective = effectiveTimeOverride();
        return effective >= 0 ? effective : base.getTime();
    }

    /** The weather override, or {@code null} when inheriting the parent's/base's (persistence reads these, not the effective values). */
    public @Nullable Weather weatherOverride() { return sky.weatherOverride(); }

    /** The fixed-time override, or {@code -1} when inheriting the parent's/base's clock. */
    public long timeOverride() { return sky.timeOverride(); }

    /** FIXED time of day (clock rate 0 on the wire); negative = back to the parent view's clock. */
    @Override public void setTime(long time) {
        sky.setTime(time);
        ServerPacket packet = timePacket();
        if (packet != null) fanSky(packet, true);
    }

    // sky follows the block view: own viewers + descendants without their own override
    private void fanSky(ServerPacket packet, boolean time) {
        sendToBlockViewers(packet);
        for (Shard child : children) {
            boolean masked = time ? child.sky.timeOverride() >= 0 : child.sky.weatherOverride() != null;
            if (!masked) child.fanSky(packet, time);
        }
    }

    private @Nullable ServerPacket timePacket() {
        long effective = effectiveTimeOverride();
        return effective >= 0 ? ShardSky.fixedTimePacket(base, effective) : base.createTimePacket();
    }

    /** This shard's effective sky (weather + time) to one viewer - the block-view swap re-send. */
    void sendSky(Player player) {
        ShardSky.sendWeather(weather(), player::sendPacket);
        ServerPacket packet = timePacket();
        if (packet != null) player.sendPacket(packet);
    }

    @Override public long worldAge() { return base.getWorldAge(); }

    @Override public void scheduleNextTick(@NotNull Consumer<MechanicsWorld> task) {
        ExecutionDomain d = domain;
        // a dead clock runs execute() INLINE, and the usual idiom reschedules itself every tick: that is a
        // stack overflow on the caller. A stasis shard is frozen, so the base's next tick is the honest answer
        if (d != null && d.live()) {
            d.execute(() -> task.accept(this));
            return;
        }
        base.scheduleNextTick(in -> task.accept(this));
    }

    /**
     * Routes block placement/breaking into the actor's shard, intercepted at the packet - Minestom's own
     * pipeline reads the base map and resends base blocks on reject. The standard interact/dig/place/break
     * events re-fire shard-backed. Editing requires block view == membership; other views are read-only unless
     * {@link WorldPolicy#edits} grants it. Also replaces the stock placement listener with
     * {@link #placementListener} for players outside any shard view. Install once, globally.
     */
    public static EventNode<Event> blockRouting() {
        return BlockRouting.node();
    }

    /**
     * The stock placement listener with its entity-collision scan scoped through {@link WorldPolicy#canAffect}
     * (a co-located other-world entity never blocks the placer) - what {@link #blockRouting()} registers for
     * players outside any shard view. Public so wrappers (e.g. a 1.8 self-place compat fix) can compose and
     * re-register it.
     */
    public static void placementListener(@NotNull ClientPlayerBlockPlacementPacket packet, @NotNull Player player) {
        BasePlacement.listener(packet, player);
    }

    /** One body vs one placement: whether {@code body} blocks {@code placer} placing {@code block} at the cell ({@code cellRelativeBody} = body pos minus the cell). */
    @FunctionalInterface
    public interface PlacementBodyCheck {
        boolean blocks(@NotNull Player placer, @NotNull Entity body, @NotNull Block block,
                       @NotNull Point cellRelativeBody, @NotNull BoundingBox bodyBox);
    }

    /**
     * Replaces the per-body placement entity check (default: precise collision-shape intersection). Placer and
     * body are both in hand so the rule can follow the placer's client - a 1.8-mechanics lib excludes a LEGACY
     * placer's own body outright (the Paper 1.8 server did) and skips no-collision-box blocks for anyone,
     * leaving modern placers precise. Applies to every body: the placer, members, entities.
     */
    public static void placementBodyCheck(@NotNull PlacementBodyCheck rule) {
        BlockRouting.placementBodyCheck = rule;
    }

    /**
     * Neighbor placement rules for a change at {@code pos}, over THIS shard's view; every block they change
     * cascades. Routed places/digs fire it themselves; call after direct {@link #setBlock}
     * edits that should behave like gameplay (an explosion crater, a scripted break).
     */
    public void applyPhysics(@NotNull Point pos) {
        ShardUpdates.applyPhysics(this, pos);
    }

    /** The shard behind a rule-facing view the router handed out, or {@code null} for any other getter. */
    public static @Nullable Shard of(net.minestom.server.instance.block.Block.@NotNull Getter getter) {
        return getter instanceof ShardBlockView view ? view.shard() : null;
    }

    /** The break effect of a routed dig. Default: the vanilla world event to the shard's watchers minus
     *  the breaker (both client generations predict their own). */
    @FunctionalInterface
    public interface BreakFx {
        void play(@NotNull Player breaker, @NotNull Shard shard, @NotNull Point pos, @NotNull Block broken);
    }

    /** Replaces the routed-dig break effect (a mechanics lib re-points it through its own fx pipeline). */
    public static void breakFx(@NotNull BreakFx fx) {
        BlockRouting.breakFx = fx;
    }

    /**
     * Wraps the base placement listener {@link #blockRouting()} installs (a compat layer arming per-placement
     * state). Order-independent: registering before or after {@code blockRouting()} both work - the routing
     * re-applies the decorator whenever it (re)installs the listener.
     */
    public static void decoratePlacement(
            @NotNull java.util.function.UnaryOperator<java.util.function.BiConsumer<ClientPlayerBlockPlacementPacket, Player>> decorator) {
        BlockRouting.decoratePlacement(decorator);
    }
}
