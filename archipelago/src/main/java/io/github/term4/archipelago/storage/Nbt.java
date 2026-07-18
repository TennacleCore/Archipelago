package io.github.term4.archipelago.storage;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.utils.block.BlockUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** NBT helpers for the save/replay vocabulary. */
public final class Nbt {

    private Nbt() {}

    public static @NotNull BinaryTag item(@NotNull ItemStack item) {
        return ItemStack.CODEC.encode(Transcoder.NBT, item).orElseThrow();
    }

    public static @NotNull ItemStack item(@NotNull BinaryTag tag) {
        return ItemStack.CODEC.decode(Transcoder.NBT, tag).orElseThrow();
    }

    // NBT has no double-array type; 3-vectors ride double lists (the vanilla Pos/Motion convention)
    public static @NotNull ListBinaryTag vec3(double x, double y, double z) {
        return ListBinaryTag.builder(BinaryTagTypes.DOUBLE)
                .add(DoubleBinaryTag.doubleBinaryTag(x))
                .add(DoubleBinaryTag.doubleBinaryTag(y))
                .add(DoubleBinaryTag.doubleBinaryTag(z)).build();
    }

    /** Polar's palette entry format, {@code minecraft:block[k=v,...]}; {@link #block(String)} inverts. */
    public static @NotNull String blockString(@NotNull Block block) {
        if (block.properties().isEmpty()) return block.name();
        StringBuilder out = new StringBuilder(block.name()).append('[');
        for (var entry : block.properties().entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
        }
        out.setCharAt(out.length() - 1, ']');
        return out.toString();
    }

    /** The inverse of {@link #blockString}; throws on unknown blocks or property values. */
    public static @NotNull Block block(@NotNull String state) {
        int props = state.indexOf('[');
        String name = props < 0 ? state : state.substring(0, props);
        Block block = Block.fromKey(name);
        if (block == null) throw new IllegalArgumentException("unknown block '" + state + "'");
        return props < 0 ? block : block.withProperties(BlockUtils.parseProperties(state.substring(props)));
    }

    /**
     * Deep-rebuilds compounds through one construction path (sorted-key insertion) so equal content
     * serializes to equal bytes - a compound's write order otherwise depends on how its backing map was
     * built (adventure builder vs reader, run-salted codec immutables). Every lib file write goes through this.
     */
    public static @NotNull CompoundBinaryTag canonical(@NotNull CompoundBinaryTag tag) {
        List<String> keys = new ArrayList<>(tag.keySet());
        keys.sort(Comparator.naturalOrder());
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
        for (String key : keys) out.put(key, canonical(tag.get(key)));
        return out.build();
    }

    /** {@code tag} minus {@code key} (compounds are immutable). */
    public static @NotNull CompoundBinaryTag without(@NotNull CompoundBinaryTag tag, @NotNull String key) {
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
        for (var e : tag) {
            if (!e.getKey().equals(key)) out.put(e.getKey(), e.getValue());
        }
        return out.build();
    }

    private static BinaryTag canonical(BinaryTag tag) {
        if (tag instanceof CompoundBinaryTag compound) return canonical(compound);
        if (tag instanceof ListBinaryTag list && list.size() > 0
                && (list.elementType() == BinaryTagTypes.COMPOUND || list.elementType() == BinaryTagTypes.LIST)) {
            ListBinaryTag.Builder<BinaryTag> out = ListBinaryTag.builder();
            for (BinaryTag element : list) out.add(canonical(element));
            return out.build();
        }
        return tag;
    }
}
