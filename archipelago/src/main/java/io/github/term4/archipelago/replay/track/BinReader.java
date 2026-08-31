package io.github.term4.archipelago.replay.track;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/** The read half of {@link BinWriter}; the string table is consumed up front, exactly as it was written. */
public final class BinReader {

    private final byte[] buf;
    private int at;
    private final String[] pool;

    public BinReader(byte @NotNull [] blob) {
        this.buf = blob;
        int count = (int) var();
        this.pool = new String[count];
        for (int i = 0; i < count; i++) {
            int len = (int) var();
            pool[i] = new String(buf, at, len, StandardCharsets.UTF_8);
            at += len;
        }
    }

    public long var() {
        long value = 0;
        for (int shift = 0; ; shift += 7) {
            byte b = buf[at++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
    }

    public long zig() {
        long raw = var();
        return (raw >>> 1) ^ -(raw & 1);
    }

    public float angle() {
        int a = ((buf[at++] & 0xFF) << 8) | (buf[at++] & 0xFF);
        return (short) a * (360f / 65536f);
    }

    public boolean bit(byte @NotNull [] bits, int i) {
        return (bits[i >> 3] & (1 << (i & 7))) != 0;
    }

    public byte @NotNull [] bits(int count) {
        byte[] out = new byte[(count + 7) / 8];
        System.arraycopy(buf, at, out, 0, out.length);
        at += out.length;
        return out;
    }

    public @NotNull String pooled(int id) {
        return pool[id];
    }

    public static double unfixed(long fixed) {
        return fixed / BinWriter.POS_SCALE;
    }

    public boolean done() {
        return at >= buf.length;
    }
}
