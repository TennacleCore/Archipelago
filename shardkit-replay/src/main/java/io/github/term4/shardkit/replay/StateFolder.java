package io.github.term4.shardkit.replay;

import org.jetbrains.annotations.NotNull;

/**
 * Folds a channel's ops into {@link EntityStates} for seeking: keep the latest op per key, drop ops that a later
 * op cancels (a remove drops its spawn). {@link Playback#seek} then applies only the survivors.
 */
public interface StateFolder<T> {

    void fold(@NotNull EntityStates states, @NotNull Recorded<T> op);
}
