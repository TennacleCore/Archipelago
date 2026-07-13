package io.github.term4.shardkit.storage;

import io.github.term4.shardkit.shard.Shard;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.command.builder.arguments.minecraft.ArgumentBlockState;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The bare-minimum shard format: a shard IS its diff. One gzip'd NBT compound - a string-state palette (version
 * stable), columnar position/state arrays, an optional {@code before} column (what each position showed beneath
 * the diff - unread today, carried for commit-history/rollback), and a {@link DataKey} section for EVERYTHING
 * else (sky = the two override accessors, entities = {@code EntityDescriptors.write/read}, light, items, any
 * game data). No polar dependency; for big diffs polar's palette sections still compress tighter.
 * State strings only - block NBT/handlers need a custom section (or the polar codec).
 */
public final class ShardFile {

    private static final int FORMAT = 1;
    private static final int NO_BEFORE = -1;

    private ShardFile() {}

    public static byte @NotNull [] save(@NotNull Shard shard) {
        return save(shard, null, false);
    }

    public static byte @NotNull [] save(@NotNull Shard shard, @Nullable Consumer<UserDataWriter> userData) {
        return save(shard, userData, false);
    }

    /** {@code withBefore} also records what each position showed beneath the diff (unloaded-chunk = unknown). */
    public static byte @NotNull [] save(@NotNull Shard shard, @Nullable Consumer<UserDataWriter> userData,
                                        boolean withBefore) {
        Map<Point, Block> diff = shard.diff();
        // position-sorted columns turn into near-arithmetic byte sequences - most of the compression
        List<Map.Entry<Point, Block>> entries = new ArrayList<>(diff.entrySet());
        entries.sort(java.util.Comparator.comparingInt((Map.Entry<Point, Block> en) -> en.getKey().blockY())
                .thenComparingInt(en -> en.getKey().blockZ()).thenComparingInt(en -> en.getKey().blockX()));
        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new HashMap<>();
        int[] xs = new int[entries.size()], ys = new int[entries.size()], zs = new int[entries.size()];
        int[] states = new int[entries.size()];
        int[] before = withBefore ? new int[entries.size()] : null;
        Shard parent = shard.parent();
        int i = 0;
        for (Map.Entry<Point, Block> entry : entries) {
            Point pos = entry.getKey();
            xs[i] = pos.blockX();
            ys[i] = pos.blockY();
            zs[i] = pos.blockZ();
            states[i] = paletteId(entry.getValue(), palette, paletteIndex);
            if (before != null) {
                before[i] = !shard.instance().isChunkLoaded(pos) ? NO_BEFORE
                        : paletteId(parent != null ? parent.getBlock(pos) : shard.instance().getBlock(pos),
                        palette, paletteIndex);
            }
            i++;
        }
        ListBinaryTag.Builder<StringBinaryTag> states0 = ListBinaryTag.builder(BinaryTagTypes.STRING);
        for (String state : palette) states0.add(StringBinaryTag.stringBinaryTag(state));
        CompoundBinaryTag.Builder root = CompoundBinaryTag.builder()
                .putInt("v", FORMAT)
                .put("palette", states0.build())
                .putIntArray("x", xs).putIntArray("y", ys).putIntArray("z", zs);
        putColumn(root, "state", states, palette.size());
        if (before != null) putColumn(root, "before", before, palette.size());
        if (userData != null) {
            UserDataWriter writer = new UserDataWriter();
            userData.accept(writer);
            root.put("user", writer.tags.build());
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BinaryTagIO.writer().write(root.build(), out, BinaryTagIO.Compression.GZIP);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // palettes up to 255 entries (the norm) store one BYTE per block; 0xFF = the no-before sentinel
    private static void putColumn(CompoundBinaryTag.Builder root, String key, int[] column, int paletteSize) {
        if (paletteSize > 255) {
            root.putIntArray(key, column);
            return;
        }
        byte[] packed = new byte[column.length];
        for (int i = 0; i < column.length; i++) packed[i] = column[i] == NO_BEFORE ? (byte) 0xFF : (byte) column[i];
        root.putByteArray(key, packed);
    }

    private static int[] column(CompoundBinaryTag root, String key) {
        byte[] packed = root.getByteArray(key);
        if (packed.length == 0) return root.getIntArray(key);
        int[] out = new int[packed.length];
        for (int i = 0; i < packed.length; i++) {
            int v = packed[i] & 0xFF;
            out[i] = v == 0xFF ? NO_BEFORE : v;
        }
        return out;
    }

    private static int paletteId(Block block, List<String> palette, Map<String, Integer> index) {
        String state = Nbt.blockString(block);
        Integer known = index.get(state);
        if (known != null) return known;
        int id = palette.size();
        palette.add(state);
        index.put(state, id);
        return id;
    }

    /**
     * Applies a saved diff onto {@code shard} (one batched {@code setBlocks}, merging over anything there) and
     * returns the typed user-data view. Nothing mutates until the whole file parses - a corrupt file (or one
     * saved on a different Minecraft version) throws and leaves the shard untouched.
     */
    public static @NotNull UserDataView load(@NotNull Shard shard, byte @NotNull [] data) {
        CompoundBinaryTag root = read(data);
        Block[] palette = parsePalette(root);
        int[] xs = root.getIntArray("x"), ys = root.getIntArray("y"), zs = root.getIntArray("z");
        int[] states = column(root, "state");
        Map<Point, Block> diff = new HashMap<>();
        for (int i = 0; i < states.length; i++) diff.put(new Vec(xs[i], ys[i], zs[i]), palette[states[i]]);
        shard.setBlocks(diff);
        return new UserDataView(root.getCompound("user"));
    }

    /** The saved {@code before} column ({@link #save(Shard, Consumer, boolean)}); empty when absent/unknown. */
    public static @NotNull Map<Point, Block> before(byte @NotNull [] data) {
        CompoundBinaryTag root = read(data);
        int[] before = column(root, "before");
        if (before.length == 0) return Map.of();
        Block[] palette = parsePalette(root);
        int[] xs = root.getIntArray("x"), ys = root.getIntArray("y"), zs = root.getIntArray("z");
        Map<Point, Block> out = new HashMap<>();
        for (int i = 0; i < before.length; i++) {
            if (before[i] != NO_BEFORE) out.put(new Vec(xs[i], ys[i], zs[i]), palette[before[i]]);
        }
        return out;
    }

    private static CompoundBinaryTag read(byte[] data) {
        CompoundBinaryTag root;
        try {
            root = BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(data), BinaryTagIO.Compression.GZIP);
        } catch (IOException e) {
            throw new UncheckedIOException("not a shard file?", e);
        }
        if (root.getInt("v") != FORMAT) throw new IllegalArgumentException("unknown shard file version " + root.getInt("v"));
        return root;
    }

    private static Block[] parsePalette(CompoundBinaryTag root) {
        ListBinaryTag entries = root.getList("palette", BinaryTagTypes.STRING);
        Block[] palette = new Block[entries.size()];
        for (int i = 0; i < palette.length; i++) {
            String state = entries.getString(i);
            try {
                palette[i] = ArgumentBlockState.staticParse(state);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("unknown block state '" + state
                        + "' (saved on a different Minecraft version?)", e);
            }
        }
        return palette;
    }

    /** Collects {@link DataKey} entries at save time (values Codec-encode to NBT immediately). */
    public static final class UserDataWriter {
        private final CompoundBinaryTag.Builder tags = CompoundBinaryTag.builder();

        UserDataWriter() {}

        public <T> @NotNull UserDataWriter put(@NotNull DataKey<T> key, @NotNull T value) {
            tags.put(key.id(), key.codec().encode(Transcoder.NBT, value).orElseThrow());
            return this;
        }
    }

    /** The saved user data of a loaded file; {@link #get} decodes on access ({@code null} when absent). */
    public static final class UserDataView {
        private final CompoundBinaryTag tags;

        UserDataView(CompoundBinaryTag tags) {
            this.tags = tags;
        }

        public <T> @Nullable T get(@NotNull DataKey<T> key) {
            BinaryTag tag = tags.get(key.id());
            return tag == null ? null : key.codec().decode(Transcoder.NBT, tag).orElseThrow();
        }
    }
}
