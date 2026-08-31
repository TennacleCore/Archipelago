package io.github.term4.archipelago.replay.track;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Byte primitives for {@link TrackCodec}: varints, zigzag for delta chains, a string pool, and the two grids
 * motion lands on - positions at 1/4096 block (the unit modern relative-move packets themselves carry) and
 * angles at 1/65536 turn (256x finer than the byte angles the wire sends). Both are below anything a client
 * can render, so a packed track plays back indistinguishably from the doubles it came from.
 */
public final class BinWriter {

    /** Fixed-point divisor for positions: matches the protocol's own relative-move granularity. */
    public static final double POS_SCALE = 4096.0;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<String, Integer> pool = new LinkedHashMap<>();

    public @NotNull BinWriter var(long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
        return this;
    }

    /** Zigzag varint - the delta encoding, so small negative steps cost one byte like small positive ones. */
    public @NotNull BinWriter zig(long value) {
        return var((value << 1) ^ (value >> 63));
    }

    public @NotNull BinWriter angle(float degrees) {
        int a = Math.round(degrees * (65536f / 360f)) & 0xFFFF;
        out.write(a >>> 8);
        out.write(a);
        return this;
    }

    public @NotNull BinWriter bytes(byte @NotNull [] raw) {
        out.write(raw, 0, raw.length);
        return this;
    }

    /** Interns {@code s}; the id is what the body writes, the table rides {@link #finish}. */
    public int intern(@NotNull String s) {
        return pool.computeIfAbsent(s, k -> pool.size());
    }

    public static long fixed(double coordinate) {
        return Math.round(coordinate * POS_SCALE);
    }

    /** The blob: string table first (readers need it before the body), then everything written. */
    public byte @NotNull [] finish() {
        BinWriter head = new BinWriter();
        head.var(pool.size());
        for (String s : pool.keySet()) {
            byte[] utf = s.getBytes(StandardCharsets.UTF_8);
            head.var(utf.length).bytes(utf);
        }
        head.bytes(out.toByteArray());
        return head.out.toByteArray();
    }
}
