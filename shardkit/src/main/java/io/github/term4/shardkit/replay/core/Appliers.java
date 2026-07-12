package io.github.term4.shardkit.replay.core;

import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.polar.EntityDescriptors;
import io.github.term4.shardkit.polar.Nbt;
import io.github.term4.shardkit.replay.Ghost;
import io.github.term4.shardkit.replay.Playback;
import io.github.term4.shardkit.replay.Replay;
import io.github.term4.shardkit.replay.SpawnMode;
import io.github.term4.shardkit.replay.Visuals;
import io.github.term4.shardkit.replay.channel.Applier;
import io.github.term4.shardkit.replay.channel.Recorded;
import io.github.term4.shardkit.replay.channel.SpawnHook;
import net.kyori.adventure.nbt.BinaryTag;
import net.minestom.server.command.builder.arguments.minecraft.ArgumentBlockState;
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
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket;
import net.minestom.server.network.packet.server.play.CollectItemPacket;
import net.minestom.server.network.packet.server.play.EntityHeadLookPacket;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The core channels' playback side. */
final class Appliers {

    private Appliers() {}

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
    }

    static final class EntityLayerApplier implements Applier<Channels.EntityLayer> {
        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityLayer> op) {
            Shard to = pb.layer(op.op().layer());
            Entity twin = pb.entity(op.op().seq());
            if (to != null && twin != null) to.transfer(twin);
        }
    }

    static final class SkinApplier implements Applier<Channels.Skin> {
        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Skin> op) {
            pb.skin(op.op().player(), new PlayerSkin(op.op().textures(), op.op().signature()));
        }
    }

    static final class MoveApplier implements Applier<Channels.Move> {
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
    }

    static final class SpawnApplier implements Applier<Channels.Spawn> {
        // clients DISCARD a projectile whose wire owner they don't know yet: a shooter-owned spawn waits for
        // its ghost to be world-ready (ghost spawns are async), then gives up and spawns ownerless
        private static final long OWNER_WAIT_TICKS = 20;

        private record Pending(Channels.Spawn spawn, long since) {}

        private final List<Pending> pending = new ArrayList<>();

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Spawn> op) {
            if (!trySpawn(pb, op.op(), false)) pending.add(new Pending(op.op(), pb.tick()));
        }

        @Override
        public void tick(@NotNull Playback pb) {
            var it = pending.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (trySpawn(pb, p.spawn(), pb.tick() - p.since() >= OWNER_WAIT_TICKS)) it.remove();
            }
        }

        @Override
        public boolean settled() {
            return pending.isEmpty();
        }

        private boolean trySpawn(Playback pb, Channels.Spawn spawn, boolean ownerless) {
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

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.EntityMove> op) {
            Entity e = pb.entity(op.op().seq());
            if (e == null || e.getInstance() == null) return; // spawn is async; the next track op corrects
            Pos target = new Pos(op.op().x(), op.op().y(), op.op().z(), op.op().yaw(), op.op().pitch());
            lastTrack.put(op.op().seq(), new Pin(target, pb.tick()));
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
                    it.remove();
                    continue;
                }
                Pin pin = entry.getValue();
                if (pin.tick() == pb.tick()) continue; // this tick's track op already placed it
                if (e.getInstance() != null && e.getPosition().distanceSquared(pin.pos()) > 0.0025) {
                    e.setVelocity(Vec.ZERO); // stop the drift, not just the symptom
                    e.teleport(pin.pos());
                }
            }
        }
    }

    static final class BlockApplier implements Applier<Channels.BlockOp> {
        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.BlockOp> op) {
            Channels.BlockOp b = op.op();
            Shard in = pb.layer(b.layer());
            if (in != null) in.setBlock(new Vec(b.x(), b.y(), b.z()), ArgumentBlockState.staticParse(b.block()));
        }
    }

    static final class EquipApplier implements Applier<Channels.Equip> {
        private final Map<String, EnumSet<EquipmentSlot>> worn = new HashMap<>();

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
    }

    static final class DigApplier implements Applier<Channels.Dig> {
        private record Crack(int layer, int ghostId, int x, int y, int z, long start, long end) {}

        private final List<Crack> cracks = new ArrayList<>();

        @Override
        public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Dig> op) {
            if (!op.op().start()) return; // cancels only bound the lookahead below
            Ghost digger = pb.ghost(op.op().digger());
            if (digger != null) cracks.add(new Crack(op.op().layer(), digger.getEntityId(),
                    op.op().x(), op.op().y(), op.op().z(), op.tick(), end(pb, op)));
        }

        @Override
        public void tick(@NotNull Playback pb) {
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

        @Override
        public boolean settled() {
            return cracks.isEmpty();
        }

        /** When this dig visibly ends: its cancel, the block breaking, or a 3s fallback - by op-log lookahead. */
        private long end(Playback pb, Recorded<Channels.Dig> op) {
            Channels.Dig start = op.op();
            long end = op.tick() + 60;
            List<Recorded<Channels.Dig>> digs = pb.ops(Channels.DIGS);
            for (int i = pb.cursor(Channels.DIGS); i < digs.size(); i++) {
                Channels.Dig d = digs.get(i).op();
                if (!d.start() && d.layer() == start.layer()
                        && d.x() == start.x() && d.y() == start.y() && d.z() == start.z()) {
                    end = Math.min(end, digs.get(i).tick());
                    break;
                }
            }
            List<Recorded<Channels.BlockOp>> blocks = pb.ops(Channels.BLOCKS);
            for (int i = pb.cursor(Channels.BLOCKS); i < blocks.size(); i++) {
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
