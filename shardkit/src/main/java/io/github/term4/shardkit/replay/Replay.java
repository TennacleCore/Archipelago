package io.github.term4.shardkit.replay;

import io.github.term4.shardkit.replay.channel.Dispatcher;
import io.github.term4.shardkit.replay.channel.Recordable;
import io.github.term4.shardkit.replay.channel.SpawnHook;
import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.event.replay.PlaybackStartEvent;
import io.github.term4.shardkit.event.replay.RecordingStartEvent;
import io.github.term4.shardkit.storage.DataKey;
import io.github.term4.shardkit.storage.ShardFile;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.event.EventDispatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Replays over shards: one {@code .shard} file = the t0 snapshot (blocks + sky) plus a channel-per-recordable
 * op log in its user data. Everything is registration - {@link #register recordables}, {@link #dispatchers
 * sources}, {@link #sanitizeDescriptors sanitizers}, {@link #onSpawn spawn hooks};
 * {@link io.github.term4.shardkit.replay.core.CoreChannels} is the built-in set.
 */
public final class Replay {

    /** The op log's slot in the file's user-data section. */
    public static final DataKey<CompoundBinaryTag> OPS = new DataKey<>("replay:ops", Codec.NBT_COMPOUND);

    /** A moment the game marked mid-recording ({@link Recording#bookmark}); {@code data} = app payload (empty when none). */
    public record Bookmark(long tick, @NotNull String name, @NotNull CompoundBinaryTag data) {}

    /** The bookmark index's slot in the file's user-data section. */
    public static final DataKey<List<Bookmark>> BOOKMARKS = new DataKey<>("replay:bookmarks",
            StructCodec.struct("t", Codec.LONG, Bookmark::tick, "n", Codec.STRING, Bookmark::name,
                    "d", Codec.NBT_COMPOUND, Bookmark::data, Bookmark::new).list());

    private static final Map<String, Map<Integer, Recordable<?>>> RECORDABLES = new ConcurrentHashMap<>();
    private static final List<Supplier<Dispatcher>> DISPATCHERS = new CopyOnWriteArrayList<>();
    private static final List<UnaryOperator<CompoundBinaryTag>> SANITIZERS = new CopyOnWriteArrayList<>();
    private static final List<SpawnHook> SPAWN_HOOKS = new CopyOnWriteArrayList<>();

    private Replay() {}

    /** Registers a channel; same id + version replaces (bump the version for breaking payload changes). */
    public static void register(@NotNull Recordable<?> recordable) {
        RECORDABLES.computeIfAbsent(recordable.id(), id -> new ConcurrentHashMap<>())
                .put(recordable.version(), recordable);
    }

    /** Registers a recording source; every new {@link Recording} gets its own instance. */
    public static void dispatchers(@NotNull Supplier<Dispatcher> factory) {
        DISPATCHERS.add(factory);
    }

    /**
     * Rewrites save descriptors as they are recorded - a library strips what must not replay (a projectile's
     * shooter link would re-bind, and teleport, the original thrower on playback).
     */
    public static void sanitizeDescriptors(@NotNull UnaryOperator<CompoundBinaryTag> sanitizer) {
        SANITIZERS.add(sanitizer);
    }

    /** Registers a {@link SpawnHook}, run on every replayed entity before it spawns. */
    public static void onSpawn(@NotNull SpawnHook hook) {
        SPAWN_HOOKS.add(hook);
    }

    /** Starts recording {@code shard} and its whole layer tree (multiple concurrent recordings are fine). */
    public static @NotNull Recording record(@NotNull Shard shard) {
        return record(shard, layer -> true);
    }

    /**
     * {@link #record(Shard)} recording only the child layers {@code layers} accepts - the root always
     * records, and an excluded layer excludes its whole subtree (a child's diff is meaningless without its
     * parent). {@code layer -> false} = just the root, no children.
     */
    public static @NotNull Recording record(@NotNull Shard shard, @NotNull Predicate<Shard> layers) {
        if (DISPATCHERS.isEmpty()) {
            throw new IllegalStateException("no recording sources registered - CoreChannels.register() or Replay.dispatchers(...)");
        }
        Recording recording = new Recording(shard, layers);
        EventDispatcher.call(new RecordingStartEvent(recording));
        return recording;
    }

    /**
     * Loads {@code file}'s t0 snapshot into {@code target} and starts playing its op log.
     *
     * @throws IllegalArgumentException when the file has no op log (a plain shard save)
     */
    public static @NotNull Playback play(@NotNull Shard target, byte @NotNull [] file) {
        return play(target, file, SpawnMode.REVIVED);
    }

    /** {@link #play(Shard, byte[])} with an explicit {@link SpawnMode}. */
    public static @NotNull Playback play(@NotNull Shard target, byte @NotNull [] file, @NotNull SpawnMode spawns) {
        if (RECORDABLES.isEmpty()) {
            throw new IllegalStateException("no recordables registered - CoreChannels.register() or Replay.register(...)");
        }
        ShardFile.UserDataView data = ShardFile.load(target, file);
        CompoundBinaryTag ops = data.get(OPS);
        if (ops == null) throw new IllegalArgumentException("no replay op log in this file (a plain shard save?)");
        Playback playback = new Playback(target, ops, spawns, data);
        EventDispatcher.call(new PlaybackStartEvent(playback));
        return playback;
    }

    /**
     * Plays a STOPPED recording straight from memory - the t0 snapshot loads into {@code target}, the op
     * queues play as they are; no encode/decode round trip. {@link Recording#stop()} still returns the file
     * for footage that leaves the JVM.
     */
    public static @NotNull Playback play(@NotNull Shard target, @NotNull Recording recording) {
        return play(target, recording, SpawnMode.REVIVED);
    }

    /** {@link #play(Shard, Recording)} with an explicit {@link SpawnMode}. */
    public static @NotNull Playback play(@NotNull Shard target, @NotNull Recording recording, @NotNull SpawnMode spawns) {
        if (recording.active()) throw new IllegalStateException("recording still running - stop() it first");
        ShardFile.load(target, recording.t0());
        Playback playback = new Playback(target, recording, spawns);
        EventDispatcher.call(new PlaybackStartEvent(playback));
        return playback;
    }

    static @Nullable Recordable<?> recordable(String id, int version) {
        Map<Integer, Recordable<?>> versions = RECORDABLES.get(id);
        return versions == null ? null : versions.get(version);
    }

    static List<Supplier<Dispatcher>> dispatcherFactories() {
        return DISPATCHERS;
    }

    static List<UnaryOperator<CompoundBinaryTag>> sanitizers() {
        return SANITIZERS;
    }

    /** The registered spawn hooks, in registration order (playback-side). */
    public static List<SpawnHook> spawnHooks() {
        return SPAWN_HOOKS;
    }
}
