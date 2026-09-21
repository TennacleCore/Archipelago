package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.storage.Nbt;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in container-STATE channel: one op per changed slot of a stored container, keyed by the app's own container
 * key (a cell, a player, a team - the engine never reads it). Folds per (layer, key, slot) like the player
 * inventory, so a take-over or a branch reads the contents at any tick. The app records it: a change in its
 * container store becomes one {@link Slot}. Not keyframed - the ops are few, and the recorder is not the engine's.
 */
public final class ContainerChannels {

    private ContainerChannels() {}

    /** {@code item} = the encoded stack; an empty compound = the slot cleared. */
    public record Slot(int layer, String key, int slot, BinaryTag item) {}

    private record SlotKey(int layer, String key, int slot) {}

    public static final Recordable<Slot> CONTAINERS = Recordable.of("replay:container", 1, 50, StructCodec.struct(
                    "l", Codec.INT, Slot::layer, "k", Codec.STRING, Slot::key,
                    "s", Codec.INT, Slot::slot, "i", Codec.NBT, Slot::item, Slot::new),
                    () -> new StateChannels.Latest<>(ContainerChannels.CONTAINERS, op -> new SlotKey(op.layer(), op.key(), op.slot())))
            .folding((s, op) -> s.latest(ContainerChannels.CONTAINERS,
                    new SlotKey(op.op().layer(), op.op().key(), op.op().slot()), op));

    public static void register() {
        Replay.register(CONTAINERS);
    }

    /** One op's payload for a slot now holding {@code stack} (air = cleared). */
    public static @NotNull Slot slot(int layer, @NotNull String key, int slot, @NotNull ItemStack stack) {
        return new Slot(layer, key, slot, stack.isAir() ? CompoundBinaryTag.empty() : Nbt.item(stack));
    }

    /** The folded containers of {@code layer} at the playback's current tick: key -&gt; slot -&gt; stack, cleared slots as air. */
    @SuppressWarnings("unchecked")
    public static @NotNull Map<String, Map<Integer, ItemStack>> containers(@NotNull Playback pb, int layer) {
        Map<String, Map<Integer, ItemStack>> out = new HashMap<>();
        if (pb.applier(CONTAINERS) instanceof StateChannels.Latest<?, ?> latest) {
            ((StateChannels.Latest<SlotKey, Slot>) latest).forEach((k, op) -> {
                if (k.layer() != layer) return;
                ItemStack stack = op.item() instanceof CompoundBinaryTag c && c.keySet().isEmpty() ? ItemStack.AIR : Nbt.item(op.item());
                out.computeIfAbsent(k.key(), x -> new HashMap<>()).put(k.slot(), stack);
            });
        }
        return out;
    }
}
