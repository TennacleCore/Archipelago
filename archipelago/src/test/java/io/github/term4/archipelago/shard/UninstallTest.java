package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Install was one-way: each seam attached to the global handler and nothing detached it, so a hook outlived
 * the thing that made it. These check the way back exists, removes something, and can be walked twice.
 */
class UninstallTest extends HeadlessServerTest {

    private static boolean attached(String name) {
        return MinecraftServer.getGlobalEventHandler().getChildren().stream()
                .anyMatch(n -> name.equals(n.getName()));
    }

    private static void assertRoundTrip(String node, Runnable install, Runnable uninstall) {
        install.run();
        assertTrue(attached(node), node + " is attached after install");
        uninstall.run();
        assertFalse(attached(node), node + " is gone after uninstall");
        install.run(); // the latch has to clear too, or a re-install silently does nothing
        assertTrue(attached(node), node + " comes back on a re-install");
    }

    @Test
    void theWorldSeamDetaches() {
        assertRoundTrip("archipelago:worlds", ShardWorlds::install, ShardWorlds::uninstall);
    }

    @Test
    void theManagerListenerDetaches() {
        assertRoundTrip("archipelago:shard-manager", ShardManager::new, ShardManager::uninstall);
    }

    /**
     * The tab list keeps its listeners on the global node itself rather than a child, because their position
     * among the other out-event listeners matters. So this checks the filter's effect, not the tree.
     */
    @Test
    void theTabFilterStopsFiltering() {
        ShardTabList.install();
        Shard mine = new Shard(instance);
        Shard theirs = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(1200.5, 64, 1200.5), "UninstallViewer");
        FakePlayer subject = FakePlayer.connect(instance, new Pos(1202.5, 64, 1200.5), "UninstallSubject");
        try {
            mine.join(viewer.player);
            theirs.join(subject.player);
            assertFalse(ShardTabList.listed(viewer.player, subject.player), "scopes really do exclude each other");

            PlayerPacketOutEvent filtered = infoAbout(viewer, subject);
            EventDispatcher.call(filtered);
            assertTrue(filtered.isCancelled(), "installed, the out-of-scope entry is held back");

            ShardTabList.uninstall();
            PlayerPacketOutEvent unfiltered = infoAbout(viewer, subject);
            EventDispatcher.call(unfiltered);
            assertFalse(unfiltered.isCancelled(), "uninstalled, the same packet goes straight through");
        } finally {
            ShardTabList.install(); // shared JVM: leaving it off would change every later test class
            viewer.player.remove();
            subject.player.remove();
        }
    }

    private static PlayerPacketOutEvent infoAbout(FakePlayer viewer, FakePlayer subject) {
        PlayerInfoUpdatePacket.Entry entry = new PlayerInfoUpdatePacket.Entry(
                subject.player.getUuid(), subject.player.getUsername(), List.of(), true, 0,
                GameMode.SURVIVAL, null, null, 0, true);
        return new PlayerPacketOutEvent(viewer.player, new PlayerInfoUpdatePacket(
                EnumSet.of(PlayerInfoUpdatePacket.Action.ADD_PLAYER), entry));
    }
}
