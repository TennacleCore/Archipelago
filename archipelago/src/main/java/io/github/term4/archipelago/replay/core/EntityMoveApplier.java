package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Visuals;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
final class EntityMoveApplier implements Applier<Channels.EntityMove> {

    private final Map<Integer, Pin> lastTrack = new HashMap<>();
    /** Active constant-speed legs, per seq. */
    private final Map<Integer, Glide> glides = new HashMap<>();
    private final LookBack<Integer, Channels.EntityMove> history =
            new LookBack<>(Channels.ENTITY_MOVES, Channels.EntityMove::seq);
    private final LookBack<Integer, Channels.Spawn> spawns =
            new LookBack<>(Channels.SPAWNS, Channels.Spawn::seq);
    private double ranAt = 1;
    private boolean ranReversed;

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityMove> op) {
        drive(pb, op.op().seq(), new Pos(op.op().x(), op.op().y(), op.op().z(), op.op().yaw(), op.op().pitch()));
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityMove> op) {
        Recorded<Channels.EntityMove> prev = history.before(pb, op);
        if (prev != null) {
            drive(pb, prev.op().seq(), new Pos(prev.op().x(), prev.op().y(), prev.op().z(),
                    prev.op().yaw(), prev.op().pitch()));
            return;
        }
        Recorded<Channels.Spawn> born = spawns.latest(pb, op.op().seq(), op.tick()); // before its first track point
        if (born != null) {
            drive(pb, op.op().seq(), new Pos(born.op().x(), born.op().y(), born.op().z(),
                    born.op().yaw(), born.op().pitch()));
        }
    }

    @Override
    public boolean settled() { // a leg leaves the map at its ARRIVAL pass: the tail must play out
        return glides.isEmpty();
    }

    // every twin is parked and track-driven at EVERY rate (and in reverse - a sim can't fall upwards):
    // leg-flyable twins by velocity their client integrates, the rest by the per-tick glide. Letting a
    // revived twin free-run its own physics at full rate was tried and retired: the resync band, the
    // materialization catch-up and the rest pin it needed were the rate-1 rubber-band, pause leaked
    // through the live sim, and physics discontinuities (mmc18's vy<-0.6 bounce gate) made resim-vs-
    // track divergence structural
    private void drive(Playback pb, int seq, Pos target) {
        Entity e = pb.entity(seq);
        // pin advances even mid-async-spawn: a stale anchor yanks the twin back to its spawn
        Pin previous = lastTrack.put(seq, new Pin(target, pb.tick()));
        // same-tick duplicate (sampler + Recording.remove both stamp the impact): a zero-step
        // self-leg would un-pace the advance
        if (previous != null && previous.tick() == pb.tick() && previous.pos().equals(target)) return;
        if (e == null || e.getInstance() == null) return; // the next track op (or the pin) places it
        if (pb.paused()) { // a fold placed it - hold there, absolutely
            Pos at = target;
            if (e.getEntityMeta() instanceof net.minestom.server.entity.metadata.projectile.AbstractArrowMeta) {
                // no live arrival ran: restore view + freeze from the track (recorded server views
                // render arrows flipped; unfrozen zero-motion aims sideways)
                at = terminalView(pb, seq, target);
                Recorded<Channels.EntityMove> ahead = history.firstAfter(pb, seq, pb.tick());
                Visuals.freezeArrow(e, ahead == null || ahead.tick() - pb.tick() > 2);
            }
            e.teleport(at);
            return;
        }
        // no fly here: the applier tick pass runs right after this drain and aims it - a second
        // same-tick send doubled the velocity wire
        glides.put(seq, Tracks.leg(previous, e.getPosition(), target, pb));
    }

    /** A fold lands a stuck arrow without its live arrival: once the track is silent ahead,
     *  restore the freeze + terminal view (the late-viewer treatment). */
    private void restFreeze(Playback pb, int seq, Entity e, Pin pin) {
        if (!(e.getEntityMeta() instanceof net.minestom.server.entity.metadata.projectile.AbstractArrowMeta meta)
                || meta.isInGround()) return;
        if (pin.pos().distanceSquared(e.getPosition()) > 0.01) return;
        Recorded<Channels.EntityMove> ahead = history.firstAfter(pb, seq, pin.tick());
        if (ahead != null && ahead.tick() - pin.tick() <= 2) return; // mid-track, not a rest
        Visuals.freezeArrow(e, true);
        e.teleport(terminalView(pb, seq, e.getPosition()));
    }

    /** The stick orientation from the track: the last distinct movement at or before the cursor,
     *  in arrow view convention; {@code at}'s own view when it never moved. */
    private Pos terminalView(Playback pb, int seq, Pos at) {
        List<Recorded<Channels.EntityMove>> ops = history.all(pb, seq);
        int lo = LookBack.after(ops, pb.tick());
        for (int i = lo - 1; i > 0; i--) {
            Channels.EntityMove to = ops.get(i).op(), from = ops.get(i - 1).op();
            double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
            double h = Math.hypot(dx, dz);
            if (h + Math.abs(dy) < 1.0e-6) continue;
            return at.withView((float) Math.toDegrees(Math.atan2(dx, dz)),
                    (float) Math.toDegrees(Math.atan2(dy, h)));
        }
        return at;
    }

    // between track points a twin STAYS at its last one: a silent track means the real entity rested, but a
    // physics twin can drift (a zero-velocity revived arrow falls out of its wall) - pin it back
    @Override
    public void tick(@NotNull Playback pb) {
        if (pb.rate() != ranAt || pb.reversed() != ranReversed) {
            // a leg outlives a rate/direction flip by its REAL-tick deadline (up to dt/rate ticks -
            // seconds at 0.01x): the twin kept walking the old direction after /replay reverse
            ranAt = pb.rate();
            ranReversed = pb.reversed();
            glides.clear();
        }
        var it = lastTrack.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            int seq = entry.getKey();
            Entity e = pb.entity(seq);
            if (e == null) {
                // mid-defer (the spawn hasn't materialized) or untracked: the pin stays - deleting
                // it here lost every leg that crossed before a deferred spawn landed, and the twin
                // re-entered a whole window behind (the launch snap-then-rush). A glide for an
                // absent body is dropped so settled() can't hold playback open on it; a pin with
                // nothing refreshing it is a DEAD seq - drop past any defer window (reverse
                // revivals re-pin through drive)
                glides.remove(seq);
                if (Math.abs(pb.tick() - entry.getValue().tick()) > 8) it.remove();
                continue;
            }
            if (e.isRemoved()) {
                it.remove();
                glides.remove(seq);
                continue;
            }
            Pin pin = entry.getValue();
            if (pb.paused()) {
                Tracks.pinStill(e); // pause must reach the wire: client sims fall through a quiet one
                continue;
            }
            // the leg owns the motion
            Glide g = glides.get(seq);
            // no live leg: a LEG toward the pin, not a snap - drifted rests pull back gently,
            // stale targets never teleport into the past, seek folds snap
            if (g == null || Math.abs(pb.tick() - g.builtAt()) > g.span()) {
                glides.remove(seq);
                if (pin.pos().distanceSquared(e.getPosition()) > 4) {
                    e.teleport(pin.pos()); // a SNAP is absolute (a stale-anchor rel move mis-lands it)
                    continue;
                }
                // FRESH pin = a missed flight leg: store a deadline-paced leg (per-tick rebuilds
                // Zeno-decay). Mid-hole: bridge. OLD pin = rest pull-back over ~4 ticks
                boolean fresh = pb.tick() - pin.tick() <= 1 && !pb.reversed();
                // reversed, the pin is where the twin already IS - only the backward bridge moves it,
                // and waiting for the first un-crossing froze 0.01x reversals for seconds
                Glide b = fresh ? null : Tracks.bridge(pb, history, seq, e.getPosition());
                if (b != null) {
                    glides.put(seq, b);
                    g = b;
                } else {
                    long dt = fresh ? 1 : Math.max(1, Math.round(4 * pb.rate()));
                    g = Tracks.leg(e.getPosition(), pin.tick() - dt, pin.pos(), pin.tick(), pb.rate());
                    if (fresh) glides.put(seq, g);
                    else restFreeze(pb, seq, e, pin);
                }
            }
            // the IN_GROUND freeze is a REST state: a real leg (reverse included) re-flies the
            // arrow, but only once the track LEAVES the stick - an explosion's recorded nudge is
            // centimeters, and re-aiming rotation from it flopped stuck arrows sideways
            if (Visuals.clientFrozen(e) && g.target().distanceSquared(e.getPosition()) > 0.25) {
                Visuals.freezeArrow(e, false);
            }
            if (!(e instanceof net.minestom.server.entity.LivingEntity)
                    && !Visuals.clientRestless(e) && !Visuals.clientFrozen(e)) {
                // flier: a puppet's own tick is inert - advance the server along the leg SILENTLY
                // (the arrival check below reads the server position); what each viewer sees is
                // generation-split in legSets/fly. The aim rides the server view so every later
                // absolute (sets, pins, holds) stamps a consistent orientation
                if (Visuals.standIn(e) && e.getInstance() != null) {
                    Vec to = g.target().sub(e.getPosition()).asVec();
                    double d = to.length();
                    if (d > 1.0e-6) {
                        // paced to COMPLETE by the deadline: d/remaining equals perTick on-track
                        // and spreads bridge lag evenly instead of snapping it at the arrival
                        int remaining = Math.max(1, g.left()[0]);
                        double step = Math.min(d, Math.max(g.perTick(), d / remaining));
                        Pos view = Tracks.aimed(g);
                        e.refreshPosition(e.getPosition().add(to.mul(step / d))
                                .withView(view.yaw(), view.pitch()), false, false);
                    }
                }
                int left = g.left()[0]--;
                if (left <= 0) {
                    // a SHORT track hole (a recorded server hiccup - launches stall 1-2 ticks on the
                    // cross-clock pickup; slow motion magnifies the gap) bridges to the next known
                    // point instead of a stop-dead + hold
                    // bridges pace MAGNIFIED holes; at full rate a hole renders as recorded -
                    // the client coasts its last aim, the live silent-wire look
                    Glide next = pb.rate() < 1 || pb.reversed()
                            ? Tracks.bridge(pb, history, seq, e.getPosition()) : null;
                    if (next == null && pb.rate() >= 1 && !pb.reversed()
                            && history.firstAfter(pb, seq, pb.tick()) != null) {
                        glides.remove(seq); // spent; the next crossing re-aims
                        continue;
                    }
                    if (next == null && pb.reversed()) {
                        // the launch hole sits just before the spawn: the move channel has nothing
                        // earlier, so the last backward leg bridges to the spawn point itself
                        Recorded<Channels.Spawn> born = spawns.latest(pb, seq, pb.tick() - 1);
                        if (born != null && pb.tick() - born.tick() <= Tracks.BRIDGE_TICKS) {
                            next = Tracks.bridgeLeg(pb, e.getPosition(), new Pos(born.op().x(), born.op().y(),
                                    born.op().z(), born.op().yaw(), born.op().pitch()), born.tick());
                        }
                    }
                    if (next != null) {
                        glides.put(seq, next);
                        boolean fireball = Visuals.clientAccelerates(e);
                        if (fireball) Tracks.aimFireball(e, next);
                        else Tracks.fly(e, next);
                        Tracks.legSets(e, fireball);
                        continue;
                    }
                    // zero WITH the landing: a kept velocity coasts clients past the wall
                    Visuals.freezeArrow(e, true);
                    if (!e.getVelocity().isZero()) e.setVelocity(Vec.ZERO);
                    else if (Visuals.clientAccelerates(e)) { // fireball aims bypass the server field
                        e.sendPacketToViewers(new net.minestom.server.network.packet.server.play.EntityVelocityPacket(
                                e.getEntityId(), Vec.ZERO));
                    }
                    // absolute, ALWAYS sent: the client is a velocity-tick past even an
                    // on-target twin
                    e.teleport(g.step().lengthSquared() > 1.0e-4 ? Tracks.aimed(g)
                            : terminalView(pb, seq, g.target()));
                    glides.remove(seq);
                } else {
                    // the velocity RIDES the leg's whole window: zeroing early collapses rate-1
                    // bounces to 20Hz steps
                    boolean fireball = Visuals.clientAccelerates(e);
                    if (fireball) Tracks.aimFireball(e, g);
                    else Tracks.fly(e, g);
                    Tracks.legSets(e, fireball);
                }
            } else {
                if (!e.getVelocity().isZero()) e.setVelocity(Vec.ZERO); // glide-driven: the client sim stays inert
                Pos before = e.getPosition();
                Tracks.glide(e, g);
                // at-rest holds: a restless client sinks its own sim between periodic syncs (both
                // generations - the bobber sim ignores stillness), and a 1.8-generation arrow that
                // never latched (a slow-rate ray is microscopic, so its own collision test never
                // fires) sags under its own compounding gravity at the stick
                boolean restless = Visuals.clientRestless(e);
                if ((restless || Visuals.clientFrozen(e)) && e.getPosition().equals(before)) {
                    Tracks.pinAt(e, e.getPosition(), restless);
                }
            }
        }
    }
}
