package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.replay.core.Channels;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.entity.EntityType;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Fractional rates render as MOTION, not sparser teleports: the ghost glides every theater tick. */
class ReplaySlowMotionTest extends HeadlessServerTest {

    /** Ticks until the non-player twin is on stage - the revival spawn is async. */
    private static net.minestom.server.entity.Entity awaitTwin(Shard theater) {
        net.minestom.server.entity.Entity twin = null;
        for (int i = 0; i < 100 && twin == null; i++) {
            tick();
            twin = theater.entities().stream().filter(e -> !(e instanceof net.minestom.server.entity.Player))
                    .findFirst().orElse(null);
        }
        return twin;
    }

    @Test
    void quarterRateGlidesTheGhostEveryTick() {
        loadArea(251, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(4000.5, 42, 0.5), "SlowMoActor");
        Playback pb = null;
        try {
            live.join(actor.player);
            Recording rec = Replay.record(live);
            for (int i = 0; i < 12; i++) { // a straight walk, one block per recorded tick
                actor.player.teleport(new Pos(4000.5 + i, 42, 0.5));
                tick();
            }
            rec.stop();
            live.leave(actor.player);

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec);
            pb.rate(0.25);
            Ghost ghost = null;
            for (int i = 0; i < 100 && ghost == null; i++) { // first crossed move op raises the ghost (async spawn)
                tick();
                ghost = pb.ghost("SlowMoActor");
            }
            assertNotNull(ghost, "the ghost rose");
            for (int i = 0; i < 20 && ghost.getInstance() == null; i++) tick();

            int moved = 0, sampled = 24;
            double lastX = ghost.getPosition().x();
            for (int i = 0; i < sampled; i++) {
                tick();
                double x = ghost.getPosition().x();
                if (x > lastX + 1.0e-4) moved++;
                lastX = x;
            }
            // op-cadence-only movement would move on ~1 in 4 ticks; the glide moves on nearly all of them
            assertTrue(moved >= sampled / 2, "glide moved on " + moved + "/" + sampled + " ticks");
        } finally {
            if (pb != null && pb.active()) pb.halt();
            actor.player.remove();
        }
    }

    /**
     * A TICK-DILATED actor ({@code simtps self 5}) only moves every 4th tick, and moves record on CHANGE, so
     * its track is sparse. At full rate that used to render as 5Hz jumps - the glide paces the gap instead.
     */
    @Test
    void dilatedActorGlidesAcrossItsSparseTrack() {
        loadArea(800, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(12800.5, 42, 0.5), "DilatedActor");
        Playback pb = null;
        try {
            live.join(actor.player);
            Recording rec = Replay.record(live);
            for (int i = 0; i < 6; i++) { // a 5 TPS client: one 1-block step, then three still ticks
                actor.player.teleport(new Pos(12800.5 + i, 42, 0.5));
                tick();
                for (int held = 0; held < 3; held++) tick();
            }
            rec.stop();
            live.leave(actor.player);

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec); // FULL rate: the choppy case
            Ghost ghost = null;
            for (int i = 0; i < 100 && ghost == null; i++) {
                tick();
                ghost = pb.ghost("DilatedActor");
            }
            assertNotNull(ghost, "the ghost rose");
            for (int i = 0; i < 20 && ghost.getInstance() == null; i++) tick();

            int moved = 0, sampled = 12;
            double lastX = ghost.getPosition().x();
            double maxStep = 0;
            for (int i = 0; i < sampled; i++) {
                tick();
                double x = ghost.getPosition().x();
                if (x > lastX + 1.0e-4) moved++;
                maxStep = Math.max(maxStep, Math.abs(x - lastX));
                lastX = x;
            }
            // op-cadence jumps would move on 1 in 4 ticks, a full block at a time
            assertTrue(moved >= sampled / 2, "glide moved on " + moved + "/" + sampled + " ticks");
            assertTrue(maxStep < 0.9, "no whole-gap hops (max step " + maxStep + ")");
        } finally {
            if (pb != null && pb.active()) pb.halt();
            actor.player.remove();
        }
    }

    /** A seek lands the ghost absolutely; the leg it was on must not pull it back afterward. */
    @Test
    void aSeekDropsTheLegInFlight() {
        loadArea(2200, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(35200.5, 42, 0.5), "SeekActor");
        Playback pb = null;
        try {
            live.join(actor.player);
            Recording rec = Replay.record(live);
            for (int i = 0; i < 30; i++) {
                actor.player.teleport(new Pos(35200.5 + i, 42, 0.5));
                tick();
            }
            rec.stop();
            live.leave(actor.player);

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec);
            pb.rate(8);
            Ghost ghost = null;
            for (int i = 0; i < 100 && ghost == null; i++) {
                tick();
                ghost = pb.ghost("SeekActor");
            }
            assertNotNull(ghost, "the ghost rose");
            for (int i = 0; i < 100 && pb.tick() < 8; i++) tick(); // a leg toward the walk's early blocks
            pb.seek(28);
            tick();
            tick();
            assertTrue(ghost.getPosition().x() >= 35200.5 + 26, "the ghost stays where the seek put it: " + ghost.getPosition().x());
        } finally {
            if (pb != null && pb.active()) pb.halt();
            actor.player.remove();
        }
    }

    /** A launch a fractional rate holds inside its shooter is re-sent: a client kills its own projectile there. */
    @Test
    void aHeldLaunchIsResentInsideItsShooter() {
        loadArea(2300, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(36800.5, 42, 0.5), "ShotActor");
        FakePlayer watcher = null;
        Playback pb = null;
        try {
            live.join(actor.player);
            Recording rec = Replay.record(live);
            CompoundBinaryTag visual = Visuals.snapshot(new net.minestom.server.entity.Entity(EntityType.ARROW));
            for (int i = 0; i < 14; i++) {
                actor.player.teleport(new Pos(36800.5, 42, 0.5 + i * 0.01)); // a still shooter still samples
                tick();
                if (i == 3) { // the sampler's first sighting: the spawn and a move, same tick, same spot
                    rec.emit(Channels.SPAWNS, new Channels.Spawn(0, 36800.5, 43.0, 0.5, 0, 0, 7, visual, "ShotActor"));
                    rec.emit(Channels.ENTITY_MOVES, new Channels.EntityMove(7, 36800.5, 43.0, 0.5, 0, 0));
                } else if (i > 3) { // a short draw: it crawls out of the body over several ticks
                    rec.emit(Channels.ENTITY_MOVES, new Channels.EntityMove(7, 36800.5 + (i - 3) * 0.15, 43.0, 0.5, 0, 0));
                }
            }
            rec.stop();
            live.leave(actor.player);

            Shard theater = new Shard(instance);
            watcher = FakePlayer.connect(instance, new Pos(36810.5, 42, 0.5), "ShotWatcher");
            theater.join(watcher.player);
            pb = Replay.play(theater, rec);
            pb.rate(0.25);
            net.minestom.server.entity.Entity twin = null;
            for (int i = 0; i < 200 && twin == null; i++) {
                tick();
                twin = pb.entity(7);
            }
            assertNotNull(twin, "the launch rises at the bow, as it does at any rate");
            for (int i = 0; i < 30 && twin.getInstance() == null; i++) tick();
            int id = twin.getEntityId();
            watcher.sent.clear();
            for (int i = 0; i < 16; i++) tick(); // still inside the shooter at a quarter rate
            long spawns = watcher.sent(SpawnEntityPacket.class).stream().filter(p -> p.entityId() == id).count();
            assertTrue(spawns >= 2, "re-sent while it sits in the shooter, so no client eats it: " + spawns);
        } finally {
            if (pb != null && pb.active()) pb.halt();
            actor.player.remove();
            if (watcher != null) watcher.player.remove();
        }
    }

    /** At eight times, the op that ends a walk lands even when it falls inside a theater tick's window. */
    @Test
    void aStopLandsAtEightTimes() {
        loadArea(2000, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(32000.5, 42, 0.5), "StopActor");
        Playback pb = null;
        try {
            live.join(actor.player);
            Recording rec = Replay.record(live);
            for (int i = 0; i < 21; i++) {
                actor.player.teleport(new Pos(32000.5 + i, 42, 0.5));
                tick();
            }
            for (int i = 0; i < 40; i++) tick(); // then stands
            rec.stop();
            live.leave(actor.player);

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec);
            pb.rate(8);
            Ghost ghost = null;
            for (int i = 0; i < 100 && ghost == null; i++) {
                tick();
                ghost = pb.ghost("StopActor");
            }
            assertNotNull(ghost, "the ghost rose");
            for (int i = 0; i < 100 && pb.tick() < 45; i++) tick();
            tick();
            assertEquals(32020.5, ghost.getPosition().x(), 1.0e-6, "the ghost stands where the actor stopped");
        } finally {
            if (pb != null && pb.active()) pb.halt();
            actor.player.remove();
        }
    }

    /** Told to hold, a playback pauses on its last frame and stays for a seek; halt alone ends it. */
    @Test
    void aHeldPlaybackPausesAtItsEnd() {
        loadArea(1600, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(25600.5, 42, 0.5), "HeldActor");
        Playback pb = null;
        try {
            live.join(actor.player);
            Recording rec = Replay.record(live);
            for (int i = 0; i < 10; i++) tick();
            rec.stop();
            live.leave(actor.player);

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec);
            pb.holdAtEnd(true);
            for (int i = 0; i < 60 && !pb.paused(); i++) tick();
            assertTrue(pb.paused(), "held on the last frame");
            assertTrue(pb.active(), "and still there");
            assertTrue(pb.tick() >= pb.length());
            pb.seek(2);
            pb.pause(false);
            for (int i = 0; i < 60 && !pb.paused(); i++) tick();
            assertTrue(pb.paused() && pb.active(), "a seek plays on to the same hold");
            pb.halt();
            assertFalse(pb.active(), "halt ends it");
        } finally {
            if (pb != null && pb.active()) pb.halt();
            actor.player.remove();
        }
    }

    /** A ghost's swings keep the rate's time: fatigue at half speed, haste at double, nothing at one. */
    @Test
    void ghostSwingsPaceToTheRate() {
        loadArea(1400, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(22400.5, 42, 0.5), "SwingActor");
        Playback pb = null;
        try {
            live.join(actor.player);
            Recording rec = Replay.record(live);
            for (int i = 0; i < 6; i++) {
                actor.player.teleport(new Pos(22400.5 + i, 42, 0.5));
                tick();
            }
            rec.stop();
            live.leave(actor.player);

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec);
            Ghost ghost = null;
            for (int i = 0; i < 100 && ghost == null; i++) {
                tick();
                ghost = pb.ghost("SwingActor");
            }
            assertNotNull(ghost, "the ghost rose");
            assertFalse(ghost.hasEffect(net.minestom.server.potion.PotionEffect.MINING_FATIGUE), "full rate: bare");
            pb.rate(0.5);
            assertEquals(2, amplifier(ghost, net.minestom.server.potion.PotionEffect.MINING_FATIGUE), "half speed: a 12-tick swing");
            pb.rate(2);
            assertFalse(ghost.hasEffect(net.minestom.server.potion.PotionEffect.MINING_FATIGUE));
            assertEquals(2, amplifier(ghost, net.minestom.server.potion.PotionEffect.HASTE), "double speed: a 3-tick swing");
            pb.rate(16);
            assertEquals(3, amplifier(ghost, net.minestom.server.potion.PotionEffect.HASTE), "never past a swing that draws");
            pb.rate(8);
            assertEquals(25, ghost.getSynchronizationTicks(), "eight times the moves a second: the snap comes eight times as often");
            pb.rate(1);
            assertFalse(ghost.hasEffect(net.minestom.server.potion.PotionEffect.HASTE), "back to bare");
            assertEquals(Ghost.SYNC_TICKS, ghost.getSynchronizationTicks());
        } finally {
            if (pb != null && pb.active()) pb.halt();
            actor.player.remove();
        }
    }

    private static int amplifier(Ghost ghost, net.minestom.server.potion.PotionEffect effect) {
        return ghost.getActiveEffects().stream().filter(t -> t.potion().effect() == effect)
                .mapToInt(t -> t.potion().amplifier()).findFirst().orElse(-1);
    }

    /** Moves record on change: a jump after a long stand-still is one tick's move, not a crawl over the wait. */
    @Test
    void aJumpAfterStandingStillLandsAtOnce() {
        loadArea(1200, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(19200.5, 42, 0.5), "StillActor");
        Playback pb = null;
        try {
            live.join(actor.player);
            Recording rec = Replay.record(live);
            for (int i = 0; i < 40; i++) tick(); // caged, then the reset
            actor.player.teleport(new Pos(19220.5, 42, 0.5));
            for (int i = 0; i < 10; i++) tick();
            rec.stop();
            live.leave(actor.player);

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec);
            Ghost ghost = null;
            for (int i = 0; i < 100 && ghost == null; i++) {
                tick();
                ghost = pb.ghost("StillActor");
            }
            assertNotNull(ghost, "the ghost rose");
            for (int i = 0; i < 20 && ghost.getInstance() == null; i++) tick();

            int left = -1, arrived = -1;
            for (int i = 0; i < 120 && arrived < 0; i++) {
                tick();
                double x = ghost.getPosition().x();
                if (left < 0 && x > 19201.0) left = i;
                if (x > 19219.5) arrived = i;
            }
            assertTrue(arrived >= 0, "the ghost reached the reset spot");
            assertTrue(arrived - left <= 3, "the jump took " + (arrived - left) + " ticks");
        } finally {
            if (pb != null && pb.active()) pb.halt();
            actor.player.remove();
        }
    }

    /** A revived PHYSICS twin parks at fractional rates: glide steps, never full-speed hops between corrections. */
    @Test
    void quarterRateParksThePhysicsTwin() {
        loadArea(400, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        Playback pb = null;
        try {
            var mover = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.SNOWBALL);
            mover.setNoGravity(true);
            live.spawn(mover, new Pos(6400.5, 70, 0.5)).join();
            Recording rec = Replay.record(live);
            for (int i = 1; i <= 12; i++) { // 0.2 b/t east, driven manually (headless ticks don't run physics)
                mover.teleport(new Pos(6400.5 + 0.2 * i, 70, 0.5)).join();
                tick();
            }
            rec.stop();
            mover.remove();

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec); // REVIVED: the generic tier raises a physics twin, not a stand-in
            pb.rate(0.25);
            var twin = awaitTwin(theater);
            assertNotNull(twin, "the twin rose");
            // parked AT TRACK TIME (before the spawn broadcast): a revived twin's recorded launch velocity
            // must never free-run its own sim - or the client's - at full speed between corrections
            assertTrue(twin.hasNoGravity(), "the twin spawns parked");
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();

            int moved = 0, sampled = 24;
            double maxStep = 0;
            double lastX = twin.getPosition().x();
            long time = System.nanoTime() / 1_000_000; // Entity#tick takes monotonic millis (TickThread toMillis)
            for (int i = 0; i < sampled; i++) {
                twin.tick(time + i * 50L); // leg-flight: the twin's own sim integrates (in-game the domain ticks it)
                tick();
                double x = twin.getPosition().x();
                if (x > lastX + 1.0e-4) moved++;
                maxStep = Math.max(maxStep, Math.abs(x - lastX));
                lastX = x;
            }
            assertTrue(moved >= sampled / 2, "glide moved on " + moved + "/" + sampled + " ticks");
            // the recorded full-speed step was 0.2 b/t; a parked twin's glide never replays it at full pace
            assertTrue(maxStep < 0.15, "no full-speed hops (max step " + maxStep + ")");
        } finally {
            if (pb != null && pb.active()) pb.halt();
        }
    }

    /** A reverse revival rises at its REST point (where it hit), not its launch: risen at the launch it
     *  would ride at the thrower until the reverse cursor reached the moves - the backwards-throw look. */
    @Test
    void reverseRevivalRisesAtTheRestPoint() {
        loadArea(500, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        Playback pb = null;
        try {
            var mover = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.SNOWBALL);
            mover.setNoGravity(true);
            live.spawn(mover, new Pos(8000.5, 70, 0.5)).join();
            Recording rec = Replay.record(live);
            for (int i = 1; i <= 6; i++) {
                mover.teleport(new Pos(8000.5 + 0.4 * i, 70, 0.5)).join();
                tick();
            }
            mover.remove(); // the recorded impact
            tick();
            tick();
            rec.stop();

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec);
            pb.pause(true);
            pb.seek(9); // past the removal: no twin on stage
            pb.reverse(true);
            pb.pause(false);
            var twin = awaitTwin(theater);
            assertNotNull(twin, "the reverse crossing revived the twin");
            pb.pause(true); // freeze the cursor: the assert is about WHERE it rose
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();
            assertTrue(twin.getPosition().x() > 8001.5,
                    "rose at the rest point (x=" + twin.getPosition().x() + ")");
            assertTrue(twin.hasNoGravity(), "revived parked");
            assertTrue(twin.getVelocity().isZero(), "revived at rest");

            pb.pause(false); // and the reverse flight: back along the arc, then gone at its spawn crossing
            double risen = twin.getPosition().x();
            double lowest = risen;
            long time = System.nanoTime() / 1_000_000; // Entity#tick takes monotonic millis (TickThread toMillis)
            for (int i = 0; i < 60; i++) {
                if (!twin.isRemoved()) twin.tick(time + i * 50L); // leg-flight: its sim integrates (in-game the domain ticks it)
                tick();
                lowest = Math.min(lowest, twin.getPosition().x());
            }
            assertTrue(lowest < risen - 0.5, "the twin flew backwards (reached x=" + lowest + ")");
            assertTrue(twin.isRemoved(), "crossing its spawn in reverse despawned it");
        } finally {
            if (pb != null && pb.active()) pb.halt();
        }
    }

    /** The client counts a TNT fuse in REAL ticks and discards at zero: the twin's fuse must keep tracking
     *  the timeline's remaining wall time - rate changes and pauses included. */
    @Test
    void tntFuseTracksTheTimeline() {
        loadArea(700, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        Playback pb = null;
        try {
            var mover = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.TNT);
            mover.setNoGravity(true);
            live.spawn(mover, new Pos(11200.5, 70, 0.5)).join();
            Recording rec = Replay.record(live);
            for (int i = 1; i <= 8; i++) {
                mover.teleport(new Pos(11200.5 + 0.2 * i, 70, 0.5)).join();
                tick();
            }
            mover.remove(); // the recorded detonation moment
            tick();
            rec.stop();

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, rec);
            pb.rate(0.25);
            var twin = awaitTwin(theater);
            assertNotNull(twin, "the twin rose");
            for (int i = 0; i < 4; i++) tick();
            var meta = (net.minestom.server.entity.metadata.other.PrimedTntMeta) twin.getEntityMeta();
            // ~8 timeline ticks remain at 0.25x = ~32 wall ticks; the default 80 would blow the client's
            // fuse mid-flight at rates above 2.5x and hold a dead prop below
            int paced = meta.getFuseTime();
            assertTrue(paced > 8 && paced < 60, "fuse tracks remaining wall time (got " + paced + ")");
            pb.pause(true);
            for (int i = 0; i < 10; i++) tick();
            assertTrue(meta.getFuseTime() > 1000, "a paused timeline parks the fuse (got " + meta.getFuseTime() + ")");
        } finally {
            if (pb != null && pb.active()) pb.halt();
        }
    }

    /** OWN_SIM clients (a fireball re-accelerates ANY velocity toward ~2 b/t) leg-fly like everyone:
     *  the per-tick re-aims bound the client's self-acceleration to one tick, where the 20Hz glide
     *  stepped on its non-interpolating client (the in-game fireball chop at every rate). */
    @Test
    void fireballTwinGlidesWithoutVelocityRedirects() {
        loadArea(600, 0);
        CoreChannels.register();
        Shard live = new Shard(instance);
        FakePlayer viewer = null;
        Playback pb = null;
        try {
            var mover = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.FIREBALL);
            mover.setNoGravity(true);
            live.spawn(mover, new Pos(9600.5, 70, 0.5)).join();
            Recording rec = Replay.record(live);
            for (int i = 1; i <= 12; i++) {
                mover.teleport(new Pos(9600.5 + 0.5 * i, 70, 0.5)).join();
                tick();
            }
            rec.stop();
            mover.remove();

            Shard theater = new Shard(instance);
            viewer = FakePlayer.connect(instance, new Pos(9603.5, 70, 0.5), "FireballViewer");
            theater.join(viewer.player);
            pb = Replay.play(theater, rec);
            pb.rate(0.25);
            var twin = awaitTwin(theater);
            assertNotNull(twin, "the twin rose");
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();
            int id = twin.getEntityId();
            // a MODERN fireball client self-accelerates ((v + 0.1 v-hat) x 0.95 per tick, 26.1 source):
            // in the slow mid-band the wire sends the INVERTED aim, so the client's own model lands on
            // the leg pace exactly - emulate that sim and gate the rendered steps
            Pos clientPos = twin.getPosition();
            net.minestom.server.coordinate.Vec clientVel = net.minestom.server.coordinate.Vec.ZERO;
            int moved = 0, sampled = 24;
            double maxStep = 0, minMovingStep = Double.MAX_VALUE;
            for (int i = 0; i < sampled; i++) {
                viewer.sent.clear();
                tick();
                Pos before = clientPos;
                for (SendablePacket sp : java.util.List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityPositionPacket p && p.entityId() == id) {
                        clientPos = clientPos.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                    } else if (pk instanceof EntityPositionAndRotationPacket p && p.entityId() == id) {
                        clientPos = clientPos.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        clientPos = p.position().asPos();
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        clientPos = p.position().asPos();
                    } else if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        clientVel = p.velocity();
                    }
                }
                if (clientVel.length() > 1.0e-9) {
                    clientVel = clientVel.add(clientVel.normalize().mul(0.1)).mul(0.95);
                    clientPos = clientPos.add(clientVel);
                }
                double step = clientPos.distance(before);
                if (step > 1.0e-4) moved++;
                if (i >= 4) {
                    maxStep = Math.max(maxStep, step);
                    if (step > 1.0e-3) minMovingStep = Math.min(minMovingStep, step);
                }
            }
            assertTrue(moved >= sampled / 2, "flight moved on " + moved + "/" + sampled + " ticks");
            // recorded 0.5 b/t at 0.25x: ~0.125 per tick, uniform - never a full-speed hop
            assertTrue(maxStep < 0.3, "client step too large: " + maxStep);
            assertTrue(minMovingStep != Double.MAX_VALUE && maxStep / minMovingStep < 2.0,
                    "client motion pulses: " + minMovingStep + ".." + maxStep);
        } finally {
            if (pb != null && pb.active()) pb.halt();
            if (viewer != null) viewer.player.remove();
        }
    }
}
