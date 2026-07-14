package io.github.term4.archipelago.replay.channel;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * The seek accumulator: per channel, the latest op per key ({@link StateFolder}s fill it). Applying the survivors
 * in channel order lands the playback at the target tick without replaying every intermediate op.
 */
public final class EntityStates {

    private final Map<String, Map<Object, Recorded<?>>> latest = new HashMap<>();

    public <T> void latest(@NotNull Recordable<T> channel, @NotNull Object key, @NotNull Recorded<T> op) {
        latest.computeIfAbsent(channel.id(), id -> new HashMap<>()).put(key, op);
    }

    public boolean has(@NotNull Recordable<?> channel, @NotNull Object key) {
        Map<Object, Recorded<?>> ops = latest.get(channel.id());
        return ops != null && ops.containsKey(key);
    }

    public void drop(@NotNull Recordable<?> channel, @NotNull Object key) {
        Map<Object, Recorded<?>> ops = latest.get(channel.id());
        if (ops != null) ops.remove(key);
    }

    /** All folded ops of channel {@code id}, latest per key (the seek applier reads this). */
    public Map<Object, Recorded<?>> channel(String id) {
        return latest.getOrDefault(id, Map.of());
    }
}
