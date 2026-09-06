package io.github.term4.archipelago.storage;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.utils.block.BlockUtils;
import net.minestom.server.MinecraftServer;
import net.minestom.server.registry.RegistryTranscoder;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** NBT helpers for the save/replay vocabulary. */
public final class Nbt {

    private Nbt() {}

    // the bare NBT transcoder has no registries: any item carrying an enchantment (or another registry-keyed
    // component) fails to encode at all
    public static @NotNull BinaryTag item(@NotNull ItemStack item) {
        return ItemStack.CODEC.encode(items(), item).orElseThrow();
    }

    public static @NotNull ItemStack item(@NotNull BinaryTag tag) {
        return ItemStack.CODEC.decode(items(), tag).orElseThrow();
    }

    private static Transcoder<BinaryTag> items() {
        return new RegistryTranscoder<>(Transcoder.NBT, MinecraftServer.getRegistries());
    }

    /** Equipment keyed by {@link EquipmentSlot#name()}, air slots omitted; {@link #applyEquipment} inverts. */
    public static @NotNull CompoundBinaryTag equipment(@NotNull LivingEntity entity) {
        EquipmentSlot[] slots = EquipmentSlot.values();
        ItemStack[] items = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) items[i] = entity.getEquipment(slots[i]);
        return equipment(items);
    }

    /** {@link #equipment(LivingEntity)} over an already-sampled slot array, indexed by {@link EquipmentSlot#values()}. */
    public static @NotNull CompoundBinaryTag equipment(@NotNull ItemStack @NotNull [] bySlot) {
        EquipmentSlot[] slots = EquipmentSlot.values();
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
        for (int i = 0; i < slots.length; i++) {
            if (!bySlot[i].isAir()) out.put(slots[i].name(), item(bySlot[i]));
        }
        return out.build();
    }

    /** Wears the slots {@code equipment} names. Slots it omits are left ALONE - an explicit AIR clear defeats
     *  ViaBackwards' skip-empty-slots fix and wipes the chestplate on legacy clients. */
    public static void applyEquipment(@NotNull LivingEntity entity, @NotNull CompoundBinaryTag equipment) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            BinaryTag item = equipment.get(slot.name());
            if (item != null) entity.setEquipment(slot, item(item));
        }
    }

    // NBT has no double-array type; 3-vectors ride double lists (the vanilla Pos/Motion convention)
    public static @NotNull ListBinaryTag vec3(double x, double y, double z) {
        return ListBinaryTag.builder(BinaryTagTypes.DOUBLE)
                .add(DoubleBinaryTag.doubleBinaryTag(x))
                .add(DoubleBinaryTag.doubleBinaryTag(y))
                .add(DoubleBinaryTag.doubleBinaryTag(z)).build();
    }

    /** Sky-override compound: {@code time} when set ({@code >= 0}), {@code rain}/{@code thunder} when
     *  {@code weather} set; {@link #skyTime}/{@link #skyWeather} invert. */
    public static @NotNull CompoundBinaryTag sky(long time, @org.jetbrains.annotations.Nullable Weather weather) {
        CompoundBinaryTag.Builder sky = CompoundBinaryTag.builder();
        if (time >= 0) sky.putLong("time", time);
        if (weather != null) sky.putFloat("rain", weather.rainLevel()).putFloat("thunder", weather.thunderLevel());
        return sky.build();
    }

    public static long skyTime(@NotNull CompoundBinaryTag sky) {
        return sky.get("time") != null ? sky.getLong("time") : -1;
    }

    public static @org.jetbrains.annotations.Nullable Weather skyWeather(@NotNull CompoundBinaryTag sky) {
        return sky.get("rain") != null ? new Weather(sky.getFloat("rain"), sky.getFloat("thunder")) : null;
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

    /** {@link #block} as a FILE read: a state the running server doesn't know fails as a bad file, not as a
     *  parse error somewhere deep in the load. */
    public static @NotNull Block parseState(@NotNull String state) {
        try {
            return block(state);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown block state '" + state
                    + "' (saved on a different Minecraft version?)", e);
        }
    }

    /** Canonical column order for position-keyed sections - sorted columns turn into near-arithmetic byte
     *  sequences (most of the compression) AND make the round trip byte-exact. */
    public static final Comparator<Point> POSITION_ORDER = Comparator.<Point>comparingInt(Point::blockY)
            .thenComparingInt(Point::blockZ).thenComparingInt(Point::blockX);

    /** Interning string palette: {@link #id} hands out indices in first-sight order. */
    public static final class Palette {

        private final List<String> entries = new ArrayList<>();
        private final Map<String, Integer> index = new HashMap<>();

        /** {@code state}'s index, appending it on first sight. */
        public int id(@NotNull String state) {
            Integer known = index.get(state);
            if (known != null) return known;
            int id = entries.size();
            entries.add(state);
            index.put(state, id);
            return id;
        }

        /** Appends an entry WITHOUT interning it - polar's index-0 untouched sentinel is a slot, not a state. */
        public void reserve(@NotNull String state) {
            entries.add(state);
        }

        public int size() {
            return entries.size();
        }

        public @NotNull List<String> entries() {
            return entries;
        }
    }

    /**
     * Deep-rebuilds compounds through one construction path (sorted-key insertion) so equal content
     * serializes to equal bytes - a compound's write order otherwise depends on how its backing map was
     * built (adventure builder vs reader, run-salted codec immutables).
     */
    public static @NotNull CompoundBinaryTag canonical(@NotNull CompoundBinaryTag tag) {
        List<String> keys = new ArrayList<>(tag.keySet());
        keys.sort(Comparator.naturalOrder());
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
        for (String key : keys) out.put(key, canonical(tag.get(key)));
        return out.build();
    }

    /** {@link #canonical} serialized - every lib file write goes through this. */
    public static byte @NotNull [] writeBytes(@NotNull CompoundBinaryTag tag,
                                              BinaryTagIO.@NotNull Compression compression) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BinaryTagIO.writer().write(canonical(tag), out, compression);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
