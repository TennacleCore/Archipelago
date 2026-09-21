package io.github.term4.archipelago.testsupport;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Block states as they reached a client, whichever shape carried them: a live write sends one change, a re-send
 * packs each 16-cube into one {@link MultiBlockChangePacket}.
 */
public final class BlockWire {

    private BlockWire() {}

    public static Map<Point, Integer> statesSentTo(FakePlayer player) {
        Map<Point, Integer> out = new LinkedHashMap<>();
        for (var packet : player.sent) {
            if (packet instanceof BlockChangePacket b) {
                out.put(blockVec(b.blockPosition()), b.blockStateId());
            } else if (packet instanceof MultiBlockChangePacket m) {
                forEach(m, (pos, state) -> out.put(pos, state));
            }
        }
        return out;
    }

    /** How many states for {@code pos} reached the client, in either shape. */
    public static long countAt(FakePlayer player, Point pos) {
        long[] n = {0};
        for (var packet : player.sent) {
            if (packet instanceof BlockChangePacket b && b.blockPosition().sameBlock(pos)) n[0]++;
            else if (packet instanceof MultiBlockChangePacket m) {
                forEach(m, (at, state) -> { if (at.sameBlock(pos)) n[0]++; });
            }
        }
        return n[0];
    }

    /** {@code pos}'s state in this packet, or null when it carries none. */
    public static Integer stateAt(MultiBlockChangePacket packet, Point pos) {
        Integer[] out = {null};
        forEach(packet, (at, state) -> { if (at.sameBlock(pos)) out[0] = state; });
        return out[0];
    }

    private interface Entry { void accept(Point pos, int state); }

    private static void forEach(MultiBlockChangePacket packet, Entry out) {
        long section = packet.chunkSectionPosition();
        int sectionX = (int) (section >> 42);
        int sectionY = (int) (section << 44 >> 44);
        int sectionZ = (int) (section << 22 >> 42);
        for (long entry : packet.blocks()) {
            int index = (int) (entry & 0xFFF);
            int state = (int) (entry >>> 12);
            out.accept(new Vec((sectionX << 4) + ((index >> 8) & 0xF),
                    (sectionY << 4) + (index & 0xF),
                    (sectionZ << 4) + ((index >> 4) & 0xF)), state);
        }
    }

    private static Point blockVec(Point pos) {
        return new Vec(pos.blockX(), pos.blockY(), pos.blockZ());
    }
}
