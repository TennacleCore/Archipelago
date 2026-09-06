package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.LivingEntityMeta;
import org.jetbrains.annotations.NotNull;

final class ActApplier implements Applier<Channels.Act> {
    private final HeldOps<Channels.Act> held = new HeldOps<>(true);

    @Override
    public void tick(@NotNull Playback pb) {
        held.tick(pb, this::apply); // a held op re-enters apply past the cursor, so the gate passes it
    }

    @Override
    public boolean settled() {
        return held.settled();
    }

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Act> op) {
        // the hurt flash rides the same one-tick presentation latency as FX, both directions (damage
        // must not appear before its arrow lands / the fall ends)
        if (op.op().kind() == Channels.HURT && held.hold(pb, op)) return;
        String player = op.op().player();
        Ghost ghost = pb.ghost(player);
        int kind = op.op().kind();
        if (ghost == null && kind != Channels.RESPAWN && kind != Channels.HIDDEN && kind != Channels.SHOWN
                && kind != Channels.GONE && kind != Channels.LAYER) return;
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
            // they walked out: the scene loses them, and nothing is held shut - their next move op, whenever
            // they come back, raises them again
            case Channels.GONE -> pb.dropGhost(player);
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
            // back before they left: whatever a later SHOWN shut stays open, and the reverse move op re-raises them
            case Channels.GONE -> {
                pb.show(player);
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
