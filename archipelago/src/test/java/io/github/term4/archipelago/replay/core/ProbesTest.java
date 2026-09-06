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
    /** The copies of one broadcast reach the probe as each connection flushes: a beat split across the tick end is one cue. */
    @Test
    void aCueSplitAcrossTicksStillLands() {
        loadArea(2110, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer a = FakePlayer.connect(instance, new Pos(33760.5, 42, 0.5), "SplitA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(33763.5, 42, 0.5), "SplitB");
        Recording rec = null;
        try {
            live.join(a.player);
            live.join(b.player);
            rec = Replay.record(live);
            Probes.FxProbe probe = new Probes.FxProbe();
            probe.packet(a.player, new SoundEffectPacket(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER,
                    a.player.getPosition(), 1f, 2f, 11L), rec);
            probe.sample(rec); // a's copy flushed before the tick end
            tick();
            probe.packet(b.player, new SoundEffectPacket(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER,
                    b.player.getPosition(), 1f, 2f, 99L), rec);
            probe.sample(rec); // b's after it
            tick();
            assertEquals(1, rec.counts().getOrDefault(Channels.FX.id(), 0), "one beat both heard, however the flushes fell");

            SoundEffectPacket place = new SoundEffectPacket(SoundEvent.BLOCK_STONE_PLACE, Sound.Source.BLOCK,
                    new Pos(33762.5, 42, 0.5), 1f, 1f, 7L);
            probe.packet(a.player, place, rec);
            probe.sample(rec);
            tick();
            probe.packet(b.player, place, rec);
            probe.sample(rec);
            tick();
            assertEquals(2, rec.counts().getOrDefault(Channels.FX.id(), 0), "and one broadcast split the same way is one sound");
        } finally {
            if (rec != null) rec.stop();
            a.player.remove();
            b.player.remove();
        }
    }
}
