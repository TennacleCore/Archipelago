package io.github.term4.shardkit;

import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/** Fired when a {@link ShardManager} registers a new shard. */
public final class ShardCreateEvent implements Event {

    private final Shard shard;

    public ShardCreateEvent(@NotNull Shard shard) {
        this.shard = shard;
    }

    public @NotNull Shard shard() { return shard; }
}
