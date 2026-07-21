package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fractional rates render as MOTION, not sparser teleports: the ghost glides every theater tick. */
class ReplaySlowMotionTest extends HeadlessServerTest {

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    @Test
    void quarterRateGlidesTheGhostEveryTick() {
        for (int cx = 250; cx <= 252; cx++) {
            for (int cz = -1; cz <= 1; cz++) instance.loadChunk(cx, cz).join();
        }
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

    /** A revived PHYSICS twin parks at fractional rates: glide steps, never full-speed hops between corrections. */
    @Test
    void quarterRateParksThePhysicsTwin() {
        for (int cx = 399; cx <= 401; cx++) {
            for (int cz = -1; cz <= 1; cz++) instance.loadChunk(cx, cz).join();
        }
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
            net.minestom.server.entity.Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = theater.entities().stream().filter(e -> !(e instanceof net.minestom.server.entity.Player))
                        .findFirst().orElse(null);
            }
            assertNotNull(twin, "the twin rose");
            // parked AT TRACK TIME (before the spawn broadcast): a revived twin's recorded launch velocity
            // must never free-run its own sim - or the client's - at full speed between corrections
            assertTrue(twin.hasNoGravity(), "the twin spawns parked");
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();

            int moved = 0, sampled = 24;
            double maxStep = 0;
            double lastX = twin.getPosition().x();
            long time = System.currentTimeMillis();
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
        for (int cx = 499; cx <= 501; cx++) {
            for (int cz = -1; cz <= 1; cz++) instance.loadChunk(cx, cz).join();
        }
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
            net.minestom.server.entity.Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = theater.entities().stream().filter(e -> !(e instanceof net.minestom.server.entity.Player))
                        .findFirst().orElse(null);
            }
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
            long time = System.currentTimeMillis();
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
        for (int cx = 699; cx <= 701; cx++) {
            for (int cz = -1; cz <= 1; cz++) instance.loadChunk(cx, cz).join();
        }
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
            net.minestom.server.entity.Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = theater.entities().stream().filter(e -> !(e instanceof net.minestom.server.entity.Player))
                        .findFirst().orElse(null);
            }
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
        for (int cx = 599; cx <= 601; cx++) {
            for (int cz = -1; cz <= 1; cz++) instance.loadChunk(cx, cz).join();
        }
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
            net.minestom.server.entity.Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = theater.entities().stream().filter(e -> !(e instanceof net.minestom.server.entity.Player))
                        .findFirst().orElse(null);
            }
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
                        clientPos = Pos.fromPoint(p.position());
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        clientPos = Pos.fromPoint(p.position());
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
