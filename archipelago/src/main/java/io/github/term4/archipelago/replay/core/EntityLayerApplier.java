package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.List;
final class EntityLayerApplier implements Applier<Channels.EntityLayer> {
    private final LookBack<Integer, Channels.EntityLayer> history =
            new LookBack<>(Channels.ENTITY_LAYERS, Channels.EntityLayer::seq);
    private final LookBack<Integer, Channels.Spawn> spawns =
            new LookBack<>(Channels.SPAWNS, Channels.Spawn::seq);

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityLayer> op) {
        Shard to = pb.layer(op.op().layer());
        Entity twin = pb.entity(op.op().seq());
        if (to != null && twin != null) to.transfer(twin);
    }

    @Override
    public void prepare(@NotNull Playback playback) {
        history.warm(playback);
        spawns.warm(playback);
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityLayer> op) {
        Recorded<Channels.EntityLayer> prev = history.before(pb, op);
        if (prev != null) {
            apply(pb, prev);
            return;
        }
        List<Recorded<Channels.Spawn>> born = spawns.all(pb, op.op().seq()); // back to its spawn layer
        if (!born.isEmpty()) {
            Shard to = pb.layer(born.get(0).op().layer());
            Entity twin = pb.entity(op.op().seq());
            if (to != null && twin != null) to.transfer(twin);
        }
    }
}
