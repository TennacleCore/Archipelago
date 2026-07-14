package io.github.term4.archipelago.event.ticking;

import io.github.term4.archipelago.ticking.ExecutionDomain;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/**
 * Fired from the domain's clock thread at the start of each tick, before hooks and entities - the
 * {@code InstanceTickEvent} analog for domain-clocked execution ({@link ExecutionDomain#onTick} is the
 * cheaper programmatic hook). Listeners run ON the clock thread; keep them domain-local.
 */
public record DomainTickEvent(@NotNull ExecutionDomain domain, long tick) implements Event {}
