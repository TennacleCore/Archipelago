package io.github.term4.archipelago.event;

import io.github.term4.archipelago.shard.Shard;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/** Fired after a shard is destroyed: members evicted, diff + entities dropped, registry entry removed. */
public record ShardDestroyEvent(@NotNull Shard shard) implements Event {}
