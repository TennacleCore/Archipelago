package io.github.term4.shardkit.event;

import io.github.term4.shardkit.shard.Shard;
import net.minestom.server.entity.Player;
import net.minestom.server.event.trait.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/** Fired after a player leaves a shard (also on disconnect cleanup) - undo whatever {@link ShardJoinEvent} set up. */
public record ShardLeaveEvent(@NotNull Player player, @NotNull Shard shard) implements PlayerEvent {

    @Override public @NotNull Player getPlayer() { return player; }
}
