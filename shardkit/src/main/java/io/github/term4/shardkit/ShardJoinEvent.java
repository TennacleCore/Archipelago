package io.github.term4.shardkit;

import net.minestom.server.entity.Player;
import net.minestom.server.event.trait.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/** Fired after a player becomes a member of a shard (visibility + view already applied) - the hook for per-game chat, scoreboards, tab. */
public final class ShardJoinEvent implements PlayerEvent {

    private final Player player;
    private final Shard shard;

    public ShardJoinEvent(@NotNull Player player, @NotNull Shard shard) {
        this.player = player;
        this.shard = shard;
    }

    public @NotNull Shard shard() { return shard; }

    @Override public @NotNull Player getPlayer() { return player; }
}
