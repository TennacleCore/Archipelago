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

    /**
     * Once after the playback starts, off its clock: build what the first reverse crossing would otherwise build
     * on the clock (a keyed channel's whole op index, a second's pass over a long recording).
     */
    default void prepare(@NotNull Playback playback) {}

    /** Runs once per playback tick after that tick's ops (ongoing effects, e.g. crack animations). */
    default void tick(@NotNull Playback playback) {}

    /**
     * A backward seek to {@code target}, before the refold: undo what the fold from its base will not write back
     * - theater state this channel put there after {@code target} that no op at or before it covers. Ghosts,
     * twins and risen layers the playback clears itself; blocks are the case, each touched position written
     * back to its state at {@code target}.
     */
    default void revert(@NotNull Playback playback, long target) {}

    /** Playback only ends once every applier settles (pending effects drained). */
    default boolean settled() {
        return true;
    }
}
