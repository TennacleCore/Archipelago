package io.github.term4.archipelago.replay.channel;

import net.minestom.server.codec.Codec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A replay channel: a discrete versioned op type. Files store ops under {@code id} + {@code version}; a breaking
 * payload change bumps the version and registers a parallel {@code Recordable} so old files stay playable.
 *
 * @param order playback position within a tick, lowest first - ops that create what others reference come first
 *              (the core actor track runs at {@code -20}; app channels usually sit above {@code 0})
 * @param applier one instance per playback (appliers may hold playback-local state)
 * @param folder seek support: keys ops so skipping time applies only each key's latest op; {@code null} = the
 *               channel is transient (skipped entirely when seeking)
 */
public record Recordable<T>(@NotNull String id, int version, int order, @NotNull Codec<T> codec,
                            @NotNull Supplier<? extends Applier<T>> applier, @Nullable StateFolder<T> folder) {

    public static <T> @NotNull Recordable<T> of(@NotNull String id, int version, int order, @NotNull Codec<T> codec,
                                                @NotNull Supplier<? extends Applier<T>> applier) {
        return new Recordable<>(id, version, order, codec, applier, null);
    }

    public @NotNull Recordable<T> folding(@NotNull StateFolder<T> folder) {
        return new Recordable<>(id, version, order, codec, applier, folder);
    }
}
