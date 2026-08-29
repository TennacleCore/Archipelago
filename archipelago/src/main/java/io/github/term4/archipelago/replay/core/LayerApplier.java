package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.coordinate.Vec;
import org.jetbrains.annotations.NotNull;

final class LayerApplier implements Applier<Channels.Layer> {
    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Layer> op) {
        Channels.Layer l = op.op();
        if (!l.alive()) {
            pb.dropLayer(l.id());
            return;
        }
        if (pb.layer(l.id()) != null) return; // already raised (seek fold)
        Shard parent = pb.layer(l.parent());
        // a bare child inherits the theater's domain: one clock plays the whole tree
        pb.layer(l.id(), new Shard(parent != null ? parent : pb.shard()));
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Layer> op) {
        Channels.Layer l = op.op();
        if (l.alive()) { // un-create: the layer didn't exist yet
            pb.dropLayer(l.id());
            return;
        }
        if (pb.layer(l.id()) != null) return;
        // un-destroy: re-raise from the CREATE op (the destroy carries no parent) and repaint the layer
        // to its state at the destroy tick - its own ops, dumps included, replay in order
        for (Recorded<Channels.Layer> r : pb.ops(Channels.LAYERS)) {
            if (r.op().id() == l.id() && r.op().alive()) {
                apply(pb, r);
                break;
            }
        }
        Shard in = pb.layer(l.id());
        if (in == null) return;
        for (Recorded<Channels.BlockOp> b : pb.ops(Channels.BLOCKS)) {
            if (b.tick() > op.tick()) break;
            if (b.op().layer() == l.id()) {
                in.pinBlock(new Vec(b.op().x(), b.op().y(), b.op().z()), Nbt.block(b.op().block()));
            }
        }
    }
}
