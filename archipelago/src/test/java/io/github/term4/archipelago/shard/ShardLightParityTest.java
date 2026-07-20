package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.packet.server.play.data.LightData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Incremental flushes must land on the SAME light as a fresh full recompute of the final diff -
 *  the reference harness for any change to the flush's seeding/occlusion strategy. */
class ShardLightParityTest extends HeadlessServerTest {

    private static final int CX = 3600 >> 4, CZ = 3600 >> 4;

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    /** The edit script, applied to both shards: buried lights, add+remove, a roof punch (sky shaft
     *  down), a built roof (sky shaft darkening), chunk-border edits (cross-chunk propagation). */
    private static void edits(Shard shard, int burst) {
        switch (burst) {
            case 0 -> { // buried block light
                shard.setBlock(new Vec(3600, 30, 3600), Block.GLOWSTONE);
                shard.setBlock(new Vec(3605, 25, 3605), Block.SEA_LANTERN);
            }
            case 1 -> { // punch the surface: sky floods the shaft
                for (int y = 66; y >= 55; y--) shard.setBlock(new Vec(3602, y, 3602), Block.AIR);
                shard.setBlock(new Vec(3600, 30, 3600), Block.AIR); // and un-light the glowstone
            }
            case 2 -> { // build a roof: sky darkens under it
                for (int x = 3596; x <= 3604; x++)
                    for (int z = 3596; z <= 3604; z++) shard.setBlock(new Vec(x, 80, z), Block.STONE);
            }
            case 3 -> { // chunk-border edits: light must cross into the neighbor
                shard.setBlock(new Vec((CX << 4) - 1, 70, 3600), Block.GLOWSTONE);
                shard.setBlock(new Vec(CX << 4, 70, 3601), Block.TORCH);
            }
        }
    }

    private static Map<Long, LightData> latestLight(FakePlayer viewer) {
        Map<Long, LightData> out = new HashMap<>();
        for (UpdateLightPacket p : viewer.sent(UpdateLightPacket.class)) {
            out.put(((long) p.chunkX() << 32) | (p.chunkZ() & 0xFFFFFFFFL), p.lightData());
        }
        return out;
    }

    /** Drives ticks until no new light packet arrives for a full coalescing window (+ flush slack). */
    private static void awaitQuiescent(FakePlayer viewer) {
        int window = net.minestom.server.ServerFlag.SEND_LIGHT_AFTER_BLOCK_PLACEMENT_DELAY * 2 + 60;
        long deadline = System.currentTimeMillis() + 20_000;
        int stable = 0, seen = -1;
        while (System.currentTimeMillis() < deadline && stable < window) {
            tick();
            int now = viewer.sent(UpdateLightPacket.class).size();
            stable = now == seen ? stable + 1 : 0;
            seen = now;
            LockSupport.parkNanos(500_000);
        }
        assertTrue(stable >= window, "light flushes never settled");
    }

    private static java.util.Map<Integer, byte[]> sections(java.util.BitSet mask, List<byte[]> arrays) {
        java.util.Map<Integer, byte[]> out = new HashMap<>();
        int n = 0;
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) out.put(i, arrays.get(n++));
        return out;
    }

    /** Incremental seeds a subset of the reference's sections (ring columns only compute what a change
     *  could reach); every section it DOES hold must match the full recompute byte-for-byte. */
    private static void compareLight(StringBuilder out, long chunk, LightData inc, LightData full) {
        String at = "chunk " + (chunk >> 32) + "," + (int) chunk;
        compareLayer(out, at + " sky", sections(inc.skyMask(), inc.skyLight()),
                sections(full.skyMask(), full.skyLight()));
        compareLayer(out, at + " block", sections(inc.blockMask(), inc.blockLight()),
                sections(full.blockMask(), full.blockLight()));
    }

    private static void compareLayer(StringBuilder out, String at,
                                     java.util.Map<Integer, byte[]> inc, java.util.Map<Integer, byte[]> full) {
        for (var e : inc.entrySet()) {
            byte[] ref = full.get(e.getKey());
            if (ref == null) {
                out.append(at).append(" sec ").append(e.getKey()).append(": incremental-only section\n");
                continue;
            }
            byte[] x = e.getValue();
            int diffs = 0, first = -1;
            for (int j = 0; j < Math.min(x.length, ref.length); j++) {
                if (x[j] != ref[j] && diffs++ == 0) first = j;
            }
            if (diffs > 0) out.append(at).append(" sec ").append(e.getKey()).append(": ").append(diffs)
                    .append(" bytes differ, first@").append(first).append(" inc=").append(x[first])
                    .append(" full=").append(ref[first]).append('\n');
        }
    }

    @Test
    void incrementalFlushesMatchAFullRecompute() {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) instance.loadChunk(CX + dx, CZ + dz).join();

        Shard evolved = new Shard(instance);
        evolved.relight(true);
        FakePlayer viewerA = FakePlayer.connect(instance, new Pos(3600.5, 70, 3600.5), "ParityA");
        FakePlayer viewerB = null;
        Shard reference = null;
        try {
            evolved.join(viewerA.player);
            for (int burst = 0; burst < 4; burst++) {
                edits(evolved, burst);
                awaitQuiescent(viewerA); // each burst flushes incrementally before the next
            }
            Map<Long, LightData> incremental = latestLight(viewerA);
            assertFalse(incremental.isEmpty(), "the evolved shard published light");

            // the reference: the identical FINAL diff, lit fresh - one full compute, no history
            reference = new Shard(instance);
            for (int burst = 0; burst < 4; burst++) edits(reference, burst);
            reference.relight(true);
            viewerB = FakePlayer.connect(instance, new Pos(3600.5, 70, 3600.5), "ParityB");
            reference.join(viewerB.player);
            awaitQuiescent(viewerB);
            Map<Long, LightData> full = latestLight(viewerB);

            // the fresh sighting publishes its whole candidate ring (base-identical at the rim); the
            // incremental path only publishes what a change could reach - every chunk IT lit must match
            assertTrue(full.keySet().containsAll(incremental.keySet()),
                    "incremental lit chunks the full recompute never did: " + incremental.keySet().stream()
                            .filter(k -> !full.containsKey(k)).map(k -> (k >> 32) + "," + (int) (long) k).toList());
            StringBuilder report = new StringBuilder();
            for (var e : incremental.entrySet()) compareLight(report, e.getKey(), e.getValue(), full.get(e.getKey()));
            if (!report.isEmpty()) System.out.println("[parity]\n" + report);
            assertTrue(report.isEmpty(), "incremental light diverges from the full recompute (see [parity])");
        } finally {
            if (viewerB != null) viewerB.player.remove();
            if (reference != null) reference.relight(false);
            viewerA.player.remove();
            evolved.relight(false);
        }
    }
}
