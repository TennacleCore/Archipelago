package io.github.term4.archipelago.ticking;

import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Contract marker: this entity class opts out of the global dispatcher while domain-owned (it carries
 * {@link DomainEntity}'s {@code refreshCurrentChunk} override or a replica). Only covered entities may be
 * handed to a domain - an unmarked one would re-register on its next chunk crossing and double-tick.
 */
public interface DomainTicked {

    /** Cross-library opt-out: classes carrying the override that cannot implement this interface (a bridge wires it once). */
    static void cover(@NotNull Predicate<Entity> rule) {
        Holder.COVERED.add(rule);
    }

    /** Whether {@code entity}'s class opts out of the global dispatcher - safe to hand to a domain. */
    static boolean covers(@NotNull Entity entity) {
        if (entity instanceof DomainTicked) return true;
        for (Predicate<Entity> rule : Holder.COVERED) {
            if (rule.test(entity)) return true;
        }
        return false;
    }

    /**
     * Whether something other than the owning clock is calling {@code tick}: the dispatcher's eviction is queued,
     * so a server stalled past the one-period defer can still tick a just-adopted entity. Every covered
     * {@code tick} override bails out first when true.
     */
    static boolean foreignDriver(@NotNull Entity entity) {
        ExecutionDomain owner = ExecutionDomain.of(entity);
        return owner != null && !owner.onThread();
    }

    final class Holder {
        private static final List<Predicate<Entity>> COVERED = new CopyOnWriteArrayList<>();
        private Holder() {}
    }
}
