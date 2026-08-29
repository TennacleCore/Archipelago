package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Recorded;

import java.util.ArrayList;
import java.util.List;
/** One-tick presentation hold for instantaneous ops at live fractional crossings: the scene renders
 *  one timeline tick behind the cursor, so an op played at its own crossing beats the thing it
 *  belongs to. {@code bothWays} holds under reverse too (one-shots); otherwise a direction flip
 *  drops the held op - the fold owns the scene. */
final class HeldOps<T> {
    private final boolean bothWays;
    private final List<Recorded<T>> held = new ArrayList<>();

    HeldOps(boolean bothWays) {
        this.bothWays = bothWays;
    }

    /** True when {@code op} queued for the next tick instead of playing now. */
    boolean hold(Playback pb, Recorded<T> op) {
        if (op.tick() == pb.tick() && pb.rate() < 1 && !pb.paused() && (bothWays || !pb.reversed())) {
            held.add(op);
            return true;
        }
        return false;
    }

    void tick(Playback pb, java.util.function.BiConsumer<Playback, Recorded<T>> play) {
        held.removeIf(op -> {
            long behind = pb.tick() - op.tick();
            if (bothWays) behind = Math.abs(behind);
            if (behind < 0 || behind > 2) return true; // the cursor jumped: the fold owns the scene
            if (behind >= 1) {
                play.accept(pb, op);
                return true;
            }
            return false;
        });
    }

    boolean settled() {
        return held.isEmpty();
    }
}
