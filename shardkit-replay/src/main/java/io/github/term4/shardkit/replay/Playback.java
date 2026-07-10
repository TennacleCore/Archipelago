package io.github.term4.shardkit.replay;

import io.github.term4.shardkit.Shard;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.entity.Entity;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One playback session: the generic tick loop over the file's channels, each applied through its registered
 * {@link Applier} in {@link Recordable#order()} order. Channels whose id/version has no registered recordable
 * (or won't decode) are {@link #skipped()}; everything else still plays.
 */
public final class Playback {

    private static final Logger LOGGER = LoggerFactory.getLogger(Playback.class);

    private final Shard shard;
    private final List<Channel<?>> channels = new ArrayList<>();
    private final Map<Recordable<?>, Channel<?>> byType = new HashMap<>();
    private final List<String> skipped = new ArrayList<>();
    private final long length;
    private final SpawnMode spawnMode;
    private final Task ticker;

    final Map<String, Ghost> ghosts = new HashMap<>();
    final Map<String, Channels.Skin> skins = new HashMap<>();
    final Map<Integer, Entity> entities = new HashMap<>();
    /** Seqs whose remove op drained before their (deferred) spawn materialized - that spawn must never happen. */
    final Set<Integer> removedEarly = new HashSet<>();
    /** Actors whose real counterpart isn't renderable (hidden, or between death and respawn): no ghost rises. */
    final Set<String> hiddenActors = new HashSet<>();

    private long tick;
    private Runnable whenDone;
    private boolean ended;
    private boolean loggedError;

    Playback(@NotNull Shard shard, @NotNull CompoundBinaryTag container, @NotNull SpawnMode spawnMode) {
        this.shard = shard;
        this.spawnMode = spawnMode;
        this.length = container.getLong("ticks");
        CompoundBinaryTag channelsTag = container.getCompound("channels");
        for (String id : channelsTag.keySet()) {
            CompoundBinaryTag channel = channelsTag.getCompound(id);
            int version = channel.getInt("v");
            Recordable<?> type = Replay.recordable(id, version);
            if (type == null) {
                skipped.add(id + " v" + version);
                continue;
            }
            try {
                channels.add(decode(type, channel.getList("ops", BinaryTagTypes.COMPOUND)));
            } catch (RuntimeException e) {
                skipped.add(id + " v" + version + " (undecodable: " + e.getMessage() + ")");
            }
        }
        channels.sort(Comparator.comparingInt((Channel<?> c) -> c.type.order()).thenComparing(c -> c.type.id()));
        channels.forEach(c -> byType.put(c.type, c));
        ticker = MinecraftServer.getSchedulerManager().scheduleTask(this::step,
                TaskSchedule.tick(1), TaskSchedule.tick(1));
    }

    private static <T> Channel<T> decode(Recordable<T> type, ListBinaryTag ops) {
        List<Recorded<T>> out = new ArrayList<>(ops.size());
        for (BinaryTag tag : ops) {
            CompoundBinaryTag entry = (CompoundBinaryTag) tag;
            out.add(new Recorded<>(entry.getLong("t"),
                    type.codec().decode(Transcoder.NBT, entry.get("op")).orElseThrow()));
        }
        return new Channel<>(type, out);
    }

    private void step() {
        tick++;
        for (Channel<?> channel : channels) channel.drain(this);
        for (Channel<?> channel : channels) safely(() -> channel.applier.tick(this));
        if (tick >= length && channels.stream().allMatch(c -> c.drained() && c.applier.settled())) end();
    }

    /**
     * Jumps forward to {@code target}: folds the skipped ops through each channel's {@link StateFolder} and
     * applies only the survivors (each entity's latest state), so ghosts land in place without replaying every
     * intermediate op. Backward seeking is unsupported (block ops don't unwind).
     */
    public void seek(long target) {
        if (ended) throw new IllegalStateException("playback ended");
        if (target <= tick) throw new IllegalArgumentException("seek is forward-only (at tick " + tick + ")");
        EntityStates states = new EntityStates();
        for (Channel<?> channel : channels) channel.fold(states, target);
        for (Channel<?> channel : channels) channel.applyLatest(this, states);
        tick = target;
    }

    public @NotNull Shard shard() {
        return shard;
    }

    /** The current playback tick. */
    public long tick() {
        return tick;
    }

    /** The recording's length in ticks (playback never ends before it). */
    public long length() {
        return length;
    }

    public boolean active() {
        return !ended;
    }

    public @NotNull SpawnMode spawnMode() {
        return spawnMode;
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

    /** The recorded actor's ghost, or {@code null} (not spawned yet, or unknown). */
    public @Nullable Ghost ghost(@NotNull String player) {
        return ghosts.get(player);
    }

    /** The live twin of recorded entity {@code seq}, or {@code null}. */
    public @Nullable Entity entity(int seq) {
        return entities.get(seq);
    }

    /** Binds a replayed entity to its recorded seq - the position track and removal target it. */
    public void track(int seq, @NotNull Entity entity) {
        entities.put(seq, entity);
    }

    /** A channel's full op list (appliers may look ahead, e.g. for a dig's end). */
    public <T> @NotNull List<Recorded<T>> ops(@NotNull Recordable<T> type) {
        @SuppressWarnings("unchecked")
        Channel<T> channel = (Channel<T>) byType.get(type);
        return channel == null ? List.of() : channel.ops;
    }

    /** The index of the first not-yet-applied op in {@code type}'s channel. */
    public int cursor(@NotNull Recordable<?> type) {
        Channel<?> channel = byType.get(type);
        return channel == null ? 0 : channel.cursor;
    }

    /** Ends playback early: ghosts and replayed entities are removed. */
    public void halt() {
        end();
    }

    /** Runs when playback ends (finished or halted). */
    public @NotNull Playback whenDone(@NotNull Runnable callback) {
        this.whenDone = callback;
        return this;
    }

    private void end() {
        if (ended) return;
        ended = true;
        ticker.cancel();
        ghosts.values().forEach(Entity::remove);
        entities.values().forEach(Entity::remove);
        if (whenDone != null) whenDone.run();
    }

    // one bad op must never stall the rest of the replay
    void safely(Runnable op) {
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
        final Applier<T> applier;
        int cursor;

        Channel(Recordable<T> type, List<Recorded<T>> ops) {
            this.type = type;
            this.ops = ops;
            this.applier = type.applier().get();
        }

        void drain(Playback pb) {
            while (cursor < ops.size() && ops.get(cursor).tick() <= pb.tick) {
                Recorded<T> op = ops.get(cursor++);
                pb.safely(() -> applier.apply(pb, op));
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
