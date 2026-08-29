package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import org.jetbrains.annotations.NotNull;

/** The recorded {@code /shard sky} overrides: static state, no cycles - a layer's time/weather at
 *  the crossing. Rate/pause need no scaling (fixed values), reverse restores the previous state. */
final class SkyApplier implements Applier<Channels.Sky> {
    private final LookBack<Integer, Channels.Sky> history = new LookBack<>(Channels.SKIES, Channels.Sky::layer);

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Sky> op) {
        Shard in = pb.layer(op.op().layer());
        if (in == null) return;
        in.setTime(op.op().time());
        if (op.op().weatherSet()) {
            in.setWeather(new net.minestom.server.instance.Weather(op.op().rain(), op.op().thunder()));
        }
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Sky> op) {
        Recorded<Channels.Sky> prev = history.before(pb, op);
        if (prev != null) {
            apply(pb, prev);
            return;
        }
        // before the first change: the t0 override state (with any) rides a tick-0 op, so no-prev
        // means no override existed - clear time; weather has no public clear, it keeps
        Shard in = pb.layer(op.op().layer());
        if (in != null) in.setTime(-1);
    }
}
