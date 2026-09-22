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

    /**
     * From games: a replay watcher heard only the trailing beats of a cage countdown, or none at all. The copies
     * reach this probe at WRITE time, so anything skewing one player's flush past the tally's window drops the
     * beat silently - /lag is the obvious one, and the two replays that showed holes were recorded under it.
     */
    @Test
    void aWidelySplitBeatStillLands() {
        loadArea(2120, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer a = FakePlayer.connect(instance, new Pos(33920.5, 42, 0.5), "SkewA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(33923.5, 42, 0.5), "SkewB");
        Recording rec = null;
        try {
            live.join(a.player);
            live.join(b.player);
            rec = Replay.record(live);
            Probes.FxProbe probe = new Probes.FxProbe();
            probe.packet(a.player, pling(a, 11L), rec);
            for (int i = 0; i < 5; i++) { probe.sample(rec); tick(); } // b's flush is five ticks behind
            probe.packet(b.player, pling(b, 99L), rec);
            probe.sample(rec);
            tick();
            assertEquals(1, rec.counts().getOrDefault(Channels.FX.id(), 0), "the beat survives the skew");
        } finally {
            if (rec != null) rec.stop();
            a.player.remove();
            b.player.remove();
        }
    }

    /**
     * The other side of that window: consecutive beats are byte-identical bar the seed, so nothing but time
     * tells them apart. The window has to stay well under the beat cadence or two beats read as one.
     */
    @Test
    void beatsACadenceApartStayApart() {
        loadArea(2130, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer a = FakePlayer.connect(instance, new Pos(34080.5, 42, 0.5), "BeatA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(34083.5, 42, 0.5), "BeatB");
        Recording rec = null;
        try {
            live.join(a.player);
            live.join(b.player);
            rec = Replay.record(live);
            Probes.FxProbe probe = new Probes.FxProbe();
            probe.packet(a.player, pling(a, 11L), rec); // this one's second copy never arrives
            for (int i = 0; i < 20; i++) { probe.sample(rec); tick(); }
            probe.packet(a.player, pling(a, 12L), rec);
            probe.packet(b.player, pling(b, 98L), rec);
            probe.sample(rec);
            tick();
            assertEquals(1, rec.counts().getOrDefault(Channels.FX.id(), 0),
                    "the orphan aged out a cadence ago; only the beat both heard counts");
        } finally {
            if (rec != null) rec.stop();
            a.player.remove();
            b.player.remove();
        }
    }

    private static SoundEffectPacket pling(FakePlayer p, long seed) {
        return new SoundEffectPacket(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER,
                p.player.getPosition(), 1f, 1f, seed);
    }
}
