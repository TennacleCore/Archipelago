package io.github.term4.shardkit.replay;

/** How spawn ops materialize on playback. */
public enum SpawnMode {
    /** Registered revivers upgrade descriptors to their real classes; visual stand-ins cover the rest. */
    REVIVED,
    /** Visual stand-ins only - the zero-dependency look, or when revived behavior is unwanted in the theater. */
    VISUAL
}
