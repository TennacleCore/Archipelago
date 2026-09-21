package io.github.term4.archipelago.replay.channel;

import io.github.term4.archipelago.replay.Recording;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.server.ServerPacket;
import org.jetbrains.annotations.NotNull;

/** The Packet source: outgoing packets to the recorded shard's members. */
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minestom.server.MinecraftServer;
public abstract class PacketDispatcher extends EventDispatcher {

    protected abstract void packet(@NotNull Player player, @NotNull ServerPacket packet, @NotNull Recording recording);

    // one listener for the JVM, not one per recording: with any listener present Minestom allocates an event
    // for every packet write, and K recordings meant K dispatches and K covers() walks on top
    private static final Map<PacketDispatcher, Recording> LIVE = new ConcurrentHashMap<>();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override
    protected final void listen(@NotNull EventNode<Event> node, @NotNull Recording recording) {
        LIVE.put(this, recording);
        if (INSTALLED.compareAndSet(false, true)) {
            EventNode<Event> shared = EventNode.all("archipelago:replay-packets");
            shared.addListener(PlayerPacketOutEvent.class, e -> {
                if (LIVE.isEmpty()) return;
                LIVE.forEach((dispatcher, live) -> {
                    if (live.covers(e.getPlayer())) dispatcher.packet(e.getPlayer(), e.getPacket(), live);
                });
            });
            MinecraftServer.getGlobalEventHandler().addChild(shared);
        }
    }

    @Override
    protected void stopped() {
        LIVE.remove(this);
    }
}
