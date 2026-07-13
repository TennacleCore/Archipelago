package io.github.term4.shardkit.event;

import io.github.term4.shardkit.shard.Shard;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/** Fired when a {@code ShardManager} registers a new shard. */
public record ShardCreateEvent(@NotNull Shard shard) implements Event {}
