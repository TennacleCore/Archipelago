package io.github.term4.shardkit.storage;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;

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

    /** Polar's palette entry format, {@code minecraft:block[k=v,...]}; parse back with {@code ArgumentBlockState}. */
    public static @NotNull String blockString(@NotNull Block block) {
        if (block.properties().isEmpty()) return block.name();
        StringBuilder out = new StringBuilder(block.name()).append('[');
        for (var entry : block.properties().entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
        }
        out.setCharAt(out.length() - 1, ']');
        return out.toString();
    }
}
