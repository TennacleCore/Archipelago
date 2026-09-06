package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.network.packet.server.play.EntityHeadLookPacket;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
final class MoveApplier implements Applier<Channels.Move> {
    private final LookBack<String, Channels.Move> history = new LookBack<>(Channels.MOVES, Channels.Move::player);
    private final LookBack<String, Channels.Equip> fits = new LookBack<>(Channels.EQUIPS, Channels.Equip::player);
    /** Active constant-speed legs, per actor. */
    private final Map<String, Glide> glides = new HashMap<>();
    private final Map<String, Pin> lastMove = new HashMap<>();
    private double ranAt = 1;
    private boolean ranReversed;

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Move> op) {
        Channels.Move m = op.op();
        Pos pos = new Pos(m.x(), m.y(), m.z(), m.yaw(), m.pitch());
        Ghost ghost = pb.ghost(m.player());
        if (ghost == null) {
            if (pb.hidden(m.player())) return; // no ghost while the real one wasn't renderable
            PlayerSkin skin = pb.skin(m.player());
            ghost = new Ghost(m.player(), skin != null ? skin.textures() : null,
                    skin != null ? skin.signature() : null);
            // a re-raised ghost (respawn/unhide) rises bare, and the recording's re-emitted equips
            // land a few samples later: dress it from the equip state already at the cursor
            Recorded<Channels.Equip> fit = fits.latest(pb, m.player(), pb.tick());
            if (fit != null) Nbt.applyEquipment(ghost, fit.op().items());
            pb.ghost(m.player(), ghost);
            pb.layerFor(m.player()).spawn(ghost, pos); // rises straight into its recorded layer
            lastMove.put(m.player(), new Pin(pos, pb.tick())); // the next op paces from HERE, not one tick back
        } else {
            ghost.grounded(m.ground());
            Pin previous = lastMove.put(m.player(), new Pin(pos, pb.tick()));
            if (pb.paused() || op.tick() != pb.tick()) { // a fold/seek lands absolutely, at its cursor
                if (pb.paused()) ghost.teleport(pos);
                else Tracks.wireMove(ghost, pos);
                ghost.sendPacketToViewers(new EntityHeadLookPacket(ghost.getEntityId(), m.yaw()));
            } else {
                // glide at EVERY rate, like the entity twins: moves are recorded on CHANGE, so a
                // tick-dilated actor (simtps 5) only lands an op every 4th tick and full-rate
                // per-op jumps rendered its ghost at 5Hz. A per-tick track paces to one tick, which
                // IS the plain move.
                glides.put(m.player(), Tracks.leg(previous, ghost.getPosition(), pos, pb));
            }
        }
    }

    @Override
    public void tick(@NotNull Playback pb) {
        if (pb.paused()) return;
        if (pb.rate() != ranAt || pb.reversed() != ranReversed) {
            ranAt = pb.rate(); // a leg outlives a flip by its real-tick deadline; re-aim from the next op
            ranReversed = pb.reversed();
            glides.clear();
        }
        for (var it = glides.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Ghost ghost = pb.ghost(entry.getKey());
            Glide g = entry.getValue();
            if (ghost == null) {
                it.remove();
                continue;
            }
            if (Math.abs(pb.tick() - g.builtAt()) > g.span()) {
                // the leg outlived the gap it was paced for: a fast rate crosses whole legs inside one theater
                // tick, and the last op before a stand-still still has to land, or the ghost stops short
                Tracks.wireMove(ghost, g.target());
                ghost.sendPacketToViewers(new EntityHeadLookPacket(ghost.getEntityId(), g.target().yaw()));
                it.remove();
                continue;
            }
            Tracks.glide(ghost, g, true);
            ghost.sendPacketToViewers(new EntityHeadLookPacket(ghost.getEntityId(), ghost.getPosition().yaw()));
        }
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Move> op) {
        Recorded<Channels.Move> prev = history.before(pb, op);
        if (prev == null) {
            pb.dropGhost(op.op().player()); // crossing the actor's first sighting: they weren't here yet
            return;
        }
        apply(pb, prev); // moves are state - the previous point IS the inverse
    }
}
