package io.github.term4.archipelago.world;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.command.builder.Command;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.DeclareCommandsPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World-scoped commands: condition follows membership, the client command list re-sends on world changes. */
class WorldCommandsTest extends HeadlessServerTest {

    @Test
    void commandScopesToTheShardAndRefreshesOnMembershipChanges() {
        instance.loadChunk(0, 0).join();
        Shard arena = new Shard(instance);
        Command cmd = new Command("arena-only");
        Runnable unregister = WorldCommands.register(cmd, w -> w == arena);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(0.5, 42, 0.5), "CmdViewer");
        try {
            var condition = cmd.getCondition();
            assertFalse(condition.canUse(viewer.player, null), "outside the shard the command is hidden");

            viewer.sent.clear();
            arena.join(viewer.player);
            assertTrue(condition.canUse(viewer.player, null), "members see the command");
            assertFalse(viewer.sent(DeclareCommandsPacket.class).isEmpty(), "join re-sends the command list");

            viewer.sent.clear();
            arena.leave(viewer.player);
            assertFalse(condition.canUse(viewer.player, null), "leaving hides it again");
            assertFalse(viewer.sent(DeclareCommandsPacket.class).isEmpty(), "leave re-sends the command list");
        } finally {
            viewer.player.remove();
            unregister.run();
        }
    }

    @Test
    void composesWithAnExistingCondition() {
        instance.loadChunk(0, 0).join();
        Shard arena = new Shard(instance);
        Command cmd = new Command("arena-admin");
        cmd.setCondition((sender, s) -> false); // an app permission gate that never passes
        Runnable unregister = WorldCommands.register(cmd, w -> w == arena);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(0.5, 42, 0.5), "CmdAdmin");
        try {
            arena.join(viewer.player);
            assertFalse(cmd.getCondition().canUse(viewer.player, null), "the existing condition still gates");
        } finally {
            viewer.player.remove();
            unregister.run();
        }
    }
}
