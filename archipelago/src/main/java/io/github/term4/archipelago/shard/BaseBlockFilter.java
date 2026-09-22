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
 *
 * <p>The test is the packet's VALUE, never who sent it. A real player's {@code sendPacket} only queues, and the
 * out event fires later on that connection's write thread, so nothing the sender marks about itself survives the
 * hop - a thread-local said "this is ours" on the shard's clock and read false on the wire, and the shard's own
 * writes were dropped as if they were the base's. A packet already carrying what the viewer's chain says at that
 * position is correct for them whoever sent it; only one that disagrees is the base painting over the mask.
 */
final class BaseBlockFilter {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static volatile EventNode<Event> node;

    private BaseBlockFilter() {}

    static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        EventNode<Event> fresh = EventNode.all("archipelago:base-blocks");
        fresh.addListener(PlayerPacketOutEvent.class, BaseBlockFilter::filter);
        MinecraftServer.getGlobalEventHandler().addChild(fresh);
        node = fresh;
    }

    /** Detaches the filter. A re-install attaches a fresh node; leaving it on is what outlived its owner. */
    static void uninstall() {
        if (!INSTALLED.compareAndSet(true, false)) return;
        EventNode<Event> attached = node;
        node = null;
        if (attached != null) MinecraftServer.getGlobalEventHandler().removeChild(attached);
    }

    // only for the re-send below, which happens inside the event on this very thread: without it the trimmed
    // packet walks back into this listener. It is NOT how an Archipelago packet is recognised - see the class doc
    private static final ThreadLocal<Boolean> RESENDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Runs {@code send} without re-entering the filter, for a send made from inside it. */
    static void ownSend(Runnable send) {
        if (RESENDING.get()) { send.run(); return; }
        RESENDING.set(Boolean.TRUE);
        try {
            send.run();
        } finally {
            RESENDING.remove();
        }
    }

    private static void filter(PlayerPacketOutEvent e) {
        if (RESENDING.get()) return;
        SendablePacket sendable = e.getPacket();
        if (!(sendable instanceof BlockChangePacket) && !(sendable instanceof MultiBlockChangePacket)) return;
        Player player = e.getPlayer();
        Shard view = ShardView.of(player).blocks();
        if (view == null) return; // a base viewer sees the base, which is what this packet carries

        ServerPacket packet = SendablePacket.extractServerPacket(ConnectionState.PLAY, sendable);
        if (packet instanceof BlockChangePacket b) {
            if (masks(view, b.blockPosition().blockX(), b.blockPosition().blockY(), b.blockPosition().blockZ(),
                    b.blockStateId())) {
                e.setCancelled(true);
            }
        } else if (packet instanceof MultiBlockChangePacket m) {
            long section = m.chunkSectionPosition();
            int sx = (int) (section >> 42), sy = (int) (section << 44 >> 44), sz = (int) (section << 22 >> 42);
            List<Long> kept = new ArrayList<>(m.blocks().length);
            for (long entry : m.blocks()) {
                int index = (int) (entry & 0xFFF);
                if (!masks(view, (sx << 4) + ((index >> 8) & 0xF), (sy << 4) + (index & 0xF),
                        (sz << 4) + ((index >> 4) & 0xF), (int) (entry >>> 12))) {
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

    /** Whether {@code state} contradicts what {@code view}'s chain shows at that block; unoverlaid never does. */
    private static boolean masks(Shard view, int x, int y, int z, int state) {
        Block overlay = view.overlaidAt(x, y, z);
        return overlay != null && overlay.stateId() != state;
    }
}
