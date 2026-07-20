package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.SpawnMode;
import io.github.term4.archipelago.replay.Visuals;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.channel.SpawnHook;
import net.kyori.adventure.nbt.BinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.entity.metadata.other.PrimedTntMeta;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.LivingEntityMeta;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket;
import net.minestom.server.network.packet.server.play.CollectItemPacket;
import net.minestom.server.network.packet.server.play.EntityHeadLookPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** The core channels' playback side. */
final class Appliers {

    private Appliers() {}

    /**
     * Reverse-crossing support: each key's op history, binary-searched by tick - "what was this key's state
     * just before op X". Built lazily from the playback's full op list, so seek folds can't desync it.
     */
    static final class LookBack<K, T> {
        private final Recordable<T> channel;
        private final Function<T, K> key;
        private Map<K, List<Recorded<T>>> index;

        LookBack(Recordable<T> channel, Function<T, K> key) {
            this.channel = channel;
            this.key = key;
        }

        @Nullable Recorded<T> before(Playback pb, Recorded<T> op) {
            List<Recorded<T>> list = built(pb).get(key.apply(op.op()));
            if (list == null) return null;
            int lo = 0, hi = list.size();
            while (lo < hi) { // first index at op's tick, then identity within the equal-tick run
                int mid = (lo + hi) >>> 1;
                if (list.get(mid).tick() < op.tick()) lo = mid + 1;
                else hi = mid;
            }
            while (lo < list.size() && list.get(lo) != op) lo++;
            return lo > 0 && lo < list.size() ? list.get(lo - 1) : null;
        }

        /** The key's newest op at or before {@code tick} - its state at the cursor. */
        @Nullable Recorded<T> latest(Playback pb, K k, long tick) {
            List<Recorded<T>> list = built(pb).get(k);
            if (list == null) return null;
            int lo = 0, hi = list.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (list.get(mid).tick() <= tick) lo = mid + 1;
                else hi = mid;
            }
            return lo > 0 ? list.get(lo - 1) : null;
        }

        private Map<K, List<Recorded<T>>> built(Playback pb) {
            if (index == null) {
                index = new HashMap<>();
                for (Recorded<T> r : pb.ops(channel)) {
                    index.computeIfAbsent(key.apply(r.op()), k -> new ArrayList<>()).add(r);
                }
            }
            return index;
        }
    }

    static final class LayerApplier implements Applier<Channels.Layer> {
        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Layer> op) {
            Channels.Layer l = op.op();
            if (!l.alive()) {
                pb.dropLayer(l.id());
                return;
            }
            if (pb.layer(l.id()) != null) return; // already raised (seek fold)
            Shard parent = pb.layer(l.parent());
            // a bare child inherits the theater's domain: one clock plays the whole tree
            pb.layer(l.id(), new Shard(parent != null ? parent : pb.shard()));
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Layer> op) {
            Channels.Layer l = op.op();
            if (l.alive()) { // un-create: the layer didn't exist yet
                pb.dropLayer(l.id());
                return;
            }
            if (pb.layer(l.id()) != null) return;
            // un-destroy: re-raise from the CREATE op (the destroy carries no parent) and repaint the layer
            // to its state at the destroy tick - its own ops, dumps included, replay in order
            for (Recorded<Channels.Layer> r : pb.ops(Channels.LAYERS)) {
                if (r.op().id() == l.id() && r.op().alive()) {
                    apply(pb, r);
                    break;
                }
            }
            Shard in = pb.layer(l.id());
            if (in == null) return;
            for (Recorded<Channels.BlockOp> b : pb.ops(Channels.BLOCKS)) {
                if (b.tick() > op.tick()) break;
                if (b.op().layer() == l.id()) {
                    in.pinBlock(new Vec(b.op().x(), b.op().y(), b.op().z()), Nbt.block(b.op().block()));
                }
            }
        }
    }

    static final class EntityLayerApplier implements Applier<Channels.EntityLayer> {
        private final LookBack<Integer, Channels.EntityLayer> history =
                new LookBack<>(Channels.ENTITY_LAYERS, Channels.EntityLayer::seq);

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityLayer> op) {
            Shard to = pb.layer(op.op().layer());
            Entity twin = pb.entity(op.op().seq());
            if (to != null && twin != null) to.transfer(twin);
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityLayer> op) {
            Recorded<Channels.EntityLayer> prev = history.before(pb, op);
            if (prev != null) {
                apply(pb, prev);
                return;
            }
            for (Recorded<Channels.Spawn> spawn : pb.ops(Channels.SPAWNS)) { // back to its spawn layer
                if (spawn.op().seq() == op.op().seq()) {
                    Shard to = pb.layer(spawn.op().layer());
                    Entity twin = pb.entity(op.op().seq());
                    if (to != null && twin != null) to.transfer(twin);
                    return;
                }
            }
        }
    }

    static final class SkinApplier implements Applier<Channels.Skin> {
        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Skin> op) {
            pb.skin(op.op().player(), new PlayerSkin(op.op().textures(), op.op().signature()));
        }
    }

    static final class MoveApplier implements Applier<Channels.Move> {
        private final LookBack<String, Channels.Move> history = new LookBack<>(Channels.MOVES, Channels.Move::player);
        private final LookBack<String, Channels.Equip> fits = new LookBack<>(Channels.EQUIPS, Channels.Equip::player);
        /** Active constant-speed legs at fractional rates, per actor; {@link #tick} walks the ghost along. */
        private final Map<String, Glide> glides = new HashMap<>();
        private final Map<String, Pin> lastMove = new HashMap<>();

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
                if (fit != null) {
                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        BinaryTag item = fit.op().items().get(slot.name());
                        if (item != null) ghost.setEquipment(slot, Nbt.item(item));
                    }
                }
                pb.ghost(m.player(), ghost);
                pb.layerFor(m.player()).spawn(ghost, pos); // rises straight into its recorded layer
            } else {
                ghost.grounded(m.ground());
                Pin previous = lastMove.put(m.player(), new Pin(pos, pb.tick()));
                if (pb.rate() >= 1 || pb.paused()) { // fractional rates glide in tick() instead
                    ghost.teleport(pos);
                    ghost.sendPacketToViewers(new EntityHeadLookPacket(ghost.getEntityId(), m.yaw()));
                } else {
                    glides.put(m.player(), leg(previous != null ? previous.pos() : ghost.getPosition(),
                            previous != null ? previous.tick() : pb.tick() - 1, pos, pb.tick(), pb.rate()));
                }
            }
        }

        @Override
        public void tick(@NotNull Playback pb) {
            if (pb.rate() >= 1 || pb.paused()) return;
            for (var it = glides.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                Ghost ghost = pb.ghost(entry.getKey());
                if (ghost == null || Math.abs(pb.tick() - entry.getValue().builtAt()) > 1) {
                    it.remove(); // gone, or a rate flip left the leg stale (its target is the past)
                    continue;
                }
                glide(ghost, entry.getValue(), true);
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

    static final class SpawnApplier implements Applier<Channels.Spawn> {
        // clients DISCARD a projectile whose wire owner they don't know yet: a shooter-owned spawn waits for
        // its ghost to be world-ready (ghost spawns are async), then gives up and spawns ownerless
        private static final long OWNER_WAIT_TICKS = 20;

        private record Pending(Channels.Spawn spawn, long since, long notBefore) {}

        private final List<Pending> pending = new ArrayList<>();

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
            if (gone != null) dismiss(gone); // it didn't exist before this
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
                Pos at = pb.tick() - p.since() > 1 ? trackPointAt(pb, p.spawn().seq(), pb.tick()) : null;
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

        private void noteFuse(Playback pb, Channels.Spawn spawn) {
            Entity e = pb.entity(spawn.seq());
            if (e == null || !(e.getEntityMeta() instanceof PrimedTntMeta)) return;
            for (Recorded<Channels.Remove> r : pb.ops(Channels.REMOVES)) {
                if (r.op().seq() == spawn.seq()) {
                    fused.add(new Fused(spawn.seq(), r.tick()));
                    return;
                }
            }
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
                if (!Visuals.standIn(e) && !pb.parked(f.seq())) continue; // free-running: its own sim paces
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
            for (SpawnHook hook : Replay.spawnHooks()) hook.enhance(entity, owner, pb);
            pb.track(spawn.seq(), entity);
            in.spawn(entity, at != null ? at : new Pos(spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()));
            return true;
        }
    }

    /** A track point at a timeline tick (glide legs + rest pins). */
    private record Pin(Pos pos, long tick) {}

    static final class EntityMoveApplier implements Applier<Channels.EntityMove> {

        private final Map<Integer, Pin> lastTrack = new HashMap<>();
        /** Active constant-speed legs, per seq. */
        private final Map<Integer, Glide> glides = new HashMap<>();
        private final LookBack<Integer, Channels.EntityMove> history =
                new LookBack<>(Channels.ENTITY_MOVES, Channels.EntityMove::seq);

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
            for (Recorded<Channels.Spawn> spawn : pb.ops(Channels.SPAWNS)) { // before its first track point
                if (spawn.op().seq() == op.op().seq()) {
                    drive(pb, op.op().seq(), new Pos(spawn.op().x(), spawn.op().y(), spawn.op().z(),
                            spawn.op().yaw(), spawn.op().pitch()));
                    return;
                }
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
            // the pin advances even while the async spawn hasn't landed: with a stale spawn-tick anchor,
            // the rest pin yanked a freshly revived twin back to its spawn point (zero velocity + teleport
            // = the 1.8 frozen-item chop, capture-diagnosed)
            Pin previous = lastTrack.put(seq, new Pin(target, pb.tick()));
            if (e == null || e.getInstance() == null) return; // the next track op (or the pin) places it
            if (pb.paused()) { // a fold placed it - hold there, absolutely
                e.teleport(target);
                return;
            }
            if (!Visuals.standIn(e)) pb.park(seq); // stand-ins are born inert: park's zero would broadcast mid-flight
            // no fly here: the applier tick pass runs right after this drain and aims it - a second
            // same-tick send doubled the velocity wire
            glides.put(seq, leg(previous != null ? previous.pos() : e.getPosition(),
                    previous != null ? previous.tick() : pb.tick() - 1, target, pb.tick(), pb.rate()));
        }

        // between track points a twin STAYS at its last one: a silent track means the real entity rested, but a
        // physics twin can drift (a zero-velocity revived arrow falls out of its wall) - pin it back
        @Override
        public void tick(@NotNull Playback pb) {
            var it = lastTrack.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                Entity e = pb.entity(entry.getKey());
                if (e == null) {
                    // mid-defer (the spawn hasn't materialized) or untracked: the pin stays - deleting
                    // it here lost every leg that crossed before a deferred spawn landed, and the twin
                    // re-entered a whole window behind (the launch snap-then-rush). A glide for an
                    // absent body is dropped so settled() can't hold playback open on it
                    glides.remove(entry.getKey());
                    continue;
                }
                if (e.isRemoved()) {
                    it.remove();
                    glides.remove(entry.getKey());
                    continue;
                }
                Pin pin = entry.getValue();
                if (pb.paused()) {
                    pinStill(e); // pause must reach the wire: client sims fall through a quiet one
                    continue;
                }
                // the leg owns the motion
                Glide g = glides.get(entry.getKey());
                // no live leg (none yet, or a rate flip left a stale one): a LEG toward the pin, not a
                // snap - a drifted rest gets a gentle pull-back, a stale target never teleports the twin
                // into the past, and a seek-fold's whole-legs displacement snaps rather than crawls
                if (g == null || Math.abs(pb.tick() - g.builtAt()) > g.span()) {
                    glides.remove(entry.getKey());
                    if (pin.pos().distanceSquared(e.getPosition()) > 4) {
                        e.teleport(pin.pos()); // a SNAP is absolute (a stale-anchor rel move mis-lands it)
                        continue;
                    }
                    // a FRESH pin is a missed flight leg (the spawn defer ate its crossing): pace it to
                    // the deadline and STORE it - rebuilt each tick, the step decays by (1-rate) into a
                    // Zeno crawl that leaves the twin a leg behind for the whole flight. Mid-hole (the
                    // track resumes within reach): bridge forward. An OLD pin with nothing ahead is a
                    // rest pull-back: rebuild on the remainder, spread over ~4 ticks (a dt-1 leg at full
                    // rate is the whole remainder in one tick - the same snap the leg exists to avoid)
                    boolean fresh = pb.tick() - pin.tick() <= 1;
                    Glide b = fresh ? null : bridge(pb, entry.getKey(), e.getPosition());
                    if (b != null) {
                        glides.put(entry.getKey(), b);
                        g = b;
                    } else {
                        long dt = fresh ? 1 : Math.max(1, Math.round(4 * pb.rate()));
                        g = leg(e.getPosition(), pin.tick() - dt, pin.pos(), pin.tick(), pb.rate());
                        if (fresh) glides.put(entry.getKey(), g);
                    }
                }
                // the IN_GROUND freeze is a REST state: a real leg (reverse included) re-flies the arrow
                if (Visuals.clientFrozen(e) && g.step().length() > 1.0e-3) Visuals.freezeArrow(e, false);
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
                            double step = Math.min(d, g.perTick());
                            Pos view = aimed(g);
                            e.refreshPosition(e.getPosition().add(to.mul(step / d))
                                    .withView(view.yaw(), view.pitch()), false, false);
                        }
                    }
                    int left = g.left()[0]--;
                    if (left <= 0) {
                        // a SHORT track hole (a recorded server hiccup - launches stall 1-2 ticks on the
                        // cross-clock pickup; slow motion magnifies the gap) bridges to the next known
                        // point instead of a stop-dead + hold
                        Glide next = bridge(pb, entry.getKey(), e.getPosition());
                        if (next != null) {
                            glides.put(entry.getKey(), next);
                            if (!Visuals.clientAccelerates(e)) fly(e, next, true);
                            legSets(e, Visuals.clientAccelerates(e));
                            continue;
                        }
                        // zero WITH the landing: a kept velocity coasts velocity-driven clients blocks
                        // PAST the wall (the rest wire is quiet, nothing pulls them back)
                        Visuals.freezeArrow(e, true);
                        if (!e.getVelocity().isZero()) e.setVelocity(Vec.ZERO);
                        // ABSOLUTE landing, ALWAYS sent to everyone: an on-target twin still has a
                        // leg-integrating client one velocity-tick past it - the absolute is for the
                        // CLIENT, not the twin
                        e.teleport(aimed(g));
                        glides.remove(entry.getKey());
                    } else {
                        // the velocity RIDES through the leg's whole window - zeroing it early (even
                        // landed-on-target: the next leg is a tick away) strips the client's integration
                        // and collapses rate-1 bounces to 20Hz steps
                        boolean everyone = Visuals.clientAccelerates(e);
                        if (!everyone) {
                            fly(e, g, true);
                        } else {
                            // fireballs get per-tick anchors for EVERY generation: their client accel
                            // model AMPLIFIES any perturbation (a spectator's hit prediction, packet
                            // jitter) instead of decaying it, so unanchored velocity desyncs the view.
                            // The model ((v + 0.1 v-hat) x 0.95 per tick) inverts - a compensated aim
                            // keeps modern frames moving between anchors at any pace down to its
                            // 0.095 b/t floor; below it, steps are too small to strobe anyway
                            double bt = g.step().length();
                            if (bt > 0.096) {
                                e.setVelocity(g.step().normalize().mul((bt / 0.95 - 0.1)
                                        * ServerFlag.SERVER_TICKS_PER_SECOND));
                            } else if (!e.getVelocity().isZero()) {
                                e.setVelocity(Vec.ZERO);
                            }
                        }
                        legSets(e, everyone);
                    }
                } else {
                    if (!e.getVelocity().isZero()) e.setVelocity(Vec.ZERO); // glide-driven: the client sim stays inert
                    Pos before = e.getPosition();
                    glide(e, g);
                    // at-rest holds: a restless client sinks its own sim between periodic syncs (both
                    // generations - the bobber sim ignores stillness), and a 1.8-generation arrow that
                    // never latched (a slow-rate ray is microscopic, so its own collision test never
                    // fires) sags under its own compounding gravity at the stick
                    if ((Visuals.clientRestless(e) || Visuals.clientFrozen(e)) && e.getPosition().equals(before)) {
                        pinStill(e);
                    }
                }
            }
        }
    }

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
    private static Pos aimed(Glide g) {
        Vec d = g.step();
        double h = Math.hypot(d.x(), d.z());
        if (h + Math.abs(d.y()) < 1.0e-6) return g.target();
        return g.target().withView((float) Math.toDegrees(Math.atan2(d.x(), d.z())),
                (float) Math.toDegrees(Math.atan2(d.y(), h)));
    }

    /** The leg's per-tick sets at the advanced (view-stamped) position; {@code everyone} when velocity
     *  is unusable for anyone (the fireball family under its accel floor). */
    private static void legSets(Entity e, boolean everyone) {
        if (e.getInstance() != null) sets(e, e.getPosition(), everyone);
    }

    private static void pinStill(Entity e) {
        pinAt(e, e.getPosition());
    }

    /** Pin for a resting, paused, or removal-held twin: one zero-velocity broadcast stops modern sims
     *  dead (zero never unlatches their IN_GROUND meta), then the per-tick legacy pair holds the rest. */
    private static void pinAt(Entity e, Pos at) {
        if (e.getInstance() == null) return;
        if (!e.getVelocity().isZero()) e.setVelocity(Vec.ZERO);
        sets(e, at, Visuals.clientRestless(e)); // both bobber generations sim through stillness
    }

    /** The legacy half of the twin wire: an absolute set + a velocity every tick. Legacy renderers
     *  frame-lerp sets natively (modern ones snap prev=cur and strobe - they ride velocity instead),
     *  and each send lands before the client's move, so its own rate-unscaled gravity never displaces
     *  it (the motion would otherwise COMPOUND between packets). The sent velocity is never the real
     *  leg: a set-driven client's motion only feeds its OWN collision sweep (a slow arrow lingering
     *  near its shooter past the 5-tick grace self-collides and dies - the sometimes-vanishing arrows)
     *  and arrow rotation-from-motion, where atan2 of zero drags the model sideways (the stuck shake).
     *  A CRUMB along the aim starves the rays and keeps the aim; non-rotating twins get plain zero. */
    private static void sets(Entity e, Pos at, boolean everyone) {
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
            boolean legacy = Replay.legacyViewer(viewer);
            if (everyone || legacy) viewer.sendPacket(set);
            // the starved velocity is strictly LEGACY medicine: velocity is a modern client's motion
            // carrier - starving it there strands the render on bare 20Hz anchors (which also trail
            // the timeline by the sim's one-step lead)
            if (legacy) viewer.sendPacket(still);
        }
    }

    /** Removes a twin; a removal racing its own async spawn re-runs once the spawn lands (or never). */
    static void dismiss(Entity gone) {
        gone.remove();
        if (gone.getInstance() == null) gone.scheduler().scheduleNextTick(gone::remove);
    }

    /** (Re)aims a flier along its leg: ONE velocity broadcast, then the client's own no-gravity sim flies it
     *  frame-perfect and the server twin integrates the same velocity - NOTHING touches its position per tick
     *  (that is what makes it glassy). The tight tolerance re-anchors BOTH sims about once per leg: their
     *  native drags decay a few percent per leg, and unchecked that drift becomes visible sync rubber-banding. */
    private static void fly(Entity e, Glide g) {
        fly(e, g, false);
    }

    /** {@code resend}: re-aim even at an unchanged value - 1.8 clients have no NO_GRAVITY flag, so their
     *  own gravity decays the leg between aims (the 1.8 sag-to-ground); a per-tick resend re-pins every
     *  client generation. The want floor keeps rests quiet. */
    private static void fly(Entity e, Glide g, boolean resend) {
        if (e.getInstance() == null) return; // deferred: tick() re-tries once the spawn lands
        Vec want = g.step().mul(ServerFlag.SERVER_TICKS_PER_SECOND);
        boolean force = (resend || Visuals.clientMeddles(e)) && want.length() > 0.02;
        if (!force && e.getVelocity().sub(want).length() <= Math.max(0.02, 0.03 * want.length())) return;
        // only a REAL aim unfreezes (a flying arrow rotates toward its velocity again): the rest-zero
        // cleanup of an arrival's kept velocity must leave the stuck advertisement alone
        if (want.length() > 0.02) Visuals.freezeArrow(e, false);
        e.setVelocity(want);
    }

    /** One glide step toward the leg's target, paced to arrive AT its deadline. */
    private static void glide(Entity e, Glide g) {
        glide(e, g, false);
    }

    /** {@code turnAtRest}: ghosts keep TURNING while standing (their look froze without it); entity twins
     *  stay quiet at rest - re-stamping a stuck arrow's recorded view fights its client orientation. */
    private static void glide(Entity e, Glide g, boolean turnAtRest) {
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

    private static float lerpAngle(float from, float to, double t) {
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
    private static void wireMove(Entity e, Pos step) {
        if (e.getInstance() == null) return;
        if (step.distanceSquared(e.getPosition()) > 16) {
            e.teleport(step);
            return;
        }
        e.refreshPosition(step, false, true);
    }

    /** Positional FX re-broadcast: raw packet bytes off the recorded wire, played on crossing - both
     *  directions (one-shots, like swings). At fractional rates live crossings hold ONE timeline tick:
     *  the whole scene renders one tick behind the cursor, and an impact sound played at op time would
     *  beat its arrow there. Codec drift across server versions decodes to a drop, not a crash. */
    static final class FxApplier implements Applier<Channels.Fx> {
        private final List<Recorded<Channels.Fx>> held = new ArrayList<>();

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Fx> op) {
            if (op.tick() == pb.tick() && pb.rate() < 1 && !pb.paused()) {
                held.add(op);
                return;
            }
            play(pb, op.op());
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Fx> op) {
            apply(pb, op); // one-shots hold in reverse too (the scene lags the cursor both ways)
        }

        @Override
        public void tick(@NotNull Playback pb) {
            held.removeIf(op -> {
                long behind = Math.abs(pb.tick() - op.tick());
                if (behind > 2) return true; // the cursor jumped: the fold owns the scene
                if (behind >= 1) {
                    play(pb, op.op());
                    return true;
                }
                return false;
            });
        }

        @Override
        public boolean settled() {
            return held.isEmpty();
        }

        private static void play(Playback pb, Channels.Fx fx) {
            ServerPacket packet = decode(fx);
            Shard in = pb.layer(0); // FX are positional and the tree overlays ONE space: root broadcast
            if (packet != null && in != null) in.broadcast(packet);
        }

        static @Nullable ServerPacket decode(Channels.Fx fx) {
            try {
                NetworkBuffer buffer = NetworkBuffer.wrap(fx.data(), 0, fx.data().length, MinecraftServer.process());
                return switch (fx.kind()) {
                    case Channels.FX_PARTICLE -> ParticlePacket.SERIALIZER.read(buffer);
                    case Channels.FX_SOUND -> SoundEffectPacket.SERIALIZER.read(buffer);
                    case Channels.FX_EVENT -> WorldEventPacket.SERIALIZER.read(buffer);
                    // the modern explosion's flash+boom ride THIS packet; its knockback field must not
                    // shove replay viewers
                    case Channels.FX_EXPLOSION -> {
                        ExplosionPacket boom = ExplosionPacket.SERIALIZER.read(buffer);
                        yield new ExplosionPacket(boom.center(), boom.radius(), boom.blockCount(), null,
                                boom.particle(), boom.sound(), boom.blockParticles());
                    }
                    default -> null;
                };
            } catch (Exception e) {
                return null;
            }
        }
    }

    static final class BlockApplier implements Applier<Channels.BlockOp> {
        private record At(int layer, int x, int y, int z) {}

        private final LookBack<At, Channels.BlockOp> history = new LookBack<>(Channels.BLOCKS,
                b -> new At(b.layer(), b.x(), b.y(), b.z()));

        private final List<Recorded<Channels.BlockOp>> held = new ArrayList<>();

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.BlockOp> op) {
            // one-tick presentation latency on live fractional crossings, like FX: the scene renders one
            // timeline tick behind, and an explosion's holes must not beat its boom (seek folds exempt)
            if (op.tick() == pb.tick() && pb.rate() < 1 && !pb.paused() && !pb.reversed()) {
                held.add(op);
                return;
            }
            applyNow(pb, op);
        }

        @Override
        public void tick(@NotNull Playback pb) {
            held.removeIf(op -> {
                long behind = pb.tick() - op.tick();
                if (behind < 0 || behind > 2) return true; // the cursor jumped: the fold owns the scene
                if (behind >= 1) {
                    applyNow(pb, op);
                    return true;
                }
                return false;
            });
        }

        @Override
        public boolean settled() {
            return held.isEmpty();
        }

        private void applyNow(@NotNull Playback pb, @NotNull Recorded<Channels.BlockOp> op) {
            Channels.BlockOp b = op.op();
            Shard in = pb.layer(b.layer());
            if (in != null) write(in, op, new Vec(b.x(), b.y(), b.z()), Nbt.block(b.block()));
        }

        // pin, don't set: a cross-clock tree's layer dumps may land child-before-parent, and a mask that
        // momentarily equals the parent view must not normalize away. EXCEPT tick-0 root ops - the
        // recording's understructure masks: plain writes, so a fresh theater's parent-equal copies drop
        // while a drifted live parent (the time-rewind recipe) stays masked
        private static void write(Shard in, Recorded<Channels.BlockOp> op, Vec pos, Block block) {
            if (op.tick() == 0 && op.op().layer() == 0) in.setBlock(pos, block);
            else in.pinBlock(pos, block);
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.BlockOp> op) {
            Channels.BlockOp b = op.op();
            Shard in = pb.layer(b.layer());
            if (in == null) return;
            Vec pos = new Vec(b.x(), b.y(), b.z());
            Recorded<Channels.BlockOp> prev = history.before(pb, op);
            if (prev != null) {
                write(in, prev, pos, Nbt.block(prev.op().block()));
                return;
            }
            // no earlier op: back to the t0 snapshot (root layer) or the parent view (the entry clears -
            // under an unloaded chunk too, or the un-applied overlay state survives the reversal)
            Block t0 = b.layer() == 0 ? pb.t0Block(pos) : null;
            if (t0 != null) {
                in.pinBlock(pos, t0);
            } else {
                in.clearBlock(pos);
            }
        }
    }

    static final class EquipApplier implements Applier<Channels.Equip> {
        private final Map<String, EnumSet<EquipmentSlot>> worn = new HashMap<>();
        private final LookBack<String, Channels.Equip> history = new LookBack<>(Channels.EQUIPS, Channels.Equip::player);

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Equip> op) {
            Recorded<Channels.Equip> prev = history.before(pb, op);
            if (prev != null) {
                apply(pb, prev);
                return;
            }
            EnumSet<EquipmentSlot> before = worn.remove(op.op().player()); // back to bare
            Ghost ghost = pb.ghost(op.op().player());
            if (ghost == null || before == null) return;
            for (EquipmentSlot slot : before) ghost.setEquipment(slot, ItemStack.AIR);
        }

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Equip> op) {
            Ghost ghost = pb.ghost(op.op().player());
            if (ghost == null) return;
            EnumSet<EquipmentSlot> now = EnumSet.noneOf(EquipmentSlot.class);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                BinaryTag item = op.op().items().get(slot.name());
                if (item == null) continue;
                ghost.setEquipment(slot, Nbt.item(item));
                now.add(slot);
            }
            // clear only slots the ghost actually wore: an explicit BODY=AIR broadcast is the ViaBackwards
            // chestplate wipe (single-slot AIR clears pass every skip-empty-slots fix by design)
            EnumSet<EquipmentSlot> before = worn.put(op.op().player(), now);
            if (before != null) {
                before.removeAll(now);
                for (EquipmentSlot slot : before) ghost.setEquipment(slot, ItemStack.AIR);
            }
        }
    }

    static final class ActApplier implements Applier<Channels.Act> {
        private final List<Recorded<Channels.Act>> held = new ArrayList<>();

        @Override
        public void tick(@NotNull Playback pb) {
            held.removeIf(op -> {
                long behind = Math.abs(pb.tick() - op.tick());
                if (behind > 2) return true;
                if (behind >= 1) {
                    apply(pb, op);
                    return true;
                }
                return false;
            });
        }

        @Override
        public boolean settled() {
            return held.isEmpty();
        }

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Act> op) {
            // the hurt flash rides the same one-tick presentation latency as FX, both directions (damage
            // must not appear before its arrow lands / the fall ends); held ops re-enter here past the cursor
            if (op.op().kind() == Channels.HURT && op.tick() == pb.tick()
                    && pb.rate() < 1 && !pb.paused()) {
                held.add(op);
                return;
            }
            String player = op.op().player();
            Ghost ghost = pb.ghost(player);
            int kind = op.op().kind();
            if (ghost == null && kind != Channels.RESPAWN && kind != Channels.HIDDEN && kind != Channels.SHOWN
                    && kind != Channels.LAYER) return;
            switch (kind) {
                case Channels.SWING_MAIN -> ghost.swingMainHand();
                case Channels.SWING_OFF -> ghost.swingOffHand();
                case Channels.HURT -> ghost.damage(DamageType.GENERIC, 0f);
                case Channels.SNEAK_ON -> { meta(ghost).setSneaking(true); ghost.setPose(EntityPose.SNEAKING); }
                case Channels.SNEAK_OFF -> { meta(ghost).setSneaking(false); ghost.setPose(EntityPose.STANDING); }
                case Channels.USE_ON -> { meta(ghost).setActiveHand(PlayerHand.MAIN); meta(ghost).setHandActive(true); }
                case Channels.USE_OFF -> meta(ghost).setHandActive(false);
                case Channels.ARROWS -> meta(ghost).setArrowCount(op.op().value());
                // status 3 = the wire death animation; the corpse persists until the RECORDED hide/respawn
                case Channels.DEATH -> ghost.triggerStatus((byte) 3);
                // a death-posed client entity never stands back up: drop any lingering corpse; the next move op
                // raises a fresh ghost at the respawn point (the recording re-emits its full state)
                case Channels.RESPAWN -> {
                    pb.show(player);
                    pb.dropGhost(player);
                }
                case Channels.HIDDEN -> {
                    pb.hide(player);
                    pb.dropGhost(player);
                }
                case Channels.SHOWN -> pb.show(player); // next move op raises the ghost
                case Channels.LAYER -> { // the recorded layer switch: visibility follows the layer, combat-continuous
                    pb.actorLayer(player, op.op().value());
                    Shard to = pb.layer(op.op().value());
                    if (to != null && ghost != null) to.transfer(ghost);
                }
            }
        }

        // stateful act kinds look back per (actor, state); one-shots and lifecycle kinds invert directly
        private static int group(int kind) {
            return switch (kind) {
                case Channels.SNEAK_ON, Channels.SNEAK_OFF -> 1;
                case Channels.USE_ON, Channels.USE_OFF -> 2;
                case Channels.ARROWS -> 3;
                case Channels.LAYER -> 4;
                default -> 0;
            };
        }

        private record ActKey(String player, int group) {}

        private final LookBack<ActKey, Channels.Act> history = new LookBack<>(Channels.ACTS,
                a -> new ActKey(a.player(), group(a.kind())));

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Act> op) {
            String player = op.op().player();
            int kind = op.op().kind();
            switch (kind) {
                // instantaneous FX: crossing them in either direction plays them
                case Channels.SWING_MAIN, Channels.SWING_OFF, Channels.HURT -> apply(pb, op);
                // un-die / back-before-the-respawn: drop the corpse/fresh ghost; the next reverse move op
                // re-raises one alive (the same 1-tick flicker class as seek-across-respawn)
                case Channels.DEATH, Channels.RESPAWN -> pb.dropGhost(player);
                case Channels.HIDDEN -> pb.show(player);
                case Channels.SHOWN -> {
                    pb.hide(player);
                    pb.dropGhost(player);
                }
                default -> {
                    Recorded<Channels.Act> prev = history.before(pb, op);
                    if (prev != null) {
                        apply(pb, prev);
                        return;
                    }
                    Ghost ghost = pb.ghost(player); // the group's first op reverts to its default state
                    switch (kind) {
                        case Channels.SNEAK_ON -> {
                            if (ghost != null) {
                                meta(ghost).setSneaking(false);
                                ghost.setPose(EntityPose.STANDING);
                            }
                        }
                        case Channels.USE_ON -> {
                            if (ghost != null) meta(ghost).setHandActive(false);
                        }
                        case Channels.ARROWS -> {
                            if (ghost != null) meta(ghost).setArrowCount(0);
                        }
                        case Channels.LAYER -> {
                            pb.actorLayer(player, 0);
                            Shard home = pb.layer(0);
                            if (ghost != null && home != null) home.transfer(ghost);
                        }
                    }
                }
            }
        }

        private static LivingEntityMeta meta(Ghost ghost) {
            return (LivingEntityMeta) ghost.getEntityMeta();
        }
    }

    static final class RemoveApplier implements Applier<Channels.Remove> {
        private record Held(Recorded<Channels.Remove> op, int[] settle) {}

        private final List<Held> held = new ArrayList<>();
        /** Theater ticks a landed twin rests before the destroy: the CLIENT integrates the same legs a
         *  stream latency later - released on server arrival, its rendered ball is still legs short and
         *  vanishes mid-air. A brief rest ON the wall reads as the hit. */
        private static final int SETTLE_TICKS = 3;

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Remove> op) {
            // the scene renders one timeline tick behind at fractional rates: removing at op time despawns
            // a projectile a full leg short of its wall
            if (op.tick() == pb.tick() && pb.rate() < 1 && !pb.paused() && !pb.reversed()) {
                held.add(new Held(op, new int[]{SETTLE_TICKS}));
                return;
            }
            applyNow(pb, op);
        }

        @Override
        public void tick(@NotNull Playback pb) {
            held.removeIf(h -> {
                Recorded<Channels.Remove> op = h.op();
                long behind = pb.tick() - op.tick();
                if (behind < 0 || behind > 3) return true; // the cursor jumped: the fold owns the scene
                Entity e = pb.entity(op.op().seq());
                Pos impact = trackPointAt(pb, op.op().seq(), op.tick());
                boolean present = e != null && impact != null && e.getInstance() != null;
                boolean landed = !present || impact.distanceSquared(e.getPosition()) <= 0.09;
                // a held twin rests without re-aims, and a legacy viewer's own gravity sinks it mid-air
                // through the hold (freeze, sag, then the flush snap) - per-tick pins hold it, with the
                // twin's aimed view (the recorded view wobbled arrow sticks)
                if (present && landed) {
                    pinAt(e, impact.withView(e.getPosition().yaw(), e.getPosition().pitch()));
                }
                if (behind < 1) return false;
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
            Pos impact = trackPointAt(pb, op.op().seq(), op.tick());
            if (impact != null && gone.getInstance() != null) gone.teleport(impact);
            Ghost collector = pb.ghost(op.op().collector());
            if (collector != null) { // the recorded pickup swoosh toward the ghost
                int amount = gone instanceof ItemEntity item ? item.getItemStack().amount() : 1;
                gone.sendPacketToViewers(new CollectItemPacket(gone.getEntityId(), collector.getEntityId(), amount));
            }
            dismiss(gone);
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Remove> op) {
            pb.clearEarlyRemove(op.op().seq());
            if (pb.entity(op.op().seq()) != null) return;
            for (Recorded<Channels.Spawn> spawn : pb.ops(Channels.SPAWNS)) { // it exists again: re-run its spawn
                if (spawn.op().seq() == op.op().seq()) {
                    // at its REST point, not its recorded launch: the moves that would carry a launch-spawned
                    // twin to its rest sit EARLIER on the timeline - it would ride at the thrower until the
                    // reverse cursor reached them (mid-timeline: the ghost is long alive)
                    SpawnApplier.spawnNow(pb, spawn.op(), true, trackPointAt(pb, op.op().seq(), op.tick()));
                    return;
                }
            }
        }

    }

    /** Ticks of recorded silence a leg spans toward the next known point instead of stopping dead. */
    private static final long BRIDGE_TICKS = 4;

    /** A leg from {@code from} to {@code seq}'s next move op in the travel direction, or null (none near). */
    static @Nullable Glide bridge(Playback pb, int seq, Pos from) {
        List<Recorded<Channels.EntityMove>> moves = pb.ops(Channels.ENTITY_MOVES);
        int lo = 0, hi = moves.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (pb.reversed() ? moves.get(mid).tick() < pb.tick() : moves.get(mid).tick() <= pb.tick()) lo = mid + 1;
            else hi = mid;
        }
        if (pb.reversed()) {
            for (int i = lo - 1; i >= 0; i--) {
                Recorded<Channels.EntityMove> r = moves.get(i);
                if (pb.tick() - r.tick() > BRIDGE_TICKS) return null;
                if (r.op().seq() != seq) continue;
                return bridgeLeg(pb, from, r);
            }
        } else {
            for (int i = lo; i < moves.size(); i++) {
                Recorded<Channels.EntityMove> r = moves.get(i);
                if (r.tick() - pb.tick() > BRIDGE_TICKS) return null;
                if (r.op().seq() != seq) continue;
                return bridgeLeg(pb, from, r);
            }
        }
        return null;
    }

    /** Paced on REAL ticks ({@code subtick} counts the window already consumed) to arrive one window
     *  AFTER the op's crossing - the phase every normal leg keeps (built at its op's crossing, walked
     *  through that window). A bridge landing AT the crossing sat a whole window on its target while
     *  the crossing's own leg re-covered it (the launch rush-then-stall); whole-tick pacing also
     *  compressed a mid-window bridge into a 2x rush. */
    private static Glide bridgeLeg(Playback pb, Pos from, Recorded<Channels.EntityMove> r) {
        Pos target = new Pos(r.op().x(), r.op().y(), r.op().z(), r.op().yaw(), r.op().pitch());
        long dt = Math.max(1, Math.abs(r.tick() - pb.tick()));
        int real = (int) Math.max(1, Math.round((dt - pb.subtick() + 1) / Math.max(1.0e-9, pb.rate())));
        return new Glide(target, target.sub(from).asVec().div(real), pb.tick(), dt + 1, new int[]{real});
    }

    /** The last track point at or before {@code tick}, or null (it never moved). */
    static @Nullable Pos trackPointAt(Playback pb, int seq, long tick) {
        List<Recorded<Channels.EntityMove>> moves = pb.ops(Channels.ENTITY_MOVES);
        int lo = 0, hi = moves.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (moves.get(mid).tick() <= tick) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo - 1; i >= 0; i--) {
            Channels.EntityMove m = moves.get(i).op();
            if (m.seq() == seq) return new Pos(m.x(), m.y(), m.z(), m.yaw(), m.pitch());
        }
        return null;
    }

    static final class DigApplier implements Applier<Channels.Dig> {
        private record Crack(int layer, int ghostId, int x, int y, int z, long start, long end) {}
        private record Window(Channels.Dig dig, long from, long to) {}

        private final List<Crack> cracks = new ArrayList<>();
        private List<Window> windows;

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Dig> op) {
            if (!op.op().start()) return; // cancels only bound the lookahead below
            Ghost digger = pb.ghost(op.op().digger());
            if (digger != null) cracks.add(new Crack(op.op().layer(), digger.getEntityId(),
                    op.op().x(), op.op().y(), op.op().z(), op.tick(), end(pb, op)));
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Dig> op) {
            if (!op.op().start()) return; // crossing a start in reverse: the crack never began
            cracks.removeIf(c -> c.layer() == op.op().layer()
                    && c.x() == op.op().x() && c.y() == op.op().y() && c.z() == op.op().z());
            Shard in = pb.layer(op.op().layer());
            Ghost digger = pb.ghost(op.op().digger());
            if (in != null && digger != null) {
                in.broadcast(new BlockBreakAnimationPacket(digger.getEntityId(),
                        new Vec(op.op().x(), op.op().y(), op.op().z()), (byte) -1));
            }
        }

        @Override
        public void tick(@NotNull Playback pb) {
            if (pb.reversed()) reraise(pb);
            var it = cracks.iterator();
            while (it.hasNext()) {
                Crack c = it.next();
                Shard in = pb.layer(c.layer());
                if (in == null) { // its layer died mid-dig
                    it.remove();
                    continue;
                }
                long duration = Math.max(1, c.end() - c.start());
                int stage = (int) Math.min(9, (pb.tick() - c.start()) * 10 / duration);
                byte wire = pb.tick() >= c.end() ? (byte) -1 : (byte) stage; // -1 clears (the break/cancel arrived)
                in.broadcast(new BlockBreakAnimationPacket(c.ghostId(), new Vec(c.x(), c.y(), c.z()), wire));
                if (pb.tick() >= c.end()) it.remove();
            }
        }

        // reverse re-entry: a break-ended dig's window closes on a BLOCK op, so no dig op crosses when the
        // timeline backs into it - re-raise the crack from the window index and it animates 9 -> 0
        private void reraise(Playback pb) {
            if (windows == null) {
                windows = new ArrayList<>();
                List<Recorded<Channels.Dig>> digs = pb.ops(Channels.DIGS);
                for (int i = 0; i < digs.size(); i++) {
                    Recorded<Channels.Dig> op = digs.get(i);
                    if (op.op().start()) {
                        windows.add(new Window(op.op(), op.tick(),
                                end(pb, op, i + 1, after(pb.ops(Channels.BLOCKS), op.tick()))));
                    }
                }
            }
            for (Window w : windows) {
                if (pb.tick() < w.from() || pb.tick() >= w.to()) continue;
                Ghost digger = pb.ghost(w.dig().digger());
                if (digger == null) continue;
                boolean present = cracks.stream().anyMatch(c -> c.layer() == w.dig().layer()
                        && c.x() == w.dig().x() && c.y() == w.dig().y() && c.z() == w.dig().z());
                if (!present) {
                    cracks.add(new Crack(w.dig().layer(), digger.getEntityId(),
                            w.dig().x(), w.dig().y(), w.dig().z(), w.from(), w.to()));
                }
            }
        }

        private static int after(List<? extends Recorded<?>> ops, long tick) {
            int lo = 0, hi = ops.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ops.get(mid).tick() <= tick) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        @Override
        public boolean settled() {
            return cracks.isEmpty();
        }

        /** When this dig visibly ends: its cancel, the block breaking, or a 3s fallback - by op-log lookahead. */
        private long end(Playback pb, Recorded<Channels.Dig> op) {
            return end(pb, op, pb.cursor(Channels.DIGS), pb.cursor(Channels.BLOCKS));
        }

        private long end(Playback pb, Recorded<Channels.Dig> op, int digFrom, int blockFrom) {
            Channels.Dig start = op.op();
            long end = op.tick() + 60;
            List<Recorded<Channels.Dig>> digs = pb.ops(Channels.DIGS);
            for (int i = digFrom; i < digs.size(); i++) {
                Channels.Dig d = digs.get(i).op();
                if (!d.start() && d.layer() == start.layer()
                        && d.x() == start.x() && d.y() == start.y() && d.z() == start.z()) {
                    end = Math.min(end, digs.get(i).tick());
                    break;
                }
            }
            List<Recorded<Channels.BlockOp>> blocks = pb.ops(Channels.BLOCKS);
            for (int i = blockFrom; i < blocks.size(); i++) {
                Channels.BlockOp b = blocks.get(i).op();
                if (b.layer() == start.layer()
                        && b.x() == start.x() && b.y() == start.y() && b.z() == start.z()) {
                    end = Math.min(end, blocks.get(i).tick());
                    break;
                }
            }
            return Math.max(end, op.tick() + 1);
        }
    }
}
