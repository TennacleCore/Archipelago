package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.replay.channel.Dispatcher;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.Viewers;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.event.replay.PlaybackStartEvent;
import io.github.term4.archipelago.event.replay.RecordingStartEvent;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Replays over shards: one {@code .shard} file = the t0 snapshot (blocks + sky) plus a channel-per-recordable
 * op log in its user data. Everything is registration - {@link #register recordables}, {@link #dispatchers sources};
 * {@link io.github.term4.archipelago.replay.core.CoreChannels} is the built-in set.
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

    /** @deprecated one era per viewer, library-wide: {@link Viewers#protocol}. */
    @Deprecated
    public static void viewerProtocol(@NotNull ToIntFunction<Player> source) {
        Viewers.protocol(source);
    }

    /** @deprecated {@link Viewers#protocolWhenUnknown}. */
    @Deprecated
    public static void protocolWhenUnknown(int protocol) {
        Viewers.protocolWhenUnknown(protocol);
    }

    /** Whether {@code viewer} renders the legacy (1.8-line) twin wire - see docs/replay-bridge.md. */
    public static boolean legacyViewer(@NotNull Player viewer) {
        return Viewers.legacy(viewer);
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
     * The file a spooled recording whose process died mid-game leaves behind: the spool's header (t0 and the
     * app's data) and its closed segments, cut at the last one. Null when the spool has no header or no closed
     * segment. Reads the file alone - the recording is gone.
     */
    public static byte @Nullable [] salvage(@NotNull Path spool) throws IOException {
        return Recording.salvage(spool);
    }

    /**
     * Loads {@code file}'s t0 snapshot into {@code target} and starts playing its op log.
     *
     * @throws IllegalArgumentException when the file has no op log (a plain shard save)
     */
    public static @NotNull Playback play(@NotNull Shard target, byte @NotNull [] file) {
        if (RECORDABLES.isEmpty()) {
            throw new IllegalStateException("no recordables registered - CoreChannels.register() or Replay.register(...)");
        }
        // validate before touching the target - a plain shard save must throw with the target unchanged
        ShardFile.Content content = ShardFile.decode(file);
        CompoundBinaryTag ops = ShardFile.userData(content).get(OPS);
        if (ops == null) throw new IllegalArgumentException("no replay op log in this file (a plain shard save?)");
        UserData.View data = ShardFile.load(target, content);
        Playback playback = new Playback(target, ops, data);
        EventDispatcher.call(new PlaybackStartEvent(playback));
        return playback;
    }

    /**
     * Plays a STOPPED recording straight from memory - the t0 snapshot loads into {@code target}, the op
     * queues play as they are; no encode/decode round trip. {@link Recording#stop()} still returns the file
     * for footage that leaves the JVM.
     */
    public static @NotNull Playback play(@NotNull Shard target, @NotNull Recording recording) {
        if (recording.active()) throw new IllegalStateException("recording still running - stop() it first");
        ShardFile.load(target, recording.t0());
        Playback playback = new Playback(target, recording);
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
}
