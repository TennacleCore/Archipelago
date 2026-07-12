package io.github.term4.shardkit.replay.channel;

import io.github.term4.shardkit.replay.Recording;
import org.jetbrains.annotations.NotNull;

/**
 * A recording source. Implement directly for custom hooks (the Misc kind), or extend {@link EventDispatcher} /
 * {@link PacketDispatcher}; override {@link #sample} for per-tick sampling (the Tick kind). One instance per
 * {@link Recording} - register a factory with {@link io.github.term4.shardkit.replay.Replay#dispatchers}.
 */
public interface Dispatcher {

    default void start(@NotNull Recording recording) {}

    /** Once per recorded tick. */
    default void sample(@NotNull Recording recording) {}

    default void stop(@NotNull Recording recording) {}
}
