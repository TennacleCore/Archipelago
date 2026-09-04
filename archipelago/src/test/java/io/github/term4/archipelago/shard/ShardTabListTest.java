package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tab-list scoping: names follow the viewer's world/scope, membership changes re-sync live. */
class ShardTabListTest extends HeadlessServerTest {

    @Test
    void scopesFollowMembershipAndResyncLive() {
        instance.loadChunk(0, 0).join();
        ShardTabList.install();
        Shard game = new Shard(instance);
        FakePlayer inShard = FakePlayer.connect(instance, new Pos(0.5, 42, 0.5), "TabInside");
        FakePlayer outside = FakePlayer.connect(instance, new Pos(2.5, 42, 0.5), "TabOutside");
        try {
            inShard.sent.clear();
            outside.sent.clear();
            game.join(inShard.player);

            // WORLD scope: the member no longer lists the base player, and vice versa
            assertFalse(ShardTabList.listed(inShard.player, outside.player));
            assertFalse(ShardTabList.listed(outside.player, inShard.player));
            assertTrue(inShard.sent(PlayerInfoRemovePacket.class).stream()
                            .anyMatch(p -> p.uuids().contains(outside.player.getUuid())),
                    "joining re-syncs the member's own list (remove the outsider)");
            assertTrue(outside.sent(PlayerInfoRemovePacket.class).stream()
                            .anyMatch(p -> p.uuids().contains(inShard.player.getUuid())),
                    "the outsider's list drops the member");

            // INSTANCE scope: back to the vanilla list, re-synced live
            inShard.sent.clear();
            outside.sent.clear();
            game.tabScope(ShardTabList.TabScope.INSTANCE);
            assertTrue(ShardTabList.listed(inShard.player, outside.player));
            assertTrue(inShard.sent(PlayerInfoUpdatePacket.class).stream().anyMatch(p ->
                            p.entries().stream().anyMatch(en -> en.uuid().equals(outside.player.getUuid()))),
                    "scope change re-adds the outsider to the member's list");
            // viewer-side scope: the outsider still doesn't list the member
            assertFalse(ShardTabList.listed(outside.player, inShard.player));

            // TREE scope: a child-layer member lists the parent's member
            game.tabScope(ShardTabList.TabScope.WORLD);
            Shard layer = new Shard(game);
            FakePlayer inLayer = FakePlayer.connect(instance, new Pos(4.5, 42, 0.5), "TabLayer");
            try {
                layer.join(inLayer.player);
                assertFalse(ShardTabList.listed(inLayer.player, inShard.player), "WORLD: layers are separate worlds");
                layer.tabScope(ShardTabList.TabScope.TREE);
                assertTrue(ShardTabList.listed(inLayer.player, inShard.player), "TREE: the whole root tree lists");
                assertFalse(ShardTabList.listed(inShard.player, inLayer.player),
                        "asymmetric: the parent's member stays on WORLD");
            } finally {
                inLayer.player.remove();
            }

            // leaving restores the base view both ways; the info ADD must precede the spawn the viewable
            // rule sends, or the client drops the spawn (invisible-until-relog)
            inShard.sent.clear();
            outside.sent.clear();
            game.leave(inShard.player);
            assertTrue(ShardTabList.listed(inShard.player, outside.player));
            assertTrue(inShard.sent(PlayerInfoUpdatePacket.class).stream().anyMatch(p ->
                    p.entries().stream().anyMatch(en -> en.uuid().equals(outside.player.getUuid()))));
            assertAddBeforeSpawn(outside, inShard.player);
        } finally {
            inShard.player.remove();
            outside.player.remove();
        }
    }

    /** A spectator VIEWING a game lists its players out of the box (WORLD scope rides canSee, which honors views). */
    @Test
    void spectatorListsTheGameItViews() {
        instance.loadChunk(0, 0).join();
        ShardTabList.install();
        Shard game = new Shard(instance);
        FakePlayer member = FakePlayer.connect(instance, new Pos(0.5, 42, 0.5), "TabMember");
        FakePlayer spectator = FakePlayer.connect(instance, new Pos(2.5, 42, 0.5), "TabSpec");
        try {
            game.join(member.player);
            assertFalse(ShardTabList.listed(spectator.player, member.player), "not spectating yet");

            spectator.sent.clear();
            ShardView.set(spectator.player, ShardView.of(game));
            assertTrue(ShardTabList.listed(spectator.player, member.player), "the view grants the game's names");
            assertTrue(spectator.sent(PlayerInfoUpdatePacket.class).stream().anyMatch(p ->
                            p.entries().stream().anyMatch(en -> en.uuid().equals(member.player.getUuid()))),
                    "the view change re-syncs the spectator's list");
            assertAddBeforeSpawn(spectator, member.player);
            assertFalse(ShardTabList.listed(member.player, spectator.player), "the member doesn't list the spectator");

            // the override seam: members list their spectators (the javadoc recipe), live-resynced
            member.sent.clear();
            Runnable unregister = ShardTabList.rule((viewer, subject) ->
                    Shard.of(viewer) != null && ShardView.explicit(subject) != null
                            && ShardView.of(subject).blocks() == Shard.of(viewer) ? true : null);
            assertTrue(ShardTabList.listed(member.player, spectator.player), "the rule force-lists the spectator");
            assertTrue(member.sent(PlayerInfoUpdatePacket.class).stream().anyMatch(p ->
                            p.entries().stream().anyMatch(en -> en.uuid().equals(spectator.player.getUuid()))),
                    "registering the rule re-syncs everyone");
            unregister.run();
            assertFalse(ShardTabList.listed(member.player, spectator.player), "unregistering restores the scope");

            // force-hide beats even INSTANCE scope
            Runnable hide = ShardTabList.rule((viewer, subject) -> subject == member.player ? false : null);
            game.tabScope(ShardTabList.TabScope.INSTANCE);
            assertFalse(ShardTabList.listed(spectator.player, member.player));
            hide.run();
        } finally {
            ShardView.set(spectator.player, null);
            member.player.remove();
            spectator.player.remove();
        }
    }
    /**
     * The library decides "legacy?" itself: a viewer whose protocol nobody reported reads as the server's own
     * build, and the app can move that fallback without a per-viewer source.
     */
    @Test
    void anUnknownProtocolReadsAsTheServersOwnBuild() {
        instance.loadChunk(0, 0).join();
        ShardTabList.install();
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(6.5, 42, 0.5), "TabProto");
        String sort = "§a" + viewer.player.getUsername();
        try {
            viewer.player.setTag(ShardTabList.SORT_NAME, sort);
            assertEquals(viewer.player.getUsername(), listedName(viewer), "no source wired: modern");

            ShardTabList.viewerProtocol(p -> -1); // the proxy handshake has not landed yet
            assertEquals(viewer.player.getUsername(), listedName(viewer), "unknown still reads modern");

            ShardTabList.protocolWhenUnknown(47);
            assertEquals(sort, listedName(viewer), "the app moves the fallback for a legacy-only server");

            ShardTabList.protocolWhenUnknown(MinecraftServer.PROTOCOL_VERSION);
            ShardTabList.viewerProtocol(p -> 47);
            assertEquals(sort, listedName(viewer), "a known 1.8 viewer sorts under the sort name");
        } finally {
            ShardTabList.viewerProtocol(p -> -1);
            ShardTabList.protocolWhenUnknown(MinecraftServer.PROTOCOL_VERSION);
            viewer.player.remove();
        }
    }

    /** The /tablog buffer is per-viewer: it must not outlive the connection that fed it. */
    @Test
    void theTabPacketLogDiesWithTheConnection() {
        instance.loadChunk(0, 0).join();
        ShardTabList.install();
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(8.5, 42, 0.5), "TabLog");
        ShardTabList.resync(viewer.player);
        assertFalse(ShardTabList.history(viewer.player).isEmpty(), "tab packets are logged while online");
        viewer.player.remove();
        assertTrue(ShardTabList.history(viewer.player).isEmpty(), "and the buffer goes with the disconnect");
    }

    // the viewer's own row as it goes out to them: the name a 1.8 client would sort under
    private static String listedName(FakePlayer viewer) {
        viewer.sent.clear();
        ShardTabList.resync(viewer.player);
        return viewer.sent(PlayerInfoUpdatePacket.class).stream()
                .flatMap(p -> p.entries().stream())
                .filter(e -> e.uuid().equals(viewer.player.getUuid()))
                .reduce((a, b) -> b).orElseThrow().username();
    }

    /** The modern-client spawn contract: a player spawn without an already-received info entry is DROPPED. */
    private static void assertAddBeforeSpawn(FakePlayer viewer, net.minestom.server.entity.Player subject) {
        var list = java.util.List.copyOf(viewer.sent);
        int add = -1, spawn = -1;
        for (int i = 0; i < list.size(); i++) {
            var pk = net.minestom.server.network.packet.server.SendablePacket
                    .extractServerPacket(net.minestom.server.network.ConnectionState.PLAY, list.get(i));
            if (add < 0 && pk instanceof PlayerInfoUpdatePacket u
                    && u.entries().stream().anyMatch(en -> en.uuid().equals(subject.getUuid()))) add = i;
            if (spawn < 0 && pk instanceof net.minestom.server.network.packet.server.play.SpawnEntityPacket se
                    && se.entityId() == subject.getEntityId()) spawn = i;
        }
        assertTrue(spawn >= 0, "the viewable rule re-add spawned the subject");
        assertTrue(add >= 0 && add < spawn, "the info ADD precedes the spawn (add@" + add + " spawn@" + spawn + ")");
    }
}
