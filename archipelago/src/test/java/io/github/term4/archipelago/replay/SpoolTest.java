package io.github.term4.archipelago.replay;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.codec.Codec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.List;

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

    /** A process dies mid-game: the header and the closed segments make the file the stop would have. */
    @Test
    void aDeadRecordingSalvages(@TempDir Path dir) throws Exception {
        DataKey<String> game = new DataKey<>("test:game", Codec.STRING);
        Shard stage = new Shard(instance);
        Path spool = dir.resolve("dead.spool");
        Recording rec = Replay.record(stage).spool(spool, 5, data -> data.put(game, "g1"));
        try {
            for (int t = 1; t <= 23; t++) {
                if (t == 3) rec.blockCovered(new Vec(7, 65, 7), Block.STONE);
                if (t == 5) stage.setBlock(new Vec(5, 65, 5), Block.GOLD_BLOCK);
                tick();
                if (t == 7) rec.bookmark("goal");
            }
        } finally {
            rec.stop(); // settles the appends; the spool stays, as a dead process leaves it
        }
        byte[] cut = Replay.salvage(spool);
        assertNotNull(cut, "the closed segments make a file");
        UserData.View data = ShardFile.userData(cut);
        assertEquals("g1", data.get(game), "the data the spool opened with");
        List<Replay.Bookmark> marks = data.get(Replay.BOOKMARKS);
        assertNotNull(marks);
        assertEquals(1, marks.size());
        assertEquals(7, marks.getFirst().tick());
        assertEquals("goal", marks.getFirst().name());

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, cut);
        pb.pause(true);
        try {
            assertEquals(20, pb.length(), "cut at the last closed segment");
            pb.seek(18);
            assertEquals(Block.GOLD_BLOCK, theater.getBlock(5, 65, 5), "a spooled block op");
            assertEquals(Block.STONE, theater.getBlock(7, 65, 7), "the mask leads the channel");
        } finally {
            pb.halt();
        }
        assertNull(Replay.salvage(dir.resolve("none.spool")), "nothing to salvage without a spool");
        rec.discard();
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
