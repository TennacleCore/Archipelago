package io.github.term4.archipelago.ticking;

import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.Chunk;
import org.jetbrains.annotations.NotNull;

/**
 * The global-dispatcher OPT-OUT - the one deliberately fragile class (see the module README). Overrides the
 * {@code @ApiStatus.Internal} {@link Entity#refreshCurrentChunk}: a domain-owned entity must never enter the
 * global dispatcher, or {@code serverTick}'s unconditional {@code updateAndAwait} would tick it twice. Verified
 * against Minestom 2026.07.12-26.2: the super body is exactly {@code currentChunk = chunk} +
 * {@code dispatcher().updateElement(this, chunk)} - re-verify on version bumps. Living hierarchies extend
 * {@link DomainLivingEntity}, players {@link DomainPlayer}; other bases replicate this exact override plus
 * the tick gate.
 *
 * <p>Subclass per-tick logic belongs in {@link Entity#update} (runs inside the gated tick); overriding
 * {@code tick} itself requires the {@link #foreignDriver()} bail-out first.
 */
public class DomainEntity extends Entity implements DomainTicked {

    public DomainEntity(@NotNull EntityType type) {
        super(type);
    }

    @Override protected void refreshCurrentChunk(@NotNull Chunk chunk) {
        if (ExecutionDomain.of(this) != null) {
            currentChunk = chunk;
            return;
        }
        super.refreshCurrentChunk(chunk);
    }

    @Override public void tick(long time) {
        if (foreignDriver()) return;
        super.tick(time);
    }

    protected final boolean foreignDriver() {
        return DomainTicked.foreignDriver(this);
    }
}
