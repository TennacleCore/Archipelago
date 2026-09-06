package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import net.minestom.server.sound.SoundEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A sound at a player's own spot is the game's cue once two members got it, seeds apart; one ear is one player's. */
class ProbesTest extends HeadlessServerTest {

    @Test
    void cuesNeedTwoListeners() {
        loadArea(2100, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer a = FakePlayer.connect(instance, new Pos(33600.5, 42, 0.5), "CueA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(33603.5, 42, 0.5), "CueB");
        Recording rec = null;
        try {
            live.join(a.player);
            live.join(b.player);
            rec = Replay.record(live);
            Probes.FxProbe probe = new Probes.FxProbe();
            // each playSound rolls its own seed and lands at its own listener
            probe.packet(a.player, new SoundEffectPacket(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER,
                    a.player.getPosition(), 1f, 2f, 11L), rec);
            probe.packet(b.player, new SoundEffectPacket(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER,
                    b.player.getPosition(), 1f, 2f, 99L), rec);
            probe.sample(rec);
            tick();
            assertEquals(1, rec.counts().getOrDefault(Channels.FX.id(), 0), "heard by both: one cue");

            probe.packet(a.player, new SoundEffectPacket(SoundEvent.ENTITY_ARROW_HIT_PLAYER, Sound.Source.PLAYER,
                    a.player.getPosition(), 1f, 1f, 5L), rec);
            probe.sample(rec);
            tick();
            assertEquals(1, rec.counts().getOrDefault(Channels.FX.id(), 0), "one player's ding is not the game's");
        } finally {
            if (rec != null) rec.stop();
            a.player.remove();
            b.player.remove();
        }
    }
}
