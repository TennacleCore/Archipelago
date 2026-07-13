package io.github.term4.shardkit.event;

import io.github.term4.shardkit.shard.Shard;
import net.minestom.server.entity.Player;
import net.minestom.server.event.trait.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/** Fired after a player becomes a member (visibility + view applied) - the hook for per-game chat, scoreboards, tab. */
public record ShardJoinEvent(@NotNull Player player, @NotNull Shard shard) implements PlayerEvent {

    @Override public @NotNull Player getPlayer() { return player; }
}
