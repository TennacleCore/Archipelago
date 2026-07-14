package io.github.term4.archipelago.event.replay;

import io.github.term4.archipelago.replay.Playback;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/** Fired when a playback session starts. */
public record PlaybackStartEvent(@NotNull Playback playback) implements Event {}
