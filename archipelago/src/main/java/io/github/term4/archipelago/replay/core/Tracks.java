package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.Visuals;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.Nullable;


/** The track-motion engine shared by the move/spawn/remove appliers: legs, glides, aims, pins, bridges. */
final class Tracks {

    private Tracks() {}

    static Glide leg(@Nullable Pin previous, Pos fallback, Pos target, Playback pb) {
        long from = previous != null ? previous.tick() : pb.tick() - 1;
        // moves record on change: a gap past the dilation cadence is a stand-still, and the move after it is one
        // tick's (a cage reset paced over the whole wait crawled across the map)
        if (Math.abs(pb.tick() - from) > BRIDGE_TICKS) from = pb.tick() - 1; // tick distance is direction-agnostic
        return leg(previous != null ? previous.pos() : fallback, from, target, pb.tick(), pb.rate());
    }

    /** The leg from {@code from}@{@code fromTick} to {@code target}@{@code toTick} at {@code rate};
     *  tick distance is direction-agnostic (reverse legs run toTick < fromTick). */
    static Glide leg(Pos from, long fromTick, Pos target, long toTick, double rate) {
        long dt = Math.max(1, Math.abs(toTick - fromTick)); // first leg has no cadence yet: assume the per-tick track
        int deadline = (int) Math.max(1, Math.round(dt / Math.max(1.0e-9, rate)));
        return new Glide(target, target.sub(from).asVec().div(dt).mul(rate), toTick, dt, new int[]{deadline});
    }

    /** The arrival pos stamped with the leg's direction in ARROW view convention (yaw = atan2(x, z),
     *  pitch up-positive, both from motion): live silent wires never send arrows an absolute view, and a
     *  teleport carrying the recorded server-convention look renders a flipped stick. Non-rotating twins
     *  ignore it. */
    static Pos aimed(Glide g) {
        Vec d = g.step();
        double h = Math.hypot(d.x(), d.z());
        if (h + Math.abs(d.y()) < 1.0e-6) return g.target();
        return g.target().withView((float) Math.toDegrees(Math.atan2(d.x(), d.z())),
                (float) Math.toDegrees(Math.atan2(d.y(), h)));
    }

    /** Per-viewer fireball aim: a legacy client MOVES first and mutates motion after (the plain leg is
     *  exact under per-tick resets), a modern one moves by (v + 0.1 v-hat) x 0.95 - the inverted aim
     *  lands on the leg pace down to its 0.095 b/t floor, and below it a per-tick zero holds (a fresh
     *  zero also heals a viewer's own hit prediction, which the accel model would amplify). */
    static void aimFireball(Entity e, Glide g) {
        if (e.getInstance() == null) return;
        Vec step = g.step();
        double bt = step.length();
        var legacyAim = new net.minestom.server.network.packet.server.play.EntityVelocityPacket(
                e.getEntityId(), step);
        var modernAim = new net.minestom.server.network.packet.server.play.EntityVelocityPacket(
                e.getEntityId(), bt > 0.096 ? step.normalize().mul(bt / 0.95 - 0.1) : Vec.ZERO);
        for (var viewer : e.getViewers()) {
            viewer.sendPacket(Replay.legacyViewer(viewer) ? legacyAim : modernAim);
        }
    }

    /** The leg's per-tick anchors at the advanced (view-stamped) position, riding ALONGSIDE the
     *  velocity: they land before the client's own move, so the frame still lerps the motion. Legacy
     *  viewers always get them (bounds their drift; a latched 1.8 arrow ignores velocity and follows
     *  only these - the reverse-arrow carrier); {@code everyone} for fireballs, whose sims amplify any
     *  perturbation instead of decaying it. */
    static void legSets(Entity e, boolean everyone) {
        if (e.getInstance() == null) return;
        Pos at = e.getPosition();
        var set = new net.minestom.server.network.packet.server.play.EntityPositionSyncPacket(
                e.getEntityId(), at, Vec.ZERO, at.yaw(), at.pitch(), false);
        for (var viewer : e.getViewers()) {
            if (everyone || Replay.legacyViewer(viewer)) viewer.sendPacket(set);
        }
    }

    /** Pause pin: legacy + restless only. A modern sim hit-kicked mid-pause stays displaced until
     *  play resumes and the aims re-sync it - not worth per-tick spam to every viewer. */
    static void pinStill(Entity e) {
        pinAt(e, e.getPosition(), Visuals.clientRestless(e));
    }

    /** Per-tick absolute + velocity pin for a paused, resting, or removal-held twin. The velocity is a
     *  CRUMB along the aim for arrow twins - their clients recompute rotation from motion every
     *  unlatched tick, and atan2 of zero drags the model sideways (the stuck shake) - plain zero
     *  otherwise; each send lands before the client's move, so its own gravity never displaces it. */
    static void pinAt(Entity e, Pos at, boolean everyone) {
        if (e.getInstance() == null) return;
        if (!e.getVelocity().isZero()) e.setVelocity(Vec.ZERO);
        Vec keep = Vec.ZERO;
        if (e.getEntityMeta() instanceof net.minestom.server.entity.metadata.projectile.AbstractArrowMeta) {
            double yaw = Math.toRadians(at.yaw()), pitch = Math.toRadians(at.pitch());
            keep = new Vec(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch),
                    Math.cos(yaw) * Math.cos(pitch)).mul(0.002);
        }
        var set = new net.minestom.server.network.packet.server.play.EntityPositionSyncPacket(
                e.getEntityId(), at, Vec.ZERO, at.yaw(), at.pitch(), false);
        var still = new net.minestom.server.network.packet.server.play.EntityVelocityPacket(
                e.getEntityId(), keep);
        for (var viewer : e.getViewers()) {
            if (everyone || Replay.legacyViewer(viewer)) {
                viewer.sendPacket(set);
                viewer.sendPacket(still);
            }
        }
    }

    /** Per-tick re-aim along the leg - client gravity decays it between aims; the want floor keeps
     *  rests quiet. Only a REAL aim unfreezes (the rest-zero must leave the stuck advertisement alone). */
    static void fly(Entity e, Glide g) {
        if (e.getInstance() == null) return; // deferred: tick() re-tries once the spawn lands
        Vec want = g.step().mul(ServerFlag.SERVER_TICKS_PER_SECOND);
        boolean aims = want.length() > 0.02;
        if (!aims && e.getVelocity().sub(want).length() <= 0.02) return;
        if (aims) Visuals.freezeArrow(e, false);
        e.setVelocity(want);
    }

    /** One glide step toward the leg's target, paced to arrive AT its deadline. */
    static void glide(Entity e, Glide g) {
        glide(e, g, false);
    }

    /** {@code turnAtRest}: ghosts keep TURNING while standing (their look froze without it); entity twins
     *  stay quiet at rest - re-stamping a stuck arrow's recorded view fights its client orientation. */
    static void glide(Entity e, Glide g, boolean turnAtRest) {
        Pos cur = e.getPosition();
        Vec to = g.target().sub(cur).asVec();
        double d2 = to.lengthSquared();
        int left = Math.max(1, g.left()[0]);
        // view eases WITH the window, not ahead of it: stamping the leg's end rotation on every
        // intermediate step turned slow-motion heads once per crossing (the capture's 2.5 look updates/s)
        float yaw = lerpAngle(cur.yaw(), g.target().yaw(), 1.0 / left);
        float pitch = lerpAngle(cur.pitch(), g.target().pitch(), 1.0 / left);
        if (d2 < 1.0e-6) {
            if (turnAtRest && !cur.sameView(g.target())) {
                wireMove(e, cur.withView(yaw, pitch));
            }
            return;
        }
        g.left()[0] = left - 1;
        Pos step = left <= 1 || Math.sqrt(d2) <= g.perTick()
                ? g.target() : cur.add(to.mul(1.0 / left)).withView(yaw, pitch);
        wireMove(e, step);
    }

    static float lerpAngle(float from, float to, double t) {
        float d = to - from;
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return (float) (from + d * t);
    }

    /** A track-driven move on the wire, through the entity's OWN position bookkeeping ({@code lastSyncedPosition}
     *  included). Never move a live-ticking entity silently: its own movementTick then re-broadcasts the
     *  accumulated stale delta every tick - the double-send that made ghosts overshoot jumps, sink into the
     *  ground, and rubber-band back on each sync. A far target (a seek) snaps; a no-op until the async spawn
     *  lands - moving a pre-spawn entity gets snapped back by setInstance. */
    static void wireMove(Entity e, Pos step) {
        if (e.getInstance() == null) return;
        if (step.distanceSquared(e.getPosition()) > 16) {
            e.teleport(step);
            return;
        }
        e.refreshPosition(step, false, true);
    }
    /** Ticks of recorded silence a leg spans toward the next known point instead of stopping dead. */
    static final long BRIDGE_TICKS = 4;

    /** A leg from {@code from} to {@code seq}'s next move op in the travel direction, or null (none near). */
    static @Nullable Glide bridge(Playback pb, LookBack<Integer, Channels.EntityMove> track, int seq, Pos from) {
        Recorded<Channels.EntityMove> r = pb.reversed()
                ? track.latest(pb, seq, pb.tick() - 1)
                : track.firstAfter(pb, seq, pb.tick());
        if (r == null || Math.abs(r.tick() - pb.tick()) > BRIDGE_TICKS) return null;
        return bridgeLeg(pb, from, r);
    }

    /** Paced on REAL ticks ({@code subtick} counts the window already consumed) to arrive one window
     *  AFTER the op's crossing - the phase every normal leg keeps (built at its op's crossing, walked
     *  through that window). A bridge landing AT the crossing sat a whole window on its target while
     *  the crossing's own leg re-covered it (the launch rush-then-stall); whole-tick pacing also
     *  compressed a mid-window bridge into a 2x rush. */
    static Glide bridgeLeg(Playback pb, Pos from, Recorded<Channels.EntityMove> r) {
        return bridgeLeg(pb, from, new Pos(r.op().x(), r.op().y(), r.op().z(), r.op().yaw(), r.op().pitch()),
                r.tick());
    }

    static Glide bridgeLeg(Playback pb, Pos from, Pos target, long atTick) {
        long dt = Math.max(1, Math.abs(atTick - pb.tick()));
        int real = (int) Math.max(1, Math.round((dt - pb.subtick() + 1) / Math.max(1.0e-9, pb.rate())));
        return new Glide(target, target.sub(from).asVec().div(real), pb.tick(), dt + 1, new int[]{real});
    }

    /** The last track point at or before {@code tick} via the caller's per-seq index, or null (it
     *  never moved) - a flat scan of the shared ENTITY_MOVES list was O(total ops) for a seq that
     *  rested long before the query. */
    static @Nullable Pos trackPointAt(Playback pb, LookBack<Integer, Channels.EntityMove> track,
                                      int seq, long tick) {
        Recorded<Channels.EntityMove> r = track.latest(pb, seq, tick);
        if (r == null) return null;
        Channels.EntityMove m = r.op();
        return new Pos(m.x(), m.y(), m.z(), m.yaw(), m.pitch());
    }
}

/** A track point at a timeline tick (glide legs + rest pins). */
record Pin(Pos pos, long tick) {}


/** A track leg: {@code step} = the nominal per-theater-tick displacement; {@code left} counts theater
 *  ticks to the leg's deadline - the glide re-paces on the REMAINDER each tick, so integer-tick crossing
 *  banding at rates like 0.4 (gaps alternating 2 and 3 ticks against a 2.5-tick leg: the visible
 *  slow/fast/slow cycle) and late starts absorb instead of pulsing. {@code builtAt} = the crossing that
 *  built it: a leg outliving its timeline tick is STALE (a rate flip left it behind) - walking toward
 *  it teleports the twin into the past. */
record Glide(Pos target, Vec step, long builtAt, long span, int[] left) {
    double perTick() {
        return step.isZero() ? Double.MAX_VALUE : step.length();
    }
}
