package io.github.term4.archipelago;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntFunction;

/**
 * Which wire a viewer draws. Archipelago never guesses an era: the app hands it a protocol source, because
 * only the app knows whether a proxy translates and when the handshake lands.
 */
public final class Viewers {

    /** The last protocol on the legacy wire (1.8.9); anything older rides it too. */
    public static final int PROTOCOL_1_8 = 47;

    private static volatile @Nullable ToIntFunction<Player> source;
    // modern: a wrong legacy guess draws raw section codes and wasted anchor bytes on a modern client, a
    // wrong modern one only mis-sorts until the next resync
    private static volatile int whenUnknown = MinecraftServer.PROTOCOL_VERSION;

    private Viewers() {}

    /** Where a viewer's protocol number comes from; negative = not known yet (the proxy handshake lands after
     *  login). Unset: every viewer reads as {@link #protocolWhenUnknown}. */
    public static void protocol(@NotNull ToIntFunction<Player> protocol) {
        source = protocol;
    }

    /** What a viewer reads as while their own protocol is unknown; the server's own build (modern) by default. */
    public static void protocolWhenUnknown(int protocol) {
        whenUnknown = protocol;
    }

    public static int protocolOf(@NotNull Player viewer) {
        ToIntFunction<Player> resolved = source;
        int protocol = resolved == null ? -1 : resolved.applyAsInt(viewer);
        return protocol < 0 ? whenUnknown : protocol;
    }

    public static boolean legacy(@NotNull Player viewer) {
        return protocolOf(viewer) <= PROTOCOL_1_8;
    }
}
