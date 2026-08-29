package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

final class BlockApplier implements Applier<Channels.BlockOp> {
    private record At(int layer, int x, int y, int z) {}

    private final LookBack<At, Channels.BlockOp> history = new LookBack<>(Channels.BLOCKS,
            b -> new At(b.layer(), b.x(), b.y(), b.z()));

    private final HeldOps<Channels.BlockOp> held = new HeldOps<>(false);

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.BlockOp> op) {
        // an explosion's holes must not beat its boom
        if (!held.hold(pb, op)) applyNow(pb, op);
    }

    @Override
    public void tick(@NotNull Playback pb) {
        held.tick(pb, this::applyNow);
    }

    @Override
    public boolean settled() {
        return held.settled();
    }

    private void applyNow(@NotNull Playback pb, @NotNull Recorded<Channels.BlockOp> op) {
        Channels.BlockOp b = op.op();
        Shard in = pb.layer(b.layer());
        if (in != null) write(in, op, new Vec(b.x(), b.y(), b.z()), Nbt.block(b.block()));
    }

    // pin, don't set: a cross-clock tree's layer dumps may land child-before-parent, and a mask that
    // momentarily equals the parent view must not normalize away. EXCEPT tick-0 root ops - the
    // recording's understructure masks: plain writes, so a fresh theater's parent-equal copies drop
    // while a drifted live parent (the time-rewind recipe) stays masked
    private static void write(Shard in, Recorded<Channels.BlockOp> op, Vec pos, Block block) {
        if (op.tick() == 0 && op.op().layer() == 0) in.setBlock(pos, block);
        else in.pinBlock(pos, block);
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.BlockOp> op) {
        Channels.BlockOp b = op.op();
        Shard in = pb.layer(b.layer());
        if (in == null) return;
        Vec pos = new Vec(b.x(), b.y(), b.z());
        Recorded<Channels.BlockOp> prev = history.before(pb, op);
        if (prev != null) {
            write(in, prev, pos, Nbt.block(prev.op().block()));
            return;
        }
        // no earlier op: back to the t0 snapshot (root layer) or the parent view (the entry clears -
        // under an unloaded chunk too, or the un-applied overlay state survives the reversal)
        Block t0 = b.layer() == 0 ? pb.t0Block(pos) : null;
        if (t0 != null) {
            in.pinBlock(pos, t0);
        } else {
            in.clearBlock(pos);
        }
    }
}
