package io.github.term4.archipelago.replay.track;

import io.github.term4.archipelago.replay.channel.Recorded;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A channel's whole op list as one packed blob, in place of the per-op NBT the file otherwise stores. Worth
 * writing only where the volume is: motion channels pack ~5x smaller than their NBT, everything else is noise
 * next to them and stays on the plain path.
 *
 * <p>Ops arrive tick-ascending and must come back tick-ascending; a codec is free to reorder within a tick
 * (grouping by key is what makes the deltas small), so only channels whose same-tick ops are independent
 * qualify. Positions and angles land on a fixed grid finer than the wire carries - see {@link BinWriter}.
 */
public interface TrackCodec<T> {

    byte @NotNull [] encode(@NotNull List<Recorded<T>> ops);

    @NotNull List<Recorded<T>> decode(byte @NotNull [] blob);
}
