package io.github.term4.archipelago.replay.track;

import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.core.Channels;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Player motion grouped per player, ticks and coordinates on delta chains - a walking player's op costs a
 *  handful of bytes instead of ~94 as NBT. */
public final class MoveTrack implements TrackCodec<Channels.Move> {

    @Override
    public byte @NotNull [] encode(@NotNull List<Recorded<Channels.Move>> ops) {
        Map<String, List<Recorded<Channels.Move>>> byPlayer = new LinkedHashMap<>();
        for (Recorded<Channels.Move> op : ops) {
            byPlayer.computeIfAbsent(op.op().player(), k -> new ArrayList<>()).add(op);
        }
        BinWriter w = new BinWriter();
        w.var(byPlayer.size());
        for (Map.Entry<String, List<Recorded<Channels.Move>>> group : byPlayer.entrySet()) {
            List<Recorded<Channels.Move>> list = group.getValue();
            w.var(w.intern(group.getKey())).var(list.size());
            long pt = 0, px = 0, py = 0, pz = 0;
            byte[] ground = new byte[(list.size() + 7) / 8];
            for (int i = 0; i < list.size(); i++) {
                Recorded<Channels.Move> op = list.get(i);
                Channels.Move m = op.op();
                long fx = BinWriter.fixed(m.x()), fy = BinWriter.fixed(m.y()), fz = BinWriter.fixed(m.z());
                w.zig(op.tick() - pt).zig(fx - px).zig(fy - py).zig(fz - pz).angle(m.yaw()).angle(m.pitch());
                pt = op.tick(); px = fx; py = fy; pz = fz;
                if (m.ground()) ground[i >> 3] |= (byte) (1 << (i & 7));
            }
            w.bytes(ground);
        }
        return w.finish();
    }

    @Override
    public @NotNull List<Recorded<Channels.Move>> decode(byte @NotNull [] blob) {
        BinReader r = new BinReader(blob);
        List<Recorded<Channels.Move>> out = new ArrayList<>();
        int groups = (int) r.var();
        for (int g = 0; g < groups; g++) {
            String player = r.pooled((int) r.var());
            int n = (int) r.var();
            long[] ticks = new long[n];
            double[] xs = new double[n], ys = new double[n], zs = new double[n];
            float[] yaws = new float[n], pitches = new float[n];
            long pt = 0, px = 0, py = 0, pz = 0;
            for (int i = 0; i < n; i++) {
                pt += r.zig(); px += r.zig(); py += r.zig(); pz += r.zig();
                ticks[i] = pt;
                xs[i] = BinReader.unfixed(px); ys[i] = BinReader.unfixed(py); zs[i] = BinReader.unfixed(pz);
                yaws[i] = r.angle(); pitches[i] = r.angle();
            }
            byte[] ground = r.bits(n); // trails the group's ops, as written
            for (int i = 0; i < n; i++) {
                out.add(new Recorded<>(ticks[i], new Channels.Move(player, xs[i], ys[i], zs[i],
                        yaws[i], pitches[i], r.bit(ground, i))));
            }
        }
        out.sort(Comparator.comparingLong(Recorded::tick)); // grouped on the way in, tick order on the way out
        return out;
    }
}
