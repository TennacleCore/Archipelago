package io.github.term4.shardkit;

import net.minestom.server.entity.Entity;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Fired after a non-player entity's ownership moves to {@link #to()} ({@link #from()} null = was unbound). */
public final class ShardTransferEvent implements Event {

    private final Entity entity;
    private final @Nullable Shard from;
    private final Shard to;

    public ShardTransferEvent(@NotNull Entity entity, @Nullable Shard from, @NotNull Shard to) {
        this.entity = entity;
        this.from = from;
        this.to = to;
    }

    public @NotNull Entity entity() { return entity; }
    public @Nullable Shard from() { return from; }
    public @NotNull Shard to() { return to; }
}
