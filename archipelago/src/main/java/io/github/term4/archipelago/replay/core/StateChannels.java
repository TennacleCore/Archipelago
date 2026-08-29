package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Dispatcher;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Opt-in player-STATE channels - health, hunger, xp, inventory. They serve state reconstruction (a player
 * taking over a recorded actor, joinable replays), not ghost visuals, so they are NOT part of
 * {@link CoreChannels#register()}. Appliers are state folders: {@link #health}, {@link #hunger}, {@link #xp}
 * and {@link #inventory} read the folded state at the playback's current tick.
 */
public final class StateChannels {

    private static volatile boolean registered;

    private StateChannels() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Replay.register(HEALTH);
        Replay.register(HUNGER);
        Replay.register(XP);
        Replay.register(INVENTORY);
        Replay.dispatchers(StateSampler::new);
    }

    public record Health(String player, float health) {}
    public record Hunger(String player, int food, float saturation) {}
    public record Xp(String player, int level, float progress) {}
    /** {@code item} = the encoded stack; an empty compound = the slot cleared. */
    public record Slot(String player, int slot, BinaryTag item) {}

    private record SlotKey(String player, int slot) {}

    public static final Recordable<Health> HEALTH = Recordable.of("replay:health", 1, 50, StructCodec.struct(
                    "p", Codec.STRING, Health::player, "h", Codec.FLOAT, Health::health, Health::new),
                    () -> new Latest<>(StateChannels.HEALTH, Health::player))
            .folding((s, op) -> s.latest(StateChannels.HEALTH, op.op().player(), op))
            .keyframing();

    public static final Recordable<Hunger> HUNGER = Recordable.of("replay:hunger", 1, 50, StructCodec.struct(
                    "p", Codec.STRING, Hunger::player, "f", Codec.INT, Hunger::food,
                    "s", Codec.FLOAT, Hunger::saturation, Hunger::new),
                    () -> new Latest<>(StateChannels.HUNGER, Hunger::player))
            .folding((s, op) -> s.latest(StateChannels.HUNGER, op.op().player(), op))
            .keyframing();

    public static final Recordable<Xp> XP = Recordable.of("replay:xp", 1, 50, StructCodec.struct(
                    "p", Codec.STRING, Xp::player, "l", Codec.INT, Xp::level,
                    "x", Codec.FLOAT, Xp::progress, Xp::new),
                    () -> new Latest<>(StateChannels.XP, Xp::player))
            .folding((s, op) -> s.latest(StateChannels.XP, op.op().player(), op))
            .keyframing();

    // one op per slot change: folds per (actor, slot), so seeks and reversals stay exact
    public static final Recordable<Slot> INVENTORY = Recordable.of("replay:inventory", 1, 50, StructCodec.struct(
                    "p", Codec.STRING, Slot::player, "s", Codec.INT, Slot::slot,
                    "i", Codec.NBT, Slot::item, Slot::new),
                    () -> new Latest<>(StateChannels.INVENTORY, op -> new SlotKey(op.player(), op.slot())))
            .folding((s, op) -> s.latest(StateChannels.INVENTORY,
                    new SlotKey(op.op().player(), op.op().slot()), op))
            .keyframing();

    /** The folded health of {@code player} at the playback's current tick, or {@code null} (unseen). */
    public static @Nullable Float health(@NotNull Playback pb, @NotNull String player) {
        Latest<String, Health> a = latest(pb, HEALTH);
        Health h = a == null ? null : a.state(player);
        return h == null ? null : h.health();
    }

    public static @Nullable Hunger hunger(@NotNull Playback pb, @NotNull String player) {
        Latest<String, Hunger> a = latest(pb, HUNGER);
        return a == null ? null : a.state(player);
    }

    public static @Nullable Xp xp(@NotNull Playback pb, @NotNull String player) {
        Latest<String, Xp> a = latest(pb, XP);
        return a == null ? null : a.state(player);
    }

    /** {@code player}'s folded inventory: slot -> stack, explicit {@link ItemStack#AIR} = a cleared slot; unseen slots absent. */
    public static @NotNull Map<Integer, ItemStack> inventory(@NotNull Playback pb, @NotNull String player) {
        Map<Integer, ItemStack> out = new HashMap<>();
        Latest<SlotKey, Slot> a = latest(pb, INVENTORY);
        if (a != null) {
            a.forEach((key, slot) -> {
                if (key.player().equals(player)) out.put(key.slot(), item(slot.item()));
            });
        }
        return out;
    }

    private static ItemStack item(BinaryTag tag) {
        return tag instanceof CompoundBinaryTag c && c.keySet().isEmpty() ? ItemStack.AIR : Nbt.item(tag);
    }

    @SuppressWarnings("unchecked")
    private static <K, T> @Nullable Latest<K, T> latest(Playback pb, Recordable<T> channel) {
        return pb.applier(channel) instanceof Latest<?, ?> l ? (Latest<K, T>) l : null;
    }

    /** The generic state folder: latest op per key, reversal-aware. */
    static final class Latest<K, T> implements Applier<T> {
        private final Function<T, K> key;
        private final LookBack<K, T> history;
        // owner-thread mutated; concurrent for cross-thread take-over reads
        private final Map<K, T> state = new ConcurrentHashMap<>();

        Latest(Recordable<T> channel, Function<T, K> key) {
            this.key = key;
            this.history = new LookBack<>(channel, key);
        }

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<T> op) {
            state.put(key.apply(op.op()), op.op());
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<T> op) {
            Recorded<T> prev = history.before(pb, op);
            K k = key.apply(op.op());
            if (prev != null) state.put(k, prev.op());
            else state.remove(k); // before its first op the state was unknown
        }

        @Nullable T state(K k) {
            return state.get(k);
        }

        void forEach(BiConsumer<K, T> consumer) {
            state.forEach(consumer);
        }
    }

    /**
     * Per-tick member sampling, delta-gated; a keyframe drops the memory so seeks can fold from the latest
     * boundary. Respawns need no re-emit - refreshActor's flag stays the ActorSampler's to consume.
     */
    static final class StateSampler implements Dispatcher {
        private final Map<String, Health> lastVitals = new HashMap<>();
        private final Map<String, Hunger> lastHunger = new HashMap<>();
        private final Map<String, Xp> lastXp = new HashMap<>();
        private final Map<String, ItemStack[]> lastInventory = new HashMap<>();

        /** Keyframe: current members re-emit everything (air slots stay implicit, as at first sight). */
        @Override
        public void reset(@NotNull Recording rec) {
            lastVitals.clear();
            lastHunger.clear();
            lastXp.clear();
            lastInventory.clear();
        }

        @Override
        public void sample(@NotNull Recording rec) {
            for (Shard layer : rec.layers()) {
                for (Player m : layer.members()) sample(rec, m);
            }
        }

        private void sample(Recording rec, Player m) {
            String name = m.getUsername();
            Health health = new Health(name, m.getHealth());
            if (!health.equals(lastVitals.put(name, health))) rec.emit(HEALTH, health);
            Hunger hunger = new Hunger(name, m.getFood(), m.getFoodSaturation());
            if (!hunger.equals(lastHunger.put(name, hunger))) rec.emit(HUNGER, hunger);
            Xp xp = new Xp(name, m.getLevel(), m.getExp());
            if (!xp.equals(lastXp.put(name, xp))) rec.emit(XP, xp);
            PlayerInventory inv = m.getInventory();
            ItemStack[] now = new ItemStack[inv.getSize()];
            ItemStack[] before = lastInventory.put(name, now);
            for (int i = 0; i < now.length; i++) {
                ItemStack stack = inv.getItemStack(i);
                now[i] = stack;
                if (before == null ? stack.isAir() : stack.equals(before[i])) continue; // first sight: air is implicit
                rec.emit(INVENTORY, new Slot(name, i,
                        stack.isAir() ? CompoundBinaryTag.empty() : Nbt.item(stack)));
            }
        }
    }
}
