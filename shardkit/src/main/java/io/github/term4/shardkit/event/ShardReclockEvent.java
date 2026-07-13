package io.github.term4.shardkit.event;

import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.ticking.ExecutionDomain;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired after {@link Shard#domain(ExecutionDomain)} moved the shard to another clock ({@code null} side =
 * server-ticked). Tick-stamped gameplay state (i-frames, sprint windows) must re-base here - stamps from the
 * old clock are meaningless on the new one.
 */
public record ShardReclockEvent(@NotNull Shard shard, @Nullable ExecutionDomain from,
                                @Nullable ExecutionDomain to) implements Event {}
