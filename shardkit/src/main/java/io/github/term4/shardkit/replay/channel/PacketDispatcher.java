package io.github.term4.shardkit.replay.channel;

import io.github.term4.shardkit.replay.Recording;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.server.ServerPacket;
import org.jetbrains.annotations.NotNull;

/** The Packet source: outgoing packets to the recorded shard's members. */
public abstract class PacketDispatcher extends EventDispatcher {

    protected abstract void packet(@NotNull Player player, @NotNull ServerPacket packet, @NotNull Recording recording);

    @Override
    protected final void listen(@NotNull EventNode<Event> node, @NotNull Recording recording) {
        node.addListener(PlayerPacketOutEvent.class, e -> {
            if (recording.covers(e.getPlayer())) packet(e.getPlayer(), e.getPacket(), recording);
        });
    }
}
