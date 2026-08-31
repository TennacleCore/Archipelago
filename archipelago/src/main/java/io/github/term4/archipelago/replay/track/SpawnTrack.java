package io.github.term4.archipelago.replay.track;

import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.channel.TrackCodec;
import io.github.term4.archipelago.replay.core.Channels;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spawns in op order, visual snapshots paletted by content - respawning entities (arrows, items) share one blob. */
public final class SpawnTrack implements TrackCodec<Channels.Spawn> {

    @Override
    public byte @NotNull [] encode(@NotNull List<Recorded<Channels.Spawn>> ops) {
        List<byte[]> palette = new ArrayList<>();
        Map<String, Integer> byContent = new LinkedHashMap<>();
        int[] visualIds = new int[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            byte[] nbt = write(ops.get(i).op().visual());
            visualIds[i] = byContent.computeIfAbsent(java.util.HexFormat.of().formatHex(nbt), k -> {
                palette.add(nbt);
                return palette.size() - 1;
            });
        }
        BinWriter w = new BinWriter();
        w.var(palette.size());
        for (byte[] blob : palette) w.var(blob.length).bytes(blob);
        w.var(ops.size());
        long pt = 0;
        for (int i = 0; i < ops.size(); i++) {
            Recorded<Channels.Spawn> op = ops.get(i);
            Channels.Spawn s = op.op();
            w.zig(op.tick() - pt);
            pt = op.tick();
            w.var(s.layer()).zig(s.seq())
                    .zig(BinWriter.fixed(s.x())).zig(BinWriter.fixed(s.y())).zig(BinWriter.fixed(s.z()))
                    .angle(s.yaw()).angle(s.pitch())
                    .var(visualIds[i]).var(w.intern(s.shooter()));
        }
        return w.finish();
    }

    @Override
    public @NotNull List<Recorded<Channels.Spawn>> decode(byte @NotNull [] blob) {
        BinReader r = new BinReader(blob);
        int paletteSize = (int) r.var();
        List<CompoundBinaryTag> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) palette.add(read(r.bytes((int) r.var())));
        int n = (int) r.var();
        List<Recorded<Channels.Spawn>> out = new ArrayList<>(n);
        long pt = 0;
        for (int i = 0; i < n; i++) {
            pt += r.zig();
            int layer = (int) r.var();
            int seq = (int) r.zig();
            double x = BinReader.unfixed(r.zig()), y = BinReader.unfixed(r.zig()), z = BinReader.unfixed(r.zig());
            float yaw = r.angle(), pitch = r.angle();
            CompoundBinaryTag visual = palette.get((int) r.var());
            String shooter = r.pooled((int) r.var());
            out.add(new Recorded<>(pt, new Channels.Spawn(layer, x, y, z, yaw, pitch, seq, visual, shooter)));
        }
        return out;
    }

    private static byte[] write(CompoundBinaryTag tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryTagIO.writer().write(tag, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CompoundBinaryTag read(byte[] nbt) {
        try {
            return BinaryTagIO.reader().read(new ByteArrayInputStream(nbt));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
