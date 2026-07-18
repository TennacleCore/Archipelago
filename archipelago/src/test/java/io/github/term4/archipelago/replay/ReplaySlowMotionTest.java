package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        } finally {
            if (pb != null && pb.active()) pb.halt();
        }
    }

    /** OWN_SIM clients (a fireball re-accelerates ANY velocity toward ~2 b/t) must never be leg-flown:
     *  their twins glide on the relative wire with a zero-velocity-only broadcast history. */
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
            viewer.sent.clear();
            int id = twin.getEntityId();
            int moved = 0, sampled = 24;
            double lastX = twin.getPosition().x();
            for (int i = 0; i < sampled; i++) {
                tick();
                double x = twin.getPosition().x();
                if (x > lastX + 1.0e-4) moved++;
                lastX = x;
            }
            assertTrue(moved >= sampled / 2, "glide moved on " + moved + "/" + sampled + " ticks");
            boolean kicked = viewer.sent(net.minestom.server.network.packet.server.play.EntityVelocityPacket.class)
                    .stream().filter(pk -> pk.entityId() == id)
                    .anyMatch(pk -> pk.velocity().length() > 1.0e-3);
            assertFalse(kicked, "no velocity redirects for an own-sim client");
        } finally {
            if (pb != null && pb.active()) pb.halt();
            if (viewer != null) viewer.player.remove();
        }
    }
}
