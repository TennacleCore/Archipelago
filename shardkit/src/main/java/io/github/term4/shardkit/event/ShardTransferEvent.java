package io.github.term4.shardkit.event;

import io.github.term4.shardkit.Shard;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Fired after a non-player entity's ownership moves to {@link #to()} ({@link #from()} null = was unbound). */
public record ShardTransferEvent(@NotNull Entity entity, @Nullable Shard from, @NotNull Shard to) implements Event {}
