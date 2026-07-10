package io.github.term4.shardkit.replay;

import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.polar.DataKey;
import io.github.term4.shardkit.polar.PolarShardCodec;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Codec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Replays over shards: a replay is ONE polar file - the t0 shard snapshot (blocks + sky) with a channel-per-
 * recordable op log in its user data. The lib ships the core channels (actor tracks, entity visuals + position
 * tracks, blocks, digs); libraries and apps contribute purely via registration: {@link #register recordables},
 * {@link #dispatchers recording sources}, {@link #sanitizeDescriptors descriptor sanitizers} and
 * {@link #enhanceSpawns spawn enhancers}.
 */
public final class Replay {

    /** The op log's slot in the polar user-data container. */
    public static final DataKey<CompoundBinaryTag> OPS = new DataKey<>("replay:ops", Codec.NBT_COMPOUND);

    private static final Map<String, Map<Integer, Recordable<?>>> RECORDABLES = new ConcurrentHashMap<>();
    private static final List<Supplier<Dispatcher>> DISPATCHERS = new CopyOnWriteArrayList<>();
    private static final List<UnaryOperator<CompoundBinaryTag>> SANITIZERS = new CopyOnWriteArrayList<>();
    private static final List<SpawnEnhancer> ENHANCERS = new CopyOnWriteArrayList<>();

    static {
        Channels.registerCore();
    }

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

    /** Registers a {@link SpawnEnhancer}, run on every replayed entity before it spawns. */
    public static void enhanceSpawns(@NotNull SpawnEnhancer enhancer) {
        ENHANCERS.add(enhancer);
    }

    /** Starts recording {@code shard} (multiple concurrent recordings are fine). */
    public static @NotNull Recording record(@NotNull Shard shard) {
        return new Recording(shard);
    }

    /**
     * Loads {@code file}'s t0 snapshot into {@code target} and starts playing its op log.
     *
     * @throws IllegalArgumentException when the file has no op log (a plain shard save)
     */
    public static @NotNull Playback play(@NotNull Shard target, byte @NotNull [] file) {
        return play(target, file, SpawnMode.UPGRADE);
    }

    /** {@link #play(Shard, byte[])} with an explicit {@link SpawnMode}. */
    public static @NotNull Playback play(@NotNull Shard target, byte @NotNull [] file, @NotNull SpawnMode spawns) {
        CompoundBinaryTag ops = PolarShardCodec.load(target, file).get(OPS);
        if (ops == null) throw new IllegalArgumentException("no replay op log in this file (a plain shard save?)");
        return new Playback(target, ops, spawns);
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

    static List<SpawnEnhancer> enhancers() {
        return ENHANCERS;
    }
}
