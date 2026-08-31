package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.core.Channels;
import io.github.term4.archipelago.replay.track.EntityMoveTrack;
import io.github.term4.archipelago.replay.track.SpawnTrack;
import io.github.term4.archipelago.replay.track.MoveTrack;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packed motion tracks against the plain per-op encoding: every field survives, on a grid finer than the
 * wire carries (1/4096 block, 1/65536 turn), and the blob is a fraction of the size.
 */
class TrackPackingTest {

    private static final double POS_EPSILON = 1.0 / 4096 / 2 + 1e-9;
    private static final float ANGLE_EPSILON = 360f / 65536 / 2 + 1e-4f;

    private static List<Recorded<Channels.Move>> moves(int players, int ticks) {
        Random rng = new Random(7);
        List<Recorded<Channels.Move>> out = new ArrayList<>();
        double[] x = new double[players], y = new double[players], z = new double[players];
        for (int i = 0; i < players; i++) { x[i] = -300 + i * 17.25; y[i] = 64 + i; z[i] = 900.5 - i * 3; }
        for (long t = 0; t < ticks; t++) {
            for (int p = 0; p < players; p++) {
                x[p] += rng.nextDouble() * 0.4 - 0.2;
                y[p] += rng.nextDouble() * 0.1 - 0.05;
                z[p] += rng.nextDouble() * 0.4 - 0.2;
                out.add(new Recorded<>(t, new Channels.Move("Player" + p, x[p], y[p], z[p],
                        rng.nextFloat() * 360 - 180, rng.nextFloat() * 180 - 90, rng.nextBoolean())));
            }
        }
        return out;
    }

    @Test
    void movesSurviveThePackRoundTrip() {
        List<Recorded<Channels.Move>> ops = moves(4, 600);
        List<Recorded<Channels.Move>> back = new MoveTrack().decode(new MoveTrack().encode(ops));

        assertEquals(ops.size(), back.size());
        for (int i = 0; i < ops.size(); i++) {
            Channels.Move a = ops.get(i).op(), b = back.get(i).op();
            assertEquals(ops.get(i).tick(), back.get(i).tick(), "tick");
            assertEquals(a.player(), b.player());
            assertEquals(a.x(), b.x(), POS_EPSILON, "x");
            assertEquals(a.y(), b.y(), POS_EPSILON, "y");
            assertEquals(a.z(), b.z(), POS_EPSILON, "z");
            assertEquals(a.yaw(), b.yaw(), ANGLE_EPSILON, "yaw");
            assertEquals(a.pitch(), b.pitch(), ANGLE_EPSILON, "pitch");
            assertEquals(a.ground(), b.ground(), "ground");
        }
    }

    @Test
    void entityMovesSurviveThePackRoundTrip() {
        Random rng = new Random(11);
        List<Recorded<Channels.EntityMove>> ops = new ArrayList<>();
        for (long t = 0; t < 400; t++) {
            for (int seq = 0; seq < 3; seq++) {
                ops.add(new Recorded<>(t, new Channels.EntityMove(seq, 12.5 + t * 0.31, 70 - t * 0.02,
                        -44.25 + t * 0.13, rng.nextFloat() * 360 - 180, rng.nextFloat() * 180 - 90)));
            }
        }
        List<Recorded<Channels.EntityMove>> back =
                new EntityMoveTrack().decode(new EntityMoveTrack().encode(ops));

        assertEquals(ops.size(), back.size());
        for (int i = 0; i < ops.size(); i++) {
            Channels.EntityMove a = ops.get(i).op(), b = back.get(i).op();
            assertEquals(ops.get(i).tick(), back.get(i).tick());
            assertEquals(a.seq(), b.seq());
            assertEquals(a.x(), b.x(), POS_EPSILON);
            assertEquals(a.y(), b.y(), POS_EPSILON);
            assertEquals(a.z(), b.z(), POS_EPSILON);
            assertEquals(a.yaw(), b.yaw(), ANGLE_EPSILON);
            assertEquals(a.pitch(), b.pitch(), ANGLE_EPSILON);
        }
    }

    /** Ops come back tick-ascending even though they pack grouped by key - playback advances on tick order. */
    @Test
    void decodedOpsAreTickOrdered() {
        List<Recorded<Channels.Move>> back = new MoveTrack().decode(new MoveTrack().encode(moves(3, 50)));
        for (int i = 1; i < back.size(); i++) {
            assertTrue(back.get(i - 1).tick() <= back.get(i).tick(), "tick order at " + i);
        }
    }

    @Test
    void spawnsSurviveThePackRoundTripWithPalettedVisuals() {
        CompoundBinaryTag arrow = CompoundBinaryTag.builder().putString("type", "minecraft:arrow").build();
        CompoundBinaryTag item = CompoundBinaryTag.builder().putString("type", "minecraft:item")
                .put("stack", CompoundBinaryTag.builder().putString("id", "minecraft:diamond").putInt("count", 3).build())
                .build();
        List<Recorded<Channels.Spawn>> ops = new ArrayList<>();
        for (int i = 0; i < 40; i++) { // 40 spawns, 2 distinct visuals: the palette carries the weight
            ops.add(new Recorded<>(i * 3L, new Channels.Spawn(i % 2, 100.25 + i, 64, -20.5 - i,
                    35.5f, -10.25f, i, i % 2 == 0 ? arrow : item, i % 4 == 0 ? "Shooter" + (i % 3) : "")));
        }
        List<Recorded<Channels.Spawn>> back = new SpawnTrack().decode(new SpawnTrack().encode(ops));

        assertEquals(ops.size(), back.size());
        for (int i = 0; i < ops.size(); i++) {
            Channels.Spawn a = ops.get(i).op(), b = back.get(i).op();
            assertEquals(ops.get(i).tick(), back.get(i).tick());
            assertEquals(a.layer(), b.layer());
            assertEquals(a.seq(), b.seq());
            assertEquals(a.x(), b.x(), POS_EPSILON);
            assertEquals(a.y(), b.y(), POS_EPSILON);
            assertEquals(a.z(), b.z(), POS_EPSILON);
            assertEquals(a.yaw(), b.yaw(), ANGLE_EPSILON);
            assertEquals(a.pitch(), b.pitch(), ANGLE_EPSILON);
            assertEquals(a.visual(), b.visual(), "the exact nbt snapshot, via the palette");
            assertEquals(a.shooter(), b.shooter());
        }
    }

    @Test
    void packingBeatsTheNbtItReplaces() {
        List<Recorded<Channels.Move>> ops = moves(4, 600);
        int packed = new MoveTrack().encode(ops).length;
        int perOpNbt = ops.size() * 94; // measured B/op for replay:move in the storage breakdown
        assertTrue(packed * 5 < perOpNbt,
                "packed " + packed + " B vs ~" + perOpNbt + " B of NBT");
    }
}
