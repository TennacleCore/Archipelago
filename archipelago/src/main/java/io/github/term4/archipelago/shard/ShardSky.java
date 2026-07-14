package io.github.term4.archipelago.shard;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.SetTimePacket;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A shard's sky overrides (weather + fixed time). They survive {@code reset()} - game settings, not diff;
 * base weather/time broadcasts can stomp them, so keep the base sky static. Effective resolution (child ->
 * parent -> base) lives on Shard.
 */
final class ShardSky {

    private volatile @Nullable Weather weather;
    private volatile long fixedTime = -1;

    @Nullable Weather weatherOverride() {
        return weather;
    }

    long timeOverride() {
        return fixedTime;
    }

    void setWeather(Weather weather) {
        this.weather = weather;
    }

    void setTime(long time) {
        this.fixedTime = time;
    }

    static void sendBase(Player player) {
        Instance in = player.getInstance();
        if (in == null) return;
        sendWeather(in.getWeather(), player::sendPacket);
        player.sendPacket(in.createTimePacket());
    }

    static @Nullable SetTimePacket fixedTimePacket(Instance base, long time) {
        var clock = base.getCachedDimensionType().defaultClock();
        if (clock == null) return null;
        return new SetTimePacket(base.getWorldAge(), Map.of(clock, new SetTimePacket.ClockState(time, 0f, 0f)));
    }

    static void sendWeather(Weather weather, Consumer<ServerPacket> out) {
        out.accept(weather.createIsRainingPacket());
        out.accept(weather.createRainLevelPacket());
        out.accept(weather.createThunderLevelPacket());
    }
}
