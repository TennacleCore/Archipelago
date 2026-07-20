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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Removing the area's LAST diff drops the chunk out of candidacy - the stale bright light must restore, not linger. */
    @Test
    void breakingTheOnlyLightSourceRestoresBase() {
        int chunkX = 3400 >> 4, chunkZ = 3400 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        shard.relight(true);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3400.5, 70, 3400.5), "DecayViewer");
        try {
            shard.join(viewer.player);
            Vec glowstone = new Vec(3400, 30, 3400);
            shard.setBlock(glowstone, Block.GLOWSTONE);
            awaitLight(viewer, chunkX, chunkZ);
            viewer.sent.clear();
            shard.setBlock(glowstone, instance.getBlock(glowstone)); // back to the base view: the entry drops
            awaitBaseRestore(viewer);
        } finally {
            viewer.player.remove();
            shard.relight(false);
        }
    }

    /** {@code relight(false)} mid-game: base light restores, but the overlay's BLOCKS must survive the full resend. */
    @Test
    void relightOffRestoresBaseAndKeepsOverlayBlocks() {
        int chunkX = 3500 >> 4, chunkZ = 3500 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        shard.relight(true);
        Vec overlay = new Vec(3500, 70, 3500);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3500.5, 72, 3500.5), "OffViewer");
        try {
            shard.join(viewer.player);
            shard.setBlock(overlay, Block.EMERALD_BLOCK);
            shard.setBlock(new Vec(3500, 30, 3500), Block.GLOWSTONE);
            awaitLight(viewer, chunkX, chunkZ);
            viewer.sent.clear();
            shard.relight(false);
            // replay in packet order: the base full resend must be FOLLOWED by the overlay's block
            Block seen = instance.getBlock(overlay);
            boolean restored = false;
            for (var packet : viewer.sent) {
                var p = net.minestom.server.network.packet.server.SendablePacket
                        .extractServerPacket(net.minestom.server.network.ConnectionState.PLAY, packet);
                if (p instanceof ChunkDataPacket c && c.chunkX() == chunkX && c.chunkZ() == chunkZ) {
                    seen = instance.getBlock(overlay);
                    restored = true;
                } else if (p instanceof net.minestom.server.network.packet.server.play.BlockChangePacket b
                        && b.blockPosition().sameBlock(overlay)) {
                    seen = Block.fromStateId(b.blockStateId());
                }
            }
            assertTrue(restored, "disabling relight restores the base chunk for lit chunks");
            assertTrue(seen.compare(Block.EMERALD_BLOCK), "the overlay block survives the restore");
        } finally {
            viewer.player.remove();
        }
    }

    /** Tree light: a relit CHILD layer computes over its whole chain - an ancestor's diff lights the child's view. */
    @Test
    void childLayerLightsTheChainIncludingAncestorDiffs() {
        int chunkX = 3600 >> 4, chunkZ = 3600 >> 4;
        loadArea(chunkX, chunkZ);
        Shard parent = new Shard(instance);
        Shard child = new Shard(parent);
        child.relight(true);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3600.5, 70, 3600.5), "TreeViewer");
        try {
            child.join(viewer.player);
            viewer.sent.clear();
            // the write lands on the PARENT: the cascade must dirty the child's light, and the child's
            // chain palette must carry the ancestor's block
            parent.setBlock(new Vec(3600, 30, 3600), Block.GLOWSTONE);
            UpdateLightPacket packet = awaitLight(viewer, chunkX, chunkZ);
            assertTrue(packet.lightData().blockLight().stream().anyMatch(ShardLightTest::lit),
                    "the child's light carries the parent-layer glowstone");
        } finally {
            viewer.player.remove();
            child.relight(false);
        }
    }

    /** The loaded-save flow: diffs land FIRST (ShardFile.load), relight second - light must compute without another write. */
    @Test
    void relightAfterExistingDiffsComputesImmediately() {
        int chunkX = 3700 >> 4, chunkZ = 3700 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3700.5, 70, 3700.5), "LoadViewer");
        try {
            shard.join(viewer.player);
            shard.setBlock(new Vec(3700, 30, 3700), Block.GLOWSTONE); // pre-existing diff, relight OFF
            viewer.sent.clear();
            shard.relight(true);
            UpdateLightPacket packet = awaitLight(viewer, chunkX, chunkZ);
            assertTrue(packet.lightData().blockLight().stream().anyMatch(ShardLightTest::lit),
                    "enabling relight lights the chain's existing diff");
        } finally {
            viewer.player.remove();
            shard.relight(false);
        }
    }

    /** Shard-as-instance: {@code getBlockLight/getSkyLight} read the CHAIN's computed light, base untouched. */
    @Test
    void lightQueriesReadTheChainAndFallBackToBase() {
        int chunkX = 3900 >> 4, chunkZ = 3900 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        shard.relight(true);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(3900.5, 70, 3900.5), "QueryViewer");
        try {
            shard.join(viewer.player);
            shard.setBlock(new Vec(3900, 30, 3900), Block.GLOWSTONE);
            awaitLight(viewer, chunkX, chunkZ); // flush done: the query maps are populated
            assertTrue(shard.getBlockLight(3900, 30, 3900) > 0, "the chain's glowstone lights the shard query");
            assertEquals(0, instance.getBlockLight(3900, 30, 3900), "the base map stays dark");
            assertEquals(instance.getSkyLight(3900, 50, 3900), shard.getSkyLight(3900, 50, 3900),
                    "above ground the chain's sky exposure matches base");
        } finally {
            viewer.player.remove();
            shard.relight(false);
        }
    }

    /** Without a lighting system, queries read base - the diff is NOT reflected (documented on the accessor). */
    @Test
    void lightQueriesWithoutRelightReadBase() {
        int chunkX = 4000 >> 4, chunkZ = 4000 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        shard.setBlock(new Vec(4000, 30, 4000), Block.GLOWSTONE);
        assertEquals(instance.getBlockLight(4000, 30, 4000), shard.getBlockLight(4000, 30, 4000));
        assertEquals(instance.getSkyLight(4000, 50, 4000), shard.getSkyLight(4000, 50, 4000));
    }

    /** The pluggable seam: a user-provided {@link ShardLighting} gets the shard's hooks and closes on swap-out. */
    @Test
    void customLightingSystemReceivesHooksAndClosesOnReplace() {
        int chunkX = 3800 >> 4, chunkZ = 3800 >> 4;
        loadArea(chunkX, chunkZ);
        Shard shard = new Shard(instance);
        RecordingLight custom = new RecordingLight();
        shard.relight(custom);
        try {
            shard.setBlock(new Vec(3800, 30, 3800), Block.GLOWSTONE);
            assertTrue(custom.changed.contains(ShardLighting.sectionKey(chunkX, 30 >> 4, chunkZ)),
                    "writes notify the installed system with the touched section");
            shard.reset();
            assertTrue(custom.refreshed, "reset drives refreshLit");
            assertFalse(custom.closed);

            shard.relight(false);
            assertTrue(custom.closed, "uninstalling closes the outgoing system");

            RecordingLight second = new RecordingLight();
            shard.relight(second);
            shard.relight(true); // swapping to the built-in closes the custom one too
            assertTrue(second.closed);
        } finally {
            shard.relight(false);
        }
    }

    private static final class RecordingLight implements ShardLighting {
        final java.util.Set<Long> changed = new java.util.HashSet<>();
        boolean refreshed, closed;
        @Override public void changed(java.util.Set<Long> chunks) { changed.addAll(chunks); }
        @Override public void refreshLit() { refreshed = true; }
        @Override public void sendTo(net.minestom.server.entity.Player player, int chunkX, int chunkZ) {}
        @Override public void close() { closed = true; }
        @Override public net.minestom.server.network.packet.server.SendablePacket packet(int chunkX, int chunkZ) { return null; }
        @Override public java.util.Set<Long> litChunks() { return java.util.Set.of(); }
        @Override public void markLit(long chunkIndex) {}
        @Override public java.util.Set<Long> candidateChunks() { return java.util.Set.of(); }
    }

    private static boolean lit(byte[] light) {
        for (byte b : light) {
            if (b != 0) return true;
        }
        return false;
    }
}
