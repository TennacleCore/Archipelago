package io.github.term4.archipelago.ticking;

import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.instance.Chunk;
import org.jetbrains.annotations.NotNull;

/**
 * {@link DomainEntity}'s LivingEntity twin: the same dispatcher opt-out and stale-driver gate. Subclass
 * per-tick logic belongs in {@code update(time)} (runs inside the gated tick); overriding {@code tick} itself
 * requires the {@link #foreignDriver()} bail-out first.
 */
public class DomainLivingEntity extends LivingEntity implements DomainTicked {

    public DomainLivingEntity(@NotNull EntityType type) {
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
