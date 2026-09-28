package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
final class EquipApplier implements Applier<Channels.Equip> {
    private final Map<String, EnumSet<EquipmentSlot>> worn = new HashMap<>();
    private final LookBack<String, Channels.Equip> history = new LookBack<>(Channels.EQUIPS, Channels.Equip::player);

    @Override
    public void prepare(@NotNull Playback playback) {
        history.warm(playback);
    }

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
        CompoundBinaryTag items = op.op().items();
        Nbt.applyEquipment(ghost, items);
        EnumSet<EquipmentSlot> now = EnumSet.noneOf(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (items.get(slot.name()) != null) now.add(slot);
        }
        // clear only slots the ghost actually wore - a blanket AIR clear is the ViaBackwards chestplate wipe
        EnumSet<EquipmentSlot> before = worn.put(op.op().player(), now);
        if (before != null) {
            before.removeAll(now);
            for (EquipmentSlot slot : before) ghost.setEquipment(slot, ItemStack.AIR);
        }
    }
}
