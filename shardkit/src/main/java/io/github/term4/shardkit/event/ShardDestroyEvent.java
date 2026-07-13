package io.github.term4.shardkit.event;

import io.github.term4.shardkit.shard.Shard;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/** Fired after a shard is destroyed: members evicted, diff + entities dropped, registry entry removed. */
public record ShardDestroyEvent(@NotNull Shard shard) implements Event {}
