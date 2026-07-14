package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shard relight: chain-effective light as per-viewer UpdateLight overlays, base restored on the way out. */
class ShardLightTest extends HeadlessServerTest {

    private static void loadArea(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) instance.loadChunk(chunkX + dx, chunkZ + dz).join();
        }
    }

    @Test
    void relitShardSendsItsLightAndLeavingRestoresBase() {
        int chunkX = 3000 >> 4, chunkZ = 3000 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        shard.relight(true);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3000.5, 70, 3000.5), "LightViewer");
        try {
            shard.join(viewer.player);
            viewer.sent.clear();
            shard.setBlock(new Vec(3000, 30, 3000), Block.GLOWSTONE); // buried: the base map is pitch dark here

            List<UpdateLightPacket> lights = viewer.sent(UpdateLightPacket.class);
            UpdateLightPacket packet = lights.stream()
                    .filter(p -> p.chunkX() == chunkX && p.chunkZ() == chunkZ).findFirst().orElse(null);
            assertTrue(packet != null, "the relit shard sent light for the changed chunk");
            assertTrue(packet.lightData().blockLight().stream().anyMatch(ShardLightTest::lit),
                    "the shard view carries the glowstone's block light");

            viewer.sent.clear();
            shard.leave(viewer.player);
            assertFalse(viewer.sent(ChunkDataPacket.class).isEmpty(),
                    "leaving restores base light (full chunk resend for previously lit chunks)");
        } finally {
            viewer.player.remove();
        }
    }

    @Test
    void shardsWithoutRelightSendNoLight() {
        int chunkX = 3100 >> 4, chunkZ = 3100 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3100.5, 70, 3100.5), "DarkViewer");
        try {
            shard.join(viewer.player);
            viewer.sent.clear();
            shard.setBlock(new Vec(3100, 30, 3100), Block.GLOWSTONE);
            assertTrue(viewer.sent(UpdateLightPacket.class).isEmpty(), "relight is opt-in");
        } finally {
            viewer.player.remove();
        }
    }

    @Test
    void resetRefreshesLightWithoutTheDiff() {
        int chunkX = 3200 >> 4, chunkZ = 3200 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        shard.relight(true);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3200.5, 70, 3200.5), "ResetViewer");
        try {
            shard.join(viewer.player);
            shard.setBlock(new Vec(3200, 30, 3200), Block.GLOWSTONE);
            viewer.sent.clear();
            shard.reset();
            // the diff is gone: nothing in the chain touches the chunk anymore -> base chunk restore
            assertFalse(viewer.sent(ChunkDataPacket.class).isEmpty(),
                    "reset restores base light where the chain no longer touches");
        } finally {
            viewer.player.remove();
        }
    }

    private static boolean lit(byte[] light) {
        for (byte b : light) {
            if (b != 0) return true;
        }
        return false;
    }
}
