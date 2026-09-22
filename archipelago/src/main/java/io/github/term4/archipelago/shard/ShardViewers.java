package io.github.term4.archipelago.shard;

import io.github.term4.polyp.world.WorldPolicy;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Who sees a shard, and how a packet reaches them. Three groups, in order: its members, observers watching from
 * outside, and the all-seeing. Each is admitted once - an observer who is also a member is not sent twice.
 *
 * <p>Only someone standing on the same base instance renders this world, so a positional packet never crosses
 * to another instance, an observer registered from elsewhere included. That test is the reason this is worth
 * being one place rather than repeated at each send.
 */
final class ShardViewers {

    private final Shard shard;

    ShardViewers(Shard shard) {
        this.shard = shard;
    }

    @NotNull Collection<@NotNull Player> players() {
        return shard.membersView;
    }

    @NotNull Collection<@NotNull Player> watchers() {
        Set<Player> allSeeing = ShardView.allSeeing();
        if (shard.observers.isEmpty() && allSeeing.isEmpty()) return shard.membersView;
        List<Player> out = new ArrayList<>(shard.members);
        forEachExtra(out::add);
        return out;
    }

    // the base instance's chunk index, filtered to this shard's members: O(what is near), not O(members)
    void forEachMemberWithin(@NotNull Point point, double range, @NotNull Consumer<Player> action) {
        shard.base.getEntityTracker().nearbyEntities(point, range, EntityTracker.Target.PLAYERS, p -> {
            if (shard.members.contains(p)) action.accept(p);
        });
    }

    /** {@link #watchers()} without the snapshot - what every send iterates. */
    void forEachWatcher(@NotNull Consumer<Player> action) {
        for (Player m : shard.members) action.accept(m);
        forEachExtra(action);
    }

    // only someone on THIS base instance renders this world, so a positional packet must never cross to
    // another instance - an observer registered from elsewhere included
    private void forEachExtra(Consumer<Player> action) {
        for (Player o : shard.observers) {
            if (o.getInstance() == shard.base && !shard.members.contains(o)) action.accept(o);
        }
        for (Player o : ShardView.allSeeing()) {
            if (o.getInstance() == shard.base && !shard.members.contains(o) && !shard.observers.contains(o)) {
                action.accept(o);
            }
        }
    }

    void broadcast(@NotNull ServerPacket packet) {
        forEachWatcher(p -> p.sendPacket(packet));
    }

    void playSound(@NotNull Sound sound, @NotNull Point pos) {
        forEachWatcher(p -> p.playSound(sound, pos.x(), pos.y(), pos.z()));
    }

    void sendToBlockViewers(SendablePacket packet) {
        forEachWatcher(p -> { if (seesBlocks(p)) p.sendPacket(packet); });
    }

    private boolean seesBlocks(Player p) {
        return p.getInstance() == shard.base && WorldPolicy.seesBlocks(p, shard);
    }

    // the same set sendToBlockViewers walks, with an early out
    boolean hasBlockViewers() {
        for (Player m : shard.members) {
            if (seesBlocks(m)) return true;
        }
        for (Player o : shard.observers) {
            if (!shard.members.contains(o) && seesBlocks(o)) return true;
        }
        for (Player o : ShardView.allSeeing()) {
            if (!shard.members.contains(o) && !shard.observers.contains(o) && seesBlocks(o)) return true;
        }
        return false;
    }

    /** {@code blocks} packed one {@link MultiBlockChangePacket} per 16-cube: a built map is thousands of positions. */
    static List<MultiBlockChangePacket> sectionPackets(Map<Point, Block> blocks) {
        record Section(int x, int y, int z) {}
        Map<Section, List<Long>> bySection = new HashMap<>();
        blocks.forEach((pos, block) -> bySection.computeIfAbsent(
                new Section(pos.blockX() >> 4, pos.blockY() >> 4, pos.blockZ() >> 4),
                k -> new ArrayList<>()).add(CoordConversion.encodeSectionBlockChange(
                pos.blockX() & 15, pos.blockY() & 15, pos.blockZ() & 15, block.stateId())));
        List<MultiBlockChangePacket> out = new ArrayList<>(bySection.size());
        bySection.forEach((section, entries) -> {
            long[] packed = new long[entries.size()];
            for (int i = 0; i < packed.length; i++) packed[i] = entries.get(i);
            out.add(new MultiBlockChangePacket(section.x(), section.y(), section.z(), packed));
        });
        return out;
    }
}
