package io.github.term4.archipelago.event.replay;

import io.github.term4.archipelago.replay.Recording;
import net.minestom.server.event.Event;
import org.jetbrains.annotations.NotNull;

/** Fired when a recording session ends, before the file is encoded. */
public record RecordingStopEvent(@NotNull Recording recording) implements Event {}
