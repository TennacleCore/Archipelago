package io.github.term4.archipelago.replay.channel;

import io.github.term4.archipelago.replay.Playback;
import org.jetbrains.annotations.NotNull;

/** Plays one channel's ops back into the playback shard. One instance per {@link Playback}. */
public interface Applier<T> {

    void apply(@NotNull Playback playback, @NotNull Recorded<T> op);

    /**
     * Crosses {@code op} in reverse ({@link Playback#reverse}): restore the state from just before it.
     * Default no-op fits one-shot effects (swings, hurt flashes) - stateful channels look back to their
     * previous op for the key and re-apply it.
     */
    default void unapply(@NotNull Playback playback, @NotNull Recorded<T> op) {}

    /** Runs once per playback tick after that tick's ops (ongoing effects, e.g. crack animations). */
    default void tick(@NotNull Playback playback) {}

    /** Playback only ends once every applier settles (pending effects drained). */
    default boolean settled() {
        return true;
    }
}
