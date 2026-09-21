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
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
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

    private final Instance base;
    private final @Nullable Shard parent;
    final List<Shard> children = new CopyOnWriteArrayList<>();
    private final Overlay overlay = new Overlay();
    private final ShardSky sky = new ShardSky();
    private final Set<Player> members = ConcurrentHashMap.newKeySet();
    private final Set<Player> membersView = Collections.unmodifiableSet(members);
    /** Non-members with an explicit {@link ShardView} referencing this shard; maintained by ShardView. */
    final Set<Player> observers = ConcurrentHashMap.newKeySet();
    private final Set<Player> observersView = Collections.unmodifiableSet(observers);
    final Set<Entity> entities = ConcurrentHashMap.newKeySet();
    private final ShardView memberView = ShardView.newMemberView(this);
    private final TagHandler tags = TagHandler.newHandler();
    private volatile @Nullable ExecutionDomain domain;
    private final List<BlockChangeHook> blockHooks = new CopyOnWriteArrayList<>();
    private final List<LongConsumer> tickHooks = new CopyOnWriteArrayList<>();
    /** Removal handle of the one underlying registration driving {@link #tickHooks} on the current clock. */
    private @Nullable Runnable tickDriver;
    private volatile @Nullable ShardLighting light;
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
        if (domain != null) onDomainBound.accept(this);
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
            this.domain = target;
            if (tickDriver != null) {
                tickDriver.run();
                tickDriver = startTickDriver();
            }
        }
        for (Player p : members) adoptTicking(p);
        onDomainBound.accept(this); // the world pass follows the clock, registered with a manager or not
        for (Player o : observers) adoptObserver(o);
        for (Entity e : entities) adoptTicking(e);
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
    private void lightChanged(Set<Long> sections) {
        ShardLighting l = light;
        if (l != null) l.changed(sections);
        for (Shard child : children) child.lightChanged(sections);
    }

    Set<Long> overlayChunks() {
        return overlay.chunkKeys();
    }

    boolean overlayTouches(int chunkX, int chunkZ) {
        return overlay.touches(chunkX, chunkZ);
    }

    /**
     * THIS shard's clock: its domain's tick, or the base instance's age when server-ticked. Stamp AND judge
     * tick-stamped state against this one clock; never compare across shards, and re-base stamps on
     * {@link #domain(ExecutionDomain) re-clock} ({@link ShardReclockEvent}).
     */
    public long ticks() {
        ExecutionDomain d = domain;
        return d != null ? d.ticks() : base.getWorldAge();
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
        blockHooks.add(hook);
        return () -> blockHooks.remove(hook);
    }

    private @Nullable Block preWrite(Point pos) {
        return blockHooks.isEmpty() ? null : getBlock(pos);
    }

    private void fireBlockChange(Point pos, @Nullable Block previous, Block block) {
        if (previous == null || previous.equals(block)) return; // nobody listened, or a view no-op
        for (BlockChangeHook hook : blockHooks) hook.changed(pos, previous, block);
    }

    /**
     * Per-tick hook on this shard's clock, called with {@link #ticks()} - a domain shard's own thread, a plain
     * shard's server scheduler. Survives a {@link #domain(ExecutionDomain) re-clock}. Returns the removal handle.
     */
    public @NotNull Runnable onTick(@NotNull LongConsumer hook) {
        synchronized (this) {
            tickHooks.add(hook);
            if (tickDriver == null) tickDriver = startTickDriver();
        }
        return () -> {
            synchronized (this) {
                if (!tickHooks.remove(hook)) return;
                // last hook out stops the driver - otherwise the scheduler task retains the shard forever
                if (tickHooks.isEmpty() && tickDriver != null) {
                    tickDriver.run();
                    tickDriver = null;
                }
            }
        };
    }

    // one underlying registration on the CURRENT clock fans out to the shard's hooks; swapped on re-clock
    private Runnable startTickDriver() {
        ExecutionDomain d = domain;
        if (d != null) return d.onTick(this::fireTickHooks);
        Task task = MinecraftServer.getSchedulerManager().scheduleTask(() -> fireTickHooks(ticks()),
                TaskSchedule.tick(1), TaskSchedule.tick(1));
        return task::cancel;
    }

    private void fireTickHooks(long tick) {
        for (LongConsumer hook : tickHooks) hook.accept(tick);
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
            forEachDiff((pos, block) -> {
                if (!base.isChunkLoaded(pos)) return; // never reached a client; nothing to revert
                Block revert = parent != null ? parent.getBlock(pos) : base.getBlock(pos);
                        // descendants saw this layer too, where unmasked
                BaseBlockFilter.ownSend(() -> fanBlock(pos, new BlockChangePacket(pos, revert)));
                fireBlockChange(pos, blockHooks.isEmpty() ? null : block, revert); // the entry IS the pre-reset view
            });
            overlay.clear();
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
        return overlay.size();
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
        overlay.forEach(consumer);
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
        Block b = overlay.at(x, y, z);
        if (b != null) return b;
        return parent != null ? parent.getBlock(x, y, z) : base.getBlock(x, y, z);
    }

    @Override public @NotNull Block getBlock(@NotNull Point pos, Block.Getter.@NotNull Condition condition) {
        return getBlock(pos.blockX(), pos.blockY(), pos.blockZ(), condition);
    }

    @Override public @NotNull Block getBlock(int x, int y, int z, Block.Getter.@NotNull Condition condition) {
        Block b = overlay.at(x, y, z);
        if (b != null) return b;
        return parent != null ? parent.getBlock(x, y, z, condition) : base.getBlock(x, y, z, condition);
    }

    /** This chain's own entry at the position, or {@code null} when the chain reads straight through to the base. */
    @Nullable Block overlaidAt(int x, int y, int z) {
        for (Shard s = this; s != null; s = s.parent) {
            Block block = s.overlay.at(x, y, z);
            if (block != null) return block;
        }
        return null;
    }

    /** This chain's overlays in front of {@code bottom}, child-first. */
    private Block.Getter overlaid(Block.Getter bottom) {
        return overlay.over(parent != null ? parent.overlaid(bottom) : bottom);
    }

    @Override public void setBlock(@NotNull Point pos, @NotNull Block block) {
        if (revertsToParent(pos, block)) {
            if (overlay.at(pos.blockX(), pos.blockY(), pos.blockZ()) == null) return; // total no-op
            Block previous = preWrite(pos);
            overlay.remove(pos);
            changed(pos, block);
            fireBlockChange(pos, previous, block);
            return;
        }
        pinBlock(pos, block);
    }

    /**
     * Drops this layer's diff entry at {@code pos} - the position reverts to the parent view. Works under an
     * unloaded chunk too (no viewer holds it; chunk load shows the parent view), where {@link #setBlock} would
     * have to read an unloadable parent block.
     */
    public void clearBlock(@NotNull Point pos) {
        if (base.isChunkLoaded(pos)) {
            setBlock(pos, parent != null ? parent.getBlock(pos) : base.getBlock(pos));
            return;
        }
        overlay.remove(pos);
    }

    /**
     * {@link #setBlock} that always keeps the diff entry: a write equal to the parent view PINS the position
     * (later parent/base changes no longer show through) instead of clearing it. Replay theaters use it -
     * recorded layer state must reproduce verbatim whatever order the ops land in.
     */
    public void pinBlock(@NotNull Point pos, @NotNull Block block) {
        Block previous = preWrite(pos);
        boolean shown = base.isChunkLoaded(pos) && block.equals(previous != null ? previous : getBlock(pos));
        overlay.write(pos, block);
        if (shown) return; // the pin lands, the wire stays quiet: a replay refold re-pins whole diffs
        changed(pos, block);
        fireBlockChange(pos, previous, block);
    }

    private void changed(Point pos, Block block) {
        BaseBlockFilter.ownSend(() -> fanBlock(pos, new BlockChangePacket(pos, block)));
        // no lighting anywhere below: skip the per-block section set and the descent
        if (light == null && children.isEmpty()) return;
        lightChanged(Set.of(ShardLighting.sectionKey(pos.blockX() >> 4, pos.blockY() >> 4, pos.blockZ() >> 4)));
    }

    // a write equal to the parent view isn't a diff: place-then-break ends with an EMPTY overlay, not a
    // redundant explicit-air entry (unloaded chunks can't be compared - keep the entry)
    private boolean revertsToParent(Point pos, Block block) {
        if (!base.isChunkLoaded(pos)) return false;
        return block.equals(parent != null ? parent.getBlock(pos) : base.getBlock(pos));
    }

    // a shard write has no native broadcast: it reaches this shard's block viewers plus every descendant's,
    // pruned wherever a descendant's own diff masks the position (masking cuts off that whole subtree)
    private void fanBlock(Point pos, BlockChangePacket packet) {
        sendToBlockViewers(packet);
        for (Shard child : children) {
            if (child.overlay.at(pos.blockX(), pos.blockY(), pos.blockZ()) == null) child.fanBlock(pos, packet);
        }
    }

    /** One overlay write pass, then one {@link MultiBlockChangePacket} per touched section to each block viewer. */
    @Override public void setBlocks(@NotNull Map<Point, Block> blocks) {
        relay(() -> {
            Map<Point, Block> changed = new HashMap<>();
            blocks.forEach((pos, block) -> {
                boolean reverts = revertsToParent(pos, block);
                if (reverts && overlay.at(pos.blockX(), pos.blockY(), pos.blockZ()) == null) return; // total no-op
                Block previous = preWrite(pos);
                if (reverts) overlay.remove(pos);
                else overlay.write(pos, block);
                changed.put(pos, block);
                fireBlockChange(pos, previous, block);
            });
            fanBatch(changed);
            Set<Long> sections = new HashSet<>();
            for (Point pos : changed.keySet()) {
                sections.add(ShardLighting.sectionKey(pos.blockX() >> 4, pos.blockY() >> 4, pos.blockZ() >> 4));
            }
            if (!sections.isEmpty()) lightChanged(sections);
            return null;
        });
    }

    // each level builds its own section packets (only if it has viewers) from the positions its diff doesn't mask
    private void fanBatch(Map<Point, Block> blocks) {
        if (blocks.isEmpty()) return;
        if (hasBlockViewers()) sendSections(blocks);
        for (Shard child : children) {
            Map<Point, Block> unmasked = new HashMap<>();
            blocks.forEach((pos, block) -> {
                if (child.overlay.at(pos.blockX(), pos.blockY(), pos.blockZ()) == null) unmasked.put(pos, block);
            });
            child.fanBatch(unmasked);
        }
    }

    private void sendSections(Map<Point, Block> blocks) {
        BaseBlockFilter.ownSend(() -> { for (MultiBlockChangePacket packet : sectionPackets(blocks)) sendToBlockViewers(packet); });
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
        ExecutionDomain d = domain;
        return d == null ? op.get() : d.relay(op);
    }

    /** {@link #relay} without waiting: {@code op} runs inline when already on this shard's clock, else on its
     *  next tick. The safe direction from another clock. */
    public void onOwner(@NotNull Runnable op) {
        ExecutionDomain d = domain;
        if (d != null && !d.onThread()) d.execute(op);
        else op.run();
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
        return relay(() -> { // the whole snapshot reads settled state, never mid-tick
            Shard copy = parent != null ? new Shard(parent, null) : new Shard(base);
            Map<Point, Block> kept = new HashMap<>();
            forEachDiff((pos, block) -> {
                Block out = blocks.apply(pos, block);
                if (out != null) kept.put(pos, out);
            });
            copy.setBlocks(kept);
            if (sky.timeOverride() >= 0) copy.setTime(sky.timeOverride());
            Weather weather = sky.weatherOverride();
            if (weather != null) copy.setWeather(weather);
            forEachClone(entityCloner, copy::spawn);
            return copy;
        });
    }

    /**
     * Seeds this shard with copies of the base instance's OWN entities ({@link #fork} semantics: a {@code null}
     * clone skips that entity). Shard-owned entities and players never copy. A shard over a base otherwise
     * starts empty - base entities are not shard-visible, so nothing carries over without this.
     */
    public @NotNull Shard seedFromBase(@Nullable Function<Entity, Entity> cloner) {
        if (cloner == null) return this;
        for (Entity entity : base.getEntities()) {
            if (entity.isRemoved() || entity instanceof Player || of(entity) != null) continue;
            Entity clone = cloner.apply(entity);
            if (clone != null) spawn(clone, entity.getPosition());
        }
        return this;
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
        if (bottom.base != base) {
            throw new IllegalArgumentException("shards flatten over one shared base instance");
        }
        Layer bottomSide;
        Layer topSide;
        if (sameClock(bottom)) { // one relay: the whole combine reads one settled tick
            Layer[] both = relay(() -> new Layer[]{bottom.snapshot(cloner), snapshot(cloner)});
            bottomSide = both[0];
            topSide = both[1];
        } else {
            bottomSide = bottom.relay(() -> bottom.snapshot(cloner));
            topSide = relay(() -> snapshot(cloner));
        }
        Shard flat = new Shard(base);
        Map<Point, Block> merged = new HashMap<>();
        Set<Point> union = new HashSet<>(bottomSide.blocks().keySet());
        union.addAll(topSide.blocks().keySet());
        for (Point pos : union) {
            Block block = merger.merge(pos, bottomSide.blocks().get(pos), topSide.blocks().get(pos));
            if (block != null) merged.put(pos, block);
        }
        flat.setBlocks(merged);
        long time = topSide.time() >= 0 ? topSide.time() : bottomSide.time();
        if (time >= 0) flat.setTime(time);
        Weather weather = topSide.weather() != null ? topSide.weather() : bottomSide.weather();
        if (weather != null) flat.setWeather(weather);
        bottomSide.clones().forEach(flat::spawn);
        topSide.clones().forEach(flat::spawn);
        return flat;
    }

    /**
     * This shard's whole CHAIN collapsed into one independent shard over the base: effective blocks, effective
     * sky, and every layer's own entities via {@code cloner} ({@link #fork} semantics; root layer's clones
     * spawn first). Sources untouched. BRANCHES never merge - siblings are alternative realities of the same
     * coordinates; flatten each leaf's chain. One tick-atomic snapshot on this shard's clock (a cross-clock
     * ancestor's entities read best-effort - one tree one clock is the recommended default).
     */
    public @NotNull Shard flatten(@Nullable Function<Entity, Entity> cloner) {
        return relay(() -> {
            Shard flat = new Shard(base);
            Map<Point, Block> blocks = new HashMap<>();
            effectiveDiff(blocks);
            flat.setBlocks(blocks);
            long time = effectiveTimeOverride();
            if (time >= 0) flat.setTime(time);
            Weather weather = effectiveWeatherOverride();
            if (weather != null) flat.setWeather(weather);
            if (cloner != null) {
                List<Shard> chain = new ArrayList<>();
                for (Shard s = this; s != null; s = s.parent) chain.add(s);
                for (int i = chain.size() - 1; i >= 0; i--) chain.get(i).forEachClone(cloner, flat::spawn);
            }
            return flat;
        });
    }

    /** {@link #flatten(Function)} without entities: blocks + sky only. */
    public @NotNull Shard flatten() {
        return flatten(null);
    }

    private boolean sameClock(Shard other) {
        if (domain == null || other.domain == null) return domain == other.domain;
        return domain.sharesClock(other.domain);
    }

    private record Layer(Map<Point, Block> blocks, long time, @Nullable Weather weather, Map<Entity, Pos> clones) {}

    private void forEachClone(@Nullable Function<Entity, Entity> cloner, BiConsumer<Entity, Pos> out) {
        if (cloner == null) return;
        for (Entity entity : entities) {
            if (entity.isRemoved()) continue;
            Entity clone = cloner.apply(entity);
            if (clone != null) out.accept(clone, entity.getPosition());
        }
    }

    // one side's settled state; call inside this shard's relay
    private Layer snapshot(@Nullable Function<Entity, Entity> cloner) {
        Map<Point, Block> blocks = new HashMap<>();
        effectiveDiff(blocks);
        Map<Entity, Pos> clones = new LinkedHashMap<>();
        forEachClone(cloner, clones::put);
        return new Layer(blocks, effectiveTimeOverride(), effectiveWeatherOverride(), clones);
    }

    // root-most first: upper layers overwrite
    private void effectiveDiff(Map<Point, Block> out) {
        if (parent != null) parent.effectiveDiff(out);
        forEachDiff(out::put);
    }

    private long effectiveTimeOverride() {
        long own = sky.timeOverride();
        if (own >= 0) return own;
        return parent != null ? parent.effectiveTimeOverride() : -1;
    }

    private @Nullable Weather effectiveWeatherOverride() {
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
            committed.forEach((pos, block) -> {
                if (revertsToParent(pos, block)) overlay.remove(pos);
            });
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

    @Override public @NotNull Collection<@NotNull Player> players() { return membersView; }

    @Override public @NotNull Collection<@NotNull Player> watchers() {
        Set<Player> allSeeing = ShardView.allSeeing();
        if (observers.isEmpty() && allSeeing.isEmpty()) return membersView;
        List<Player> out = new ArrayList<>(members);
        forEachExtraWatcher(out::add);
        return out;
    }

    // the base instance's chunk index, filtered to this shard's members: O(what is near), not O(members)
    @Override public void forEachMemberWithin(@NotNull Point point, double range, @NotNull Consumer<Player> action) {
        base.getEntityTracker().nearbyEntities(point, range, EntityTracker.Target.PLAYERS, p -> {
            if (members.contains(p)) action.accept(p);
        });
    }

    /** {@link #watchers()} without the snapshot - what every send iterates. */
    @Override public void forEachWatcher(@NotNull Consumer<Player> action) {
        for (Player m : members) action.accept(m);
        forEachExtraWatcher(action);
    }

    // only someone on THIS base instance renders this world, so a positional packet must never cross to
    // another instance - an observer registered from elsewhere included
    private void forEachExtraWatcher(Consumer<Player> action) {
        for (Player o : observers) {
            if (o.getInstance() == base && !members.contains(o)) action.accept(o);
        }
        for (Player o : ShardView.allSeeing()) {
            if (o.getInstance() == base && !members.contains(o) && !observers.contains(o)) action.accept(o);
        }
    }

    @Override public void broadcast(@NotNull ServerPacket packet) {
        forEachWatcher(p -> p.sendPacket(packet));
    }

    @Override public void playSound(@NotNull Sound sound, @NotNull Point pos) {
        forEachWatcher(p -> p.playSound(sound, pos.x(), pos.y(), pos.z()));
    }

    void sendToBlockViewers(SendablePacket packet) {
        forEachWatcher(p -> { if (blockViewer(p)) p.sendPacket(packet); });
    }

    private boolean blockViewer(Player p) {
        return p.getInstance() == base && WorldPolicy.seesBlocks(p, this);
    }

    // the same set sendToBlockViewers walks, with an early out
    boolean hasBlockViewers() {
        for (Player m : members) {
            if (blockViewer(m)) return true;
        }
        for (Player o : observers) {
            if (!members.contains(o) && blockViewer(o)) return true;
        }
        for (Player o : ShardView.allSeeing()) {
            if (!members.contains(o) && !observers.contains(o) && blockViewer(o)) return true;
        }
        return false;
    }

    /** {@code blocks} packed one {@link MultiBlockChangePacket} per 16-cube: a built map is thousands of positions. */
    static List<MultiBlockChangePacket> sectionPackets(Map<Point, Block> blocks) {
        record Section(int x, int y, int z) {}
        Map<Section, List<Long>> bySection = new HashMap<>();
        blocks.forEach((pos, block) -> bySection.computeIfAbsent(
                new Section(pos.blockX() >> 4, pos.blockY() >> 4, pos.blockZ() >> 4),
                k -> new ArrayList<>()).add(CoordConversion.encodeSectionBlockChange(
                pos.blockX() & 15, pos.blockY() & 15, pos.blockZ() & 15, block.stateId())));
        List<MultiBlockChangePacket> out = new ArrayList<>(bySection.size());
        bySection.forEach((section, entries) -> {
            long[] packed = new long[entries.size()];
            for (int i = 0; i < packed.length; i++) packed[i] = entries.get(i);
            out.add(new MultiBlockChangePacket(section.x(), section.y(), section.z(), packed));
        });
        return out;
    }

    /** This chain's overlay entries in {@code (chunkX, chunkZ)} as one effective view (child wins) - the chunk-load re-send. */
    void sendChunkOverlay(Player player, int chunkX, int chunkZ) {
        Map<Point, Block> effective = new HashMap<>();
        if (parent == null) overlay.forEachInChunk(chunkX, chunkZ, effective::put);
        else collectChunk(chunkX, chunkZ, effective);
        BaseBlockFilter.ownSend(() -> { for (MultiBlockChangePacket packet : sectionPackets(effective)) player.sendPacket(packet); });
        ShardLighting l = light;
        if (l != null) l.sendTo(player, chunkX, chunkZ);
    }

    void collectChunk(int chunkX, int chunkZ, Map<Point, Block> out) {
        if (parent != null) parent.collectChunk(chunkX, chunkZ, out);
        overlay.forEachInChunk(chunkX, chunkZ, out::put);
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
