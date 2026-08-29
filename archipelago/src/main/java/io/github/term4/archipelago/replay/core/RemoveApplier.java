package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Visuals;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.network.packet.server.play.CollectItemPacket;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
final class RemoveApplier implements Applier<Channels.Remove> {
    private record Held(Recorded<Channels.Remove> op, int[] settle, long[] seen, double[] dist) {}

    private final List<Held> held = new ArrayList<>();
    private final LookBack<Integer, Channels.EntityMove> track =
            new LookBack<>(Channels.ENTITY_MOVES, Channels.EntityMove::seq);
    private final LookBack<Integer, Channels.Spawn> spawns =
            new LookBack<>(Channels.SPAWNS, Channels.Spawn::seq);
    /** Theater ticks a landed twin rests before the destroy: the CLIENT integrates the same legs a
     *  stream latency later - released on server arrival, its rendered ball is still legs short and
     *  vanishes mid-air. A brief rest ON the wall reads as the hit. */
    private static final int SETTLE_TICKS = 3;

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Remove> op) {
        // the scene renders one timeline tick behind at fractional rates: removing at op time despawns
        // a projectile a full leg short of its wall
        if (op.tick() == pb.tick() && pb.rate() < 1 && !pb.paused() && !pb.reversed()) {
            held.add(new Held(op, new int[]{SETTLE_TICKS}, new long[]{0}, new double[]{Double.MAX_VALUE}));
            return;
        }
        applyNow(pb, op);
    }

    @Override
    public void tick(@NotNull Playback pb) {
        held.removeIf(h -> {
            Recorded<Channels.Remove> op = h.op();
            long behind = pb.tick() - op.tick();
            // a reverse or a seek JUMPS the cursor: the fold owns the scene. Gradual playback only
            // ever advances behind one window at a time
            if (behind < 0 || behind - h.seen()[0] > 1) return true;
            h.seen()[0] = behind;
            Entity e = pb.entity(op.op().seq());
            Pos impact = Tracks.trackPointAt(pb, track, op.op().seq(), op.tick());
            boolean present = e != null && impact != null && e.getInstance() != null;
            double dist = present ? impact.distanceSquared(e.getPosition()) : 0;
            boolean landed = !present || dist <= 0.09;
            // a held twin rests without re-aims, and a legacy viewer's own gravity sinks it mid-air
            // through the hold (freeze, sag, then the flush snap) - per-tick pins hold it, with the
            // twin's aimed view (the recorded view wobbled arrow sticks)
            if (present && landed) {
                Tracks.pinAt(e, impact.withView(e.getPosition().yaw(), e.getPosition().pitch()),
                        Visuals.clientRestless(e));
            }
            if (behind < 1) return false;
            // track holes run the scene later than the cursor (each bridge lands a window late):
            // while the twin still CONVERGES on its impact keep holding - a fixed deadline flushed
            // it a leg short, and teleport + destroy in one instant reads as a vanish at the wall
            if (!landed && behind < 8 && dist < h.dist()[0] - 1.0e-6) {
                h.dist()[0] = dist;
                return false;
            }
            if (behind < 3 && (!landed || --h.settle()[0] > 0)) return false;
            applyNow(pb, op);
            return true;
        });
    }

    @Override
    public boolean settled() {
        return held.isEmpty();
    }

    private void applyNow(@NotNull Playback pb, @NotNull Recorded<Channels.Remove> op) {
        Entity gone = pb.untrack(op.op().seq());
        if (gone == null) {
            pb.earlyRemove(op.op().seq()); // beat a deferred spawn: that twin must never appear
            return;
        }
        // die AT the last track point: the impact move and the remove cross in the SAME drain, and a
        // leg carrier only aimed velocity at it - the tick hook never gets to land it (absolute: a
        // stale-anchor rel move mis-lands it). Always sent: an on-point TWIN still has a leg-integrating
        // client a tick behind it
        Pos impact = Tracks.trackPointAt(pb, track, op.op().seq(), op.tick());
        if (impact != null && gone.getInstance() != null) gone.teleport(impact);
        Ghost collector = pb.ghost(op.op().collector());
        if (collector != null) { // the recorded pickup swoosh toward the ghost
            int amount = gone instanceof ItemEntity item ? item.getItemStack().amount() : 1;
            gone.sendPacketToViewers(new CollectItemPacket(gone.getEntityId(), collector.getEntityId(), amount));
        }
        Playback.dismiss(gone);
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Remove> op) {
        pb.clearEarlyRemove(op.op().seq());
        if (pb.entity(op.op().seq()) != null) return;
        List<Recorded<Channels.Spawn>> born = spawns.all(pb, op.op().seq()); // it exists again: re-run its spawn
        if (!born.isEmpty()) {
            // at its REST point, not its recorded launch: the moves that would carry a launch-spawned
            // twin to its rest sit EARLIER on the timeline - it would ride at the thrower until the
            // reverse cursor reached them (mid-timeline: the ghost is long alive)
            SpawnApplier.spawnNow(pb, born.get(0).op(), true, Tracks.trackPointAt(pb, track, op.op().seq(), op.tick()));
        }
    }

}
