package io.github.term4.archipelago.replay.channel;

import io.github.term4.archipelago.replay.Recording;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;

/** The Event source: a global {@link EventNode} that lives exactly as long as the recording. */
public abstract class EventDispatcher implements Dispatcher {

    private EventNode<Event> node;

    /** Add listeners; emit ops via {@code recording} (filter with {@link Recording#covers}). */
    protected abstract void listen(@NotNull EventNode<Event> node, @NotNull Recording recording);

    @Override
    public final void start(@NotNull Recording recording) {
        node = EventNode.all("archipelago:replay-" + getClass().getSimpleName() + "@" + System.identityHashCode(this));
        listen(node, recording);
        MinecraftServer.getGlobalEventHandler().addChild(node);
    }

    /** The subclass's own release, if it registered anything outside {@code node}. */
    protected void stopped() {}

    @Override
    public final void stop(@NotNull Recording recording) {
        if (node != null) MinecraftServer.getGlobalEventHandler().removeChild(node);
        stopped();
    }
}
