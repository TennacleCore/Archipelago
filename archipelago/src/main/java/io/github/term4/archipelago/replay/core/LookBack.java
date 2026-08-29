package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
/**
 * Reverse-crossing support: each key's op history, binary-searched by tick - "what was this key's state
 * just before op X". Built lazily from the playback's full op list, so seek folds can't desync it.
 */
final class LookBack<K, T> {
    private final Recordable<T> channel;
    private final Function<T, K> key;
    private Map<K, List<Recorded<T>>> index;

    LookBack(Recordable<T> channel, Function<T, K> key) {
        this.channel = channel;
        this.key = key;
    }

    @Nullable Recorded<T> before(Playback pb, Recorded<T> op) {
        List<Recorded<T>> list = built(pb).get(key.apply(op.op()));
        if (list == null) return null;
        int lo = after(list, op.tick() - 1); // then identity within the equal-tick run
        while (lo < list.size() && list.get(lo) != op) lo++;
        return lo > 0 && lo < list.size() ? list.get(lo - 1) : null;
    }

    /** The key's newest op at or before {@code tick} - its state at the cursor. */
    @Nullable Recorded<T> latest(Playback pb, K k, long tick) {
        List<Recorded<T>> list = built(pb).get(k);
        if (list == null) return null;
        int lo = after(list, tick);
        return lo > 0 ? list.get(lo - 1) : null;
    }

    /** The key's oldest op strictly after {@code tick}, or null. */
    @Nullable Recorded<T> firstAfter(Playback pb, K k, long tick) {
        List<Recorded<T>> list = built(pb).get(k);
        if (list == null) return null;
        int lo = after(list, tick);
        return lo < list.size() ? list.get(lo) : null;
    }

    /** The key's full op list (chronological), empty when it has none. */
    List<Recorded<T>> all(Playback pb, K k) {
        return built(pb).getOrDefault(k, List.of());
    }

    private Map<K, List<Recorded<T>>> built(Playback pb) {
        if (index == null) {
            index = pb.opIndex(channel.id(), () -> {
                Map<K, List<Recorded<T>>> map = new HashMap<>();
                for (Recorded<T> r : pb.ops(channel)) {
                    map.computeIfAbsent(key.apply(r.op()), k -> new ArrayList<>()).add(r);
                }
                return map;
            });
        }
        return index;
    }

    /** First index in the tick-sorted {@code ops} strictly after {@code tick}. */
    static int after(List<? extends Recorded<?>> ops, long tick) {
        int lo = 0, hi = ops.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ops.get(mid).tick() <= tick) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
