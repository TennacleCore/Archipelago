package io.github.term4.shardkit;

import io.github.term4.worldaccess.InstanceWorldAccess;
import io.github.term4.worldaccess.WorldAccess;
import io.github.term4.worldaccess.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.collision.EntityCollisionResult;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.collision.PhysicsUtils;
import net.minestom.server.collision.Shape;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.ExperienceOrb;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityDespawnEvent;
import net.minestom.server.event.entity.EntityItemMergeEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerCancelDiggingEvent;
import net.minestom.server.event.player.PlayerChunkLoadEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerFinishDiggingEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.WorldBorder;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.BlockPredicates;
import net.minestom.server.item.component.ItemBlockState;
import net.minestom.server.item.component.Tool;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import net.minestom.server.network.packet.server.play.SetTimePacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.tag.TagHandler;
import net.minestom.server.utils.block.BlockBreakCalculation;
import net.minestom.server.world.DimensionType;
import net.minestom.server.worldevent.WorldEvent;
import net.kyori.adventure.sound.Sound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A game world as a DIFF over a shared base map: block mutations live in an overlay (the base instance is never
 * written), entities and viewers are shard-owned, and every member sees base + this shard's overlay. N shards run on
 * ONE loaded map at the same coordinates; {@link #reset} drops the diff. Gameplay code reaches this shard through
 * the {@link WorldAccess#ENTITY_TAG} binding ({@link #join}/{@link #spawn}/{@link #transfer} stamp it).
 *
 * <p>Audiences: {@link #players()} = game MEMBERS (gameplay targets them); {@link #watchers()} adds non-member
 * observers whose {@link ShardView} shows this shard (FX: {@link #broadcast}/{@link #playSound}); block changes go to
 * whoever's block view is this shard. {@link ShardJoinEvent}/{@link ShardLeaveEvent} are the hooks for routing chat,
 * scoreboards or tab per game.
 */
public final class Shard implements WorldAccess {

    private final Instance base;
    // diff storage: chunk key -> (packed chunk-local pos -> block). The chunk grouping IS the chunk-load/view-swap
    // index (a flat map + separate index doubled every position key), and an untouched chunk misses in one hop.
    private final Map<Long, ConcurrentHashMap<Integer, Block>> overlay = new ConcurrentHashMap<>();
    private final Set<Player> members = ConcurrentHashMap.newKeySet();
    private final Set<Player> membersView = Collections.unmodifiableSet(members);
    /** Non-members with an explicit {@link ShardView} referencing this shard; maintained by ShardView. */
    final Set<Player> observers = ConcurrentHashMap.newKeySet();
    private final Set<Player> observersView = Collections.unmodifiableSet(observers);
    private final Set<Entity> entities = ConcurrentHashMap.newKeySet();
    private final ShardView memberView = ShardView.newMemberView(this);
    private final TagHandler tags = TagHandler.newHandler();
    // sky overrides survive reset() (game settings, not diff); base weather/time broadcasts can stomp them - keep the base sky static
    private volatile @Nullable Weather weather;
    private volatile long fixedTime = -1;

    public Shard(@NotNull Instance base) {
        this.base = base;
    }

    // --- membership ---

    /** Admits {@code player}: binds them to this shard, drops any explicit {@link ShardView}, applies the member view. */
    public void join(@NotNull Player player) {
        // a shard->shard swap retags in ONE visibility pass - routing it through leave() put an unbound frame
        // in between, flashing the swapper and base players at each other
        Shard previous = player.getTag(ENTITY_TAG) instanceof Shard s && s != this ? s : null;
        ShardView old = ShardView.of(player);
        boolean left = previous != null && previous.members.remove(player);
        boolean added = members.add(player);
        player.setTag(ENTITY_TAG, this);
        ShardView.forget(player);
        ShardView.applyChange(player, old);
        if (left) EventDispatcher.call(new ShardLeaveEvent(player, previous));
        if (added) EventDispatcher.call(new ShardJoinEvent(player, this));
    }

    /** Removes {@code player}: unbinds, reverts to their explicit view or the base map, rescopes visibility. */
    public void leave(@NotNull Player player) {
        if (!members.remove(player)) return;
        ShardView old = ShardView.of(player);
        player.removeTag(ENTITY_TAG);
        ShardView.applyChange(player, old);
        EventDispatcher.call(new ShardLeaveEvent(player, this));
    }

    /** Drops the diff: every overlaid position reverts to the base map (block viewers get the changes). */
    public void reset() {
        forEachDiff((pos, block) -> {
            if (!base.isChunkLoaded(pos)) return; // never reached a client; nothing to revert
            sendToBlockViewers(new BlockChangePacket(pos, base.getBlock(pos)));
        });
        overlay.clear();
        for (Entity e : entities) e.remove();
        entities.clear();
    }

    public @NotNull Set<Player> members() { return membersView; }

    /** Players with an explicit {@link ShardView} showing this shard (spectators/staff; not members). */
    public @NotNull Set<Player> observers() { return observersView; }

    public int overlaySize() {
        int size = 0;
        for (Map<Integer, Block> chunk : overlay.values()) size += chunk.size();
        return size;
    }

    /** A snapshot of the diff - the EXPORT half; load one back with {@link #setBlocks}. */
    public @NotNull Map<Point, Block> diff() {
        Map<Point, Block> out = new HashMap<>();
        forEachDiff(out::put);
        return out;
    }

    /** Copy-free diff read (persistence/replay recorders). */
    public void forEachDiff(@NotNull BiConsumer<Point, Block> consumer) {
        overlay.forEach((chunkKey, blocks) -> blocks.forEach((local, block) ->
                consumer.accept(unpackLocal(chunkKey, local), block)));
    }

    ShardView memberView() { return memberView; }

    // --- world identity ---

    @Override public @NotNull Instance instance() { return base; }

    @Override public @NotNull TagHandler tagHandler() { return tags; }

    // --- blocks: overlay over the base map ---

    @Override public @NotNull Block getBlock(@NotNull Point pos) { return getBlock(pos.blockX(), pos.blockY(), pos.blockZ()); }

    @Override public @NotNull Block getBlock(int x, int y, int z) {
        Block b = diffAt(x, y, z);
        return b != null ? b : base.getBlock(x, y, z);
    }

    @Override public @NotNull Block getBlock(@NotNull Point pos, Block.Getter.@NotNull Condition condition) {
        return getBlock(pos.blockX(), pos.blockY(), pos.blockZ(), condition);
    }

    @Override public @NotNull Block getBlock(int x, int y, int z, Block.Getter.@NotNull Condition condition) {
        Block b = diffAt(x, y, z);
        return b != null ? b : base.getBlock(x, y, z, condition);
    }

    private @Nullable Block diffAt(int x, int y, int z) {
        Map<Integer, Block> chunk = overlay.get(chunkKey(x >> 4, z >> 4));
        return chunk != null ? chunk.get(local(x, y, z)) : null;
    }

    @Override public void setBlock(@NotNull Point pos, @NotNull Block block) {
        write(pos, block);
        sendToBlockViewers(new BlockChangePacket(pos, block));
    }

    /** One overlay write pass, then one {@link MultiBlockChangePacket} per touched section to each block viewer. */
    @Override public void setBlocks(@NotNull Map<Point, Block> blocks) {
        if (!hasBlockViewers()) { // bulk load into an unviewed shard: skip the packet build entirely
            blocks.forEach(this::write);
            return;
        }
        Map<Long, List<Long>> bySection = new HashMap<>();
        blocks.forEach((pos, block) -> {
            write(pos, block);
            long section = ((long) ((pos.blockX() >> 4) & 0x3FFFFF) << 42)
                    | ((pos.blockY() >> 4) & 0xFFFFF)
                    | ((long) ((pos.blockZ() >> 4) & 0x3FFFFF) << 20);
            bySection.computeIfAbsent(section, k -> new ArrayList<>()).add(CoordConversion.encodeSectionBlockChange(
                    pos.blockX() & 15, pos.blockY() & 15, pos.blockZ() & 15, block.stateId()));
        });
        bySection.forEach((section, entries) -> {
            long[] packed = new long[entries.size()];
            for (int i = 0; i < packed.length; i++) packed[i] = entries.get(i);
            sendToBlockViewers(new MultiBlockChangePacket(section, packed));
        });
    }

    private void write(Point pos, Block block) {
        overlay.computeIfAbsent(chunkKey(pos.blockX() >> 4, pos.blockZ() >> 4), k -> new ConcurrentHashMap<>())
                .put(local(pos.blockX(), pos.blockY(), pos.blockZ()), block);
    }

    @Override public boolean isChunkLoaded(@NotNull Point pos) { return base.isChunkLoaded(pos); }

    @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return base.isChunkLoaded(chunkX, chunkZ); }

    @Override public @NotNull DimensionType dimension() { return base.getCachedDimensionType(); }

    // --- physics: the base getters composited with the overlay ---

    @Override
    public PhysicsResult sweep(@NotNull BoundingBox box, @NotNull Pos position, @NotNull Vec velocity,
                               @Nullable PhysicsResult last, boolean singleCollision) {
        return CollisionUtils.handlePhysics(overlaid(InstanceWorldAccess.solidGetter(base, position)),
                box, position, velocity, last, singleCollision);
    }

    @Override
    public PhysicsResult sweepLoaded(@NotNull BoundingBox box, @NotNull Pos position, @NotNull Vec velocity,
                                     @Nullable PhysicsResult last, boolean singleCollision) {
        return CollisionUtils.handlePhysics(overlaid(InstanceWorldAccess.airGetter(base, position)),
                box, position, velocity, last, singleCollision);
    }

    /** The base getter with this shard's overlay in front. */
    private Block.Getter overlaid(Block.Getter baseGetter) {
        return (x, y, z, condition) -> {
            Block b = diffAt(x, y, z);
            return b != null ? b : baseGetter.getBlock(x, y, z, condition);
        };
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
                overlaid(InstanceWorldAccess.solidGetter(base, position)), aerodynamics, noGravity, hasPhysics, onGround, false, last);
    }

    /** Whether {@code entity} belongs to this shard (member player or shard-spawned entity). */
    public boolean contains(Entity entity) {
        return entity.getTag(ENTITY_TAG) == this;
    }

    @Override public @NotNull WorldBorder worldBorder() { return base.getWorldBorder(); }

    @Override public boolean isInVoid(@NotNull Point pos) { return base.isInVoid(pos); }

    // --- entities: shard-owned ---

    @Override public @NotNull Collection<@NotNull Entity> entities() {
        entities.removeIf(Entity::isRemoved); // safety net; the despawn listener is the primary cleanup
        members.removeIf(m -> !m.isOnline());
        List<Entity> all = new ArrayList<>(entities);
        all.addAll(members);
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

    @Override public void nearbyPlayers(@NotNull Point point, double range, @NotNull Consumer<Player> consumer) {
        double rangeSq = range * range;
        for (Player p : members) {
            if (p.getPosition().distanceSquared(point) <= rangeSq) consumer.accept(p);
        }
    }

    @Override public @NotNull CompletableFuture<Void> spawn(@NotNull Entity entity, @NotNull Pos position) {
        entity.setTag(ENTITY_TAG, this);
        entity.updateViewableRule(v -> ShardView.sees(v, entity));
        entities.add(entity);
        // re-fire the rule once tracked - a rule set before setInstance isn't applied to the initial viewer add
        return entity.setInstance(base, position).thenRun(entity::updateViewableRule);
    }

    /**
     * An independent copy over the same base: diff + sky, plus owned entities reproduced by {@code entityCloner}
     * (players never copy - membership is session state; a {@code null} clone skips that entity).
     */
    public @NotNull Shard fork(@Nullable Function<Entity, Entity> entityCloner) {
        Shard copy = new Shard(base);
        copy.setBlocks(diff());
        if (fixedTime >= 0) copy.setTime(fixedTime);
        Weather sky = weather;
        if (sky != null) copy.setWeather(sky);
        if (entityCloner != null) {
            for (Entity entity : entities) {
                if (entity.isRemoved()) continue;
                Entity clone = entityCloner.apply(entity);
                if (clone != null) copy.spawn(clone, entity.getPosition());
            }
        }
        return copy;
    }

    /** {@link #fork(Function)} without entities: blocks + sky only. */
    public @NotNull Shard fork() {
        return fork(null);
    }

    /**
     * The default fork cloner: {@link WorldAccess#ENTITY_COPY}-stamped entities run their supplier; exact-class
     * {@code Entity}/{@code LivingEntity}/{@code ItemEntity}/{@code ExperienceOrb} get a data copy - velocity,
     * SERIALIZABLE tags (transient tags are runtime state and never copy), plus health/equipment, stack/pickup
     * delay, or xp amount. Custom classes return {@code null} - only their owner can rebuild behavior; stamp
     * them, or compose: {@code e -> e instanceof MyNpc n ? n.copy() : Shard.copyEntity(e)}.
     */
    public static @Nullable Entity copyEntity(@NotNull Entity entity) {
        Supplier<Entity> stamped = entity.getTag(WorldAccess.ENTITY_COPY);
        if (stamped != null) return stamped.get();
        Class<?> type = entity.getClass();
        if (type == Entity.class) {
            Entity copy = new Entity(entity.getEntityType());
            copyData(entity, copy);
            return copy;
        }
        if (type == LivingEntity.class) {
            LivingEntity living = (LivingEntity) entity;
            LivingEntity copy = new LivingEntity(entity.getEntityType());
            copyData(entity, copy);
            copy.setHealth(living.getHealth());
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                copy.setEquipment(slot, living.getEquipment(slot));
            }
            return copy;
        }
        if (type == ItemEntity.class) {
            ItemEntity item = (ItemEntity) entity;
            ItemEntity copy = new ItemEntity(item.getItemStack());
            copy.setPickupDelay(Duration.ofMillis(item.getPickupDelay()));
            copyData(entity, copy);
            return copy;
        }
        if (type == ExperienceOrb.class) {
            ExperienceOrb copy = new ExperienceOrb(((ExperienceOrb) entity).getExperienceCount());
            copyData(entity, copy);
            return copy;
        }
        return null;
    }

    private static void copyData(Entity from, Entity to) {
        to.setVelocity(from.getVelocity());
        to.tagHandler().updateContent(from.tagHandler().asCompound()); // serializable tags only; Transient = empty serializer
    }

    /** Takes ownership of {@code entity} (already in the base instance) from its current world; players = {@link #join}. */
    public void transfer(@NotNull Entity entity) {
        if (entity instanceof Player p) {
            join(p);
            return;
        }
        Shard from = entity.getTag(ENTITY_TAG) instanceof Shard s ? s : null;
        if (from == this) return;
        if (from != null) from.entities.remove(entity);
        entity.setTag(ENTITY_TAG, this);
        entities.add(entity);
        entity.updateViewableRule(v -> ShardView.sees(v, entity));
        EventDispatcher.call(new ShardTransferEvent(entity, from, this));
    }

    // --- viewers / broadcast ---

    @Override public @NotNull Collection<@NotNull Player> players() { return membersView; }

    @Override public @NotNull Collection<@NotNull Player> watchers() {
        Set<Player> allSeeing = ShardView.allSeeing();
        if (observers.isEmpty() && allSeeing.isEmpty()) return membersView;
        List<Player> out = new ArrayList<>(members);
        for (Player o : observers) {
            if (!members.contains(o)) out.add(o);
        }
        for (Player o : allSeeing) {
            if (!members.contains(o) && !observers.contains(o)) out.add(o);
        }
        return out;
    }

    @Override public void broadcast(@NotNull ServerPacket packet) {
        for (Player p : watchers()) p.sendPacket(packet);
    }

    @Override public void playSound(@NotNull Sound sound, @NotNull Point pos) {
        for (Player p : watchers()) p.playSound(sound, pos.x(), pos.y(), pos.z());
    }

    private void sendToBlockViewers(ServerPacket packet) {
        for (Player m : members) {
            if (blockViewer(m)) m.sendPacket(packet);
        }
        for (Player o : observers) {
            if (!members.contains(o) && blockViewer(o)) o.sendPacket(packet);
        }
    }

    private boolean blockViewer(Player p) {
        if (p.getInstance() != base) return false;
        if (members.contains(p)) {
            ShardView v = ShardView.explicit(p);
            return v == null || v.blocks() == this;
        }
        return ShardView.of(p).blocks() == this;
    }

    private boolean hasBlockViewers() {
        for (Player m : members) {
            if (blockViewer(m)) return true;
        }
        for (Player o : observers) {
            if (blockViewer(o)) return true;
        }
        return false;
    }

    void sendChunkOverlay(Player player, int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        Map<Integer, Block> blocks = overlay.get(key);
        if (blocks == null) return;
        blocks.forEach((local, b) -> player.sendPacket(new BlockChangePacket(unpackLocal(key, local), b)));
    }

    @Override public @NotNull Weather weather() {
        Weather w = weather;
        return w != null ? w : base.getWeather();
    }

    @Override public void setWeather(@NotNull Weather weather) {
        this.weather = weather;
        sendWeather(weather, this::sendToBlockViewers);
    }

    @Override public long time() {
        long t = fixedTime;
        return t >= 0 ? t : base.getTime();
    }

    /** The weather override, or {@code null} when inheriting the base's (persistence reads these, not the effective values). */
    public @Nullable Weather weatherOverride() { return weather; }

    /** The fixed-time override, or {@code -1} when inheriting the base clock. */
    public long timeOverride() { return fixedTime; }

    /** FIXED time of day (clock rate 0 on the wire); negative = back to the base clock. */
    @Override public void setTime(long time) {
        this.fixedTime = time;
        var packet = time >= 0 ? fixedTimePacket(time) : base.createTimePacket();
        if (packet != null) sendToBlockViewers(packet);
    }

    private @Nullable SetTimePacket fixedTimePacket(long time) {
        var clock = base.getCachedDimensionType().defaultClock();
        if (clock == null) return null;
        return new SetTimePacket(base.getWorldAge(), Map.of(clock, new SetTimePacket.ClockState(time, 0f, 0f)));
    }

    /** This shard's effective sky (weather + time) to one viewer - the block-view swap re-send. */
    void sendSky(Player player) {
        sendWeather(weather(), player::sendPacket);
        var packet = fixedTime >= 0 ? fixedTimePacket(fixedTime) : base.createTimePacket();
        if (packet != null) player.sendPacket(packet);
    }

    static void sendBaseSky(Player player) {
        Instance in = player.getInstance();
        if (in == null) return;
        sendWeather(in.getWeather(), player::sendPacket);
        player.sendPacket(in.createTimePacket());
    }

    private static void sendWeather(Weather weather, Consumer<ServerPacket> out) {
        out.accept(weather.createIsRainingPacket());
        out.accept(weather.createRainLevelPacket());
        out.accept(weather.createThunderLevelPacket());
    }

    @Override public long worldAge() { return base.getWorldAge(); }

    @Override public void scheduleNextTick(@NotNull Consumer<WorldAccess> task) {
        base.scheduleNextTick(in -> task.accept(this));
    }

    // --- wiring ---

    /**
     * Routes block placement/breaking into the actor's shard, INTERCEPTED AT THE PACKET so Minestom's pipeline
     * (whose replaceable/entity/dig checks all read the BASE map, and whose reject paths resend base blocks or whole
     * chunks) never runs for them. The standard interact/dig/{@link PlayerBlockPlaceEvent}/{@link PlayerBlockBreakEvent}
     * events are re-fired shard-backed, so item listeners, drops and crack overlays keep working; every client update
     * is a single block change. Editing requires block view == membership; any other shard view is read-only unless
     * {@link WorldPolicy#edits} grants it. Install once, globally.
     */
    public static EventNode<Event> blockRouting() {
        // the plain default can't know about ShardView block-view state; don't clobber a custom policy
        if (WorldPolicy.get() == WorldPolicy.SAME_WORLD) WorldPolicy.set(ShardPolicy.DEFAULT);
        EventNode<Event> node = EventNode.all("shardkit:blocks");
        node.addListener(PlayerPacketEvent.class, e -> {
            boolean place = e.getPacket() instanceof ClientPlayerBlockPlacementPacket;
            if (!place && !(e.getPacket() instanceof ClientPlayerActionPacket)) return;
            Player player = e.getPlayer();
            Shard bound = player.getTag(ENTITY_TAG) instanceof Shard d && player.getInstance() == d.base ? d : null;
            Shard viewed = ShardView.of(player).blocks();
            if (viewed != null && player.getInstance() != viewed.base) viewed = null;
            boolean handled;
            if (bound != null && bound == viewed) {
                handled = place ? bound.handlePlace(player, (ClientPlayerBlockPlacementPacket) e.getPacket())
                        : bound.handleDig(player, (ClientPlayerActionPacket) e.getPacket());
            } else if (viewed != null && bound != viewed && WorldPolicy.canEdit(player, viewed)) {
                handled = place ? viewed.handlePlace(player, (ClientPlayerBlockPlacementPacket) e.getPacket())
                        : viewed.handleDig(player, (ClientPlayerActionPacket) e.getPacket());
            } else if (bound != null || viewed != null) {
                handled = place ? readOnlyPlace(player, viewed, (ClientPlayerBlockPlacementPacket) e.getPacket())
                        : readOnlyDig(player, viewed, (ClientPlayerActionPacket) e.getPacket());
            } else return;
            if (handled) e.setCancelled(true);
        });
        // late joiners get the visibility rules, or they'd see every shard's players/entities until the next join/leave
        node.addListener(PlayerSpawnEvent.class, e -> {
            ShardView.applyRules(e.getPlayer());
            Shard viewed = ShardView.of(e.getPlayer()).blocks();
            if (viewed != null && e.getPlayer().getInstance() == viewed.base) viewed.sendSky(e.getPlayer());
        });
        // membership dies with the connection - a stale member's body would keep blocking placements at its last position
        node.addListener(PlayerDisconnectEvent.class, e -> {
            if (e.getPlayer().getTag(ENTITY_TAG) instanceof Shard shard) shard.leave(e.getPlayer());
            ShardView.forget(e.getPlayer());
        });
        node.addListener(EntityDespawnEvent.class, e -> {
            if (e.getEntity().getTag(ENTITY_TAG) instanceof Shard shard) shard.entities.remove(e.getEntity());
        });
        // Minestom's item-merge scan is instance-wide - co-located items from different worlds absorb each
        // other (fork clones spawn exactly on their originals)
        node.addListener(EntityItemMergeEvent.class, e -> {
            if (!WorldPolicy.canAffect(e.getEntity(), e.getMerged())) e.setCancelled(true);
        });
        node.addListener(PlayerChunkLoadEvent.class, e -> {
            Shard viewed = ShardView.of(e.getPlayer()).blocks();
            if (viewed == null || e.getPlayer().getInstance() != viewed.base) return;
            viewed.sendChunkOverlay(e.getPlayer(), e.getChunkX(), e.getChunkZ());
        });
        return node;
    }

    /** WorldAccess-backed placement; {@code true} = handled (swallow the packet). Non-block items pass to Minestom (interactions, throwables). */
    private boolean handlePlace(Player player, ClientPlayerBlockPlacementPacket p) {
        ItemStack held = player.getItemInHand(p.hand());
        Material material = held.material();
        if (!material.isBlock()) return false;
        if (!base.isChunkLoaded(p.blockPosition())) return true;
        player.sendPacket(new AcknowledgeBlockChangePacket(p.sequence()));
        BlockVec clicked = p.blockPosition().asBlockVec();
        Vec cursor = new Vec(p.cursorPositionX(), p.cursorPositionY(), p.cursorPositionZ());
        Block interacted = getBlock(clicked);

        // block-handler onInteract is skipped: overlay writes never attach handlers
        PlayerBlockInteractEvent interact = new PlayerBlockInteractEvent(player, p.hand(), base, interacted, clicked, cursor, p.blockFace());
        EventDispatcher.call(interact);
        if (interact.isBlockingItemUse()) return true;

        boolean canPlace = switch (player.getGameMode()) {
            case SPECTATOR -> false;
            case ADVENTURE -> held.get(DataComponents.CAN_PLACE_ON, BlockPredicates.NEVER).test(interacted);
            default -> true;
        };

        var manager = MinecraftServer.getBlockManager();
        // vanilla (and the client's prediction) replaces a replaceable clicked block in place - upstream Minestom
        // bumps those to the neighbour; isSelfReplaceable = slab merging
        BlockVec target;
        var interactedRule = manager.getBlockPlacementRule(interacted);
        if (interacted.registry().isReplaceable() || (interactedRule != null && interactedRule.isSelfReplaceable(
                new BlockPlacementRule.Replacement(interacted, p.blockFace(), cursor, false, material)))) {
            target = clicked;
        } else {
            target = clicked.relative(p.blockFace());
            Block atTarget = getBlock(target);
            var targetRule = manager.getBlockPlacementRule(atTarget);
            if (!atTarget.registry().isReplaceable() && !(targetRule != null && targetRule.isSelfReplaceable(
                    new BlockPlacementRule.Replacement(atTarget, p.blockFace(), cursor, true, material)))) {
                canPlace = false;
            }
        }

        DimensionType dim = base.getCachedDimensionType();
        if (target.y() >= dim.maxY() || target.y() < dim.minY()) {
            rejectPlace(player, clicked, target);
            return true;
        }
        if (!base.getWorldBorder().inBounds(target)) canPlace = false;

        Block block = held.get(DataComponents.BLOCK_STATE, ItemBlockState.EMPTY).apply(material.block());
        if (!canPlace || intersectsEntity(target, player, block)) {
            rejectPlace(player, clicked, target);
            return true;
        }
        // orientation/shape rules over the SHARD view; the base pipeline runs them inside placeBlock - here pre-event
        var rule = manager.getBlockPlacementRule(block);
        if (rule != null) {
            Block ruled = rule.blockPlace(new BlockPlacementRule.PlacementState(
                    (x, y, z, cond) -> getBlock(x, y, z, cond), block, p.blockFace(), target,
                    cursor, player.getPosition(), held, player.isSneaking()));
            if (ruled == null) { rejectPlace(player, clicked, target); return true; } // the rule rejected the spot
            block = ruled;
        }
        PlayerBlockPlaceEvent event = new PlayerBlockPlaceEvent(player, base, block, p.blockFace(), target, cursor, p.hand());
        event.consumeBlock(player.getGameMode() != GameMode.CREATIVE);
        EventDispatcher.call(event);
        if (event.isCancelled()) {
            rejectPlace(player, clicked, target);
            return true;
        }
        setBlock(target, event.getBlock());
        if (event.doesConsumeBlock()) player.setItemInHand(p.hand(), held.consume(1));
        else player.getInventory().update();
        return true;
    }

    /** WorldAccess-backed digging; {@code true} = handled. Trusts the client's FINISHED timing like the base pipeline. */
    private boolean handleDig(Player player, ClientPlayerActionPacket p) {
        var status = p.status();
        boolean start = status == ClientPlayerActionPacket.Status.STARTED_DIGGING;
        boolean finish = status == ClientPlayerActionPacket.Status.FINISHED_DIGGING;
        boolean cancel = status == ClientPlayerActionPacket.Status.CANCELLED_DIGGING;
        if (!start && !finish && !cancel) return false; // drops / item state etc. stay Minestom's
        if (!base.isChunkLoaded(p.blockPosition())) return true;
        player.sendPacket(new AcknowledgeBlockChangePacket(p.sequence()));
        BlockVec pos = p.blockPosition().asBlockVec();
        Block block = getBlock(pos);
        if (cancel) {
            EventDispatcher.call(new PlayerCancelDiggingEvent(player, base, block, pos));
            return true;
        }
        if (block.isAir()) return true;
        if (shouldPreventBreaking(player, block)) {
            resync(player, pos, pos);
            return true;
        }
        int breakTicks = BlockBreakCalculation.breakTicks(block, player);
        if (start && breakTicks != 0) { // the client animates the dig; its FINISHED completes it
            EventDispatcher.call(new PlayerStartDiggingEvent(player, base, block, pos, p.blockFace()));
            return true;
        }
        if (finish) {
            if (breakTicks == BlockBreakCalculation.UNBREAKABLE) { // hacked-client FINISH on bedrock
                EventDispatcher.call(new PlayerCancelDiggingEvent(player, base, block, pos));
                resync(player, pos, pos);
                return true;
            }
            PlayerFinishDiggingEvent finishEvent = new PlayerFinishDiggingEvent(player, base, block, pos);
            EventDispatcher.call(finishEvent);
            block = finishEvent.getBlock();
        }
        PlayerBlockBreakEvent event = new PlayerBlockBreakEvent(player, base, block, Block.AIR, pos, p.blockFace());
        EventDispatcher.call(event);
        if (event.isCancelled()) {
            resync(player, pos, pos);
            return true;
        }
        setBlock(pos, event.getResultBlock());
        var fx = new WorldEventPacket(WorldEvent.PARTICLES_DESTROY_BLOCK.id(), pos, block.stateId(), false);
        for (Player w : watchers()) {
            if (w != player) w.sendPacket(fx);
        }
        return true;
    }

    // upstream PlayerActionListener.shouldPreventBreaking
    private static boolean shouldPreventBreaking(Player player, Block block) {
        ItemStack held = player.getItemInMainHand();
        return switch (player.getGameMode()) {
            case SPECTATOR -> true;
            case ADVENTURE -> !held.get(DataComponents.CAN_BREAK, BlockPredicates.NEVER).test(block);
            case CREATIVE -> {
                Tool tool = held.get(DataComponents.TOOL);
                yield tool != null && !tool.canDestroyBlocksInCreative();
            }
            default -> false;
        };
    }

    /** A non-member's (or view-swapped member's) edit: swallowed, the client corrected from its viewed world. */
    private static boolean readOnlyPlace(Player player, @Nullable Shard viewed, ClientPlayerBlockPlacementPacket p) {
        if (!player.getItemInHand(p.hand()).material().isBlock()) return false;
        player.sendPacket(new AcknowledgeBlockChangePacket(p.sequence()));
        player.getInventory().update();
        BlockVec clicked = p.blockPosition().asBlockVec();
        sendViewedBlock(player, viewed, clicked);
        sendViewedBlock(player, viewed, clicked.relative(p.blockFace()));
        return true;
    }

    private static boolean readOnlyDig(Player player, @Nullable Shard viewed, ClientPlayerActionPacket p) {
        var status = p.status();
        if (status != ClientPlayerActionPacket.Status.STARTED_DIGGING
                && status != ClientPlayerActionPacket.Status.FINISHED_DIGGING
                && status != ClientPlayerActionPacket.Status.CANCELLED_DIGGING) return false;
        player.sendPacket(new AcknowledgeBlockChangePacket(p.sequence()));
        if (status != ClientPlayerActionPacket.Status.CANCELLED_DIGGING) {
            sendViewedBlock(player, viewed, p.blockPosition().asBlockVec());
        }
        return true;
    }

    private static void sendViewedBlock(Player player, @Nullable Shard viewed, Point pos) {
        Instance world = player.getInstance();
        if (world == null || !world.isChunkLoaded(pos)) return;
        Block block = viewed != null ? viewed.getBlock(pos) : world.getBlock(pos);
        player.sendPacket(new BlockChangePacket(pos, block));
    }

    /** Blocks a placer may put into their OWN body (e.g. a 1.8-mechanics lib exempts ladders/vines/webs). */
    private static volatile Predicate<Block> selfPlaceExempt = block -> false;

    public static void selfPlaceExempt(@NotNull Predicate<Block> rule) {
        selfPlaceExempt = rule;
    }

    /** Whether a block at {@code pos} would overlap a shard entity/member (upstream canPlaceBlockAt over this shard). */
    private boolean intersectsEntity(Point pos, Player placer, Block placing) {
        boolean placerExempt = selfPlaceExempt.test(placing);
        Shape shape = placing.registry().collisionShape();
        for (Entity e : entities) {
            if (!e.isRemoved() && intersects(shape, pos, e)) return true;
        }
        for (Player m : members) {
            if (m == placer && placerExempt) continue;
            if (intersects(shape, pos, m)) return true;
        }
        return false;
    }

    private static boolean intersects(Shape shape, Point pos, Entity e) {
        if (!e.preventBlockPlacement()) return false; // items/arrows never block placement (vanilla)
        Point ep = e.getPosition();
        if (e instanceof Player) ep = ep.add(ep.sub(pos).mul(0.0000001)); // upstream: nudged off the block edge
        return shape.intersectBox(ep.sub(pos), e.getBoundingBox());
    }

    /** A rejected placement: the client already decremented its held stack, so refresh the inventory too. */
    private void rejectPlace(Player actor, Point clicked, Point target) {
        actor.getInventory().update();
        resync(actor, clicked, target);
    }

    /** Re-asserts the shard view of the interaction site to the actor (whose client predicted a change). */
    private void resync(Player actor, Point clicked, Point target) {
        actor.sendPacket(new BlockChangePacket(clicked, getBlock(clicked)));
        if (!target.equals(clicked)) actor.sendPacket(new BlockChangePacket(target, getBlock(target)));
    }

    // chunk-local key: x | z<<4 | y<<8 (the arithmetic unshift keeps y's sign)
    private static int local(int x, int y, int z) {
        return (x & 15) | ((z & 15) << 4) | (y << 8);
    }

    private static Point unpackLocal(long chunkKey, int local) {
        int cx = (int) (chunkKey >> 32), cz = (int) chunkKey;
        return new Vec((cx << 4) + (local & 15), local >> 8, (cz << 4) + ((local >> 4) & 15));
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
