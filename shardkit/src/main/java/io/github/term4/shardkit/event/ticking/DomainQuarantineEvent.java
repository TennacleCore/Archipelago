package io.github.term4.shardkit.event.ticking;

import io.github.term4.shardkit.ticking.ExecutionDomain;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/**
 * Fired from the watchdog thread when a domain FAILS (fail-stop; crash report on disk). A {@code ShardManager}
 * destroys its shards on the dead domain; bare-shard apps tear down here. Never reuse it - rebuild fresh.
 */
public record DomainQuarantineEvent(@NotNull ExecutionDomain domain) implements Event {}
