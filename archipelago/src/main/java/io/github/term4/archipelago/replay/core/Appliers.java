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
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.PlayerSkin;
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
            if (index == null) {
                index = new HashMap<>();
                for (Recorded<T> r : pb.ops(channel)) {
                    index.computeIfAbsent(key.apply(r.op()), k -> new ArrayList<>()).add(r);
                }
            }
            List<Recorded<T>> list = index.get(key.apply(op.op()));
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
                pb.ghost(m.player(), ghost);
                pb.layerFor(m.player()).spawn(ghost, pos); // rises straight into its recorded layer
            } else {
                ghost.grounded(m.ground());
                ghost.teleport(pos);
                ghost.sendPacketToViewers(new EntityHeadLookPacket(ghost.getEntityId(), m.yaw()));
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

        private record Pending(Channels.Spawn spawn, long since) {}

        private final List<Pending> pending = new ArrayList<>();

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Spawn> op) {
            if (!spawnNow(pb, op.op(), false)) pending.add(new Pending(op.op(), pb.tick()));
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Spawn> op) {
            pending.removeIf(p -> p.spawn().seq() == op.op().seq()); // never materialize an un-crossed spawn
            Entity gone = pb.untrack(op.op().seq());
            if (gone != null) gone.remove(); // it didn't exist before this
        }

        @Override
        public void tick(@NotNull Playback pb) {
            var it = pending.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (spawnNow(pb, p.spawn(), pb.tick() - p.since() >= OWNER_WAIT_TICKS)) it.remove();
            }
        }

        @Override
        public boolean settled() {
            return pending.isEmpty();
        }

        // shared with RemoveApplier's reverse crossing (an un-removed twin re-runs its spawn)
        static boolean spawnNow(Playback pb, Channels.Spawn spawn, boolean ownerless) {
            if (pb.earlyRemoved(spawn.seq())) return true; // its remove already played; never materialize
            Shard in = pb.layer(spawn.layer());
            if (in == null) return true; // its layer never rose (seek-folded away): consume
            Ghost owner = spawn.shooter().isEmpty() ? null : pb.ghost(spawn.shooter());
            if (!spawn.shooter().isEmpty() && (owner == null || owner.getInstance() == null) && !ownerless) return false;
            Entity entity = pb.spawnMode() == SpawnMode.VISUAL || spawn.desc().keySet().isEmpty()
                    ? null : EntityDescriptors.reviveDescriptor(spawn.desc());
            if (entity == null) entity = Visuals.entity(spawn.visual(), owner); // visual fallback: no libs needed
            if (entity == null) return true;
            for (SpawnHook hook : Replay.spawnHooks()) hook.enhance(entity, owner, pb);
            pb.track(spawn.seq(), entity);
            in.spawn(entity, new Pos(spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()));
            return true;
        }
    }

    static final class EntityMoveApplier implements Applier<Channels.EntityMove> {
        private record Pin(Pos pos, long tick) {}

        private final Map<Integer, Pin> lastTrack = new HashMap<>();
        private final Set<Integer> reversedTwins = new HashSet<>();
        private final LookBack<Integer, Channels.EntityMove> history =
                new LookBack<>(Channels.ENTITY_MOVES, Channels.EntityMove::seq);

        // a per-tick teleport fights the client's LOCAL sim of the entity (items especially) - chop. Inside
        // the band a revived twin's own physics drives the wire (relative moves + velocity, the client
        // glides) and the track only corrects real divergence. Stand-ins have no sim: any drift is wrong.
        private static double toleranceSq(Entity e) {
            return Visuals.standIn(e) ? 0.0025 : 0.0625;
        }

        // a real item's wire is nearly SILENT (spawn + a couple of syncs; the client sims it locally) - the
        // twin's must be too. Per-tick corrections of ANY kind (teleports or setVelocity, which always
        // broadcasts) re-kick the client's sim 20x/s: the measured chop. So inside this envelope the twin's
        // own physics IS the wire; the track hard-resyncs only on real divergence, and the rest pin (below,
        // tighter) trues up the final position once the track goes silent.
        private static final double RESYNC_SQ = 1.0;
        /** Silent-track ticks before the rest pin may fire (a stall is not a rest). */
        private static final long REST_GRACE_TICKS = 3;

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityMove> op) {
            drive(pb, op.op().seq(), new Pos(op.op().x(), op.op().y(), op.op().z(), op.op().yaw(), op.op().pitch()),
                    false);
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityMove> op) {
            Recorded<Channels.EntityMove> prev = history.before(pb, op);
            if (prev != null) { // reverse is non-physical (a sim can't fall upwards): plainly track-driven
                drive(pb, prev.op().seq(), new Pos(prev.op().x(), prev.op().y(), prev.op().z(),
                        prev.op().yaw(), prev.op().pitch()), true);
                return;
            }
            for (Recorded<Channels.Spawn> spawn : pb.ops(Channels.SPAWNS)) { // before its first track point
                if (spawn.op().seq() == op.op().seq()) {
                    drive(pb, op.op().seq(), new Pos(spawn.op().x(), spawn.op().y(), spawn.op().z(),
                            spawn.op().yaw(), spawn.op().pitch()), true);
                    return;
                }
            }
        }

        private void drive(Playback pb, int seq, Pos target, boolean hard) {
            Entity e = pb.entity(seq);
            // the pin advances even while the async spawn hasn't landed: with a stale spawn-tick anchor,
            // the rest pin yanked a freshly revived twin back to its spawn point (zero velocity + teleport
            // = the 1.8 frozen-item chop, capture-diagnosed)
            Pin previous = lastTrack.put(seq, new Pin(target, pb.tick()));
            if (e == null || e.getInstance() == null) return; // the next track op (or the pin) places it
            if (Visuals.standIn(e)) {
                e.teleport(target);
                return;
            }
            if (hard) {
                // reverse is non-physical (a sim can't fall upwards): the track drives, but through the
                // tracker's RELATIVE-MOVE wire - what a live entity emits, so clients lerp it; a teleport
                // packet per op reads as snapping. Entry parks the twin's own physics ONCE (no per-tick
                // velocity packets) so only the track moves it
                if (reversedTwins.add(seq)) {
                    e.setVelocity(Vec.ZERO);
                    e.setNoGravity(true);
                }
                e.refreshPosition(target, false, true);
                return;
            }
            if (reversedTwins.remove(seq)) { // forward again: free-run resumes, re-seeded along the track
                e.setNoGravity(false);
                if (previous != null && pb.tick() > previous.tick()) {
                    long dt = pb.tick() - previous.tick();
                    e.setVelocity(target.sub(previous.pos()).asVec().div(dt).mul(ServerFlag.SERVER_TICKS_PER_SECOND));
                }
                e.teleport(target);
                return;
            }
            Vec toTarget = target.sub(e.getPosition()).asVec();
            if (toTarget.lengthSquared() <= RESYNC_SQ) return; // free-run: the twin's physics is the wire
            if (previous != null && pb.tick() > previous.tick()) { // resume along the recorded path, not the stale sim
                long dt = pb.tick() - previous.tick();
                e.setVelocity(target.sub(previous.pos()).asVec().div(dt).mul(ServerFlag.SERVER_TICKS_PER_SECOND));
            }
            e.teleport(target);
        }

        // between track points a twin STAYS at its last one: a silent track means the real entity rested, but a
        // physics twin can drift (a zero-velocity revived arrow falls out of its wall) - pin it back
        @Override
        public void tick(@NotNull Playback pb) {
            var it = lastTrack.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                Entity e = pb.entity(entry.getKey());
                if (e == null || e.isRemoved()) {
                    reversedTwins.remove(entry.getKey());
                    it.remove();
                    continue;
                }
                Pin pin = entry.getValue();
                // a track can stall a tick or two WITHOUT the entity resting: the RECORDED item's own async
                // spawn holds its position (no delta, no ops) while the revived twin already flies - pinning
                // then sends the zero+snap that kills a 1.8 client's local item sim (capture-diagnosed).
                // only a track silent past the grace is a real rest
                if (pb.tick() - pin.tick() < REST_GRACE_TICKS) continue;
                if (e.getInstance() != null && e.getPosition().distanceSquared(pin.pos()) > toleranceSq(e)) {
                    e.setVelocity(Vec.ZERO); // stop the drift, not just the symptom
                    e.teleport(pin.pos());
                }
            }
        }
    }

    static final class BlockApplier implements Applier<Channels.BlockOp> {
        private record At(int layer, int x, int y, int z) {}

        private final LookBack<At, Channels.BlockOp> history = new LookBack<>(Channels.BLOCKS,
                b -> new At(b.layer(), b.x(), b.y(), b.z()));

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.BlockOp> op) {
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
            // no earlier op: back to the t0 snapshot (root layer) or the parent view (the entry clears)
            Block t0 = b.layer() == 0 ? pb.t0Block(pos) : null;
            if (t0 != null) {
                in.pinBlock(pos, t0);
            } else if (in.instance().isChunkLoaded(pos)) {
                in.setBlock(pos, in.parent() != null ? in.parent().getBlock(pos) : in.instance().getBlock(pos));
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
        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Act> op) {
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
        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Remove> op) {
            Entity gone = pb.untrack(op.op().seq());
            if (gone == null) {
                pb.earlyRemove(op.op().seq()); // beat a deferred spawn: that twin must never appear
                return;
            }
            Ghost collector = pb.ghost(op.op().collector());
            if (collector != null) { // the recorded pickup swoosh toward the ghost
                int amount = gone instanceof ItemEntity item ? item.getItemStack().amount() : 1;
                gone.sendPacketToViewers(new CollectItemPacket(gone.getEntityId(), collector.getEntityId(), amount));
            }
            gone.remove();
        }

        @Override
        public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Remove> op) {
            pb.clearEarlyRemove(op.op().seq());
            if (pb.entity(op.op().seq()) != null) return;
            for (Recorded<Channels.Spawn> spawn : pb.ops(Channels.SPAWNS)) { // it exists again: re-run its spawn
                if (spawn.op().seq() == op.op().seq()) {
                    SpawnApplier.spawnNow(pb, spawn.op(), true); // mid-timeline: the ghost is long alive
                    return;
                }
            }
        }
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
