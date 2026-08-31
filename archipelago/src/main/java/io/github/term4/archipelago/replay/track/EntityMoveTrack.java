package io.github.term4.archipelago.replay.track;

import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.core.Channels;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link MoveTrack} for entities - same delta chains, keyed by spawn seq instead of a player name. */
public final class EntityMoveTrack implements TrackCodec<Channels.EntityMove> {

    @Override
    public byte @NotNull [] encode(@NotNull List<Recorded<Channels.EntityMove>> ops) {
        Map<Integer, List<Recorded<Channels.EntityMove>>> bySeq = new LinkedHashMap<>();
        for (Recorded<Channels.EntityMove> op : ops) {
            bySeq.computeIfAbsent(op.op().seq(), k -> new ArrayList<>()).add(op);
        }
        BinWriter w = new BinWriter();
        w.var(bySeq.size());
        for (Map.Entry<Integer, List<Recorded<Channels.EntityMove>>> group : bySeq.entrySet()) {
            List<Recorded<Channels.EntityMove>> list = group.getValue();
            w.zig(group.getKey()).var(list.size());
            long pt = 0, px = 0, py = 0, pz = 0;
            for (Recorded<Channels.EntityMove> op : list) {
                Channels.EntityMove m = op.op();
                long fx = BinWriter.fixed(m.x()), fy = BinWriter.fixed(m.y()), fz = BinWriter.fixed(m.z());
                w.zig(op.tick() - pt).zig(fx - px).zig(fy - py).zig(fz - pz).angle(m.yaw()).angle(m.pitch());
                pt = op.tick(); px = fx; py = fy; pz = fz;
            }
        }
        return w.finish();
    }

    @Override
    public @NotNull List<Recorded<Channels.EntityMove>> decode(byte @NotNull [] blob) {
        BinReader r = new BinReader(blob);
        List<Recorded<Channels.EntityMove>> out = new ArrayList<>();
        int groups = (int) r.var();
        for (int g = 0; g < groups; g++) {
            int seq = (int) r.zig();
            int n = (int) r.var();
            long pt = 0, px = 0, py = 0, pz = 0;
            for (int i = 0; i < n; i++) {
                pt += r.zig(); px += r.zig(); py += r.zig(); pz += r.zig();
                float yaw = r.angle(), pitch = r.angle();
                out.add(new Recorded<>(pt, new Channels.EntityMove(seq, BinReader.unfixed(px),
                        BinReader.unfixed(py), BinReader.unfixed(pz), yaw, pitch)));
            }
        }
        out.sort(Comparator.comparingLong(Recorded::tick));
        return out;
    }
}
