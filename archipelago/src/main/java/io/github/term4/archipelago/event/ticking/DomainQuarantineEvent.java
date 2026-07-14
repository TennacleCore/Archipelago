package io.github.term4.archipelago.event.ticking;

import io.github.term4.archipelago.ticking.ExecutionDomain;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/**
 * Fired from the watchdog thread when a domain FAILS (fail-stop; crash report on disk). A {@code ShardManager}
 * hands member players back to server ticking; the shards sit frozen in STASIS until the app re-clocks them
 * onto a FRESH domain, destroys them, or leaves them. Never reuse the failed domain.
 */
public record DomainQuarantineEvent(@NotNull ExecutionDomain domain) implements Event {}
