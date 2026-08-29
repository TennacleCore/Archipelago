package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
final class DigApplier implements Applier<Channels.Dig> {
    private record Crack(int layer, int ghostId, int x, int y, int z, long start, long end, byte[] sent) {}
    private record Window(Channels.Dig dig, long from, long to) {}

    private final List<Crack> cracks = new ArrayList<>();
    private List<Window> windows;

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Dig> op) {
        if (!op.op().start()) return; // cancels only bound the lookahead below
        Ghost digger = pb.ghost(op.op().digger());
        if (digger != null) cracks.add(new Crack(op.op().layer(), digger.getEntityId(),
                op.op().x(), op.op().y(), op.op().z(), op.tick(), end(pb, op), new byte[]{-2}));
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Dig> op) {
        if (!op.op().start()) return; // crossing a start in reverse: the crack never began
        cracks.removeIf(c -> c.layer() == op.op().layer()
                && c.x() == op.op().x() && c.y() == op.op().y() && c.z() == op.op().z());
        Shard in = pb.layer(op.op().layer());
        Ghost digger = pb.ghost(op.op().digger());
        if (in != null && digger != null) {
            in.broadcast(new BlockBreakAnimationPacket(digger.getEntityId(),
                    new Vec(op.op().x(), op.op().y(), op.op().z()), (byte) -1));
        }
    }

    @Override
    public void tick(@NotNull Playback pb) {
        if (pb.reversed()) reraise(pb);
        var it = cracks.iterator();
        while (it.hasNext()) {
            Crack c = it.next();
            Shard in = pb.layer(c.layer());
            if (in == null) { // its layer died mid-dig
                it.remove();
                continue;
            }
            long duration = Math.max(1, c.end() - c.start());
            int stage = (int) Math.min(9, (pb.tick() - c.start()) * 10 / duration);
            byte wire = pb.tick() >= c.end() ? (byte) -1 : (byte) stage; // -1 clears (the break/cancel arrived)
            if (c.sent()[0] != wire) { // stages hold for many ticks (paused: forever)
                c.sent()[0] = wire;
                in.broadcast(new BlockBreakAnimationPacket(c.ghostId(), new Vec(c.x(), c.y(), c.z()), wire));
            }
            if (pb.tick() >= c.end()) it.remove();
        }
    }

    // reverse re-entry: a break-ended dig's window closes on a BLOCK op, so no dig op crosses when the
    // timeline backs into it - re-raise the crack from the window index and it animates 9 -> 0
    private void reraise(Playback pb) {
        if (windows == null) {
            windows = new ArrayList<>();
            List<Recorded<Channels.Dig>> digs = pb.ops(Channels.DIGS);
            for (int i = 0; i < digs.size(); i++) {
                Recorded<Channels.Dig> op = digs.get(i);
                if (op.op().start()) {
                    windows.add(new Window(op.op(), op.tick(),
                            end(pb, op, i + 1, LookBack.after(pb.ops(Channels.BLOCKS), op.tick()))));
                }
            }
        }
        for (Window w : windows) {
            if (pb.tick() < w.from() || pb.tick() >= w.to()) continue;
            Ghost digger = pb.ghost(w.dig().digger());
            if (digger == null) continue;
            boolean present = cracks.stream().anyMatch(c -> c.layer() == w.dig().layer()
                    && c.x() == w.dig().x() && c.y() == w.dig().y() && c.z() == w.dig().z());
            if (!present) {
                cracks.add(new Crack(w.dig().layer(), digger.getEntityId(),
                        w.dig().x(), w.dig().y(), w.dig().z(), w.from(), w.to(), new byte[]{-2}));
            }
        }
    }

    @Override
    public boolean settled() {
        return cracks.isEmpty();
    }

    /** When this dig visibly ends: its cancel, the block breaking, or a 3s fallback - by op-log lookahead. */
    private long end(Playback pb, Recorded<Channels.Dig> op) {
        return end(pb, op, pb.cursor(Channels.DIGS), pb.cursor(Channels.BLOCKS));
    }

    private long end(Playback pb, Recorded<Channels.Dig> op, int digFrom, int blockFrom) {
        Channels.Dig start = op.op();
        long end = op.tick() + 60;
        List<Recorded<Channels.Dig>> digs = pb.ops(Channels.DIGS);
        for (int i = digFrom; i < digs.size(); i++) {
            Channels.Dig d = digs.get(i).op();
            if (!d.start() && d.layer() == start.layer()
                    && d.x() == start.x() && d.y() == start.y() && d.z() == start.z()) {
                end = Math.min(end, digs.get(i).tick());
                break;
            }
        }
        List<Recorded<Channels.BlockOp>> blocks = pb.ops(Channels.BLOCKS);
        for (int i = blockFrom; i < blocks.size(); i++) {
            Channels.BlockOp b = blocks.get(i).op();
            if (b.layer() == start.layer()
                    && b.x() == start.x() && b.y() == start.y() && b.z() == start.z()) {
                end = Math.min(end, blocks.get(i).tick());
                break;
            }
        }
        return Math.max(end, op.tick() + 1);
    }
}
