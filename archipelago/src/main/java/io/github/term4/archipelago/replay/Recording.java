package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.event.ShardDestroyEvent;
import io.github.term4.archipelago.event.replay.RecordingStopEvent;
import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import io.github.term4.archipelago.replay.channel.Dispatcher;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.core.Channels;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.polyp.world.MechanicsWorld;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import java.util.HashSet;

/**
 * One recording session: the t0 snapshot is taken immediately, dispatchers fill the channels until
 * {@link #stop()} returns the file. Entities are not part of the snapshot - every one (t0 included) rides the
 * spawn channel as a visual snapshot. The session auto-ends when its shard is destroyed (fail-stop included)
 * or its {@link Budget} runs out - the stop event fires, and {@link #stop()} still hands over the footage.
 * With a {@link #spool} the log leaves the heap in closed segments as it grows; without one it stays until the
 * stop.
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
    private volatile int keyframeInterval;
    private volatile @Nullable Spool spool;
    private volatile int spoolEvery;
    private volatile @Nullable Consumer<UserData.Writer> spoolHeader;
    private boolean headed; // owner thread, with closeSegment
    private final Set<Point> masksSpooled = new HashSet<>();
    private int marksSpooled;
    private final Map<Recordable<?>, Integer> spooled = new ConcurrentHashMap<>();
    private volatile boolean understructure = true;
    private volatile Budget budget = defaultBudget;
    private final AtomicInteger opCount = new AtomicInteger();
    private volatile boolean truncated;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Set<Class<?>> failed = new HashSet<>(); // one report per dispatcher: a second one's fault is news too

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
        shard.onOwner(op);
    }

    private void sample() {
        // a stop lands mid-tick with this hook still in flight: sampling on would re-enter stop()ped dispatchers
        if (!active.get()) return;
        long cap = budget.ticks();
        if (cap > 0 && tick >= cap) {
            truncate(cap + " ticks");
            return;
        }
        tick++;
        if (spool != null && tick % spoolEvery == 0) closeSegment();
        List<Shard> layers = new ArrayList<>();
        collectLayers(shard, layers);
        sampledLayers = List.copyOf(layers); // one tree walk per tick, not one per sampler
        if (keyframeInterval > 0 && tick % keyframeInterval == 0) {
            emit(Channels.BOUNDARIES, new Channels.Boundary(keyframeInterval));
            // delta memory dropped BEFORE sampling: this tick's samples re-emit full state for keyframed channels
            for (Dispatcher dispatcher : dispatchers) {
                try {
                    dispatcher.reset(this);
                } catch (Exception e) {
                    failed(dispatcher, e);
                }
            }
        }
        for (Dispatcher dispatcher : dispatchers) {
            try {
                dispatcher.sample(this);
            } catch (Exception e) { // one bad dispatcher must never stall the rest of the recording
                failed(dispatcher, e);
            }
        }
        sampledLayers = null;
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
        // tag identity up the chain, not MechanicsWorld.of(): a wrapper can never match, and of() NPEs pre-spawn
        return entity.getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard bound && inTree(bound);
    }

    // an excluded layer cuts off its whole subtree
    private boolean inTree(Shard bound) {
        for (Shard s = bound; s != null; s = s.parent()) {
            if (s == shard) return true;
            if (!layerFilter.test(s)) return false;
        }
        return false;
    }

    private volatile List<Shard> sampledLayers;

    /** The recorded layer shards, parents before children (the sweep and samplers iterate this). */
    public @NotNull List<Shard> layers() {
        List<Shard> cached = sampledLayers;
        if (cached != null) return cached;
        List<Shard> out = new ArrayList<>();
        collectLayers(shard, out);
        return out;
    }

    private void failed(Dispatcher dispatcher, Exception e) {
        if (failed.add(dispatcher.getClass())) {
            LOGGER.warn("recording dispatcher {} failed (logged once per dispatcher)", dispatcher.getClass().getSimpleName(), e);
        }
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

    // the write hook is THE block source (a cancelled place never writes): event probes would double these
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
        return entity.getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard bound && inTree(bound) ? layer(bound) : 0;
    }

    /** Appends an op to {@code channel} at the current tick (dropped once stopped). */
    public <T> void emit(@NotNull Recordable<T> channel, @NotNull T op) {
        long at = tick;
        onOwner(() -> emitAt(channel, op, at));
    }

    private <T> void emitAt(Recordable<T> channel, T op, long atTick) {
        if (!active.get()) return;
        int cap = budget.ops();
        if (cap > 0 && opCount.incrementAndGet() > cap) {
            truncate(cap + " ops");
            return;
        }
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
            if (!active.get()) return; // the hop is a tick wide: a stopped recording must not re-track
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
     * What a recording may spend before it stops itself and reads {@link #truncated()}: {@code ops} bounds the
     * op log's memory, {@code ticks} bounds a recording nobody stopped by wall time. {@code <= 0} = no cap.
     * The defaults sit far above any real game - this is the runaway brake, not a target.
     */
    public record Budget(int ops, long ticks) {
        /** 8M ops (~1GB of log at worst) or 2h at the server rate. */
        public static final Budget DEFAULT = new Budget(8_000_000, 144_000);
    }

    private static volatile Budget defaultBudget = Budget.DEFAULT;

    /** The {@link Budget} every NEW recording starts on. */
    public static void defaultBudget(@NotNull Budget budget) {
        defaultBudget = budget;
    }

    public static @NotNull Budget defaultBudget() {
        return defaultBudget;
    }

    /** This recording's own {@link Budget}, overriding {@link #defaultBudget()}. */
    public @NotNull Recording budget(@NotNull Budget budget) {
        this.budget = budget;
        return this;
    }

    public @NotNull Budget budget() {
        return budget;
    }

    /** Whether the {@link Budget} ended this recording early - the footage stops mid-game. */
    public boolean truncated() {
        return truncated;
    }

    // the runaway brake: an unattended recording on a busy shard grows until the JVM dies
    private void truncate(String spent) {
        if (!active.get()) return;
        truncated = true;
        LOGGER.warn("replay recording@{} spent its budget ({}) at tick {} - stopped, footage truncated",
                Integer.toHexString(System.identityHashCode(this)), spent, tick);
        endRecording();
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

    /** Emits a keyframe every {@code ticks} (0 = off, the default): keyframed channels re-emit full state
     *  there, so a backward {@link Playback#seek} folds from the latest keyframe instead of tick 0. */
    public @NotNull Recording keyframeInterval(int ticks) {
        this.keyframeInterval = Math.max(0, ticks);
        return this;
    }

    /**
     * Spools the log to {@code file} in segments of {@code everyTicks}: a closed segment is encoded and appended
     * off the clock, and its ops leave the heap. The final file reads the spool back at {@link #stop}; delete it
     * with {@link #discard()} once that file is kept. Set before the first tick.
     */
    public @NotNull Recording spool(@NotNull Path file, int everyTicks) throws IOException {
        return spool(file, everyTicks, null);
    }

    /**
     * {@link #spool(Path, int)} with the app's data in the spool's header - what {@link #stop(Consumer)} would
     * put in - so a process that dies mid-recording leaves a spool {@link Replay#salvage} makes a file of.
     * {@code header} runs on the recording's clock at the first segment close, when the game has settled.
     */
    public @NotNull Recording spool(@NotNull Path file, int everyTicks, @Nullable Consumer<UserData.Writer> header)
            throws IOException {
        if (tick > 0) throw new IllegalStateException("the spool must be set before the first tick");
        this.spool = new Spool(file);
        this.spoolEvery = Math.max(1, everyTicks);
        this.spoolHeader = header;
        return this;
    }

    /** The spool file, or null when the log stays on the heap. */
    public @Nullable Path spoolFile() {
        Spool s = spool;
        return s != null ? s.file() : null;
    }

    /** Deletes the spool file; the recording is over and its final file is kept elsewhere. */
    public void discard() throws IOException {
        Spool s = spool;
        if (s != null) s.delete();
    }

    /** Ends the recording and drops its spool, encoding nothing: footage nobody wants (a game that never played). */
    public void abandon() throws IOException {
        endRecording();
        discard();
    }

    // the owner thread: the queues have no other writer. Every close lands, ops or not: its tick is where a
    // salvage cuts, and the masks and marks since the last close ride with it
    private void closeSegment() {
        Spool s = spool;
        if (s == null) return;
        if (!headed) {
            headed = true;
            s.append(header());
        }
        CompoundBinaryTag.Builder channels = CompoundBinaryTag.builder();
        for (Map.Entry<Recordable<?>, Queue<Recorded<?>>> entry : ops.entrySet()) {
            Queue<Recorded<?>> queue = entry.getValue();
            if (queue.isEmpty()) continue;
            List<Recorded<?>> drained = new ArrayList<>(queue);
            queue.clear();
            drained.sort(java.util.Comparator.comparingLong(Recorded::tick)); // a hop can land one tick late
            spooled.merge(entry.getKey(), drained.size(), Integer::sum);
            channels.put(entry.getKey().id(), channelTag(entry.getKey(), drained));
        }
        CompoundBinaryTag.Builder segment = CompoundBinaryTag.builder().putLong("ticks", tick).put("channels", channels.build());
        List<Recorded<?>> masks = masks(pos -> masksSpooled.add(pos));
        if (!masks.isEmpty()) segment.put("masks", channelTag(Channels.BLOCKS, masks));
        List<Replay.Bookmark> marks = List.copyOf(bookmarks.subList(marksSpooled, bookmarks.size()));
        marksSpooled += marks.size();
        if (!marks.isEmpty()) segment.put("marks", Replay.BOOKMARKS.codec().encode(Transcoder.NBT, marks).orElseThrow());
        s.append(segment.build());
    }

    // an app header that throws costs its data, not the segment
    private CompoundBinaryTag header() {
        CompoundBinaryTag.Builder head = CompoundBinaryTag.builder().putInt("v", 1).putByteArray("t0", t0.encode());
        Consumer<UserData.Writer> app = spoolHeader;
        if (app != null) {
            try {
                UserData.Writer data = new UserData.Writer();
                app.accept(data);
                head.put("data", data.build());
            } catch (RuntimeException e) {
                LOGGER.warn("replay recording@{}: the spool header's data failed",
                        Integer.toHexString(System.identityHashCode(this)), e);
            }
        }
        return head.build();
    }

    /**
     * The file a recording that never reached {@link #stop} leaves in its spool: the header's t0 and data, the
     * closed segments' ops, masks and marks, cut at the last closed tick. Null without a header (a spool from
     * before headers) or without a closed segment.
     */
    static byte @Nullable [] salvage(@NotNull Path file) throws IOException {
        List<CompoundBinaryTag> records = new ArrayList<>();
        Spool.read(file, records::add);
        if (records.isEmpty() || records.getFirst().get("t0") == null) return null;
        CompoundBinaryTag header = records.getFirst();
        List<CompoundBinaryTag> segments = records.subList(1, records.size());
        if (segments.isEmpty()) return null;
        ShardFile.Content t0 = ShardFile.decode(header.getByteArray("t0"));
        Map<String, List<CompoundBinaryTag>> parts = new LinkedHashMap<>();
        for (CompoundBinaryTag segment : segments) { // the masks lead the channel, whichever close found them
            if (segment.get("masks") instanceof CompoundBinaryTag masks) {
                parts.computeIfAbsent(Channels.BLOCKS.id(), id -> new ArrayList<>()).add(masks);
            }
        }
        List<Replay.Bookmark> marks = new ArrayList<>();
        for (CompoundBinaryTag segment : segments) {
            gather(parts, segment);
            BinaryTag marked = segment.get("marks");
            if (marked != null) marks.addAll(Replay.BOOKMARKS.codec().decode(Transcoder.NBT, marked).orElseThrow());
        }
        long ticks = segments.getLast().getLong("ticks");
        CompoundBinaryTag data = header.getCompound("data");
        return t0.encode(out -> {
            if (data.size() > 0) out.put(data);
            if (!marks.isEmpty()) out.put(Replay.BOOKMARKS, marks);
            out.put(Replay.OPS, container(ticks, parts));
        });
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
    public byte @NotNull [] stop(@Nullable Consumer<UserData.Writer> userData) {
        endRecording();
        return encodeFile(userData);
    }

    /** {@link #stop()} with the encode (NBT build + gzip, pure CPU) off-thread; recording still ends immediately. */
    public @NotNull CompletableFuture<byte[]> stopAsync() {
        return stopAsync(null);
    }

    /**
     * {@link #stop(Consumer)} with only the encode off-thread: the recording ends and {@code userData} is
     * collected on the CALLING thread, so a teardown can drop the future without losing the stop or reading
     * game state off a pool thread. The future completes with the file bytes - nothing is written to disk.
     */
    public @NotNull CompletableFuture<byte[]> stopAsync(@Nullable Consumer<UserData.Writer> userData) {
        endRecording();
        UserData.Writer collected = new UserData.Writer();
        if (userData != null) userData.accept(collected);
        CompoundBinaryTag entries = collected.build();
        CompletableFuture<byte[]> encoded = CompletableFuture.supplyAsync(() ->
                encodeFile(entries.size() == 0 ? null : data -> data.put(entries)));
        encoded.exceptionally(e -> { // a dropped future would swallow the loss of a whole game's footage
            LOGGER.warn("replay recording@{} failed to encode",
                    Integer.toHexString(System.identityHashCode(this)), e);
            return null;
        });
        return encoded;
    }

    private void endRecording() {
        if (!active.compareAndSet(true, false)) return; // a manual stop may race the shard-death auto-end
        stopSampler.run();
        unhooks.forEach(Runnable::run);
        dispatchers.forEach(d -> d.stop(this));
        MinecraftServer.getGlobalEventHandler().removeChild(shardWatch);
        EventDispatcher.call(new RecordingStopEvent(this));
    }

    private byte[] encodeFile(@Nullable Consumer<UserData.Writer> userData) {
        Map<String, List<CompoundBinaryTag>> parts = new LinkedHashMap<>();
        if (!covered.isEmpty()) parts.computeIfAbsent(Channels.BLOCKS.id(), id -> new ArrayList<>()).add(channelTag(Channels.BLOCKS, masks()));
        Spool s = spool;
        if (s != null) {
            try {
                s.forEach(segment -> gather(parts, segment));
            } catch (IOException e) {
                throw new IllegalStateException("the spool could not be read back", e);
            }
        }
        ops.forEach((type, list) -> {
            if (list.isEmpty()) return;
            parts.computeIfAbsent(type.id(), id -> new ArrayList<>()).add(channelTag(type, List.copyOf(list)));
        });
        CompoundBinaryTag container = container(tick, parts);
        return t0.encode(data -> {
            if (userData != null) userData.accept(data);
            if (!bookmarks.isEmpty()) data.put(Replay.BOOKMARKS, List.copyOf(bookmarks));
            data.put(Replay.OPS, container); // last: the lib's slots always win
        });
    }

    // a spool record's channel pieces onto the parts (the header has none)
    private static void gather(Map<String, List<CompoundBinaryTag>> parts, CompoundBinaryTag record) {
        CompoundBinaryTag channels = record.getCompound("channels");
        for (String id : channels.keySet()) {
            parts.computeIfAbsent(id, k -> new ArrayList<>()).add(channels.getCompound(id));
        }
    }

    private static CompoundBinaryTag container(long ticks, Map<String, List<CompoundBinaryTag>> parts) {
        CompoundBinaryTag.Builder channels = CompoundBinaryTag.builder();
        parts.forEach((id, pieces) -> channels.put(id, join(pieces)));
        return CompoundBinaryTag.builder().putInt("v", 1).putLong("ticks", ticks).put("channels", channels.build()).build();
    }

    // the understructure masks lead BLOCKS as tick-0 ops (position-sorted: deterministic bytes)
    private List<Recorded<?>> masks() {
        return masks(pos -> true);
    }

    private List<Recorded<?>> masks(Predicate<Point> take) {
        List<Recorded<?>> out = new ArrayList<>();
        covered.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Nbt.POSITION_ORDER))
                .filter(en -> take.test(en.getKey()))
                .forEach(en -> out.add(new Recorded<>(0, new Channels.BlockOp(0, en.getKey().blockX(),
                        en.getKey().blockY(), en.getKey().blockZ(), en.getValue()))));
        return out;
    }

    private List<Recorded<?>> materialize(Recordable<?> type, Queue<Recorded<?>> queue) {
        if (type != Channels.BLOCKS || covered.isEmpty()) return List.copyOf(queue);
        List<Recorded<?>> out = masks();
        out.addAll(queue);
        return out;
    }

    /** One channel's ops as the file stores them: a packed blob, or per-op NBT under the channel version. */
    private static CompoundBinaryTag channelTag(Recordable<?> type, List<Recorded<?>> all) {
        CompoundBinaryTag.Builder channel = CompoundBinaryTag.builder().putInt("v", type.version());
        if (type.pack() != null) {
            channel.putByteArray("bin", pack(type, all)); // motion channels: one blob, no per-op tags
        } else {
            ListBinaryTag.Builder<CompoundBinaryTag> encoded = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (Recorded<?> op : all) {
                encoded.add(CompoundBinaryTag.builder().putLong("t", op.tick()).put("op", encode(type, op)).build());
            }
            channel.put("ops", encoded.build());
        }
        return channel.build();
    }

    // several segments of one channel: packed blobs stay separate parts, per-op lists concatenate
    private static CompoundBinaryTag join(List<CompoundBinaryTag> pieces) {
        if (pieces.size() == 1) return pieces.getFirst();
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder().putInt("v", pieces.getFirst().getInt("v"));
        if (pieces.getFirst().get("bin") != null) {
            ListBinaryTag.Builder<ByteArrayBinaryTag> parts = ListBinaryTag.builder(BinaryTagTypes.BYTE_ARRAY);
            for (CompoundBinaryTag piece : pieces) parts.add(ByteArrayBinaryTag.byteArrayBinaryTag(piece.getByteArray("bin")));
            return out.put("parts", parts.build()).build();
        }
        ListBinaryTag.Builder<CompoundBinaryTag> all = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag piece : pieces) {
            for (BinaryTag op : piece.getList("ops", BinaryTagTypes.COMPOUND)) all.add((CompoundBinaryTag) op);
        }
        return out.put("ops", all.build()).build();
    }

    /** The t0 snapshot (the in-memory playback seeds its theater from it). */
    @NotNull ShardFile.Content t0() {
        return t0;
    }

    // the in-memory playback seam: every non-empty channel's ops, in file order (masks included)
    void forEachChannel(BiConsumer<Recordable<?>, List<Recorded<?>>> consumer) {
        Map<Recordable<?>, List<Recorded<?>>> all = new LinkedHashMap<>();
        Spool s = spool;
        if (s != null) {
            try {
                s.forEach(segment -> {
                    CompoundBinaryTag channels = segment.getCompound("channels");
                    for (String id : channels.keySet()) {
                        Recordable<?> type = Replay.recordable(id, channels.getCompound(id).getInt("v"));
                        if (type != null) all.computeIfAbsent(type, t -> new ArrayList<>()).addAll(Playback.ops(type, channels.getCompound(id)));
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException("the spool could not be read back", e);
            }
        }
        ops.forEach((type, list) -> all.computeIfAbsent(type, t -> new ArrayList<>()).addAll(materialize(type, list)));
        all.forEach((type, list) -> {
            if (!list.isEmpty()) consumer.accept(type, list);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> BinaryTag encode(Recordable<T> type, Recorded<?> op) {
        return type.codec().encode(Transcoder.NBT, (T) op.op()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> byte[] pack(Recordable<T> type, List<Recorded<?>> ops) {
        return Objects.requireNonNull(type.pack()).encode((List<Recorded<T>>) (List<?>) ops);
    }

    /** Op counts per channel id, spooled segments included, for status messages. */
    public @NotNull Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        ops.forEach((type, list) -> out.put(type.id(), list.size() + spooled.getOrDefault(type, 0)));
        spooled.forEach((type, n) -> out.putIfAbsent(type.id(), n));
        return out;
    }
}
