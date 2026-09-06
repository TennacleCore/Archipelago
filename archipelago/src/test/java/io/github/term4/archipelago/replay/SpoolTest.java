package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.shard.Shard;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A spooled recording plays back, from its file and from memory, exactly like one kept on the heap. */
class SpoolTest {

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

    private static void await(String what, Supplier<Boolean> done) {
        for (int i = 0; i < 200 && !done.get(); i++) tick();
        assertTrue(done.get(), what);
    }

    @Test
    void segmentsLeaveTheHeapAndPlayBack(@TempDir Path dir) throws Exception {
        Shard stage = new Shard(instance);
        Entity mover = new Entity(EntityType.SNOWBALL);
        stage.spawn(mover, new Pos(10.5, 70, 10.5)).join();
        Map<Entity, Integer> seqs = new HashMap<>();
        Path spool = dir.resolve("game.spool");
        Recording rec = Replay.record(stage).spool(spool, 5);
        byte[] file;
        try {
            for (int t = 1; t <= 20; t++) {
                mover.teleport(new Pos(10.5 + t, 70, 10.5)).join();
                if (t == 5) stage.setBlock(new Vec(5, 65, 5), Block.GOLD_BLOCK);
                tick();
                rec.forEachTracked(seqs::put);
            }
        } finally {
            file = rec.stop();
        }
        assertTrue(Files.exists(spool), "the segments went to disk");
        assertTrue(rec.counts().get("replay:emove") >= 20, "counts cover the spooled ops: " + rec.counts());
        int moverSeq = seqs.get(mover);

        Shard theater = new Shard(instance);
        Playback fromFile = Replay.play(theater, file);
        fromFile.pause(true);
        try {
            fromFile.seek(18);
            await("twin materializes", () -> fromFile.entity(moverSeq) != null);
            Entity twin = fromFile.entity(moverSeq);
            await("the mover at its tick-18 position", () -> twin.getPosition().x() == 28.5);
            assertEquals(Block.GOLD_BLOCK, theater.getBlock(5, 65, 5), "a block op from a spooled segment");
        } finally {
            fromFile.halt();
        }

        Shard rewind = new Shard(instance);
        Playback fromMemory = Replay.play(rewind, rec);
        fromMemory.pause(true);
        try {
            fromMemory.seek(12);
            await("the in-memory twin materializes", () -> fromMemory.entity(moverSeq) != null);
            Entity twin = fromMemory.entity(moverSeq);
            await("read back through the spool", () -> twin.getPosition().x() == 22.5);
        } finally {
            fromMemory.halt();
        }
        rec.discard();
        assertFalse(Files.exists(spool), "discarded once the file is kept");
    }
}
