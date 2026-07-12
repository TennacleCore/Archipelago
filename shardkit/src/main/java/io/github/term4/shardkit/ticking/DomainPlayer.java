package io.github.term4.shardkit.ticking;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.jetbrains.annotations.NotNull;

/**
 * {@link DomainEntity}'s Player twin: the same dispatcher opt-out and stale-driver gate. Install as (or extend
 * from) the connection manager's player provider; apps with their own Player class replicate the two overrides
 * and register via {@link DomainTicked#cover} instead. Subclass per-tick logic belongs in {@code update(time)}
 * (runs inside the gated tick); overriding {@code tick} itself requires the {@link #foreignDriver()} bail-out first.
 */
public class DomainPlayer extends Player implements DomainTicked {

    public DomainPlayer(@NotNull PlayerConnection connection, @NotNull GameProfile gameProfile) {
        super(connection, gameProfile);
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

    /** {@link DomainTicked#foreignDriver}; subclasses overriding {@code tick} bail out first when true. */
    protected final boolean foreignDriver() {
        return DomainTicked.foreignDriver(this);
    }
}
