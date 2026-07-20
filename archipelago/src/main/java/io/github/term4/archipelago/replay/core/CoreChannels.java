package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.channel.Dispatcher;

import java.util.function.Supplier;

/**
 * The built-in channel set: actor tracks, entity spawns/tracks/removals, blocks, digs, plus their recording
 * sources. Opt-in - register once at boot, or record purely custom channels without it.
 */
public final class CoreChannels {

    private static volatile boolean registered;

    private CoreChannels() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Replay.register(Channels.LAYERS);
        Replay.register(Channels.SKINS);
        Replay.register(Channels.MOVES);
        Replay.register(Channels.SPAWNS);
        Replay.register(Channels.ENTITY_LAYERS);
        Replay.register(Channels.ENTITY_MOVES);
        Replay.register(Channels.BLOCKS);
        Replay.register(Channels.EQUIPS);
        Replay.register(Channels.ACTS);
        Replay.register(Channels.REMOVES);
        Replay.register(Channels.DIGS);
        Replay.register(Channels.FX);
        for (Supplier<Dispatcher> probe : Probes.core()) Replay.dispatchers(probe);
    }
}
