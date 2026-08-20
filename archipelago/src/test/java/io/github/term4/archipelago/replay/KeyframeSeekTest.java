package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.replay.core.CoreChannels;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyframes: {@code keyframeInterval(n)} makes keyframed channels re-emit full state every n ticks, and a
 * backward seek folds them from the latest keyframe instead of tick 0. The bar is equivalence - a keyframed
 * seek must land on exactly the scene a from-zero fold produces.
 */
class KeyframeSeekTest {

    private static InstanceContainer instance;

    @BeforeAll
    static void boot() {
        if (instance != null) return;
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
            MinecraftServer.process().dispatcher().start();
        }
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 64, Block.STONE));
        instance.loadChunk(0, 0).join();
        CoreChannels.register();
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    /** Pumps the scheduler while polling: twin spawns/teleports land on scheduled tasks. Playback stays paused. */
    private static void await(String what, Supplier<Boolean> done) {
        for (int i = 0; i < 200 && !done.get(); i++) tick();
        assertTrue(done.get(), what);
    }

    private static Entity awaitEntity(Playback pb, int seq) {
        await("twin " + seq + " materializes", () -> pb.entity(seq) != null);
        return pb.entity(seq);
    }

    @Test
    void backwardSeekFoldsFromTheLatestKeyframe() {
        Shard stage = new Shard(instance);
        Entity mover = new Entity(EntityType.SNOWBALL);
        Entity still = new Entity(EntityType.SNOWBALL);
        Entity brief = new Entity(EntityType.SNOWBALL);
        stage.spawn(mover, new Pos(10.5, 70, 10.5)).join();
        stage.spawn(still, new Pos(20.5, 70, 20.5)).join();

        Map<Entity, Integer> seqs = new HashMap<>();
        Recording rec = Replay.record(stage).keyframeInterval(5);
        byte[] file;
        try {
            for (int t = 1; t <= 20; t++) {
                mover.teleport(new Pos(10.5 + t, 70, 10.5)).join();
                // still's LAST real move sits below the tick-10 keyframe: only the re-emission carries it forward
                if (t == 7) still.teleport(new Pos(25.5, 70, 20.5)).join();
                if (t == 8) stage.spawn(brief, new Pos(30.5, 70, 30.5)).join();
                if (t == 12) brief.remove();
                // the write hook stamps the pre-increment tick: this lands the block op at tick 4
                if (t == 5) stage.setBlock(new Vec(5, 65, 5), Block.GOLD_BLOCK);
                tick();
                rec.forEachTracked(seqs::put);
            }
        } finally {
            file = rec.stop();
        }
        assertEquals(4, rec.counts().get("replay:keyframe"), "boundaries at 5/10/15/20");
        int moverSeq = seqs.get(mover), stillSeq = seqs.get(still), briefSeq = seqs.get(brief);

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        pb.pause(true); // seeks drive the cursor; the paused clock only lands scheduled twin work
        try {
            pb.seek(18); // forward: the plain cursor fold
            assertEquals(18, pb.tick());
            Entity m18 = awaitEntity(pb, moverSeq);
            await("mover at its tick-18 position", () -> m18.getPosition().x() == 28.5);
            assertNull(pb.entity(briefSeq), "spawned and removed inside the window: never materializes");

            pb.seek(12); // backward: folds from the tick-10 keyframe
            assertEquals(12, pb.tick());
            Entity m12 = awaitEntity(pb, moverSeq);
            await("mover at its tick-12 position", () -> m12.getPosition().x() == 22.5);
            Entity s12 = awaitEntity(pb, stillSeq);
            await("the keyframe re-emission carries the below-base move", () -> s12.getPosition().x() == 25.5);
            assertNull(pb.entity(briefSeq), "removed at the target: the spawn/remove interplay folds from zero");
            assertEquals(Block.GOLD_BLOCK, theater.getBlock(5, 65, 5), "below-base block ops still fold (blocks are not keyframed)");

            pb.seek(3); // backward below every keyframe: base 0, today's full refold
            assertEquals(3, pb.tick());
            Entity m3 = awaitEntity(pb, moverSeq);
            await("mover at its tick-3 position", () -> m3.getPosition().x() == 13.5);
            Entity s3 = awaitEntity(pb, stillSeq);
            await("still hasn't moved yet", () -> s3.getPosition().x() == 20.5);
            assertNull(pb.entity(briefSeq), "not spawned yet");
            assertTrue(theater.getBlock(5, 65, 5).air(), "the block isn't set yet");
        } finally {
            pb.halt();
        }
    }
}
