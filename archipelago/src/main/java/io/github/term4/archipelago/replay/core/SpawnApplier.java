package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Visuals;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.metadata.other.PrimedTntMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
final class SpawnApplier implements Applier<Channels.Spawn> {
    // clients DISCARD a projectile whose wire owner they don't know yet: a shooter-owned spawn waits for
    // its ghost to be world-ready (ghost spawns are async), then gives up and spawns ownerless
    private static final long OWNER_WAIT_TICKS = 20;

    private record Pending(Channels.Spawn spawn, long since, long notBefore) {}

    private final List<Pending> pending = new ArrayList<>();
    private final LookBack<Integer, Channels.EntityMove> track =
            new LookBack<>(Channels.ENTITY_MOVES, Channels.EntityMove::seq);

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Spawn> op) {
        // the fractional glide runs ONE timeline tick behind (legs aim at the op just crossed), so a
        // twin born at op time would HOLD a full leg before its first move - materialize it with the
        // first leg instead (the tick hook runs after that leg's op landed its pin). LIVE crossings
        // only: a seek-fold applies ops under a stale cursor and must materialize in place
        boolean live = op.tick() == pb.tick();
        long notBefore = live && pb.rate() < 1 && !pb.paused() && !pb.reversed() ? op.tick() + 1 : 0;
        if (notBefore > pb.tick() || !spawnNow(pb, op.op(), false)) {
            pending.add(new Pending(op.op(), pb.tick(), notBefore));
        } else {
            noteFuse(pb, op.op());
        }
    }

    private final List<Recorded<Channels.Spawn>> heldDespawns = new ArrayList<>();

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Spawn> op) {
        pending.removeIf(p -> p.spawn().seq() == op.op().seq()); // never materialize an un-crossed spawn
        // the reverse scene lags the cursor one timeline tick like everything else: an at-crossing
        // despawn vanishes the twin a leg short of its launch
        if (op.tick() == pb.tick() && pb.rate() < 1 && !pb.paused() && pb.reversed()) {
            heldDespawns.add(op);
            return;
        }
        despawnNow(pb, op.op().seq());
    }

    private static void despawnNow(Playback pb, int seq) {
        Entity gone = pb.untrack(seq);
        if (gone != null) Playback.dismiss(gone); // it didn't exist before this
    }

    @Override
    public void tick(@NotNull Playback pb) {
        heldDespawns.removeIf(op -> {
            long behind = op.tick() - pb.tick(); // reverse: the cursor sinks below the op
            if (behind < 0 || behind > 2) return true; // flipped forward / jumped: it lives (the fold owns it)
            // the hold exists so the last backward leg lands - once the twin reaches its launch point
            // there is nothing left to play: waiting the full presentation tick reads as a pause at
            // the thrower
            Entity e = pb.entity(op.op().seq());
            boolean arrived = e != null && e.getInstance() != null && new Pos(op.op().x(), op.op().y(),
                    op.op().z()).distanceSquared(e.getPosition()) <= 0.09;
            if (behind >= 1 || arrived) {
                despawnNow(pb, op.op().seq());
                return true;
            }
            return false;
        });
        var it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (pb.tick() < p.notBefore() && !pb.reversed()) continue;
            // a LATE materialization (an owner-wait) rises ON the track, not at its launch - risen at
            // the launch it would visibly re-fly from the thrower to catch its pin up
            Pos at = pb.tick() - p.since() > 1 ? Tracks.trackPointAt(pb, track, p.spawn().seq(), pb.tick()) : null;
            if (spawnNow(pb, p.spawn(), pb.tick() - p.since() >= OWNER_WAIT_TICKS, at)) {
                noteFuse(pb, p.spawn());
                it.remove();
            }
        }
        paceFuses(pb);
    }

    // the client counts a fuse in REAL ticks and DISCARDS the entity at zero (reads as an explosion no
    // matter the rate, even paused) - keep re-stamping it with the timeline's remaining wall time
    private record Fused(int seq, long removeTick) {}

    private final List<Fused> fused = new ArrayList<>();
    private final Map<Integer, Integer> fuseAge = new HashMap<>();
    private final LookBack<Integer, Channels.Remove> removes =
            new LookBack<>(Channels.REMOVES, Channels.Remove::seq);

    private void noteFuse(Playback pb, Channels.Spawn spawn) {
        Entity e = pb.entity(spawn.seq());
        if (e == null || !(e.getEntityMeta() instanceof PrimedTntMeta)) return;
        List<Recorded<Channels.Remove>> gone = removes.all(pb, spawn.seq());
        if (!gone.isEmpty()) fused.add(new Fused(spawn.seq(), gone.get(0).tick()));
    }

    private void paceFuses(Playback pb) {
        var it = fused.iterator();
        while (it.hasNext()) {
            Fused f = it.next();
            Entity e = pb.entity(f.seq());
            if (e == null || e.isRemoved()) {
                it.remove();
                fuseAge.remove(f.seq());
                continue;
            }
            if (!(e.getEntityMeta() instanceof PrimedTntMeta tnt)) continue;
            long remaining = Math.max(0, f.removeTick() - pb.tick());
            int want = pb.paused() || pb.rate() <= 0 ? 24000
                    : (int) Math.min(24000, Math.max(1, Math.ceil(remaining / pb.rate())));
            if (Math.abs(tnt.getFuseTime() - want) > 4) tnt.setFuseTime(want);
            // fuse meta only reaches modern clients: a 1.8-generation TNT counts its OWN 80 real
            // ticks from the spawn and setDead()s itself at zero. Re-spawning the viewers before
            // that deadline re-arms the client fuse - same id, same tick, position unchanged
            if (fuseAge.merge(f.seq(), 1, Integer::sum) % 60 == 0) {
                for (var viewer : List.copyOf(e.getViewers())) {
                    e.removeViewer(viewer);
                    e.addViewer(viewer);
                }
            }
        }
    }

    @Override
    public boolean settled() {
        return pending.isEmpty() && heldDespawns.isEmpty();
    }

    // shared with RemoveApplier's reverse crossing (an un-removed twin re-runs its spawn)
    static boolean spawnNow(Playback pb, Channels.Spawn spawn, boolean ownerless) {
        return spawnNow(pb, spawn, ownerless, null);
    }

    /** {@code at} overrides the spawn-op position (a reverse revival rises at its rest, not its launch). */
    static boolean spawnNow(Playback pb, Channels.Spawn spawn, boolean ownerless, @Nullable Pos at) {
        if (pb.entity(spawn.seq()) != null) return true; // already on stage (a fold or flip re-crossed it)
        if (pb.earlyRemoved(spawn.seq())) return true; // its remove already played; never materialize
        Shard in = pb.layer(spawn.layer());
        if (in == null) return true; // its layer never rose (seek-folded away): consume
        Ghost owner = spawn.shooter().isEmpty() ? null : pb.ghost(spawn.shooter());
        if (!spawn.shooter().isEmpty() && (owner == null || owner.getInstance() == null) && !ownerless) return false;
        // puppets ONLY: the track is the truth at every rate. A revived body's own physics is never
        // used (parked everywhere) and only ever fought the driver - descriptors stay save/load-side,
        // and playback needs no game libs at all
        Entity entity = Visuals.entity(spawn.visual(), owner);
        if (entity == null) return true;
        pb.track(spawn.seq(), entity);
        in.spawn(entity, at != null ? at : new Pos(spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()));
        return true;
    }
}
