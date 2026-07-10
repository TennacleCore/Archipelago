package io.github.term4.shardkit.replay;

/** One op in a channel, stamped with the recording tick it happened on. */
public record Recorded<T>(long tick, T op) {}
