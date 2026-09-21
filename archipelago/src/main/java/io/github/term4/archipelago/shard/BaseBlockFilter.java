package io.github.term4.archipelago.shard;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The base instance broadcasts its own block writes to every chunk viewer, which includes the members of every
 * co-located shard. A viewer whose chain masks that position must not see it: the mask is the whole point of a
 * shard, and the stale render (a solid block where the shard says air) survives until the next view swap.
 */
final class BaseBlockFilter {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private BaseBlockFilter() {}

    static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        EventNode<Event> node = EventNode.all("archipelago:base-blocks");
        node.addListener(PlayerPacketOutEvent.class, BaseBlockFilter::filter);
        MinecraftServer.getGlobalEventHandler().addChild(node);
    }

    // every block packet Archipelago emits is already the viewer's chain view; only foreign writes need filtering,
    // and a send from inside the out-event would otherwise re-enter this listener
    private static final ThreadLocal<Boolean> SENDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Runs {@code send} as an Archipelago block send: the filter leaves its packets alone. */
    static void ownSend(Runnable send) {
        if (SENDING.get()) { send.run(); return; }
        SENDING.set(Boolean.TRUE);
        try {
            send.run();
        } finally {
            SENDING.remove();
        }
    }

    private static void filter(PlayerPacketOutEvent e) {
        if (SENDING.get()) return;
        SendablePacket sendable = e.getPacket();
        if (!(sendable instanceof BlockChangePacket) && !(sendable instanceof MultiBlockChangePacket)) return;
        Player player = e.getPlayer();
        Shard view = ShardView.of(player).blocks();
        if (view == null) return; // a base viewer sees the base, which is what this packet carries

        ServerPacket packet = SendablePacket.extractServerPacket(ConnectionState.PLAY, sendable);
        if (packet instanceof BlockChangePacket b) {
            // the viewer's chain already shows its own block here and their client renders it: drop the base's word
            if (view.overlaidAt(b.blockPosition().blockX(), b.blockPosition().blockY(), b.blockPosition().blockZ()) != null) {
                e.setCancelled(true);
            }
        } else if (packet instanceof MultiBlockChangePacket m) {
            long section = m.chunkSectionPosition();
            int sx = (int) (section >> 42), sy = (int) (section << 44 >> 44), sz = (int) (section << 22 >> 42);
            List<Long> kept = new ArrayList<>(m.blocks().length);
            for (long entry : m.blocks()) {
                int index = (int) (entry & 0xFFF);
                if (view.overlaidAt((sx << 4) + ((index >> 8) & 0xF), (sy << 4) + (index & 0xF),
                        (sz << 4) + ((index >> 4) & 0xF)) == null) {
                    kept.add(entry);
                }
            }
            if (kept.size() == m.blocks().length) return;
            e.setCancelled(true);
            if (kept.isEmpty()) return;
            long[] packed = new long[kept.size()];
            for (int i = 0; i < packed.length; i++) packed[i] = kept.get(i);
            ownSend(() -> player.sendPacket(new MultiBlockChangePacket(sx, sy, sz, packed)));
        }
    }
}
