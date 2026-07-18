package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shard relight: chain-effective light as per-viewer UpdateLight overlays (flushed ASYNC on the shard's clock,
 *  coalesced by the vanilla resend delay), base restored on the way out. */
class ShardLightTest extends HeadlessServerTest {

    private static void loadArea(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) instance.loadChunk(chunkX + dx, chunkZ + dz).join();
        }
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    /** Drives shard ticks past the coalescing window and awaits the async flush's packet. */
    private static UpdateLightPacket awaitLight(FakePlayer viewer, int chunkX, int chunkZ) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            tick();
            var found = viewer.sent(UpdateLightPacket.class).stream()
                    .filter(p -> p.chunkX() == chunkX && p.chunkZ() == chunkZ).findFirst();
            if (found.isPresent()) return found.get();
            LockSupport.parkNanos(1_000_000);
        }
        throw new AssertionError("no shard light for chunk " + chunkX + "," + chunkZ + " within 5s");
    }

    private static void awaitBaseRestore(FakePlayer viewer) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            tick();
            if (!viewer.sent(ChunkDataPacket.class).isEmpty()) return;
            LockSupport.parkNanos(1_000_000);
        }
        throw new AssertionError("no base chunk restore within 5s");
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

            UpdateLightPacket packet = awaitLight(viewer, chunkX, chunkZ);
            assertTrue(packet.lightData().blockLight().stream().anyMatch(ShardLightTest::lit),
                    "the shard view carries the glowstone's block light");

            viewer.sent.clear();
            shard.leave(viewer.player);
            assertFalse(viewer.sent(ChunkDataPacket.class).isEmpty(),
                    "leaving restores base light (full chunk resend for previously lit chunks)");
        } finally {
            viewer.player.remove();
            shard.relight(false);
        }
    }

    /** A relit-to-UNLIT view swap: the light fallback full-resends BASE chunks, which must not clobber the destination overlay. */
    @Test
    void relitToUnlitSwapKeepsTheDestinationOverlay() {
        int chunkX = 3300 >> 4, chunkZ = 3300 >> 4;
        loadArea(chunkX, chunkZ);
        Shard lit = new Shard(instance);
        lit.relight(true);
        Shard dest = new Shard(instance); // unlit destination with its own overlay in the lit chunk
        Vec overlay = new Vec(3300, 70, 3300);
        dest.setBlock(overlay, Block.EMERALD_BLOCK);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3300.5, 72, 3300.5), "SwapViewer");
        try {
            lit.join(viewer.player);
            lit.setBlock(new Vec(3300, 30, 3300), Block.GLOWSTONE); // buried light: the chunk is now lit in the from-view
            awaitLight(viewer, chunkX, chunkZ); // the async flush must land (and mark the chunk lit) before the swap
            viewer.sent.clear();
            ShardView.set(viewer.player, ShardView.of(dest));
            // replay what the client renders at the overlay position, in packet order
            Block seen = instance.getBlock(overlay);
            for (var packet : viewer.sent) {
                var p = net.minestom.server.network.packet.server.SendablePacket
                        .extractServerPacket(net.minestom.server.network.ConnectionState.PLAY, packet);
                if (p instanceof ChunkDataPacket c && c.chunkX() == chunkX && c.chunkZ() == chunkZ) {
                    seen = instance.getBlock(overlay); // a full chunk resend carries BASE blocks
                } else if (p instanceof net.minestom.server.network.packet.server.play.BlockChangePacket b
                        && b.blockPosition().sameBlock(overlay)) {
                    seen = Block.fromStateId(b.blockStateId());
                }
            }
            assertTrue(seen.compare(Block.EMERALD_BLOCK), "the destination overlay survives the base light resend");
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
            for (int i = 0; i < ServerFlag.SEND_LIGHT_AFTER_BLOCK_PLACEMENT_DELAY + 5; i++) tick();
            LockSupport.parkNanos(100_000_000);
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
            awaitLight(viewer, chunkX, chunkZ); // lit only once the flush actually reached the client
            viewer.sent.clear();
            shard.reset();
            // the diff is gone: nothing in the chain touches the chunk anymore -> base chunk restore (async, next flush)
            awaitBaseRestore(viewer);
        } finally {
            viewer.player.remove();
            shard.relight(false);
        }
    }

    private static boolean lit(byte[] light) {
        for (byte b : light) {
            if (b != 0) return true;
        }
        return false;
    }
}
