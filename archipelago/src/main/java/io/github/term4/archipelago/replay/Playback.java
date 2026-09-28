package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.event.ShardDestroyEvent;
import io.github.term4.archipelago.event.replay.PlaybackStopEvent;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.EntityStates;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.channel.StateFolder;
import io.github.term4.archipelago.replay.core.Channels;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.UserData;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.instance.block.Block;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import net.kyori.adventure.text.Component;
import java.util.Map;
import java.util.function.Function;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One playback session: the generic tick loop over the file's channels, each applied through its registered
 * {@link Applier} in {@link Recordable#order()} order, on the THEATER shard's clock ({@link Shard#onTick}) -
 * a domain theater plays on its own thread and holds when it holds. Channels whose id/version has no registered
 * recordable (or won't decode) are {@link #skipped()}; everything else still plays. The session auto-halts when
 * its theater is destroyed (fail-stop included) - the stop event fires either way.
 */
public final class Playback {

    private static final Logger LOGGER = LoggerFactory.getLogger(Playback.class);

    private final Shard shard;
    private final Map<Point, Block> t0; // the theater's diff right after the file's snapshot landed - rewind re-seeds it
    private final List<Channel<?>> channels = new ArrayList<>();
    // keyed by channel id: versions share their op record, so appliers look up by the CURRENT constant
    private final Map<String, Channel<?>> byType = new HashMap<>();
    private final List<String> skipped = new ArrayList<>();
    private final long length;
    private final UserData.View data; // null on the in-memory path
    private final List<Replay.Bookmark> bookmarks;
    /** Keyframe ticks, ascending; backward seeks fold keyframed channels from the latest one at or before the target. */
    private final List<Long> keyframes = new ArrayList<>();
    private volatile Runnable ticker;

    // mutated on the owner thread only; concurrent for cross-thread status reads (commands, tests)
    private final Map<String, Ghost> ghosts = new ConcurrentHashMap<>();
    private Function<String, @Nullable Component> ghostNames = player -> null;
    private final Map<String, PlayerSkin> skins = new ConcurrentHashMap<>();
    private final Map<Integer, Entity> entities = new ConcurrentHashMap<>();
    private final Set<Integer> removedEarly = ConcurrentHashMap.newKeySet();
    private final Set<String> hiddenActors = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Shard> layerShards = new ConcurrentHashMap<>();
    private final Map<String, Integer> actorLayers = new ConcurrentHashMap<>();

    private EventNode<Event> shardWatch;
    private volatile long tick;
    private volatile boolean reversed;
    private volatile boolean paused;
    private volatile boolean holdAtEnd;
    /** Fast-forward ceiling: ~51s of timeline per theater tick; anything faster is a {@link #seek}. */
    public static final int MAX_RATE = 1024;

    private volatile double rate = 1;
    private double carry; // owner-thread only: the sub-tick remainder of a fractional rate
    private final AtomicBoolean ended = new AtomicBoolean();
    private boolean loggedError;

    Playback(@NotNull Shard shard, @NotNull CompoundBinaryTag container,
             @Nullable UserData.View data) {
        this.shard = shard;
        this.t0 = Map.copyOf(shard.diff());
        this.data = data;
        List<Replay.Bookmark> marks = data == null ? null : data.get(Replay.BOOKMARKS);
        this.bookmarks = marks == null ? List.of() : marks;
        this.length = container.getLong("ticks");
        layerShards.put(0, shard); // the recorded root plays in the target theater
        CompoundBinaryTag channelsTag = container.getCompound("channels");
        for (String id : channelsTag.keySet()) {
            CompoundBinaryTag channel = channelsTag.getCompound(id);
            int version = channel.getInt("v");
            Recordable<?> type = Replay.recordable(id, version);
            if (type == null) {
                skipped.add(id + " v" + version);
                continue;
            }
            if ((channel.get("bin") != null || channel.get("parts") != null) && type.pack() == null) {
                skipped.add(id + " v" + version + " (packed; no track codec)"); // never play a silently-empty channel
                continue;
            }
            try {
                channels.add(channel(type, ops(type, channel)));
            } catch (RuntimeException e) {
                skipped.add(id + " v" + version + " (undecodable: " + e.getMessage() + ")");
            }
        }
        start();
    }

    // the in-memory path: a stopped recording's op queues and t0 snapshot play as they are - no codec
    Playback(@NotNull Shard shard, @NotNull Recording recording) {
        this.shard = shard;
        this.t0 = Map.copyOf(shard.diff());
        this.data = null;
        this.bookmarks = recording.bookmarks();
        this.length = recording.tick();
        layerShards.put(0, shard);
        recording.forEachChannel((type, ops) -> channels.add(channel(type, ops)));
        start();
    }

    @SuppressWarnings("unchecked")
    private static <T> Channel<T> channel(Recordable<?> type, List<Recorded<?>> ops) {
        return new Channel<>((Recordable<T>) type, (List<Recorded<T>>) (List<?>) ops);
    }

    // shared ctor tail; the tick hook registers last - it may fire before the ctor returns
    private void start() {
        channels.sort(Comparator.comparingInt((Channel<?> c) -> c.type.order()).thenComparing(c -> c.type.id()));
        channels.forEach(c -> byType.put(c.type.id(), c));
        Channel<?> marks = byType.get(Channels.BOUNDARIES.id());
        if (marks != null) marks.ops.forEach(op -> keyframes.add(op.tick()));
        shardWatch = EventNode.all("archipelago:replay-playback@" + System.identityHashCode(this));
        shardWatch.addListener(ShardDestroyEvent.class, e -> {
            if (e.shard() == shard) halt();
        });
        MinecraftServer.getGlobalEventHandler().addChild(shardWatch);
        ticker = shard.onTick(this::step);
        // a zero-length playback can end inside its first tick, on the clock thread, BEFORE the handle exists
        if (ended.get()) ticker.run();
        // the reverse-crossing indexes, off the clock: built on the first backward step, a long recording's took a
        // second at the flip (the user, 2026-09-28)
        List<Channel<?>> warm = List.copyOf(channels);
        Thread.startVirtualThread(() -> {
            for (Channel<?> channel : warm) {
                try {
                    channel.applier.prepare(this);
                } catch (Exception e) {
                    LOGGER.warn("replay channel {} prepare failed", channel.type.id(), e);
                }
            }
        });
    }

    // seek/halt arrive on command threads; playback state is owner-thread-only, like Recording's mutators
    private void onOwner(Runnable op) {
        shard.onOwner(op);
    }

    /** A channel's ops as the file stores them: one packed blob, spooled packed parts in order, or per-op NBT. */
    static <T> List<Recorded<?>> ops(Recordable<T> type, CompoundBinaryTag channel) {
        List<Recorded<?>> out = new ArrayList<>();
        if (channel.get("parts") instanceof ListBinaryTag parts) {
            for (BinaryTag part : parts) out.addAll(Objects.requireNonNull(type.pack()).decode(((net.kyori.adventure.nbt.ByteArrayBinaryTag) part).value()));
            return out;
        }
        if (channel.get("bin") != null) {
            out.addAll(Objects.requireNonNull(type.pack()).decode(channel.getByteArray("bin")));
            return out;
        }
        for (BinaryTag tag : channel.getList("ops", BinaryTagTypes.COMPOUND)) {
            CompoundBinaryTag entry = (CompoundBinaryTag) tag;
            out.add(new Recorded<>(entry.getLong("t"), type.codec().decode(Transcoder.NBT, entry.get("op")).orElseThrow()));
        }
        return out;
    }

    private void step(long time) {
        if (!paused) {
            for (carry += rate; carry >= 1; carry--) advance();
        }
        for (Channel<?> channel : channels) safely(() -> channel.applier.tick(this)); // pins hold the scene
        if (!paused && !reversed
                && tick >= length && channels.stream().allMatch(c -> c.drained() && c.applier.settled())) {
            if (holdAtEnd) paused = true;
            else end();
        }
    }

    /** At the end, pause on the last frame instead of ending: a seek or a reverse plays on, {@link #halt} ends it. */
    public void holdAtEnd(boolean hold) {
        this.holdAtEnd = hold;
    }

    private void advance() {
        if (reversed) {
            if (tick == 0) return; // holds at t0
            tick--;
            // last applied, first un-applied
            for (int i = channels.size() - 1; i >= 0; i--) channels.get(i).drainBack(this);
        } else {
            tick++;
            for (Channel<?> channel : channels) channel.drain(this);
        }
    }

    /**
     * Timeline ticks advanced per theater tick: {@code |rate|} = speed (default 1; fractional = slow motion,
     * the remainder carries between ticks), a NEGATIVE rate also flips into {@link #reverse}, {@code 0}
     * holds in place. A positive rate keeps the current direction; asking for motion resumes a {@link #pause
     * paused} playback (the rewind recipe holds its moment paused). Callable from any thread.
     *
     * <p>Magnitude is clamped to {@value #MAX_RATE}: a step advances {@code |rate|} timeline ticks inside ONE
     * theater tick, so an unbounded rate would stall the clock into watchdog quarantine - {@link #seek} jumps.
     */
    public void rate(double rate) {
        if (!Double.isFinite(rate)) throw new IllegalArgumentException("finite rates only");
        if (rate < 0) reversed = true;
        if (rate != 0) paused = false;
        this.rate = Math.min(Math.abs(rate), MAX_RATE);
        for (Ghost ghost : ghosts.values()) pace(ghost);
    }

    /** The current speed ({@link #reversed()} is the direction). */
    public double rate() {
        return rate;
    }

    /**
     * Flips the timeline: each reversed step UN-applies its ops (position tracks walk backwards, spawns and
     * removals invert, blocks restore their prior state, stateful acts revert), holding at tick 0. One-shot
     * effects (swings, hurt flashes, dig cracks) play in either direction. Callable from any thread.
     */
    public void reverse(boolean reversed) {
        this.reversed = reversed;
    }

    public boolean reversed() {
        return reversed;
    }

    /** Freezes the timeline in place; the appliers' pause pins hold the scene. */
    public void pause(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused;
    }

    /**
     * Jumps to {@code target}: folds the ops in the window through each channel's {@link StateFolder} and
     * applies only the survivors (each entity's latest state), so ghosts land in place without replaying every
     * intermediate op. A BACKWARD seek clears the ghosts, twins and risen layers, has each channel revert what
     * the fold will not write back ({@link Applier#revert}: blocks written after the target, each to its state
     * there), and refolds forward from the base - ops don't unwind, they re-derive, and a re-pinned block already
     * showing sends nothing. Call from off-clock threads (commands); it relays onto the theater's owner thread
     * and waits.
     */
    public void seek(long target) {
        shard.relay(() -> {
            seekNow(target);
            return null;
        });
    }

    private void seekNow(long target) {
        if (ended.get()) throw new IllegalStateException("playback ended");
        if (target < 0) throw new IllegalArgumentException("negative tick");
        if (target == tick) return;
        if (target < tick) {
            rewind(target);
            long base = keyframeBefore(target);
            if (base > 0) {
                for (Channel<?> channel : channels) {
                    if (channel.type.keyframed()) channel.seat(base);
                }
            }
        }
        EntityStates states = new EntityStates();
        for (Channel<?> channel : channels) channel.fold(states, target);
        for (Channel<?> channel : channels) channel.applyLatest(this, states);
        tick = target;
    }

    /** The latest keyframe tick at or before {@code target}, or {@code 0} (fold from the start). */
    private long keyframeBefore(long target) {
        long base = 0;
        for (Long mark : keyframes) {
            if (mark > target) break;
            base = mark;
        }
        return base;
    }

    // back before the target: ghosts, twins and whatever else stands in the theater go (the theater is the
    // replay's, don't co-host entities in it), risen layers reset, each channel reverts what the fold cannot;
    // risen layers are reset AND unlinked: a scrubbed replay would otherwise leave one husk per seek
    private void rewind(long target) {
        ghosts.values().forEach(Playback::dismiss);
        ghosts.clear();
        entities.values().forEach(Playback::dismiss);
        entities.clear();
        removedEarly.clear();
        hiddenActors.clear();
        actorLayers.clear();
        skins.clear();
        for (Shard layer : layerShards.values()) {
            if (layer == shard) continue;
            layer.reset();
            layer.detach(); // a seek raises fresh layers; the husks would pile up in the theater's children
        }
        layerShards.clear();
        layerShards.put(0, shard);
        for (Entity e : shard.entities()) if (!(e instanceof Player)) dismiss(e);
        for (Channel<?> channel : channels) safely(() -> channel.applier.revert(this, target));
        for (Channel<?> channel : channels) channel.rewind();
        tick = 0;
    }

    public @NotNull Shard shard() {
        return shard;
    }

    /** The cursor's progress into the current timeline tick, in timeline units [0, 1). Owner-thread
     *  reads only (applier ticks) - legs paced on whole ticks compress when built mid-window. */
    public double subtick() {
        return carry;
    }

    public long tick() {
        return tick;
    }

    /** The recording's length in ticks (playback never ends before it). */
    public long length() {
        return length;
    }

    public boolean active() {
        return !ended.get();
    }

    /** Channels present in the file but not registered here (or undecodable) - they were skipped. */
    public @NotNull List<String> skipped() {
        return List.copyOf(skipped);
    }

    /** Op counts per channel id, for status messages. */
    public @NotNull Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        channels.forEach(c -> out.put(c.type.id(), c.ops.size()));
        return out;
    }

    /** The theater shard playing recorded layer {@code id}, or {@code null} (unknown or destroyed). Layer 0 = the target. */
    public @Nullable Shard layer(int id) {
        return layerShards.get(id);
    }

    /** Registers the theater shard for layer {@code id} (the layer applier raises child layers). */
    public void layer(int id, @NotNull Shard theater) {
        layerShards.put(id, theater);
    }

    /** Drops layer {@code id}: its blocks reset and the shard unlinks from the theater. */
    public void dropLayer(int id) {
        Shard gone = layerShards.remove(id);
        if (gone != null && gone != shard) {
            gone.reset();
            gone.detach();
        }
    }

    /** Reassigns {@code player}'s recorded layer ({@link #layerFor} places the ghost). */
    public void actorLayer(@NotNull String player, int layer) {
        actorLayers.put(player, layer);
    }

    /** The theater shard {@code player}'s ghost belongs in - its recorded layer, the target when unassigned. */
    public @NotNull Shard layerFor(@NotNull String player) {
        Shard in = layerShards.get(actorLayers.getOrDefault(player, 0));
        return in != null ? in : shard;
    }

    /**
     * How a recorded actor's tab row reads, {@code null} for a row it should not have - the default. Renames the
     * ghosts already raised, which the first tick may have raised before {@code Replay.play} returned.
     */
    public void ghostNames(@NotNull Function<String, @Nullable Component> names) {
        this.ghostNames = names;
        ghosts.forEach((player, ghost) -> ghost.listName(names.apply(player))); // the ones already up
    }

    /** {@link #ghostNames}'s answer for {@code player}. */
    public @Nullable Component ghostName(@NotNull String player) {
        return ghostNames.apply(player);
    }

    /** The recorded actor's ghost, or {@code null} (not spawned yet, or unknown). */
    public @Nullable Ghost ghost(@NotNull String player) {
        return ghosts.get(player);
    }

    /** Registers {@code player}'s ghost (appliers raise ghosts lazily on the actor's first move). */
    public void ghost(@NotNull String player, @NotNull Ghost ghost) {
        ghosts.put(player, ghost);
        pace(ghost);
    }

    // a client times a swing off the swinger's own haste or fatigue (6 ticks bare, 6-(1+a) haste, 6+2(1+a)
    // fatigue), so a ghost's arm keeps the rate's time; haste stops where a swing would no longer draw.
    // The relative-move drift a sync snaps away grows with the moves per real second, so a fast playback
    // snaps small and often instead of big and rarely
    private void pace(Ghost ghost) {
        ghost.setSynchronizationTicks(rate > 1 ? (int) Math.max(20, Math.round(Ghost.SYNC_TICKS / rate)) : Ghost.SYNC_TICKS);
        if (ghost.hasEffect(PotionEffect.HASTE)) ghost.removeEffect(PotionEffect.HASTE);
        if (ghost.hasEffect(PotionEffect.MINING_FATIGUE)) ghost.removeEffect(PotionEffect.MINING_FATIGUE);
        double at = Math.max(1.0e-3, rate);
        if (at < 1) {
            int amplifier = (int) Math.round((6 / at - 6) / 2) - 1;
            if (amplifier >= 0) ghost.addEffect(new Potion(PotionEffect.MINING_FATIGUE, Math.min(255, amplifier), Potion.INFINITE_DURATION, 0));
        } else if (at > 1) {
            int amplifier = (int) Math.round(6 - 6 / at) - 1;
            if (amplifier >= 0) ghost.addEffect(new Potion(PotionEffect.HASTE, Math.min(3, amplifier), Potion.INFINITE_DURATION, 0));
        }
    }

    /** Drops and removes {@code player}'s ghost (death pose, hide). */
    public void dropGhost(@NotNull String player) {
        Ghost gone = ghosts.remove(player);
        if (gone != null) gone.remove();
    }

    /** The skin recorded for {@code player}, or {@code null}. */
    public @Nullable PlayerSkin skin(@NotNull String player) {
        return skins.get(player);
    }

    public void skin(@NotNull String player, @NotNull PlayerSkin skin) {
        skins.put(player, skin);
    }

    /** While hidden (recorded vanish, or between death and respawn) no ghost rises for the actor. */
    public boolean hidden(@NotNull String player) {
        return hiddenActors.contains(player);
    }

    public void hide(@NotNull String player) {
        hiddenActors.add(player);
    }

    public void show(@NotNull String player) {
        hiddenActors.remove(player);
    }

    /** Unbinds and returns the twin of {@code seq}, or {@code null}. */
    public @Nullable Entity untrack(int seq) {
        return entities.remove(seq);
    }

    /** A remove op drained before its deferred spawn materialized: that spawn must never happen. */
    public void earlyRemove(int seq) {
        removedEarly.add(seq);
    }

    public boolean earlyRemoved(int seq) {
        return removedEarly.contains(seq);
    }

    /** The live twin of recorded entity {@code seq}, or {@code null}. */
    public @Nullable Entity entity(int seq) {
        return entities.get(seq);
    }


    /** Binds a replayed entity to its recorded seq - the position track and removal target it. */
    public void track(int seq, @NotNull Entity entity) {
        entities.put(seq, entity);
        // scheduled absolute syncs land INSTANTLY on non-interpolating clients while the stream they
        // correct is integration/lerp-driven: a periodic catch-up snap. The replay's own corrections
        // (arrivals, dying landings, resyncs) own the absolutes; rounding drift stays sub-voxel for ages
        entity.setSynchronizationTicks(1_000);
    }

    /** Removes a twin; a removal racing its own async spawn re-runs once the spawn lands (or never). */
    public static void dismiss(Entity gone) {
        gone.remove();
        if (gone.getInstance() == null) gone.scheduler().scheduleNextTick(gone::remove);
    }

    // one index shape per channel id - every LookBack on a channel must use its canonical key
    private final Map<String, Object> opIndexes = new HashMap<>();

    /** Per-playback memo for lazily built op-list indexes, keyed by channel id; {@link Applier#prepare} fills it off the clock. */
    @SuppressWarnings("unchecked")
    public <T> @NotNull T opIndex(@NotNull String channel, @NotNull java.util.function.Supplier<T> build) {
        synchronized (opIndexes) {
            T got = (T) opIndexes.get(channel);
            if (got == null) opIndexes.put(channel, got = build.get());
            return got;
        }
    }

    /** The t0 snapshot's block at {@code pos}, or {@code null} (outside the recorded root's own diff) - reverse block fallback. */
    public @Nullable Block t0Block(@NotNull Point pos) {
        return t0.get(pos);
    }

    /** A remove op crossed in reverse: its early-removal veto (if any) lifts, the spawn may materialize again. */
    public void clearEarlyRemove(int seq) {
        removedEarly.remove(seq);
    }

    /** A channel's full op list (appliers may look ahead, e.g. for a dig's end); any version of {@code type}'s id. */
    public <T> @NotNull List<Recorded<T>> ops(@NotNull Recordable<T> type) {
        @SuppressWarnings("unchecked")
        Channel<T> channel = (Channel<T>) byType.get(type.id());
        return channel == null ? List.of() : channel.ops;
    }

    /** The index of the first not-yet-applied op in {@code type}'s channel. */
    public int cursor(@NotNull Recordable<?> type) {
        Channel<?> channel = byType.get(type.id());
        return channel == null ? 0 : channel.cursor;
    }

    /**
     * A user-data entry of the played FILE ({@link Recording#stop(java.util.function.Consumer)} wrote it -
     * game results, metadata). {@code null} when absent, and always for in-memory playbacks (the app still
     * holds its own objects there).
     */
    public <T> @Nullable T data(@NotNull DataKey<T> key) {
        return data == null ? null : data.get(key);
    }

    /** The recorded bookmark index, in mark order - jump to one with {@link #seek}. */
    public @NotNull List<Replay.Bookmark> bookmarks() {
        return bookmarks;
    }

    /**
     * The live applier folding {@code type}'s channel (any version of its id), or {@code null} - the read
     * seam for state channels. A backward seek re-creates appliers: re-fetch, don't cache.
     */
    public <T> @Nullable Applier<T> applier(@NotNull Recordable<T> type) {
        @SuppressWarnings("unchecked")
        Channel<T> channel = (Channel<T>) byType.get(type.id());
        return channel == null ? null : channel.applier;
    }

    /** Ends playback early (on the owner thread; a dead theater runs it inline): ghosts and replayed entities removed. */
    public void halt() {
        onOwner(this::end);
    }

    private void end() {
        if (!ended.compareAndSet(false, true)) return; // a manual halt may race the shard-death auto-halt
        Runnable t = ticker; // null when a zero-length playback ends inside its first tick; start() re-runs then
        if (t != null) t.run();
        MinecraftServer.getGlobalEventHandler().removeChild(shardWatch);
        try {
            ghosts.values().forEach(Entity::remove);
            entities.values().forEach(Entity::remove);
            for (Shard layer : layerShards.values()) {
                if (layer == shard) continue;
                layer.reset();
                layer.detach();
            }
        } finally {
            EventDispatcher.call(new PlaybackStopEvent(this)); // the stop event fires even off a broken teardown
        }
    }

    // one bad op must never stall the rest of the replay
    private void safely(Runnable op) {
        try {
            op.run();
        } catch (Exception e) {
            if (!loggedError) {
                loggedError = true;
                LOGGER.warn("replay op failed (logged once)", e);
            }
        }
    }

    private static final class Channel<T> {
        final Recordable<T> type;
        final List<Recorded<T>> ops;
        Applier<T> applier;
        int cursor;

        Channel(Recordable<T> type, List<Recorded<T>> ops) {
            this.type = type;
            // drain() stops at the first op past the cursor and LookBack binary-searches: a producer that
            // stamps forward (a same-tick spawn and remove) or hops threads can land one out of order
            List<Recorded<T>> sorted = new java.util.ArrayList<>(ops);
            sorted.sort(java.util.Comparator.comparingLong(Recorded::tick));
            this.ops = sorted;
            this.applier = type.applier().get();
        }

        // fresh applier: per-playback applier state (worn slots, tracks) re-derives with the refold
        void rewind() {
            cursor = 0;
            applier = type.applier().get();
        }

        /** Seats the cursor at the first op of keyframe tick {@code base} - the keyframe's full re-emission
         *  makes everything earlier redundant for this channel's fold. */
        void seat(long base) {
            int lo = 0, hi = ops.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ops.get(mid).tick() < base) lo = mid + 1;
                else hi = mid;
            }
            cursor = lo;
        }

        void drain(Playback pb) {
            while (cursor < ops.size() && ops.get(cursor).tick() <= pb.tick) {
                Recorded<T> op = ops.get(cursor++);
                pb.safely(() -> applier.apply(pb, op));
            }
        }

        void drainBack(Playback pb) {
            while (cursor > 0 && ops.get(cursor - 1).tick() > pb.tick) {
                Recorded<T> op = ops.get(--cursor);
                pb.safely(() -> applier.unapply(pb, op));
            }
        }

        void fold(EntityStates states, long target) {
            StateFolder<T> folder = type.folder();
            while (cursor < ops.size() && ops.get(cursor).tick() <= target) {
                Recorded<T> op = ops.get(cursor++);
                if (folder != null) folder.fold(states, op);
            }
        }

        @SuppressWarnings("unchecked")
        void applyLatest(Playback pb, EntityStates states) {
            for (Recorded<?> op : states.channel(type.id()).values()) {
                pb.safely(() -> applier.apply(pb, (Recorded<T>) op));
            }
        }

        boolean drained() {
            return cursor >= ops.size();
        }
    }
}
