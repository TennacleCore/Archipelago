package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Weather;
import net.minestom.server.network.packet.server.play.SetTimePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A recorded {@code /shard sky} change (fixed time / weather) replays at its tick and reverses. */
class ReplaySkyTest extends HeadlessServerTest {

    @Test
    void skyOverridesReplayAndReverse() {
        CoreChannels.register();
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            for (int i = 0; i < 5; i++) tick();
            game.setTime(18000); // the mid-recording /shard sky night
            game.setWeather(new Weather(1f, 0.5f));
            for (int i = 0; i < 5; i++) tick();
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(0.5, 70, 0.5), "SkyViewer");
        Playback pb = Replay.play(theater, file);
        try {
            theater.join(viewer.player);
            assertEquals(-1, theater.timeOverride(), "t0 carried no override");
            viewer.sent.clear();
            for (int i = 0; i < 30 && theater.timeOverride() < 0; i++) tick();
            assertEquals(18000, theater.timeOverride(), "the recorded sky change landed");
            assertEquals(1f, theater.weatherOverride().rainLevel(), 1.0e-6);
            assertTrue(viewer.sent(SetTimePacket.class).stream().anyMatch(p ->
                            p.clocks().values().stream().anyMatch(c -> c.totalTicks() == 18000)),
                    "viewers received the fixed-time wire");

            pb.reverse(true);
            for (int i = 0; i < 40 && theater.timeOverride() >= 0; i++) tick();
            assertEquals(-1, theater.timeOverride(), "reverse restored the t0 state (no override)");
            assertFalse(pb.reversed() && theater.timeOverride() >= 0);
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
}
