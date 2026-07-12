package io.github.term4.shardkit.event.replay;

import io.github.term4.shardkit.replay.Recording;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/** Fired when a recording session starts. */
public record RecordingStartEvent(@NotNull Recording recording) implements Event {}
