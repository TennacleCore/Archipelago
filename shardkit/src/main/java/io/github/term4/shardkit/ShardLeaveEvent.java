package io.github.term4.shardkit;

import net.minestom.server.entity.Player;
import net.minestom.server.event.trait.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/** Fired after a player leaves a shard (also on disconnect cleanup) - undo whatever {@link ShardJoinEvent} set up. */
public final class ShardLeaveEvent implements PlayerEvent {

    private final Player player;
    private final Shard shard;

    public ShardLeaveEvent(@NotNull Player player, @NotNull Shard shard) {
        this.player = player;
        this.shard = shard;
    }

    public @NotNull Shard shard() { return shard; }

    @Override public @NotNull Player getPlayer() { return player; }
}
