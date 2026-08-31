package io.github.term4.archipelago.replay.track;

import io.github.term4.archipelago.replay.channel.Recorded;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A channel's whole op list as one packed blob instead of per-op NBT - worth it for the motion channels
 * (~5x smaller), noise for the rest.
 *
 * <p>Ops arrive tick-ascending and must come back tick-ascending; reordering within a tick is fine, so only
 * channels whose same-tick ops are independent qualify. Positions/angles land on {@link BinWriter}'s grids.
 */
public interface TrackCodec<T> {

    byte @NotNull [] encode(@NotNull List<Recorded<T>> ops);

    @NotNull List<Recorded<T>> decode(byte @NotNull [] blob);
}
