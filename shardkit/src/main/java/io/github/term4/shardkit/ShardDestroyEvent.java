package io.github.term4.shardkit;

import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/** Fired after a shard is destroyed: members evicted, diff + entities dropped, registry entry removed. */
public final class ShardDestroyEvent implements Event {

    private final Shard shard;

    public ShardDestroyEvent(@NotNull Shard shard) {
        this.shard = shard;
    }

    public @NotNull Shard shard() { return shard; }
}
