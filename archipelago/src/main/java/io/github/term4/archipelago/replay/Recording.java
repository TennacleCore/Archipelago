package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.event.ShardDestroyEvent;
import io.github.term4.archipelago.event.replay.RecordingStopEvent;
import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.replay.channel.Dispatcher;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.core.Channels;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.archipelago.world.WorldAccess;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.instance.block.Block;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One recording session: the t0 snapshot is taken immediately, dispatchers fill the channels until
 * {@link #stop()} returns the file. Entities are not part of the snapshot - every one (t0 included) rides the
 * spawn channel as a visual snapshot. The session auto-ends when its shard is
 * destroyed (fail-stop included) - the stop event fires, and {@link #stop()} still hands over the footage.
 */
public final class Recording {

    private static final Logger LOGGER = LoggerFactory.getLogger(Recording.class);

    private final Shard shard;
    private final Predicate<Shard> layerFilter;
    private final ShardFile.Content t0;
    private final Map<Recordable<?>, Queue<Recorded<?>>> ops = new ConcurrentHashMap<>();
    private final List<Dispatcher> dispatchers;
    private final Runnable stopSampler;

    private record Track(int seq, long spawnTick) {}

    // mutators hop via onOwner, but a retired domain runs them inline on the caller - stay concurrent-safe
    private final Map<Entity, Track> tracked = new ConcurrentHashMap<>();
    private final Map<Point, String> covered = new ConcurrentHashMap<>();
    private final List<Runnable> unhooks = new CopyOnWriteArrayList<>();
    private final List<Replay.Bookmark> bookmarks = new CopyOnWriteArrayList<>();
    private final Map<Entity, String> pickedUpBy = new ConcurrentHashMap<>();
    private final Set<String> refreshActors = ConcurrentHashMap.newKeySet();
    private final Set<Integer> seen = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextSeq = new AtomicInteger();
    private final Map<Shard, Integer> layerIds = new ConcurrentHashMap<>();
    private final Set<Shard> deadLayers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextLayer = new AtomicInteger();
    private final EventNode<Event> shardWatch;
    private volatile long tick;
    private volatile boolean understructure = true;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private boolean loggedError;

    Recording(@NotNull Shard shard, @NotNull Predicate<Shard> layerFilter) {
        this.shard = shard;
        this.layerFilter = layerFilter;
        this.t0 = ShardFile.snapshot(shard);
        layerIds.put(shard, nextLayer.getAndIncrement()); // the recorded root is always layer 0
        watchBlocks(shard, 0);
        this.dispatchers = new ArrayList<>();
        Replay.dispatcherFactories().forEach(factory -> dispatchers.add(factory.get()));
        dispatchers.forEach(d -> d.start(this));
        // the recording lives on the shard's clock: one thread, one phase for every stamp
        this.stopSampler = shard.onTick(t -> sample());
        this.shardWatch = EventNode.all("archipelago:replay-recording@" + System.identityHashCode(this));
        shardWatch.addListener(ShardDestroyEvent.class, e -> {
            if (e.shard() == shard) endRecording();
        });
        MinecraftServer.getGlobalEventHandler().addChild(shardWatch);
    }

    // a domain shard's probes fire on assorted threads (its clock, main end-of-tick); ops land via the owner's
    // thread - stamps captured at CALL time so a hop doesn't shift the op a tick late
    private void onOwner(Runnable op) {
        ExecutionDomain domain = shard.domain();
        if (domain != null && !domain.onThread()) domain.execute(op);
        else op.run();
    }

    private void sample() {
        tick++;
        for (Dispatcher dispatcher : dispatchers) {
            try {
                dispatcher.sample(this);
            } catch (Exception e) {
                if (!loggedError) { // one bad dispatcher must never stall the rest of the recording
                    loggedError = true;
                    LOGGER.warn("recording dispatcher failed (logged once)", e);
                }
            }
        }
    }

    public @NotNull Shard shard() {
        return shard;
    }

    /** The current recording tick (0 until the first sampled tick). */
    public long tick() {
        return tick;
    }

    public boolean active() {
        return active.get();
    }

    /** Whether {@code entity} plays anywhere in the recorded tree - the filter for event probes. */
    public boolean covers(@NotNull Entity entity) {
        // tag identity up the chain, not WorldAccess.of(): a wrapper can never match, and of() NPEs pre-spawn
        return entity.getTag(WorldAccess.ENTITY_TAG) instanceof Shard bound && inTree(bound);
    }

    // the root always records; an excluded layer cuts off its whole subtree (a child's diff is
    // meaningless without its parent)
    private boolean inTree(Shard bound) {
        for (Shard s = bound; s != null; s = s.parent()) {
            if (s == shard) return true;
            if (!layerFilter.test(s)) return false;
        }
        return false;
    }

    /** The recorded layer shards, parents before children (the sweep and samplers iterate this). */
    public @NotNull List<Shard> layers() {
        List<Shard> out = new ArrayList<>();
        collectLayers(shard, out);
        return out;
    }

    private void collectLayers(Shard s, List<Shard> out) {
        out.add(s);
        for (Shard child : s.children()) {
            if (layerFilter.test(child)) collectLayers(child, out);
        }
    }

    /** {@code layer}'s id in this recording; first sight registers it (create op + its relative diff, dumped as layered block ops). */
    public int layer(@NotNull Shard layer) {
        Integer known = layerIds.get(layer);
        if (known != null) return known;
        if (!inTree(layer)) throw new IllegalArgumentException("not a recorded layer (outside the tree, or filtered out)");
        int parent = layer.parent() == null ? 0 : layer(layer.parent()); // outside computeIfAbsent: no reentry
        return layerIds.computeIfAbsent(layer, l -> {
            int id = nextLayer.getAndIncrement();
            emit(Channels.LAYERS, new Channels.Layer(id, parent, true));
            // the dump is a snapshot: a cross-clock layer's reads ride ITS clock or a batch could tear
            // (emits hop back with call-time stamps); a plain layer under a domain recording stays best-effort
            onLayerClock(l, () -> {
                l.forEachDiff((pos, block) -> emit(Channels.BLOCKS, new Channels.BlockOp(id,
                        pos.blockX(), pos.blockY(), pos.blockZ(), Nbt.blockString(block))));
                watchBlocks(l, id); // same clock task: no write lands between the dump and the hook
            });
            return id;
        });
    }

    // the write hook is THE block source - player edits, server setBlocks, playback writes and commits all
    // record here (a cancelled place never writes, so it never records); event probes would double these
    private void watchBlocks(Shard layer, int id) {
        if (!active.get()) return;
        unhooks.add(layer.onBlockChange((pos, previous, block) -> {
            if (id == 0) blockCovered(pos, previous);
            emit(Channels.BLOCKS, new Channels.BlockOp(id,
                    pos.blockX(), pos.blockY(), pos.blockZ(), Nbt.blockString(block)));
        }));
    }

    private void onLayerClock(Shard layer, Runnable op) {
        ExecutionDomain mine = shard.domain(), theirs = layer.domain();
        if (theirs == null || theirs == mine || (mine != null && theirs.sharesClock(mine))) op.run();
        else theirs.execute(op);
    }

    /** Records {@code layer} leaving the tree; ids never reuse. */
    public void layerGone(@NotNull Shard layer) {
        Integer id = layerIds.get(layer);
        if (id != null && deadLayers.add(layer)) emit(Channels.LAYERS, new Channels.Layer(id, 0, false));
    }

    /** The layer id {@code entity} currently plays in (its binding), or {@code 0} when outside the tree. */
    public int layerOf(@NotNull Entity entity) {
        return entity.getTag(WorldAccess.ENTITY_TAG) instanceof Shard bound && inTree(bound) ? layer(bound) : 0;
    }

    /** Appends an op to {@code channel} at the current tick (dropped once stopped). */
    public <T> void emit(@NotNull Recordable<T> channel, @NotNull T op) {
        long at = tick;
        onOwner(() -> emitAt(channel, op, at));
    }

    private <T> void emitAt(Recordable<T> channel, T op, long atTick) {
        if (!active.get()) return;
        ops.computeIfAbsent(channel, c -> new ConcurrentLinkedQueue<>()).add(new Recorded<>(atTick, op));
    }

    /**
     * Records an entity appearing on the core spawn channel: visual snapshot, position track + removal from
     * then on. Repeat sightings dedup (sweep and event probes may both call); {@code pos} carries the spawn
     * rotation; {@code shooter} = the launcher's username ({@code ""} = none), the wire owner.
     */
    public void spawn(@NotNull Entity entity, @NotNull Pos pos, @NotNull String shooter) {
        if (!active.get() || !seen.add(entity.getEntityId())) return;
        // state captured at CALL time (the entity keeps moving); bookkeeping lands on the owner thread
        CompoundBinaryTag visual = Visuals.snapshot(entity);
        int layer = layerOf(entity);
        long at = tick;
        onOwner(() -> {
            int seq = nextSeq.getAndIncrement();
            tracked.put(entity, new Track(seq, at));
            emitAt(Channels.SPAWNS, new Channels.Spawn(layer, pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch(),
                    seq, visual, shooter), at);
        });
    }

    /**
     * Records a tracked entity ending at an exact position - beats the per-tick sweep by up to a tick (an
     * impacting projectile otherwise sails past its mark). The twin lives >= 1 tick so a same-tick spawn+remove
     * still flashes. No-op when untracked; {@code collector} names the pickup swoosh's ghost ({@code ""} = none).
     */
    public void remove(@NotNull Entity entity, @NotNull Pos at, @NotNull String collector) {
        if (!active.get()) return;
        long now = tick;
        onOwner(() -> {
            Track track = tracked.remove(entity);
            if (track == null) return;
            pickedUpBy.remove(entity);
            long when = Math.max(now, track.spawnTick() + 1);
            emitAt(Channels.ENTITY_MOVES, new Channels.EntityMove(track.seq(), at.x(), at.y(), at.z(), at.yaw(), at.pitch()), when);
            emitAt(Channels.REMOVES, new Channels.Remove(track.seq(), collector), when);
        });
    }

    /**
     * Records what {@code pos} showed before the recorded root's first write there. Rides out as tick-0
     * block ops ahead of the channel: they hold the historical view when a playback theater is a child of
     * the still-live shard (the time-rewind recipe) - a fresh theater's copies normalize away.
     */
    public void blockCovered(@NotNull Point pos, @NotNull Block before) {
        if (!active.get() || !understructure) return;
        covered.putIfAbsent(new Vec(pos.blockX(), pos.blockY(), pos.blockZ()), Nbt.blockString(before));
    }

    /**
     * Whether {@link #blockCovered} masks are kept (default true). Archival view-only footage can drop them
     * for size - up to one op per touched position; forward play, reverse viewing and seeks into a FRESH
     * theater never need them, only rewinding over the still-live shard does.
     */
    public @NotNull Recording understructure(boolean keep) {
        understructure = keep;
        if (!keep) covered.clear();
        return this;
    }

    /** Marks the current tick in the bookmark index (a goal, a bed break) - any thread, mid-game. */
    public void bookmark(@NotNull String name) {
        bookmark(name, CompoundBinaryTag.empty());
    }

    /** {@link #bookmark(String)} with an app payload. */
    public void bookmark(@NotNull String name, @NotNull CompoundBinaryTag data) {
        if (active.get()) bookmarks.add(new Replay.Bookmark(tick, name, data));
    }

    /** The bookmark index so far, in mark order. */
    public @NotNull List<Replay.Bookmark> bookmarks() {
        return List.copyOf(bookmarks);
    }

    /** Whether {@code entity} is on the entity track (spawned, not yet removed). */
    public boolean tracks(@NotNull Entity entity) {
        return tracked.containsKey(entity);
    }

    /** Every tracked entity with its recorded seq. */
    public void forEachTracked(@NotNull BiConsumer<Entity, Integer> consumer) {
        tracked.forEach((entity, track) -> consumer.accept(entity, track.seq()));
    }

    /** Names who collected {@code entity}: its removal plays the pickup swoosh toward that actor's ghost. */
    public void collected(@NotNull Entity entity, @NotNull String collector) {
        if (tracked.containsKey(entity)) pickedUpBy.put(entity, collector);
    }

    /** {@link #remove(Entity, Pos, String)} with the {@link #collected} attribution - the sweep path. */
    public void remove(@NotNull Entity entity, @NotNull Pos at) {
        remove(entity, at, pickedUpBy.getOrDefault(entity, ""));
    }

    /** Marks {@code actor} for a full state re-emit next sample (respawn: the ghost is recreated fresh). */
    public void refreshActor(@NotNull String actor) {
        refreshActors.add(actor);
    }

    /** Consumes {@code actor}'s pending refresh; the sampler drops its diff caches when true. */
    public boolean consumeRefresh(@NotNull String actor) {
        return refreshActors.remove(actor);
    }

    /** Ends the recording (a no-op when it already auto-ended) and returns the replay file: the t0 snapshot with the op log in its user data. */
    public byte @NotNull [] stop() {
        return stop(null);
    }

    /** {@link #stop()} with the app's own {@link io.github.term4.archipelago.storage.DataKey} entries in the file (game results, metadata - read back via {@code ShardFile.userData} or {@code Playback.data}). */
    public byte @NotNull [] stop(@Nullable Consumer<ShardFile.UserDataWriter> userData) {
        endRecording();
        return encodeFile(userData);
    }

    /** {@link #stop()} with the encode (NBT build + gzip, pure CPU) off-thread; recording still ends immediately. */
    public @NotNull CompletableFuture<byte[]> stopAsync() {
        return stopAsync(null);
    }

    /** {@link #stop(Consumer)}, encoded off-thread. */
    public @NotNull CompletableFuture<byte[]> stopAsync(@Nullable Consumer<ShardFile.UserDataWriter> userData) {
        endRecording();
        return CompletableFuture.supplyAsync(() -> encodeFile(userData));
    }

    private void endRecording() {
        if (!active.compareAndSet(true, false)) return; // a manual stop may race the shard-death auto-end
        stopSampler.run();
        unhooks.forEach(Runnable::run);
        dispatchers.forEach(d -> d.stop(this));
        MinecraftServer.getGlobalEventHandler().removeChild(shardWatch);
        EventDispatcher.call(new RecordingStopEvent(this));
    }

    private byte[] encodeFile(@Nullable Consumer<ShardFile.UserDataWriter> userData) {
        CompoundBinaryTag.Builder channels = CompoundBinaryTag.builder();
        ops.forEach((type, list) -> {
            List<Recorded<?>> all = materialize(type, list);
            if (all.isEmpty()) return;
            ListBinaryTag.Builder<CompoundBinaryTag> encoded = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (Recorded<?> op : all) {
                encoded.add(CompoundBinaryTag.builder().putLong("t", op.tick()).put("op", encode(type, op)).build());
            }
            channels.put(type.id(), CompoundBinaryTag.builder()
                    .putInt("v", type.version()).put("ops", encoded.build()).build());
        });
        CompoundBinaryTag container = CompoundBinaryTag.builder()
                .putInt("v", 1).putLong("ticks", tick).put("channels", channels.build()).build();
        return t0.encode(data -> {
            if (userData != null) userData.accept(data);
            if (!bookmarks.isEmpty()) data.put(Replay.BOOKMARKS, List.copyOf(bookmarks));
            data.put(Replay.OPS, container); // last: the lib's slots always win
        });
    }

    // BLOCKS rides out with the understructure masks prepended (position-sorted: deterministic bytes)
    private List<Recorded<?>> materialize(Recordable<?> type, Queue<Recorded<?>> queue) {
        if (type != Channels.BLOCKS || covered.isEmpty()) return List.copyOf(queue);
        List<Recorded<?>> out = new ArrayList<>();
        covered.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Point, String> en) -> en.getKey().blockY())
                        .thenComparingInt(en -> en.getKey().blockZ()).thenComparingInt(en -> en.getKey().blockX()))
                .forEach(en -> out.add(new Recorded<>(0, new Channels.BlockOp(0, en.getKey().blockX(),
                        en.getKey().blockY(), en.getKey().blockZ(), en.getValue()))));
        out.addAll(queue);
        return out;
    }

    /** The t0 snapshot (the in-memory playback seeds its theater from it). */
    @NotNull ShardFile.Content t0() {
        return t0;
    }

    // the in-memory playback seam: every non-empty channel's ops, in file order (masks included)
    void forEachChannel(BiConsumer<Recordable<?>, List<Recorded<?>>> consumer) {
        ops.forEach((type, list) -> {
            List<Recorded<?>> all = materialize(type, list);
            if (!all.isEmpty()) consumer.accept(type, all);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> BinaryTag encode(Recordable<T> type, Recorded<?> op) {
        return type.codec().encode(Transcoder.NBT, (T) op.op()).orElseThrow();
    }

    /** Op counts per channel id, for status messages. */
    public @NotNull Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        ops.forEach((type, list) -> out.put(type.id(), list.size()));
        return out;
    }
}
